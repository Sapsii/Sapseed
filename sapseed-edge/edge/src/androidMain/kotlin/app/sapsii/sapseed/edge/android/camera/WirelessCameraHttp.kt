package app.sapsii.sapseed.edge.android.camera

import java.io.EOFException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.SocketException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException

/**
 * Shared HTTP plumbing for user-entered wireless camera URLs.
 *
 * Two realities of cheap LAN camera firmware motivate this helper:
 * - Simple servers accept only TLS and kill plain-HTTP connections without sending a
 *   single byte, which Android's HttpURLConnection surfaces as "unexpected end of
 *   stream". [probe] therefore retries a dead plain-HTTP attempt over HTTPS.
 * - Those servers use self-signed certificates, so certificate validation is disabled
 *   for these explicit camera connections only. Everything else in the app keeps the
 *   platform defaults.
 */
internal object WirelessCameraHttp {
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 10_000
    private const val PROBE_CONNECT_TIMEOUT_MS = 4_000
    private const val PROBE_READ_TIMEOUT_MS = 6_000

    data class Probe(val url: URL, val contentType: String)

    fun normalizeStreamUrl(value: String): String {
        var normalized = value.trim()
        val lower = normalized.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            normalized = "http://$normalized"
        }
        val parsed = URL(normalized)
        val path = parsed.path.orEmpty()
        return if (path.isEmpty() || path == "/") "$normalized${if (normalized.endsWith('/')) "" else "/"}stream" else normalized
    }

    fun open(
        url: URL,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        if (connection is HttpsURLConnection) {
            connection.sslSocketFactory = trustAllContext.socketFactory
            connection.hostnameVerifier = trustAllHosts
        }
        return connection
    }

    /** GETs response headers and returns the working URL plus its content type. */
    fun probe(rawUrl: String): Probe {
        val first = URL(normalizeStreamUrl(rawUrl))
        val candidates = mutableListOf(first)
        if (first.protocol.equals("http", ignoreCase = true)) {
            val text = first.toString()
            candidates += URL("https" + text.substring(first.protocol.length))
        }
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                return probeOnce(candidate)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
                if (candidate.protocol.equals("https", ignoreCase = true) || !isNoResponseError(error)) {
                    throw error
                }
                // Plain HTTP produced no response at all: the server speaks TLS only.
            }
        }
        throw IllegalStateException(
            "Cannot reach wireless camera at $rawUrl over HTTP or HTTPS: ${lastError?.message}",
            lastError,
        )
    }

    private fun probeOnce(url: URL): Probe {
        val connection = open(url, PROBE_CONNECT_TIMEOUT_MS, PROBE_READ_TIMEOUT_MS)
        try {
            connection.connect()
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "Camera returned HTTP $code for $url" }
            val contentType = connection.contentType.orEmpty()
            check(contentType.isNotBlank()) { "Camera at $url returned no content type" }
            return Probe(url, contentType)
        } finally {
            connection.disconnect()
        }
    }

    private fun isNoResponseError(error: Throwable): Boolean =
        error is EOFException ||
            error is ProtocolException ||
            error is SocketException ||
            error is SSLException

    private val trustAllContext: SSLContext by lazy {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
    }

    private val trustAllHosts = HostnameVerifier { _, _ -> true }
}
