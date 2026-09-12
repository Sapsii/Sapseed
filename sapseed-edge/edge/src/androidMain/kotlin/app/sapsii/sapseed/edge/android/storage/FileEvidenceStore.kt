package app.sapsii.sapseed.edge.android.storage

import android.content.Context
import android.graphics.Bitmap
import app.sapsii.sapseed.edge.android.camera.toUprightBitmap
import app.sapsii.sapseed.edge.contract.EvidenceStore
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.VideoFrame
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileEvidenceStore(
    context: Context,
    directoryName: String = "evidence",
    private val jpegQuality: Int = 85,
) : EvidenceStore {
    private val directory = File(context.filesDir, directoryName).apply { mkdirs() }

    init {
        require(jpegQuality in 1..100) { "JPEG quality must be between 1 and 100" }
    }

    override suspend fun saveImage(frame: VideoFrame): EvidenceReference =
        withContext(Dispatchers.IO) {
            val fileName = "${frame.id.safeFileName()}-${frame.capturedAtEpochMilliseconds}.jpg"
            val destination = File(directory, fileName)
            // Observations from the same frame share this file, so it is encoded once.
            if (destination.exists()) {
                return@withContext EvidenceReference(
                    localId = fileName,
                    mediaType = "image/jpeg",
                    sizeBytes = destination.length(),
                )
            }
            val temporary = File(directory, "$fileName.tmp")
            val bitmap = frame.toUprightBitmap()
            try {
                FileOutputStream(temporary).use { fileOutput ->
                    val buffered = fileOutput.buffered()
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, buffered)) {
                        "Failed to encode evidence image"
                    }
                    buffered.flush()
                    fileOutput.fd.sync()
                }
                check(temporary.renameTo(destination)) { "Failed to commit evidence image" }
            } finally {
                bitmap.recycle()
                temporary.delete()
            }
            EvidenceReference(
                localId = fileName,
                mediaType = "image/jpeg",
                sizeBytes = destination.length(),
            )
        }

    override suspend fun read(reference: EvidenceReference): ByteArray = withContext(Dispatchers.IO) {
        resolve(reference).readBytes()
    }

    override suspend fun delete(reference: EvidenceReference) = withContext(Dispatchers.IO) {
        val file = resolve(reference)
        if (file.exists()) check(file.delete()) { "Failed to delete evidence ${reference.localId}" }
    }

    private fun resolve(reference: EvidenceReference): File {
        val file = File(directory, reference.localId)
        check(file.parentFile?.canonicalFile == directory.canonicalFile) { "Invalid evidence reference" }
        return file
    }
}

private fun String.safeFileName(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")
