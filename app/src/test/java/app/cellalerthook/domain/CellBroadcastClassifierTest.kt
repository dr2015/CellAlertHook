package app.cellalerthook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellBroadcastClassifierTest {
    @Test
    fun `mcc420 channels map per the AOSP configuration`() {
        val expected = mapOf(
            4370 to AlertClass.NATIONAL_ALERT,
            4383 to AlertClass.NATIONAL_ALERT,
            4371 to AlertClass.EXTREME_THREAT,
            4372 to AlertClass.EXTREME_THREAT,
            4384 to AlertClass.EXTREME_THREAT,
            4385 to AlertClass.EXTREME_THREAT,
            4373 to AlertClass.SEVERE_THREAT,
            4378 to AlertClass.SEVERE_THREAT,
            4386 to AlertClass.SEVERE_THREAT,
            4391 to AlertClass.SEVERE_THREAT,
            4379 to AlertClass.REGIONAL_EMERGENCY_OR_AMBER,
            4392 to AlertClass.REGIONAL_EMERGENCY_OR_AMBER,
            4380 to AlertClass.REQUIRED_MONTHLY_TEST,
            4393 to AlertClass.REQUIRED_MONTHLY_TEST,
            4381 to AlertClass.EXERCISE,
            4394 to AlertClass.EXERCISE,
            4355 to AlertClass.ETWS_TEST,
        )
        expected.forEach { (id, alertClass) -> assertEquals("message id $id", alertClass, CellBroadcastClassifier.classify(id)) }
    }

    @Test
    fun `an unassigned identifier stays unknown`() {
        assertEquals(AlertClass.UNKNOWN, CellBroadcastClassifier.classify(50))
        assertEquals(AlertClass.UNKNOWN, CellBroadcastClassifier.classify(0x1130))
    }

    @Test
    fun `test classes are marked, real classes are not, unknown says so`() {
        assertEquals(true, AlertClass.EXERCISE.testOrExercise)
        assertEquals(true, AlertClass.REQUIRED_MONTHLY_TEST.testOrExercise)
        assertEquals(false, AlertClass.NATIONAL_ALERT.testOrExercise)
        assertNull(AlertClass.UNKNOWN.testOrExercise)
    }

    @Test
    fun `severity maps CMAS values and treats everything else as unknown`() {
        assertEquals(Severity.EXTREME, CellBroadcastClassifier.severity(0))
        assertEquals(Severity.SEVERE, CellBroadcastClassifier.severity(1))
        assertEquals(Severity.UNKNOWN, CellBroadcastClassifier.severity(-1))
        assertEquals(Severity.UNKNOWN, CellBroadcastClassifier.severity(null))
    }

    @Test
    fun `serial number fields follow TS 23-041`() {
        val metadata = CellBroadcastMetadata(
            rowId = 1, messageId = 4370, serialNumber = 49808, plmn = "42001", lac = null, cid = null,
            slotIndex = null, subscriptionId = null, receivedTimeMs = 0, messageFormat = null,
            messagePriority = null, dataCodingScheme = null, cmasMessageClass = null, cmasCategory = null,
            cmasResponseType = null, cmasSeverity = null, cmasUrgency = null, cmasCertainty = null,
            etwsWarningType = null, displayed = null,
        )
        assertEquals(3, metadata.geographicalScope)
        assertEquals(0x29, metadata.messageCode)
        assertEquals(0, metadata.updateNumber)
        assertEquals("cb|42001|4370|49808", metadata.dedupKey)
    }

    @Test
    fun `only a retail release build counts as production`() {
        assertEquals(true, BuildProvenance("user", "release-keys").isProductionBuild)
        assertEquals(false, BuildProvenance("userdebug", "dev-keys").isProductionBuild)
        assertEquals(false, BuildProvenance("user", "dev-keys").isProductionBuild)
        assertEquals(false, BuildProvenance("eng", "test-keys").isProductionBuild)
    }
}
