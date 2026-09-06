package app.sapsii.sapseed.edge.android.network

import app.sapsii.sapseed.edge.android.storage.toJson
import app.sapsii.sapseed.edge.contract.EventUploader
import app.sapsii.sapseed.edge.contract.EvidenceStore
import app.sapsii.sapseed.edge.contract.UploadResult
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.UrbanEvent
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpEventUploader(
    private val endpoint: URL,
    private val evidenceStore: EvidenceStore,
    private val authorizationHeader: (() -> String?)? = null,
    private val connectTimeoutMilliseconds: Int = 10_000,
    private val readTimeoutMilliseconds: Int = 15_000,
) : EventUploader {
    override suspend fun upload(event: UrbanEvent): UploadResult = withContext(Dispatchers.IO) {
        if (event.evidence.any { !it.mediaType.startsWith("image/") }) {
            return@withContext UploadResult.Rejected("Only image evidence is supported")
        }

        val boundary = "sapseed-${UUID.randomUUID()}"
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMilliseconds
            connection.readTimeout = readTimeoutMilliseconds
            connection.setChunkedStreamingMode(64 * 1024)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("Idempotency-Key", event.id)
            authorizationHeader?.invoke()?.let { value ->
                connection.setRequestProperty("Authorization", value)
            }
            DataOutputStream(connection.outputStream.buffered()).use { output ->
                output.writePartHeader(boundary, name = "metadata", contentType = "application/json")
                output.write(event.toJson().toString().toByteArray(Charsets.UTF_8))
                output.writeCrlf()

                event.evidence.forEach { reference ->
                    output.writePartHeader(
                        boundary = boundary,
                        name = "photo",
                        fileName = reference.safeFileName(),
                        contentType = reference.mediaType,
                    )
                    output.write(evidenceStore.read(reference))
                    output.writeCrlf()
                }
                output.writeText("--$boundary--\r\n")
            }

            when (val status = connection.responseCode) {
                in 200..299 -> UploadResult.Accepted
                408, 425, 429, in 500..599 -> UploadResult.RetryLater
                else -> UploadResult.Rejected("Backend returned HTTP $status")
            }
        } catch (_: FileNotFoundException) {
            UploadResult.Rejected("Local photo evidence is missing")
        } catch (_: java.io.IOException) {
            UploadResult.RetryLater
        } finally {
            connection.disconnect()
        }
    }
}

private fun DataOutputStream.writePartHeader(
    boundary: String,
    name: String,
    contentType: String,
    fileName: String? = null,
) {
    writeText("--$boundary\r\n")
    val disposition = buildString {
        append("Content-Disposition: form-data; name=\"")
        append(name)
        append('"')
        fileName?.let {
            append("; filename=\"")
            append(it)
            append('"')
        }
    }
    writeText("$disposition\r\n")
    writeText("Content-Type: $contentType\r\n\r\n")
}

private fun DataOutputStream.writeCrlf() = writeText("\r\n")

private fun DataOutputStream.writeText(value: String) = write(value.toByteArray(Charsets.UTF_8))

private fun EvidenceReference.safeFileName(): String =
    localId.replace(Regex("[^A-Za-z0-9._-]"), "_")
