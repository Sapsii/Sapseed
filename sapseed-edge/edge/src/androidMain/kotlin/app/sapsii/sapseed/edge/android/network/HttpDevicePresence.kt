package app.sapsii.sapseed.edge.android.network

import app.sapsii.sapseed.edge.contract.DevicePresenceReporter
import app.sapsii.sapseed.edge.contract.PresenceResult
import java.net.HttpURLConnection
import java.net.URL

class HttpDevicePresence(
    private val ingestionEndpoint: URL,
    private val authorizationHeader: () -> String,
    private val connectTimeoutMilliseconds: Int = 5_000,
    private val readTimeoutMilliseconds: Int = 10_000,
) : DevicePresenceReporter {
    override suspend fun reportAlive(): PresenceResult {
        val connection = apiUrl(ingestionEndpoint, "/v1/devices/heartbeat").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMilliseconds
            connection.readTimeout = readTimeoutMilliseconds
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", authorizationHeader())
            val body = "{}".toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            return when (connection.responseCode) {
                in 200..299 -> PresenceResult.Alive
                401, 403 -> PresenceResult.CredentialRejected
                else -> PresenceResult.Unavailable
            }
        } catch (error: java.io.IOException) {
            return PresenceResult.Unavailable
        } finally {
            connection.disconnect()
        }
    }
}
