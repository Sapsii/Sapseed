package app.sapsii.sapseed.edge.android.camera

import android.graphics.Bitmap
import app.sapsii.sapseed.edge.contract.FrameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred

/**
 * Connects a manually entered wireless camera URL to the right frame source.
 *
 * Probes the URL (upgrading plain HTTP to HTTPS when the server answers plaintext
 * with silence), then dispatches on the returned content type: multipart MJPEG goes
 * to [MjpegFrameSource], raw H.264 goes to [H264FrameSource]. The returned source
 * has already reported its first successful connection, so callers can enable
 * downstream controls immediately.
 */
object WirelessCameraSources {
    suspend fun connect(
        address: String,
        onPreviewFrame: (Bitmap) -> Unit = {},
        onConnectionChanged: (connected: Boolean, message: String) -> Unit = { _, _ -> },
    ): FrameSource = withContext(Dispatchers.IO) {
        val probe = WirelessCameraHttp.probe(address)
        val contentType = probe.contentType
        val resolvedUrl = probe.url.toString()
        val firstResult = CompletableDeferred<Unit>()
        val forward: (Boolean, String) -> Unit = { connected, message ->
            onConnectionChanged(connected, message)
            if (connected) {
                firstResult.complete(Unit)
            } else if (!firstResult.isCompleted) {
                firstResult.completeExceptionally(IllegalStateException(message))
            }
        }
        val source: FrameSource = when {
            isMjpegContentType(contentType) -> MjpegFrameSource(resolvedUrl, onPreviewFrame, forward)
            isH264ContentType(contentType) -> H264FrameSource(resolvedUrl, onPreviewFrame, forward)
            else -> throw IllegalStateException(
                "Camera returned $contentType; expected an MJPEG or H.264 stream",
            )
        }
        try {
            withTimeout(FIRST_CONNECT_TIMEOUT_MS) { firstResult.await() }
        } catch (error: Throwable) {
            source.close()
            if (error is TimeoutCancellationException) {
                throw IllegalStateException("Timed out connecting to $resolvedUrl")
            }
            throw error
        }
        source
    }

    private fun isMjpegContentType(contentType: String): Boolean =
        contentType.contains("multipart", ignoreCase = true)

    private fun isH264ContentType(contentType: String): Boolean {
        if (contentType.contains("h265", ignoreCase = true) ||
            contentType.contains("hevc", ignoreCase = true)
        ) {
            throw IllegalStateException("Camera returned $contentType; only H.264 is supported, not HEVC")
        }
        return H264FrameSource.isH264ContentType(contentType)
    }

    private const val FIRST_CONNECT_TIMEOUT_MS = 15_000L
}
