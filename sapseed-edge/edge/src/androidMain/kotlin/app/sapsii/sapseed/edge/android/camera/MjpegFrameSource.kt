package app.sapsii.sapseed.edge.android.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.sapsii.sapseed.edge.contract.FrameSource
import app.sapsii.sapseed.edge.model.VideoFrame
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/** Reads an ESP32-CAM multipart MJPEG stream and keeps only its newest frame. */
class MjpegFrameSource(
    streamUrl: String,
    private val onPreviewFrame: (Bitmap) -> Unit = {},
    private val onConnectionChanged: (connected: Boolean, message: String) -> Unit = { _, _ -> },
) : FrameSource {
    private val closed = AtomicBoolean(false)
    private val frames = Channel<VideoFrame>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = VideoFrame::release,
    )
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Sapseed-MJPEG").apply { isDaemon = true }
    }
    private val url = URL(WirelessCameraHttp.normalizeStreamUrl(streamUrl))
    @Volatile private var connection: HttpURLConnection? = null

    init {
        executor.execute(::readWithReconnect)
    }

    override suspend fun nextFrame(): VideoFrame? = frames.receiveCatching().getOrNull()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connection?.disconnect()
        executor.shutdownNow()
        frames.close()
    }

    private fun readWithReconnect() {
        while (!closed.get()) {
            try {
                readStream()
            } catch (error: Throwable) {
                if (closed.get()) break
                onConnectionChanged(false, error.message ?: "Wireless camera disconnected")
                try {
                    Thread.sleep(RECONNECT_DELAY_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun readStream() {
        val activeConnection = WirelessCameraHttp.open(url).apply {
            setRequestProperty("Accept", "multipart/x-mixed-replace")
        }
        connection = activeConnection
        try {
            activeConnection.connect()
            check(activeConnection.responseCode == HttpURLConnection.HTTP_OK) {
                "Camera returned HTTP ${activeConnection.responseCode}"
            }
            val contentType = activeConnection.contentType.orEmpty()
            check(contentType.contains("multipart/x-mixed-replace", ignoreCase = true)) {
                "Camera did not return an MJPEG stream"
            }
            onConnectionChanged(true, "Wireless camera connected")
            BufferedInputStream(activeConnection.inputStream).use(::readParts)
        } finally {
            connection = null
            activeConnection.disconnect()
        }
    }

    private fun readParts(input: InputStream) {
        while (!closed.get()) {
            var line: String
            do {
                line = input.readAsciiLine()
            } while (!line.startsWith("--"))

            var contentLength = -1
            while (true) {
                line = input.readAsciiLine()
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0 && line.substring(0, separator).equals("Content-Length", ignoreCase = true)) {
                    contentLength = line.substring(separator + 1).trim().toIntOrNull() ?: -1
                }
            }
            check(contentLength in 1..MAX_JPEG_BYTES) { "Invalid MJPEG frame length: $contentLength" }

            val jpeg = ByteArray(contentLength)
            input.readFully(jpeg)
            val bitmap = BitmapFactory.decodeByteArray(
                jpeg,
                0,
                jpeg.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            ) ?: error("Could not decode camera JPEG")
            val frame = bitmap.toMjpegFrame()
            onPreviewFrame(bitmap)
            if (frames.trySend(frame).isFailure) frame.release()
        }
    }

    private fun Bitmap.toMjpegFrame(): DecodedRgbaFrame {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val rgba = ByteBuffer.allocateDirect(pixels.size * 4)
        pixels.forEach { pixel ->
            rgba.put(((pixel ushr 16) and 0xff).toByte())
            rgba.put(((pixel ushr 8) and 0xff).toByte())
            rgba.put((pixel and 0xff).toByte())
            rgba.put(((pixel ushr 24) and 0xff).toByte())
        }
        rgba.rewind()
        return DecodedRgbaFrame(width, height, rgba)
    }

    private fun InputStream.readAsciiLine(): String {
        val line = StringBuilder()
        while (true) {
            val value = read()
            if (value < 0) throw EOFException("MJPEG stream ended")
            if (value == '\n'.code) return line.toString().trimEnd('\r')
            check(line.length < MAX_HEADER_LINE_LENGTH) { "MJPEG header line is too long" }
            line.append(value.toChar())
        }
    }

    private fun InputStream.readFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = read(bytes, offset, bytes.size - offset)
            if (count < 0) throw EOFException("MJPEG frame ended early")
            offset += count
        }
    }

    companion object {
        private const val RECONNECT_DELAY_MS = 1_000L
        private const val MAX_JPEG_BYTES = 2 * 1024 * 1024
        private const val MAX_HEADER_LINE_LENGTH = 1_024
    }
}
