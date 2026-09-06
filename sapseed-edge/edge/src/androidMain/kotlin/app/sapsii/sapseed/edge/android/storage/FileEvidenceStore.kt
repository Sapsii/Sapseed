package app.sapsii.sapseed.edge.android.storage

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import app.sapsii.sapseed.edge.android.camera.AndroidVideoFrame
import app.sapsii.sapseed.edge.contract.EvidenceStore
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.VideoFrame
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
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

    override suspend fun saveImage(eventId: String, frame: VideoFrame): EvidenceReference =
        withContext(Dispatchers.IO) {
            require(frame is AndroidVideoFrame) { "FileEvidenceStore requires an AndroidVideoFrame" }
            val fileName = "${eventId.safeFileName()}-${frame.capturedAtEpochMilliseconds}.jpg"
            val destination = File(directory, fileName)
            val temporary = File(directory, "$fileName.tmp")
            try {
                FileOutputStream(temporary).use { fileOutput ->
                    val buffered = fileOutput.buffered()
                    frame.image.writeJpeg(buffered, jpegQuality)
                    buffered.flush()
                    fileOutput.fd.sync()
                }
                check(temporary.renameTo(destination)) { "Failed to commit evidence image" }
            } finally {
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

private fun ImageProxy.writeJpeg(output: OutputStream, quality: Int) {
    when (format) {
        ImageFormat.JPEG -> {
            val buffer = planes.single().buffer.duplicate().apply { rewind() }
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            output.write(bytes)
        }

        ImageFormat.YUV_420_888 -> {
            val image = YuvImage(toNv21(), ImageFormat.NV21, width, height, null)
            check(image.compressToJpeg(Rect(0, 0, width, height), quality, output)) {
                "Failed to encode evidence image"
            }
        }

        else -> error("Unsupported camera image format: $format")
    }
}

private fun ImageProxy.toNv21(): ByteArray {
    val output = ByteArray(width * height * 3 / 2)
    copyPlane(planes[0], width, height, output, offset = 0, outputPixelStride = 1)
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    copyPlane(planes[2], chromaWidth, chromaHeight, output, width * height, outputPixelStride = 2)
    copyPlane(planes[1], chromaWidth, chromaHeight, output, width * height + 1, outputPixelStride = 2)
    return output
}

private fun copyPlane(
    plane: ImageProxy.PlaneProxy,
    planeWidth: Int,
    planeHeight: Int,
    output: ByteArray,
    offset: Int,
    outputPixelStride: Int,
) {
    val buffer = plane.buffer.duplicate()
    var outputIndex = offset
    repeat(planeHeight) { row ->
        val rowOffset = row * plane.rowStride
        repeat(planeWidth) { column ->
            output[outputIndex] = buffer.get(rowOffset + column * plane.pixelStride)
            outputIndex += outputPixelStride
        }
    }
}

private fun String.safeFileName(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")
