package app.sapsii.sapseed.edge.android.network

import app.sapsii.sapseed.edge.contract.DeviceLocationReporter
import app.sapsii.sapseed.edge.contract.PresenceResult
import app.sapsii.sapseed.edge.model.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class HttpDeviceLocationReporter(
    private val ingestionEndpoint: URL,
    private val authorizationHeader: () -> String,
    private val instanceExternalId: String? = null,
    private val connectTimeoutMilliseconds: Int = 5_000,
    private val readTimeoutMilliseconds: Int = 10_000,
) : DeviceLocationReporter {
    override suspend fun reportPosition(location: GeoPoint): PresenceResult {
        val connection = apiUrl(ingestionEndpoint, TELEMETRY_POSITION_PATH).openConnection() as HttpURLConnection
        try {
            val position = JSONObject().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                location.accuracyMeters?.let { put("accuracyMeters", it.toDouble()) }
            }
            val payload = JSONObject().apply {
                put("schemaVersion", 1)
                put("capturedAt", System.currentTimeMillis().toIsoTimestamp())
                instanceExternalId?.let { put("instanceId", it) }
                put("position", position)
            }
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMilliseconds
            connection.readTimeout = readTimeoutMilliseconds
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", authorizationHeader())
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
