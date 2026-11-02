package app.cellalerthook.domain

import app.cellalerthook.data.AlertStore
import app.cellalerthook.data.db.AlertEvent
import app.cellalerthook.data.db.AlertObservation
import java.util.UUID

data class ProcessorSettings(
    val deviceId: String,
    val duplicateWindowMs: Long,
    val cellBroadcastDuplicateWindowMs: Long,
)

sealed interface ProcessingResult {
    data class Created(val event: AlertEvent) : ProcessingResult
    data class Duplicate(val event: AlertEvent) : ProcessingResult

    /** Same incident, now backed by a stronger source. The server gets a new revision. */
    data class Upgraded(val event: AlertEvent) : ProcessingResult
    data class Rejected(val reason: String) : ProcessingResult
}

/**
 * The single path every observation takes, whatever detector produced it.
 *
 * It classifies only from structured fields, so a message that merely contains
 * alarming words stays `unknown`, and it never decides what the server should do.
 */
class AlertProcessor(
    private val store: AlertStore,
    private val provenance: BuildProvenance,
    private val settings: () -> ProcessorSettings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newEventId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun process(observation: RawAlertObservation): ProcessingResult {
        val current = settings()
        val now = clock()
        val body = if (observation.hiddenTextKey != null) {
            HiddenAlertText.MESSAGE
        } else {
            TextSanitizer.clean(observation.body, TextSanitizer.MAX_BODY_LENGTH)
        }
        if (body == null) {
            store.recordRejected(audit(observation, null, ObservationOutcome.REJECTED, "empty_or_malformed_body", null))
            return ProcessingResult.Rejected("empty_or_malformed_body")
        }

        val draft = buildEvent(observation, body, current, now)
        val existing = findExisting(draft, observation, current, now)
            ?: return create(draft, observation)

        return if (observation.hiddenTextKey == null && draft.verification.rank > existing.verification.rank) {
            upgrade(existing, draft, observation, now)
        } else {
            recordDuplicate(existing, draft, observation, now)
        }
    }

    private suspend fun findExisting(
        draft: AlertEvent,
        observation: RawAlertObservation,
        current: ProcessorSettings,
        now: Long,
    ): AlertEvent? {
        if (observation.hiddenTextKey != null) return findCoveringEvent(draft, observation, current, now)
        // Identifiers alone never prove a repeat. The message code is only 10 bits and networks
        // reuse it, so a new alert can carry an old serial. Suppressing it would lose the alert.
        // AOSP compares the body in its own duplicate check for the same reason.
        val byIdentifier = draft.dedupKey
            ?.let { store.findByDedupKey(it, now - current.cellBroadcastDuplicateWindowMs) }
            ?.takeIf { AlertFingerprint.sameMessage(it.message, draft.message) }
        if (byIdentifier != null) return byIdentifier
        // A provider row is a distinct broadcast, so identical text later is a new issue of the
        // alert. UI text has no such identity: on an unattended phone nobody dismisses the dialog,
        // so the same words on screen hours later are the same alert, not a new incident.
        val window = if (observation.cellBroadcast != null) {
            current.duplicateWindowMs
        } else {
            current.cellBroadcastDuplicateWindowMs
        }
        // Partitioned by simulated, so a test can never absorb or be absorbed by a real alert.
        return store.recentEvents(now - window, observation.simulated).firstOrNull {
            (it.fingerprint == draft.fingerprint || AlertFingerprint.sameMessage(it.message, draft.message)) &&
                !isAnotherIssue(it, observation)
        }
    }

    /**
     * Real warnings repeat word for word, and a second one can follow an all-clear within minutes.
     * Every real alert measured had its own message code, so a new code is always a new alert.
     * Suppressing it would leave the target system off, with "the threat has passed" as the last word.
     */
    private fun isAnotherIssue(existing: AlertEvent, observation: RawAlertObservation): Boolean {
        val earlier = existing.cellBroadcast ?: return false
        val incoming = observation.cellBroadcast ?: return false
        return earlier.messageCode != incoming.messageCode
    }

    /**
     * Hidden alerts all carry the same placeholder, so comparing text would swallow every one
     * after the first. A hidden notification is either one already handled, or an alert reported
     * by a detector that reads text, which must still be working: an old event says nothing about
     * this alert if that detector has since failed. Otherwise it is new, because a spare event
     * is better than a lost alert.
     */
    private suspend fun findCoveringEvent(
        draft: AlertEvent,
        observation: RawAlertObservation,
        current: ProcessorSettings,
        now: Long,
    ): AlertEvent? {
        val since = now - current.cellBroadcastDuplicateWindowMs
        val sameNotification = store.findByObservedFingerprint(draft.fingerprint, since)
        if (sameNotification != null || !observation.textDetectorActive) return sameNotification
        return store.recentEvents(since, observation.simulated)
            .firstOrNull { it.captureMethod != CaptureMethod.NOTIFICATION }
    }

    private suspend fun create(draft: AlertEvent, observation: RawAlertObservation): ProcessingResult {
        store.insertNew(draft, audit(observation, draft.eventId, ObservationOutcome.CREATED, "new_event", draft.fingerprint))
        return ProcessingResult.Created(draft)
    }

    private suspend fun recordDuplicate(
        existing: AlertEvent,
        draft: AlertEvent,
        observation: RawAlertObservation,
        now: Long,
    ): ProcessingResult {
        // Without text a hidden notification corroborates nothing, and its title is only the app label.
        val hidden = observation.hiddenTextKey != null
        val updated = existing.copy(
            duplicateCount = existing.duplicateCount + 1,
            lastObservedAtMs = now,
            corroboratingMethods = if (hidden) existing.corroboratingMethods else existing.corroboratingMethods + observation.method,
            title = if (hidden) existing.title else existing.title ?: draft.title,
        )
        store.updateWithObservation(
            updated,
            audit(observation, existing.eventId, ObservationOutcome.DUPLICATE, "suppressed_duplicate", draft.fingerprint),
        )
        return ProcessingResult.Duplicate(updated)
    }

    private suspend fun upgrade(
        existing: AlertEvent,
        draft: AlertEvent,
        observation: RawAlertObservation,
        now: Long,
    ): ProcessingResult {
        val updated = existing.copy(
            revision = existing.revision + 1,
            message = draft.message,
            title = existing.title ?: draft.title,
            language = draft.language,
            captureMethod = draft.captureMethod,
            source = draft.source,
            originPackage = draft.originPackage,
            originClass = draft.originClass,
            alertClass = draft.alertClass,
            severity = draft.severity,
            verification = draft.verification,
            confidence = draft.confidence,
            validationNotes = draft.validationNotes,
            dedupKey = draft.dedupKey ?: existing.dedupKey,
            fingerprint = draft.fingerprint,
            receivedAtMs = draft.receivedAtMs ?: existing.receivedAtMs,
            cellBroadcast = draft.cellBroadcast ?: existing.cellBroadcast,
            duplicateCount = existing.duplicateCount + 1,
            lastObservedAtMs = now,
            corroboratingMethods = existing.corroboratingMethods + existing.captureMethod + observation.method,
            deliveryStatus = DeliveryStatus.PENDING,
            deliveryAttempts = 0,
            nextAttemptAtMs = now,
            lastDeliveryError = null,
        )
        store.updateWithObservation(
            updated,
            audit(observation, existing.eventId, ObservationOutcome.UPGRADED, "upgraded_to_${draft.verification.wireName}", draft.fingerprint),
        )
        return ProcessingResult.Upgraded(updated)
    }

    private fun buildEvent(
        observation: RawAlertObservation,
        body: String,
        current: ProcessorSettings,
        now: Long,
    ): AlertEvent {
        val cellBroadcast = observation.cellBroadcast
        val language = if (observation.hiddenTextKey != null) {
            LanguageDetector.Result(LanguageDetector.UNKNOWN, fromHeader = false)
        } else {
            LanguageDetector.detect(body, observation.languageHint)
        }
        val verification = verificationOf(observation)
        val notes = validationNotes(observation, language, verification)
        return AlertEvent(
            eventId = newEventId(),
            deviceId = current.deviceId,
            detectedAtMs = now,
            receivedAtMs = cellBroadcast?.receivedTimeMs,
            title = TextSanitizer.clean(observation.title, TextSanitizer.MAX_TITLE_LENGTH),
            message = body,
            language = language.language,
            captureMethod = observation.method,
            source = sourceOf(observation),
            originPackage = observation.originPackage,
            originClass = observation.originClass,
            alertClass = cellBroadcast?.let { CellBroadcastClassifier.classify(it.messageId) } ?: AlertClass.UNKNOWN,
            severity = CellBroadcastClassifier.severity(cellBroadcast?.cmasSeverity),
            verification = verification,
            confidence = confidenceOf(verification),
            validationNotes = notes,
            simulated = observation.simulated,
            catchUp = observation.catchUp,
            dedupKey = cellBroadcast?.dedupKey,
            fingerprint = observation.hiddenTextKey?.let(AlertFingerprint::sha256) ?: AlertFingerprint.of(body),
            revision = 1,
            duplicateCount = 0,
            lastObservedAtMs = now,
            corroboratingMethods = emptySet(),
            cellBroadcast = cellBroadcast,
            deliveryStatus = DeliveryStatus.PENDING,
            deliveryAttempts = 0,
            nextAttemptAtMs = now,
            deliveredAtMs = null,
            lastDeliveryError = null,
            lastHttpStatus = null,
        )
    }

    private fun sourceOf(observation: RawAlertObservation): AlertSource = when {
        observation.simulated -> AlertSource.SIMULATION
        observation.method == CaptureMethod.CELL_BROADCAST_PROVIDER -> AlertSource.ANDROID_CELL_BROADCAST
        observation.originTrust != OriginTrust.SYSTEM_IMAGE -> AlertSource.UNVERIFIED_APP_UI
        observation.method == CaptureMethod.ACCESSIBILITY -> AlertSource.ANDROID_SYSTEM_ALERT_UI
        observation.method == CaptureMethod.NOTIFICATION -> AlertSource.ANDROID_SYSTEM_NOTIFICATION
        else -> AlertSource.UNVERIFIED_APP_UI
    }

    private fun verificationOf(observation: RawAlertObservation): VerificationStatus = when {
        observation.simulated -> VerificationStatus.SIMULATED
        observation.method == CaptureMethod.CELL_BROADCAST_PROVIDER && observation.cellBroadcast != null ->
            if (provenance.isProductionBuild) {
                VerificationStatus.CELL_BROADCAST_STACK
            } else {
                VerificationStatus.CELL_BROADCAST_STACK_LAB
            }
        observation.originTrust == OriginTrust.SYSTEM_IMAGE -> VerificationStatus.SYSTEM_COMPONENT_UI
        else -> VerificationStatus.UNVERIFIED
    }

    private fun confidenceOf(verification: VerificationStatus): Confidence = when (verification) {
        VerificationStatus.CELL_BROADCAST_STACK,
        VerificationStatus.CELL_BROADCAST_STACK_LAB -> Confidence.HIGH
        VerificationStatus.SYSTEM_COMPONENT_UI -> Confidence.MEDIUM
        VerificationStatus.UNVERIFIED,
        VerificationStatus.SIMULATED -> Confidence.LOW
    }

    private fun validationNotes(
        observation: RawAlertObservation,
        language: LanguageDetector.Result,
        verification: VerificationStatus,
    ): List<String> = buildList {
        add("capture_method=${observation.method.wireName}")
        add("origin_trust=${observation.originTrust.name.lowercase()}")
        add("build=${provenance.buildType}/${provenance.buildTags}")
        if (observation.hiddenTextKey != null) {
            add(HiddenAlertText.NOTE)
        } else {
            add(if (language.fromHeader) "language_source=cell_broadcast_header" else "language_source=script_heuristic")
        }
        if (observation.cellBroadcast == null) add("classification_unavailable_from_ui_text")
        if (observation.catchUp) add("found_by_catch_up_scan")
        if (verification == VerificationStatus.CELL_BROADCAST_STACK_LAB) add("debuggable_build_lab_injection_possible")
        add("origin_not_cryptographically_verifiable")
    }

    private fun audit(
        observation: RawAlertObservation,
        eventId: String?,
        outcome: ObservationOutcome,
        detail: String,
        fingerprint: String?,
    ) = AlertObservation(
        eventId = eventId,
        observedAtMs = observation.observedAtMs,
        method = observation.method,
        originPackage = observation.originPackage,
        originClass = observation.originClass,
        outcome = outcome,
        detail = detail,
        textSha256 = fingerprint,
    )
}
