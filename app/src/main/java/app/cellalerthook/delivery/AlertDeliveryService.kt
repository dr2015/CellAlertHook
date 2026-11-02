package app.cellalerthook.delivery

import app.cellalerthook.config.ConfigValidator
import app.cellalerthook.config.ConfigProblem
import app.cellalerthook.config.ReceiverConfig
import app.cellalerthook.data.AlertStore
import app.cellalerthook.data.db.AlertEvent
import app.cellalerthook.data.db.DeliveryAttempt
import app.cellalerthook.data.db.EvidenceKind
import app.cellalerthook.data.db.EvidenceProvenance
import app.cellalerthook.data.db.LogLevel
import app.cellalerthook.diagnostics.DiagnosticsLog
import app.cellalerthook.domain.BuildProvenance
import app.cellalerthook.domain.DeliveryStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

class BackoffPolicy(
    private val baseMs: Long,
    private val maxMs: Long,
    private val random: Random = Random.Default,
) {
    /** Exponential with +/-20% jitter, so several devices do not retry in step. */
    fun delayMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 30)
        val raw = min(maxMs, baseMs shl exponent)
        val jitter = (raw * JITTER_FRACTION).toLong()
        val jittered = raw + if (jitter > 0) random.nextLong(-jitter, jitter + 1) else 0
        return jittered.coerceIn(1L, maxMs)
    }

    private companion object {
        const val JITTER_FRACTION = 0.2
    }
}

data class TransportRequest(val url: String, val body: String, val headers: Map<String, String>)
data class TransportResponse(val code: Int, val body: String)

interface AlertTransport {
    /** Throws [IOException] when no HTTP response was obtained. */
    suspend fun post(request: TransportRequest): TransportResponse
}

/** Wakes delivery later even if this process has died in the meantime. */
fun interface DeliveryScheduler {
    fun scheduleRetry(delayMs: Long)
}

data class DrainSummary(val delivered: Int, val failed: Int, val rejected: Int)

/**
 * Sends the persistent queue to the server. An event leaves PENDING only on a valid
 * acknowledgment for that exact event, or on a refusal retrying cannot fix.
 */
class AlertDeliveryService(
    private val store: AlertStore,
    private val transport: AlertTransport,
    private val config: () -> ReceiverConfig,
    private val authToken: () -> String?,
    private val log: DiagnosticsLog,
    private val provenance: BuildProvenance,
    private val scheduler: DeliveryScheduler,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val drainLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun kick() {
        scope.launch { drain() }
    }

    suspend fun drain(): DrainSummary = drainLock.withLock {
        val current = config()
        if (!current.isServerConfigured) return@withLock DrainSummary(0, 0, 0)
        var delivered = 0
        var failed = 0
        var rejected = 0
        while (true) {
            val batch = store.dueForDelivery(clock(), BATCH_SIZE)
            if (batch.isEmpty()) break
            for (event in batch) {
                when (attempt(event, current)) {
                    Outcome.DELIVERED -> delivered += 1
                    Outcome.RETRY -> failed += 1
                    Outcome.TERMINAL -> rejected += 1
                }
            }
        }
        store.nextAttemptAt()?.let { next ->
            scheduler.scheduleRetry((next - clock()).coerceAtLeast(0))
        }
        DrainSummary(delivered, failed, rejected)
    }

    private enum class Outcome { DELIVERED, RETRY, TERMINAL }

    private suspend fun attempt(event: AlertEvent, current: ReceiverConfig): Outcome {
        val path = if (event.simulated) current.testAlertsPath else current.alertsPath
        if (event.simulated && ConfigProblem.TEST_PATH_EQUALS_PRODUCTION in ConfigValidator.validate(current)) {
            // Settings refuse this, but a safety rule should not rest on one layer.
            return finishTerminal(event, DeliveryStatus.BLOCKED, null, "simulation_routed_to_production_path", path, 0)
        }
        val startedAt = clock()
        val request = TransportRequest(
            url = current.url(path),
            body = AlertPayload.from(event).toJson(),
            headers = buildMap {
                put("Content-Type", "application/json; charset=utf-8")
                put("X-CellAlertHook-Device-Id", current.deviceId)
                put("X-CellAlertHook-Event-Id", event.eventId)
                put("X-CellAlertHook-Simulation", event.simulated.toString())
                authToken()?.let { put("Authorization", "Bearer $it") }
            },
        )
        val response = try {
            transport.post(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return retry(event, current, path, startedAt, null, "network:${e.javaClass.simpleName}")
        } catch (e: Exception) {
            // A bad pinned CA or URL must leave the alert queued, not stop the drain loop.
            return retry(event, current, path, startedAt, null, "client:${e.javaClass.simpleName}")
        }
        val duration = clock() - startedAt
        return when {
            response.code in 200..299 -> {
                val ack = parseAck(response.body)
                if (ack?.ack == true && ack.eventId == event.eventId) {
                    finishDelivered(event, response.code, ack, path, startedAt, duration)
                } else {
                    retry(event, current, path, startedAt, response.code, "invalid_acknowledgment")
                }
            }
            isRetryableStatus(response.code) -> retry(event, current, path, startedAt, response.code, "http_${response.code}")
            else -> finishTerminal(event, DeliveryStatus.REJECTED, response.code, "http_${response.code}", path, duration)
        }
    }

    /** 401 and 403 retry because a corrected credential must still deliver the alert. */
    private fun isRetryableStatus(code: Int) = code == 401 || code == 403 || code == 408 || code == 429 || code >= 500

    private fun parseAck(body: String): DeliveryAck? =
        runCatching { json.decodeFromString<DeliveryAck>(body) }.getOrNull()

    private suspend fun finishDelivered(
        event: AlertEvent,
        code: Int,
        ack: DeliveryAck,
        path: String,
        startedAt: Long,
        duration: Long,
    ): Outcome {
        val now = clock()
        // Re-read: the processor may have upgraded this event while the request was in flight.
        val latest = store.find(event.eventId) ?: event
        val upgradedInFlight = latest.revision != event.revision
        store.update(
            latest.copy(
                deliveryStatus = if (upgradedInFlight) DeliveryStatus.PENDING else DeliveryStatus.DELIVERED,
                deliveredAtMs = now,
                deliveryAttempts = latest.deliveryAttempts + 1,
                lastHttpStatus = code,
                lastDeliveryError = null,
            ),
        )
        store.recordAttempt(
            DeliveryAttempt(
                eventId = event.eventId,
                revision = event.revision,
                startedAtMs = startedAt,
                durationMs = duration,
                endpoint = path,
                httpStatus = code,
                outcome = if (ack.duplicate) "acknowledged_duplicate" else "acknowledged",
                detail = null,
            ),
        )
        store.recordEvidence(
            EvidenceKind.SERVER_DELIVERY,
            provenanceOf(event),
            now,
            event.eventId,
            "http_$code path=$path",
        )
        log.log(LogLevel.INFO, CATEGORY, "delivered event=${event.eventId} revision=${event.revision} status=$code duplicate=${ack.duplicate}")
        return Outcome.DELIVERED
    }

    private suspend fun retry(
        event: AlertEvent,
        current: ReceiverConfig,
        path: String,
        startedAt: Long,
        code: Int?,
        reason: String,
    ): Outcome {
        val now = clock()
        val attempts = event.deliveryAttempts + 1
        store.recordAttempt(
            DeliveryAttempt(
                eventId = event.eventId,
                revision = event.revision,
                startedAtMs = startedAt,
                durationMs = now - startedAt,
                endpoint = path,
                httpStatus = code,
                outcome = "retry",
                detail = reason,
            ),
        )
        val latest = store.find(event.eventId) ?: event
        if (current.retryMaxAttempts in 1..attempts) {
            store.update(
                latest.copy(
                    deliveryStatus = DeliveryStatus.REJECTED,
                    deliveryAttempts = attempts,
                    lastDeliveryError = "retry_limit:$reason",
                    lastHttpStatus = code,
                ),
            )
            log.log(LogLevel.ERROR, CATEGORY, "gave_up event=${event.eventId} attempts=$attempts reason=$reason")
            return Outcome.TERMINAL
        }
        val backoff = BackoffPolicy(current.retryBaseSeconds * 1000L, current.retryMaxSeconds * 1000L, random)
        val wait = backoff.delayMs(attempts)
        store.update(
            latest.copy(
                deliveryAttempts = attempts,
                nextAttemptAtMs = now + wait,
                lastDeliveryError = reason,
                lastHttpStatus = code,
            ),
        )
        log.log(LogLevel.WARN, CATEGORY, "retry_scheduled event=${event.eventId} attempt=$attempts in_ms=$wait reason=$reason")
        return Outcome.RETRY
    }

    private suspend fun finishTerminal(
        event: AlertEvent,
        status: DeliveryStatus,
        code: Int?,
        reason: String,
        path: String,
        duration: Long,
    ): Outcome {
        val latest = store.find(event.eventId) ?: event
        store.update(
            latest.copy(
                deliveryStatus = status,
                deliveryAttempts = latest.deliveryAttempts + 1,
                lastDeliveryError = reason,
                lastHttpStatus = code,
            ),
        )
        store.recordAttempt(
            DeliveryAttempt(
                eventId = event.eventId,
                revision = event.revision,
                startedAtMs = clock(),
                durationMs = duration,
                endpoint = path,
                httpStatus = code,
                outcome = status.name.lowercase(),
                detail = reason,
            ),
        )
        log.log(LogLevel.ERROR, CATEGORY, "terminal event=${event.eventId} status=$status reason=$reason")
        return Outcome.TERMINAL
    }

    private fun provenanceOf(event: AlertEvent): EvidenceProvenance = when {
        event.simulated -> EvidenceProvenance.SIMULATED
        provenance.isProductionBuild -> EvidenceProvenance.LIVE_DEVICE
        else -> EvidenceProvenance.LAB_BUILD
    }

    private companion object {
        const val BATCH_SIZE = 20
        const val CATEGORY = "delivery"
    }
}
