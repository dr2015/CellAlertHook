package app.cellalerthook.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigValidatorTest {
    private val valid = ReceiverConfig(serverHost = "10.20.30.40")

    private fun problems(config: ReceiverConfig) = ConfigValidator.validate(config)

    @Test
    fun `defaults are valid and unconfigured`() {
        assertTrue(problems(ReceiverConfig()).isEmpty())
        assertEquals(false, ReceiverConfig().isServerConfigured)
    }

    @Test
    fun `a new install is not armed, so it can never call a URL`() {
        assertEquals(false, ReceiverConfig().triggerArmed)
        assertEquals(false, ReceiverConfig().triggerSettings().allowLabAlerts)
    }

    @Test
    fun `http is accepted for a device on a private network`() {
        listOf(
            "http://192.168.1.50/relay?on=1",
            "http://10.0.0.5:8080/hook/on",
            "http://172.16.4.9/on",
            "http://pa-controller/on",
            "http://device.example.local/on",
            "http://[fd00::5]/on",
        ).forEach { assertTrue(it, ConfigValidator.validateTriggerUrls(true, it, it).isEmpty()) }
    }

    @Test
    fun `http to a public host is refused, https is fine`() {
        val refused = listOf("http://8.8.8.8/on", "http://example.com/on", "http://172.32.0.1/on", "http://192.169.1.1/on")
        refused.forEach {
            assertTrue(it, ConfigProblem.TRIGGER_URL_INVALID in ConfigValidator.validateTriggerUrls(true, it, it))
        }
        assertTrue(ConfigValidator.validateTriggerUrls(true, "https://example.com/on?key=abc", "https://example.com/off").isEmpty())
    }

    @Test
    fun `a malformed URL or one with embedded credentials is refused`() {
        listOf("192.168.1.50/on", "ftp://192.168.1.50/on", "http://user:pass@192.168.1.50/on", "http:// bad").forEach {
            assertTrue(it, ConfigProblem.TRIGGER_URL_INVALID in ConfigValidator.validateTriggerUrls(false, it, ""))
        }
    }

    @Test
    fun `arming needs both URLs, and URLs may wait while disarmed`() {
        assertTrue(ConfigProblem.TRIGGER_URL_MISSING in ConfigValidator.validateTriggerUrls(true, "http://10.0.0.5/on", ""))
        assertTrue(ConfigValidator.validateTriggerUrls(false, "", "").isEmpty())
    }

    @Test
    fun `a phrase short enough to match nearly anything is refused`() {
        assertTrue(ConfigProblem.TRIGGER_PHRASE_INVALID in problems(valid.copy(activatePhrases = listOf("the"))))
    }

    @Test
    fun `arming needs both kinds of phrase`() {
        assertTrue(ConfigProblem.TRIGGER_PHRASE_INVALID in problems(valid.copy(triggerArmed = true, clearPhrases = emptyList())))
    }

    @Test
    fun `the trigger age limit must be sensible`() {
        assertTrue(ConfigProblem.TRIGGER_AGE_INVALID in problems(valid.copy(triggerMaxAgeMinutes = 0)))
    }

    @Test
    fun `active hours are off until someone sets them`() {
        assertEquals(false, ReceiverConfig().scheduleEnabled)
    }

    @Test
    fun `active hours must start before they end`() {
        assertTrue(ConfigProblem.SCHEDULE_INVALID in problems(valid.copy(scheduleStartMinute = 900, scheduleEndMinute = 420)))
        assertTrue(ConfigProblem.SCHEDULE_INVALID in problems(valid.copy(scheduleStartMinute = -1)))
    }

    @Test
    fun `active hours with no days are refused, because the on URL could never be called`() {
        assertTrue(ConfigProblem.SCHEDULE_INVALID in problems(valid.copy(scheduleEnabled = true, scheduleDays = emptySet())))
    }

    @Test
    fun `cleartext to a server is refused outside a private network`() {
        assertTrue(ConfigProblem.SERVER_TLS_REQUIRED in problems(valid.copy(serverHost = "example.com", useTls = false)))
        assertTrue(problems(valid.copy(serverHost = "10.0.2.2", useTls = false)).isEmpty())
    }

    @Test
    fun `TLS is the default`() {
        assertEquals(true, ReceiverConfig().useTls)
    }

    @Test
    fun `IP, hostname and IPv6 hosts are accepted`() {
        listOf("10.0.0.1", "alerts.example.local", "fd00::1").forEach {
            assertTrue(it, problems(valid.copy(serverHost = it)).isEmpty())
        }
    }

    @Test
    fun `a host with a scheme, path or space is refused`() {
        listOf("http://10.0.0.1", "10.0.0.1/api", "bad host").forEach {
            assertTrue(it, ConfigProblem.HOST_INVALID in problems(valid.copy(serverHost = it)))
        }
    }

    @Test
    fun `the test path may never equal the production path`() {
        assertTrue(ConfigProblem.TEST_PATH_EQUALS_PRODUCTION in problems(valid.copy(testAlertsPath = valid.alertsPath)))
        assertTrue(ConfigProblem.TEST_PATH_EQUALS_PRODUCTION in problems(valid.copy(testAlertsPath = valid.alertsPath + "/")))
    }

    @Test
    fun `out of range values are refused`() {
        assertTrue(ConfigProblem.PORT_INVALID in problems(valid.copy(serverPort = 70000)))
        assertTrue(ConfigProblem.PATH_INVALID in problems(valid.copy(alertsPath = "no-slash")))
        assertTrue(ConfigProblem.DEVICE_ID_INVALID in problems(valid.copy(deviceId = "has space")))
        assertTrue(ConfigProblem.RETRY_INVALID in problems(valid.copy(retryBaseSeconds = 0)))
        assertTrue(ConfigProblem.RETRY_INVALID in problems(valid.copy(retryBaseSeconds = 100, retryMaxSeconds = 50)))
        assertTrue(ConfigProblem.TIMEOUT_INVALID in problems(valid.copy(requestTimeoutSeconds = 0)))
    }

    @Test
    fun `URLs are built from their parts`() {
        assertEquals("https://10.0.0.1:8443/a", valid.copy(serverHost = "10.0.0.1").url("/a"))
        assertEquals("http://10.0.0.1:8080/a", valid.copy(serverHost = "10.0.0.1", serverPort = 8080, useTls = false).url("/a"))
        assertEquals("https://[fd00::1]:8443/a", valid.copy(serverHost = "fd00::1").url("/a"))
    }
}
