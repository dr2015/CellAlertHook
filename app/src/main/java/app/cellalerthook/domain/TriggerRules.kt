package app.cellalerthook.domain

import app.cellalerthook.data.db.AlertEvent
import kotlinx.serialization.Serializable

/** What an alert asks of the target system. */
enum class TriggerAction { ACTIVATE, CLEAR }

@Serializable
enum class TriggerMethod { GET, POST }

enum class TriggerMatch { ACTIVATE, CLEAR, NO_RULE, BOTH_RULES }

/** The outcome for every alert, logged so that a silent trigger can always be explained. */
enum class TriggerDecision(val action: TriggerAction?) {
    ACTIVATE(TriggerAction.ACTIVATE),
    CLEAR(TriggerAction.CLEAR),
    SIMULATED(null),
    NOT_ARMED(null),
    NOT_FROM_CELL_BROADCAST_STACK(null),
    TEST_OR_EXERCISE(null),
    NO_RULE_MATCHED(null),
    BOTH_RULES_MATCHED(null),
}

data class TriggerSettings(
    val armed: Boolean,
    val activatePhrases: List<String>,
    val clearPhrases: List<String>,
    val maxAgeMs: Long,
    /** Debug provisioning only: lets injected alerts fire on a test image. */
    val allowLabAlerts: Boolean,
    val schedule: TriggerSchedule,
)

/**
 * Warnings and all-clears share the same channels, so only their words tell them apart. Both sides
 * go through [AlertFingerprint.normalize], so marks and spacing in a broadcast cannot hide a match.
 */
object TriggerRules {
    fun match(message: String, activatePhrases: List<String>, clearPhrases: List<String>): TriggerMatch {
        val text = AlertFingerprint.normalize(message)
        val activate = activatePhrases.any { text.contains(AlertFingerprint.normalize(it)) }
        val clear = clearPhrases.any { text.contains(AlertFingerprint.normalize(it)) }
        return when {
            activate && clear -> TriggerMatch.BOTH_RULES
            activate -> TriggerMatch.ACTIVATE
            clear -> TriggerMatch.CLEAR
            else -> TriggerMatch.NO_RULE
        }
    }
}

/**
 * Only alerts delivered by the operating system's Cell Broadcast stack can fire a URL. Text read
 * from a screen or a notification is unverified, so it never can, whatever it says.
 */
object TriggerPolicy {
    fun decide(event: AlertEvent, settings: TriggerSettings): TriggerDecision {
        val trusted = event.verification == VerificationStatus.CELL_BROADCAST_STACK ||
            (event.verification == VerificationStatus.CELL_BROADCAST_STACK_LAB && settings.allowLabAlerts)
        return when {
            event.simulated -> TriggerDecision.SIMULATED
            !settings.armed -> TriggerDecision.NOT_ARMED
            !trusted -> TriggerDecision.NOT_FROM_CELL_BROADCAST_STACK
            event.alertClass.testOrExercise != false -> TriggerDecision.TEST_OR_EXERCISE
            else -> when (TriggerRules.match(event.message, settings.activatePhrases, settings.clearPhrases)) {
                TriggerMatch.ACTIVATE -> TriggerDecision.ACTIVATE
                TriggerMatch.CLEAR -> TriggerDecision.CLEAR
                TriggerMatch.NO_RULE -> TriggerDecision.NO_RULE_MATCHED
                TriggerMatch.BOTH_RULES -> TriggerDecision.BOTH_RULES_MATCHED
            }
        }
    }
}
