package app.sapsii.sapseed.edge.android.network

import app.sapsii.sapseed.edge.contract.BatchUploadResult
import app.sapsii.sapseed.edge.contract.EvidenceStore
import app.sapsii.sapseed.edge.contract.ObservationUploader
import app.sapsii.sapseed.edge.contract.UploadItemResult
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.UrbanObservation
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class HttpEventUploader(
    private val endpoint: URL,
    private val authorizationHeader: () -> String,
    private val evidenceStore: EvidenceStore,
    private val softwareName: String,
    private val softwareVersion: String,
    private val modelName: String,
    private val modelVersion: String,
    private val modelRuntime: String? = null,
    private val tripId: String? = null,
    private val connectTimeoutMilliseconds: Int = 10_000,
    private val readTimeoutMilliseconds: Int = 15_000,
) : ObservationUploader {
    init {
        require(softwareName.isNotBlank()) { "Software name cannot be blank" }
        require(softwareVersion.isNotBlank()) { "Software version cannot be blank" }
        require(modelName.isNotBlank()) { "Model name cannot be blank" }
        require(modelVersion.isNotBlank()) { "Model version cannot be blank" }
    }

    override suspend fun upload(observations: List<UrbanObservation>): BatchUploadResult = withContext(Dispatchers.IO) {
        require(observations.isNotEmpty()) { "Observation batch cannot be empty" }
        require(observations.size <= 100) { "Observation batch cannot exceed 100 items" }

        try {
            val evidenceIds = uploadEvidence(observations)
            val batchId = UUID.randomUUID().toString()
            val body = createBatchJson(batchId, observations, evidenceIds).toString().toByteArray(Charsets.UTF_8)
            val connection = endpoint.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = connectTimeoutMilliseconds
                connection.readTimeout = readTimeoutMilliseconds
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Idempotency-Key", batchId)
                connection.setRequestProperty("Authorization", authorizationHeader())
                connection.outputStream.use { it.write(body) }

                when (val status = connection.responseCode) {
                    in 200..299 -> parseCompletedResponse(connection.inputStream.bufferedReader().use { it.readText() })
                    401, 403 -> BatchUploadResult.CredentialRejected
                    408, 409, 425, 429, in 500..599 -> BatchUploadResult.RetryLater
                    else -> BatchUploadResult.Completed(
                        observations.associate { it.id to UploadItemResult.Rejected("Backend returned HTTP $status") },
                    )
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: java.io.IOException) {
            BatchUploadResult.RetryLater
        }
    }

    private suspend fun uploadEvidence(observations: List<UrbanObservation>): Map<String, List<String>> {
        val idsByCapture = mutableMapOf<String, String>()
        val uploaded = mutableMapOf<String, List<String>>()
        for (observation in observations) {
            val ids = mutableListOf<String>()
            for (reference in observation.evidence) {
                // Observations from one frame share a capture, so the reservation, the PUT and the
                // completion are performed once per image rather than once per observation.
                val evidenceId = idsByCapture[reference.localId]
                    ?: uploadCapture(reference).also { idsByCapture[reference.localId] = it }
                ids += evidenceId
            }
            uploaded[observation.id] = ids
        }
        return uploaded
    }

    private suspend fun uploadCapture(reference: EvidenceReference): String {
        val bytes = evidenceStore.read(reference)
        val evidenceId = UUID.nameUUIDFromBytes(reference.localId.toByteArray()).toString()
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        val upload = reserveEvidence(evidenceId, reference, checksum)
        putEvidence(upload, bytes)
        completeEvidence(evidenceId)
        return evidenceId
    }

    private fun reserveEvidence(evidenceId: String, reference: EvidenceReference, checksum: String): SignedUpload {
        val body = JSONObject().put(
            "items",
            JSONArray().put(JSONObject().apply {
                put("clientEvidenceId", evidenceId)
                put("contentType", reference.mediaType)
                put("byteSize", reference.sizeBytes)
                put("checksumSha256", checksum)
            }),
        ).toString().toByteArray(Charsets.UTF_8)
        val connection = apiUrl("/v1/evidence/reservations").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            configure(connection)
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) throw java.io.IOException("Evidence reservation failed")
            val item = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .getJSONArray("items").getJSONObject(0).getJSONObject("upload")
            val headers = item.getJSONObject("headers")
            return SignedUpload(
                url = URL(item.getString("url")),
                headers = buildMap { headers.keys().forEach { key -> put(key, headers.getString(key)) } },
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun putEvidence(upload: SignedUpload, bytes: ByteArray) {
        val connection = upload.url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMilliseconds
            connection.readTimeout = readTimeoutMilliseconds
            connection.setFixedLengthStreamingMode(bytes.size)
            upload.headers.forEach(connection::setRequestProperty)
            connection.outputStream.use { it.write(bytes) }
            if (connection.responseCode !in 200..299) throw java.io.IOException("Evidence upload failed")
        } finally {
            connection.disconnect()
        }
    }

    private fun completeEvidence(evidenceId: String) {
        val connection = apiUrl("/v1/evidence/$evidenceId/complete").openConnection() as HttpURLConnection
        try {
            val body = "{}".toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.doOutput = true
            configure(connection)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) throw java.io.IOException("Evidence completion failed")
        } finally {
            connection.disconnect()
        }
    }

    private fun configure(connection: HttpURLConnection) {
        connection.connectTimeout = connectTimeoutMilliseconds
        connection.readTimeout = readTimeoutMilliseconds
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", authorizationHeader())
    }

    private fun apiUrl(path: String): URL = apiUrl(endpoint, path)

    private fun createBatchJson(
        batchId: String,
        observations: List<UrbanObservation>,
        evidenceIds: Map<String, List<String>>,
    ) = JSONObject().apply {
        put("schemaVersion", 1)
        put("batchId", batchId)
        tripId?.let { put("tripId", it) }
        put("software", JSONObject().apply {
            put("name", softwareName)
            put("version", softwareVersion)
        })
        put("observations", JSONArray(observations.map { observationJson(it, evidenceIds[it.id].orEmpty()) }))
    }

    private fun observationJson(observation: UrbanObservation, evidenceIds: List<String>) = JSONObject().apply {
        put("clientEventId", observation.id)
        put("observationType", "object_detection")
        put("capturedAt", observation.capturedAtEpochMilliseconds.toIsoTimestamp())
        put("position", JSONObject().apply {
            put("latitude", observation.location.latitude)
            put("longitude", observation.location.longitude)
            observation.location.accuracyMeters?.let { put("accuracyMeters", it.toDouble()) }
        })
        put("model", JSONObject().apply {
            put("name", modelName)
            put("version", modelVersion)
            modelRuntime?.let { put("runtime", it) }
        })
        put("detection", JSONObject().apply {
            put("classId", observation.detectionClass.classId)
            put("className", observation.detectionClass.wireName)
            put("confidence", observation.confidence.toDouble())
            put("boundingBox", JSONObject().apply {
                put("left", observation.boundingBox.left.toDouble())
                put("top", observation.boundingBox.top.toDouble())
                put("right", observation.boundingBox.right.toDouble())
                put("bottom", observation.boundingBox.bottom.toDouble())
            })
            observation.trackingId?.let { put("trackingId", it) }
        })
        put("camera", JSONObject().apply {
            put("id", observation.cameraId)
            put("frameId", observation.frameId)
        })
        put("evidenceIds", JSONArray(evidenceIds))
        put("edgeConfidence", observation.confidence.toDouble())
        put("payload", JSONObject())
        put("metadata", JSONObject(observation.metadata))
    }

    private fun parseCompletedResponse(body: String): BatchUploadResult {
        val results = JSONObject(body).getJSONArray("results")
        val byId = buildMap {
            repeat(results.length()) { index ->
                val result = results.getJSONObject(index)
                val observationId = result.optString("clientEventId")
                if (observationId.isBlank()) return@repeat
                when (result.getString("status")) {
                    "accepted", "duplicate" -> put(observationId, UploadItemResult.Accepted)
                    "rejected" -> put(
                        observationId,
                        UploadItemResult.Rejected(
                            result.optJSONObject("error")?.optString("message") ?: "Observation rejected",
                        ),
                    )
                }
            }
        }
        return BatchUploadResult.Completed(byId)
    }
}

private data class SignedUpload(val url: URL, val headers: Map<String, String>)

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
