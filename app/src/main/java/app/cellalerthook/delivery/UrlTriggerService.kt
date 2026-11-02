package app.cellalerthook.delivery

import app.cellalerthook.config.ReceiverConfig
import app.cellalerthook.data.TriggerStore
import app.cellalerthook.data.db.AlertEvent
import app.cellalerthook.data.db.LogLevel
import app.cellalerthook.data.db.TriggerCommand
import app.cellalerthook.data.db.TriggerStatus
import app.cellalerthook.diagnostics.DiagnosticsLog
import app.cellalerthook.domain.TriggerAction
import app.cellalerthook.domain.TriggerMethod
import app.cellalerthook.domain.TriggerPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

fun interface TriggerTransport {
    /** Returns the HTTP status. Throws [IOException] when no HTTP response was obtained. */
    suspend fun call(method: TriggerMethod, url: String): Int
}

/**
 * Turns alerts into calls to the configured URLs.
 *
 * Order is the whole point. A trigger that was delayed by a network failure must never reach
 * the target after the all-clear, so only the newest command can be sent, ordered by when the phone
 * received the alert and not by when the app got to read it. A trigger too old to mean anything
 * expires instead of firing late. An all-clear never expires, because it is always safe.
 */
class UrlTriggerService(
    private val store: TriggerStore,
    private val transport: TriggerTransport,
    private val config: () -> ReceiverConfig,
    private val urlFor: (TriggerAction) -> String?,
    private val log: DiagnosticsLog,
    private val scheduler: DeliveryScheduler,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val commandLock = Mutex()
    private val sendLock = Mutex()
    private val holds = AtomicInteger()

    fun kick() {
        scope.launch { send() }
    }

    /**
     * Alerts read together are one fact. After the app was down, a warning and its all-clear can
     * be found at once, and sending as each is read would blip the target for a threat already over.
     */
    suspend fun holding(block: suspend () -> Unit) {
        holds.incrementAndGet()
        try {
            block()
        } finally {
            holds.decrementAndGet()
            kick()
        }
    }

    /** Called for every new or upgraded event. Stores the command and starts sending at once. */
    suspend fun onEvent(event: AlertEvent) {
        val decision = TriggerPolicy.decide(event, config().triggerSettings())
        log.log(LogLevel.INFO, CATEGORY, "decision event=${event.eventId} result=$decision")
        val action = decision.action ?: return
        request(event.eventId, action, event.receivedAtMs ?: event.detectedAtMs, manual = false)
    }

    /** The operator's buttons. They work while disarmed, so the URLs can be checked before arming. */
    suspend fun manual(action: TriggerAction) {
        request("$MANUAL_PREFIX${UUID.randomUUID()}", action, clock(), manual = true)
    }

    private suspend fun request(id: String, action: TriggerAction, alertAtMs: Long, manual: Boolean) {
        val now = clock()
        val status = commandLock.withLock {
            if (store.findCommand(id) != null) return
            val status = when {
                // Found late, for example by a catch-up scan after an all-clear was already read.
                store.latestCommand().let { it != null && it.alertReceivedAtMs > alertAtMs } -> TriggerStatus.SUPERSEDED
                !manual && store.latestEffectiveCommand()?.action == action -> TriggerStatus.REPEAT
                !manual && isTooOld(action, alertAtMs, now) -> TriggerStatus.EXPIRED
                else -> TriggerStatus.PENDING
            }
            if (status == TriggerStatus.PENDING) store.supersedePendingCommands()
            store.insertCommand(
                TriggerCommand(
                    eventId = id,
                    action = action,
                    alertReceivedAtMs = alertAtMs,
                    createdAtMs = now,
                    status = status,
                    nextAttemptAtMs = now,
                ),
            )
            status
        }
        log.log(LogLevel.INFO, CATEGORY, "command event=$id action=$action status=$status")
        if (status == TriggerStatus.PENDING) kick()
    }

    private fun isTooOld(action: TriggerAction, alertAtMs: Long, now: Long) =
        action == TriggerAction.ACTIVATE && now - alertAtMs > config().triggerSettings().maxAgeMs

    /** Sends the one pending command, if it is due. Safe to call from anywhere. */
    suspend fun send() = sendLock.withLock {
        while (holds.get() == 0) {
            val command = store.pendingCommand() ?: break
            val now = clock()
            if (isTooOld(command.action, command.alertReceivedAtMs, now)) {
                store.updateCommand(command.copy(status = TriggerStatus.EXPIRED, lastError = "too_old_to_send"))
                log.log(LogLevel.ERROR, CATEGORY, "expired event=${command.eventId}")
                continue
            }
            if (isOutsideHours(command, now)) {
                val opensAt = opensAt(now)
                if (opensAt == null || opensAt - command.alertReceivedAtMs > config().triggerSettings().maxAgeMs) {
                    store.updateCommand(command.copy(status = TriggerStatus.OUTSIDE_HOURS))
                    log.log(LogLevel.WARN, CATEGORY, "outside_school_hours event=${command.eventId}")
                    continue
                }
                // Still unanswered by an all-clear when the active hours start, so it still needs to fire.
                wakeAfter(opensAt - now)
                break
            }
            if (command.nextAttemptAtMs > now) break
            if (!attempt(command)) break
        }
    }

    /** The operator's buttons ignore the active hours, because they are a person's decision. */
    private fun isOutsideHours(command: TriggerCommand, now: Long): Boolean =
        command.action == TriggerAction.ACTIVATE && !command.eventId.startsWith(MANUAL_PREFIX) &&
            !config().triggerSettings().schedule.isOpen(zonedAt(now))

    private fun opensAt(now: Long): Long? =
        config().triggerSettings().schedule.nextOpen(zonedAt(now))?.toInstant()?.toEpochMilli()

    private fun zonedAt(now: Long) = Instant.ofEpochMilli(now).atZone(zone())

    private fun wakeAfter(delayMs: Long) {
        scheduler.scheduleRetry(delayMs)
        scope.launch {
            delay(delayMs)
            send()
        }
    }

    private suspend fun attempt(command: TriggerCommand): Boolean {
        val current = config()
        val url = urlFor(command.action)
        val code = try {
            if (url == null) return retry(command, current, null, "url_not_set")
            transport.call(current.triggerMethod, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return retry(command, current, null, "network:${e.javaClass.simpleName}")
        } catch (e: Exception) {
            // A bad URL or pinned CA must leave the command queued, not stop the loop.
            return retry(command, current, null, "client:${e.javaClass.simpleName}")
        }
        // Many controllers answer an action with a redirect to their status page.
        if (code !in 200..399) return retry(command, current, code, "http_$code")
        // A newer command may have replaced this one in flight, but the target did receive this call.
        store.updateCommand(
            (store.findCommand(command.eventId) ?: command).copy(
                status = TriggerStatus.SENT,
                attempts = command.attempts + 1,
                sentAtMs = clock(),
                lastHttpStatus = code,
                lastError = null,
            ),
        )
        // The URL is left out on purpose, because it may contain a key.
        log.log(LogLevel.INFO, CATEGORY, "sent event=${command.eventId} action=${command.action} status=$code")
        return true
    }

    private suspend fun retry(command: TriggerCommand, current: ReceiverConfig, code: Int?, reason: String): Boolean {
        val latest = store.findCommand(command.eventId) ?: command
        val attempts = command.attempts + 1
        val wait = BackoffPolicy(current.retryBaseSeconds * 1000L, current.retryMaxSeconds * 1000L, random).delayMs(attempts)
        // Never put a replaced command back in the queue.
        val waiting = latest.status == TriggerStatus.PENDING
        store.updateCommand(
            latest.copy(
                attempts = attempts,
                nextAttemptAtMs = clock() + wait,
                lastHttpStatus = code,
                lastError = reason,
            ),
        )
        log.log(LogLevel.WARN, CATEGORY, "retry_scheduled event=${command.eventId} attempt=$attempts in_ms=$wait reason=$reason")
        if (!waiting) return true
        wakeAfter(wait)
        return false
    }

    private companion object {
        const val CATEGORY = "trigger"
        const val MANUAL_PREFIX = "manual-"
    }
}
