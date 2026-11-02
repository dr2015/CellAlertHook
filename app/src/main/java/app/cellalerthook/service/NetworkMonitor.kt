package app.cellalerthook.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkState(
    val wifiConnected: Boolean = false,
    val wifiHasValidatedInternet: Boolean = false,
    val defaultTransport: String = "none",
)

/**
 * Tracks Wi-Fi independently of the default route. The request drops the INTERNET
 * capability on purpose: an isolated private network must count as usable.
 */
class NetworkMonitor(
    context: Context,
    private val onUsable: () -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val stateFlow = MutableStateFlow(NetworkState())
    private var started = false

    @Volatile
    var wifiNetwork: Network? = null
        private set

    val state: StateFlow<NetworkState> = stateFlow.asStateFlow()

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
            stateFlow.value = stateFlow.value.copy(wifiConnected = true)
            onUsable()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            stateFlow.value = stateFlow.value.copy(
                wifiHasValidatedInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) wifiNetwork = null
            stateFlow.value = stateFlow.value.copy(wifiConnected = false, wifiHasValidatedInternet = false)
        }
    }

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val transport = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            stateFlow.value = stateFlow.value.copy(defaultTransport = transport)
            onUsable()
        }

        override fun onLost(network: Network) {
            stateFlow.value = stateFlow.value.copy(defaultTransport = "none")
        }
    }

    fun start() {
        if (started) return
        started = true
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.requestNetwork(wifiRequest, wifiCallback)
        connectivity.registerDefaultNetworkCallback(defaultCallback)
    }
}
