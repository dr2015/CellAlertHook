package app.cellalerthook.delivery

import android.net.Network
import app.cellalerthook.config.ReceiverConfig
import app.cellalerthook.domain.TriggerMethod
import app.cellalerthook.service.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Talks to the configured servers over the Wi-Fi network specifically.
 *
 * Android moves the default route to mobile data when Wi-Fi has no internet access, which
 * is the normal state of an isolated private network. Sockets are therefore bound to the
 * Wi-Fi network object instead of following the default route.
 */
class OkHttpAlertTransport(
    private val config: () -> ReceiverConfig,
    private val network: NetworkMonitor,
) : AlertTransport, TriggerTransport {
    private data class ClientKey(
        val timeoutSeconds: Int,
        val pinnedCaPem: String,
        val network: Network?,
    )

    private var cachedKey: ClientKey? = null
    private var cachedClient: OkHttpClient? = null

    override suspend fun post(request: TransportRequest): TransportResponse = withContext(Dispatchers.IO) {
        val httpRequest = Request.Builder()
            .url(request.url)
            .post(request.body.toRequestBody(JSON))
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client().newCall(httpRequest).execute().use { response ->
            TransportResponse(response.code, response.body?.string().orEmpty())
        }
    }

    suspend fun get(url: String, headers: Map<String, String>): TransportResponse = withContext(Dispatchers.IO) {
        val httpRequest = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client().newCall(httpRequest).execute().use { response ->
            TransportResponse(response.code, response.body?.string().orEmpty())
        }
    }

    override suspend fun call(method: TriggerMethod, url: String): Int = withContext(Dispatchers.IO) {
        val httpRequest = Request.Builder()
            .url(url)
            .apply { if (method == TriggerMethod.POST) post(ByteArray(0).toRequestBody()) }
            .build()
        client().newCall(httpRequest).execute().use { it.code }
    }

    /**
     * Connects and completes TLS with the real settings, then hangs up without sending a request,
     * so a live URL can be checked without any risk of firing it.
     */
    suspend fun checkConnection(url: String) = withContext(Dispatchers.IO) {
        val target = url.toHttpUrl()
        val client = client()
        client.socketFactory.createSocket().use { socket ->
            socket.soTimeout = client.readTimeoutMillis
            socket.connect(InetSocketAddress(client.dns.lookup(target.host).first(), target.port), client.connectTimeoutMillis)
            if (target.isHttps) {
                (client.sslSocketFactory.createSocket(socket, target.host, target.port, true) as SSLSocket).use { tls ->
                    tls.startHandshake()
                    check(client.hostnameVerifier.verify(target.host, tls.session)) { "hostname_mismatch" }
                }
            }
        }
    }

    @Synchronized
    private fun client(): OkHttpClient {
        val current = config()
        val key = ClientKey(current.requestTimeoutSeconds, current.pinnedCaPem, boundNetwork(current))
        cachedClient?.takeIf { key == cachedKey }?.let { return it }
        val built = build(key)
        cachedKey = key
        cachedClient = built
        return built
    }

    private fun boundNetwork(current: ReceiverConfig): Network? =
        if (isLoopback(current.serverHost)) null else network.wifiNetwork

    private fun isLoopback(host: String) =
        host == "localhost" || host == "::1" || host.startsWith("127.")

    private fun build(key: ClientKey): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(key.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(key.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(key.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(key.timeoutSeconds * 2L, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // An alert may follow hours of silence. A pooled socket the network has quietly
            // dropped would stall the send until the read timeout, so never reuse one.
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            // The queue owns retries and every attempt goes in the audit trail.
            .followRedirects(false)
        key.network?.let { bound ->
            builder.socketFactory(bound.socketFactory)
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = bound.getAllByName(hostname).toList()
            })
        }
        if (key.pinnedCaPem.isNotBlank()) {
            val trustManager = trustManagerFor(key.pinnedCaPem)
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
            builder.sslSocketFactory(context.socketFactory, trustManager)
        }
        return builder.build()
    }

    /** Trusts only the pinned certificate. Hostname verification stays on, so the SAN must match. */
    private fun trustManagerFor(pem: String): X509TrustManager {
        val certificates = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII)))
        require(certificates.isNotEmpty()) { "Pinned CA contains no certificate" }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { index, certificate -> setCertificateEntry("ca$index", certificate) }
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
