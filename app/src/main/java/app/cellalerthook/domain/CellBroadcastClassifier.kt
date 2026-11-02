package app.cellalerthook.domain

/**
 * Alert class derived from the 3GPP TS 23.041 message identifier.
 *
 * [testOrExercise] matters to the server: an official drill is a genuine government
 * broadcast and still must not be treated as a real emergency.
 */
enum class AlertClass(val wireName: String, val testOrExercise: Boolean?) {
    NATIONAL_ALERT("national_alert", false),
    EXTREME_THREAT("extreme_threat", false),
    SEVERE_THREAT("severe_threat", false),

    /** AMBER in the standard. Some national configurations in AOSP use this channel for emergency alerts. */
    REGIONAL_EMERGENCY_OR_AMBER("regional_emergency_or_amber", false),
    REQUIRED_MONTHLY_TEST("required_monthly_test", true),
    EXERCISE("exercise", true),
    OPERATOR_DEFINED("operator_defined", null),
    PUBLIC_SAFETY("public_safety", false),
    STATE_LOCAL_TEST("state_local_test", true),
    ETWS_EARTHQUAKE("etws_earthquake", false),
    ETWS_TSUNAMI("etws_tsunami", false),
    ETWS_EARTHQUAKE_TSUNAMI("etws_earthquake_tsunami", false),
    ETWS_TEST("etws_test", true),
    ETWS_OTHER("etws_other", false),
    UNKNOWN("unknown", null),
}

object CellBroadcastClassifier {
    private const val CMAS_SEVERITY_EXTREME = 0
    private const val CMAS_SEVERITY_SEVERE = 1

    fun classify(messageId: Int): AlertClass = when (messageId) {
        0x1100 -> AlertClass.ETWS_EARTHQUAKE
        0x1101 -> AlertClass.ETWS_TSUNAMI
        0x1102 -> AlertClass.ETWS_EARTHQUAKE_TSUNAMI
        0x1103 -> AlertClass.ETWS_TEST
        0x1104 -> AlertClass.ETWS_OTHER
        0x1112, 0x111F -> AlertClass.NATIONAL_ALERT
        in 0x1113..0x1114, in 0x1120..0x1121 -> AlertClass.EXTREME_THREAT
        in 0x1115..0x111A, in 0x1122..0x1127 -> AlertClass.SEVERE_THREAT
        0x111B, 0x1128 -> AlertClass.REGIONAL_EMERGENCY_OR_AMBER
        0x111C, 0x1129 -> AlertClass.REQUIRED_MONTHLY_TEST
        0x111D, 0x112A -> AlertClass.EXERCISE
        0x111E, 0x112B -> AlertClass.OPERATOR_DEFINED
        0x112C, 0x112D -> AlertClass.PUBLIC_SAFETY
        0x112E, 0x112F -> AlertClass.STATE_LOCAL_TEST
        else -> AlertClass.UNKNOWN
    }

    fun severity(cmasSeverity: Int?): Severity = when (cmasSeverity) {
        CMAS_SEVERITY_EXTREME -> Severity.EXTREME
        CMAS_SEVERITY_SEVERE -> Severity.SEVERE
        else -> Severity.UNKNOWN
    }
}
