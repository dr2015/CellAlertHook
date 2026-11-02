package app.cellalerthook.diagnostics

import app.cellalerthook.config.ReceiverConfig
import app.cellalerthook.delivery.OkHttpAlertTransport
import app.cellalerthook.service.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

enum class TestStepResult { PASS, FAIL, SKIPPED }

data class ConnectionTestStep(
    val name: String,
    val result: TestStepResult,
    val detail: String,
    val durationMs: Long,
)

/** Walks the connection one layer at a time, so a failure names the layer that broke. */
class ConnectionTester(
    private val config: () -> ReceiverConfig,
    private val authToken: () -> String?,
    private val network: NetworkMonitor,
    private val transport: OkHttpAlertTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): List<ConnectionTestStep> = withContext(Dispatchers.IO) {
        val current = config()
        val steps = mutableListOf<ConnectionTestStep>()
        if (!current.isServerConfigured) {
            steps += ConnectionTestStep("configuration", TestStepResult.FAIL, "server_not_configured", 0)
            return@withContext steps
        }
        val bound = network.wifiNetwork
        val state = network.state.value
        steps += ConnectionTestStep(
            "network",
            if (bound != null) TestStepResult.PASS else TestStepResult.FAIL,
            "wifi=${state.wifiConnected} wifi_internet=${state.wifiHasValidatedInternet} default=${state.defaultTransport}",
            0,
        )

        val address = step(steps, "resolve") {
            val resolved = bound?.getByName(current.serverHost) ?: InetAddress.getByName(current.serverHost)
            resolved.hostAddress.orEmpty() to resolved
        } ?: return@withContext steps

        step(steps, "tcp_connect") {
            val socket = bound?.socketFactory?.createSocket() ?: Socket()
            socket.use {
                it.connect(InetSocketAddress(address, current.serverPort), current.requestTimeoutSeconds * 1000)
            }
            "port=${current.serverPort}" to Unit
        } ?: return@withContext steps

        step(steps, if (current.useTls) "tls_and_health" else "health") {
            val headers = buildMap {
                put("X-CellAlertHook-Device-Id", current.deviceId)
                authToken()?.let { put("Authorization", "Bearer $it") }
            }
            val response = transport.get(current.url(current.healthPath), headers)
            check(response.code in 200..299) { "http_${response.code}" }
            "http_${response.code}" to Unit
        }
        steps
    }

    private suspend fun <T> step(
        steps: MutableList<ConnectionTestStep>,
        name: String,
        block: suspend () -> Pair<String, T>,
    ): T? {
        val started = clock()
        return try {
            val (detail, value) = block()
            steps += ConnectionTestStep(name, TestStepResult.PASS, detail, clock() - started)
            value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = listOfNotNull(e.javaClass.simpleName, e.message?.take(120)).joinToString(": ")
            steps += ConnectionTestStep(name, TestStepResult.FAIL, detail, clock() - started)
            null
        }
    }
}
