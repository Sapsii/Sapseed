package app.sapsii.sapseed.edge.android.camera

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import app.sapsii.sapseed.edge.contract.FrameSource
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * Reads a raw H.264 Annex-B stream over HTTP (Content-Type: video/h264), decodes it
 * with MediaCodec, and keeps only the newest frame as the H.264 counterpart to
 * [MjpegFrameSource]. The decoded YUV output is converted to the same R, G, B, A
 * byte layout CameraX produces, so wireless H.264 frames flow through
 * the same runtimes as mobile-camera frames.
 */
class H264FrameSource(
    streamUrl: String,
    private val onPreviewFrame: (Bitmap) -> Unit = {},
    private val onConnectionChanged: (connected: Boolean, message: String) -> Unit = { _, _ -> },
) : FrameSource {
    private val closed = AtomicBoolean(false)
    private val frames = Channel<DecodedRgbaFrame>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = DecodedRgbaFrame::release,
    )
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Sapseed-H264").apply { isDaemon = true }
    }
    private val url = URL(WirelessCameraHttp.normalizeStreamUrl(streamUrl))
    private val rgbaPool = ArrayDeque<ByteBuffer>()
    private var lastPreviewAtMs = 0L
    @Volatile private var connection: HttpURLConnection? = null

    init {
        executor.execute(::readWithReconnect)
    }

    override suspend fun nextFrame(): DecodedRgbaFrame? = frames.receiveCatching().getOrNull()

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
            setRequestProperty("Accept", "video/h264")
        }
        connection = activeConnection
        try {
            activeConnection.connect()
            check(activeConnection.responseCode == HttpURLConnection.HTTP_OK) {
                "Camera returned HTTP ${activeConnection.responseCode}"
            }
            val contentType = activeConnection.contentType.orEmpty()
            check(isH264ContentType(contentType)) {
                "Camera returned $contentType, expected an H.264 stream"
            }
            onConnectionChanged(true, "Wireless camera connected")
            BufferedInputStream(activeConnection.inputStream).use(::decodeStream)
        } finally {
            connection = null
            activeConnection.disconnect()
        }
    }

    private fun decodeStream(input: InputStream) {
        val splitter = AnnexBSplitter()
        val state = DecoderState()
        // NAL units seen before the first SPS (AUD, SEI, early PPS) are replayed
        // in order once the decoder starts.
        val backlog = ArrayDeque<ByteArray>()
        var lastPps: ByteArray? = null
        try {
            val chunk = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get()) {
                val count = input.read(chunk)
                if (count < 0) throw EOFException("Camera closed the H.264 stream")
                splitter.append(chunk, 0, count)
                while (true) {
                    val nal = splitter.nextNal() ?: break
                    when (nal.type) {
                        NAL_SPS -> {
                            val size = runCatching { SpsParser.parseSize(nal.bytes, nal.headerOffset + 1) }
                                .getOrElse { FALLBACK_WIDTH to FALLBACK_HEIGHT }
                            val codec = state.codec
                            if (codec == null) {
                                state.codec = startDecoder(size)
                                state.width = size.first
                                state.height = size.second
                                backlog.forEach { queueNal(state, it) }
                                backlog.clear()
                                queueNal(state, nal.bytes)
                            } else if (size.first != state.width || size.second != state.height) {
                                restartDecoder(state, size)
                                queueNal(state, nal.bytes)
                                lastPps?.let { queueNal(state, it) }
                            } else {
                                queueNal(state, nal.bytes)
                            }
                        }

                        NAL_PPS -> {
                            lastPps = nal.bytes
                            feedOrBuffer(state, backlog, nal.bytes)
                        }

                        else -> feedOrBuffer(state, backlog, nal.bytes)
                    }
                }
                drainOutputs(state)
            }
        } finally {
            runCatching { state.codec?.stop() }
            runCatching { state.codec?.release() }
            state.codec = null
        }
    }

    private fun feedOrBuffer(state: DecoderState, backlog: ArrayDeque<ByteArray>, nal: ByteArray) {
        if (state.codec == null) {
            backlog.addLast(nal)
            if (backlog.size > MAX_BACKLOG_NALS) backlog.removeFirst()
        } else {
            queueNal(state, nal)
        }
    }

    private fun startDecoder(size: Pair<Int, Int>): MediaCodec {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.first, size.second),
            null,
            null,
            0,
        )
        codec.start()
        return codec
    }

    private fun restartDecoder(state: DecoderState, size: Pair<Int, Int>) {
        runCatching { state.codec?.stop() }
        runCatching { state.codec?.release() }
        state.codec = startDecoder(size)
        state.width = size.first
        state.height = size.second
    }

    private fun queueNal(state: DecoderState, nal: ByteArray) {
        val codec = state.codec ?: return
        var attempts = 0
        while (!closed.get()) {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)
                buffer?.clear()
                if (buffer != null && buffer.remaining() >= nal.size) {
                    buffer.put(nal)
                    codec.queueInputBuffer(index, 0, nal.size, System.nanoTime() / 1000, 0)
                    return
                }
                codec.queueInputBuffer(index, 0, 0, 0, 0)
                error("H.264 decoder rejected a ${nal.size}-byte NAL unit")
            }
            drainOutputs(state)
            if (++attempts >= MAX_QUEUE_ATTEMPTS) error("H.264 decoder stopped consuming input")
        }
    }

    private fun drainOutputs(state: DecoderState) {
        val codec = state.codec ?: return
        while (true) {
            when (val index = codec.dequeueOutputBuffer(state.bufferInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> break
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    state.width = format.getInteger(MediaFormat.KEY_WIDTH)
                    state.height = format.getInteger(MediaFormat.KEY_HEIGHT)
                }

                else -> {
                    if (index >= 0) {
                        state.pendingOutput?.let { codec.releaseOutputBuffer(it, false) }
                        state.pendingOutput = index
                    }
                }
            }
        }
        // Only the newest decoded picture is converted; older ones are dropped.
        val latest = state.pendingOutput ?: return
        state.pendingOutput = null
        publishOutput(state, latest)
    }

    private fun publishOutput(state: DecoderState, index: Int) {
        val codec = state.codec ?: return
        val image = codec.getOutputImage(index)
        if (image == null) {
            codec.releaseOutputBuffer(index, false)
            return
        }
        try {
            val now = SystemClock.elapsedRealtime()
            val renderPreview = now - lastPreviewAtMs >= PREVIEW_INTERVAL_MS
            if (renderPreview) lastPreviewAtMs = now
            val (frame, preview) = imageToFrame(image, renderPreview)
            preview?.let(onPreviewFrame)
            if (frames.trySend(frame).isFailure) frame.release()
        } finally {
            image.close()
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun imageToFrame(image: Image, renderPreview: Boolean): Pair<DecodedRgbaFrame, Bitmap?> {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        check(width > 0 && height > 0) { "Camera sent an empty H.264 frame" }
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val rgba = acquireRgba(width * height * RGBA_BYTES_PER_PIXEL)
        val argb = if (renderPreview) IntArray(width * height) else null
        nativeYuv420ToRgba(
            yPlane.buffer,
            yPlane.rowStride,
            yPlane.pixelStride,
            uPlane.buffer,
            uPlane.rowStride,
            uPlane.pixelStride,
            vPlane.buffer,
            vPlane.rowStride,
            vPlane.pixelStride,
            crop.left,
            crop.top,
            width,
            height,
            rgba,
            argb,
        )
        rgba.rewind()
        val frame = DecodedRgbaFrame(width, height, rgba, onRelease = ::recycleRgba)
        val preview = argb?.let { Bitmap.createBitmap(it, width, height, Bitmap.Config.ARGB_8888) }
        return frame to preview
    }

    private external fun nativeYuv420ToRgba(
        yBuffer: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        uBuffer: ByteBuffer,
        uRowStride: Int,
        uPixelStride: Int,
        vBuffer: ByteBuffer,
        vRowStride: Int,
        vPixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        width: Int,
        height: Int,
        rgba: ByteBuffer,
        previewArgb: IntArray?,
    )

    private fun acquireRgba(bytes: Int): ByteBuffer = synchronized(rgbaPool) {
        val buffer = rgbaPool.pollFirst()
        if (buffer != null && buffer.capacity() >= bytes) {
            buffer.clear()
            buffer
        } else {
            ByteBuffer.allocateDirect(bytes)
        }
    }

    private fun recycleRgba(buffer: ByteBuffer) {
        synchronized(rgbaPool) {
            if (rgbaPool.size < RGBA_POOL_SIZE) rgbaPool.addLast(buffer)
        }
    }

    private class DecoderState {
        var codec: MediaCodec? = null
        var width: Int = 0
        var height: Int = 0
        var pendingOutput: Int? = null
        val bufferInfo = MediaCodec.BufferInfo()
    }

    companion object {
        init {
            System.loadLibrary("sapseed_preprocess")
        }

        private const val RECONNECT_DELAY_MS = 1_000L
        private const val READ_CHUNK_BYTES = 64 * 1024
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val MAX_QUEUE_ATTEMPTS = 200
        private const val MAX_BACKLOG_NALS = 64
        private const val FALLBACK_WIDTH = 1280
        private const val FALLBACK_HEIGHT = 720
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val RGBA_BYTES_PER_PIXEL = 4
        private const val RGBA_POOL_SIZE = 3
        private const val PREVIEW_INTERVAL_MS = 200L

        fun isH264ContentType(contentType: String): Boolean =
            contentType.contains("h264", ignoreCase = true) ||
                contentType.contains("avc", ignoreCase = true)
    }
}

/** One Annex-B NAL unit, start code included so MediaCodec accepts it as-is. */
private class NalUnit(val bytes: ByteArray, val headerOffset: Int) {
    val type: Int get() = bytes[headerOffset].toInt() and 0x1f
}

/** Splits a byte stream into Annex-B NAL units across arbitrary TCP chunk boundaries. */
private class AnnexBSplitter {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var length = 0

    fun append(chunk: ByteArray, offset: Int, count: Int) {
        if (length + count > buffer.size) {
            var capacity = buffer.size * 2
            while (capacity < length + count) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }
        chunk.copyInto(buffer, length, offset, offset + count)
        length += count
    }

    fun nextNal(): NalUnit? {
        val first = findStartCode(0) ?: run {
            check(length <= MAX_BUFFER_BYTES) {
                "Camera stream is not Annex-B H.264 (no start code in $length bytes)"
            }
            return null
        }
        // Fold in the extra zero of a four-byte start code (00 00 00 01).
        val aligned = if (first > 0 && buffer[first - 1] == 0.toByte()) first - 1 else first
        if (aligned > 0) {
            buffer.copyInto(buffer, 0, aligned, length)
            length -= aligned
        }
        val second = findStartCode(START_CODE_BYTES) ?: run {
            check(length <= MAX_BUFFER_BYTES) { "H.264 frame exceeded $MAX_BUFFER_BYTES bytes" }
            return null
        }
        val nal = buffer.copyOfRange(0, second)
        buffer.copyInto(buffer, 0, second, length)
        length -= second
        var header = 0
        while (nal[header] != 1.toByte()) header++
        return NalUnit(nal, header + 1)
    }

    private fun findStartCode(from: Int): Int? {
        var i = from
        while (i + 2 < length) {
            if (buffer[i] == 0.toByte() && buffer[i + 1] == 0.toByte() && buffer[i + 2] == 1.toByte()) {
                return i
            }
            i++
        }
        return null
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024 * 1024
        const val MAX_BUFFER_BYTES = 8 * 1024 * 1024
        const val START_CODE_BYTES = 3
    }
}

/** Minimal SPS reader: extracts the encoded picture size for decoder setup. */
private object SpsParser {
    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    fun parseSize(nal: ByteArray, rbspOffset: Int): Pair<Int, Int> {
        val reader = BitReader(stripEmulationPrevention(nal, rbspOffset))
        val profile = reader.u(8)
        reader.u(8) // constraint flags
        reader.u(8) // level
        reader.ue() // seq_parameter_set_id
        var chromaFormat = 1
        var separatePlane = false
        if (profile in HIGH_PROFILES) {
            chromaFormat = reader.ue()
            if (chromaFormat == 3) separatePlane = reader.u(1) == 1
            reader.ue() // bit_depth_luma_minus8
            reader.ue() // bit_depth_chroma_minus8
            reader.u(1) // qpprime_y_zero_transform_bypass_flag
            if (reader.u(1) == 1) {
                val lists = if (chromaFormat == 3) 12 else 8
                repeat(lists) { if (reader.u(1) == 1) skipScalingList(reader, if (it < 6) 16 else 64) }
            }
        }
        reader.ue() // log2_max_frame_num_minus4
        when (reader.ue()) { // pic_order_cnt_type
            0 -> reader.ue()
            1 -> {
                reader.u(1)
                reader.se()
                reader.se()
                reader.ue()
                reader.ue()
            }
        }
        reader.ue() // max_num_ref_frames
        reader.u(1) // gaps_in_frame_num_value_allowed_flag
        val widthMbs = reader.ue()
        val heightMapUnits = reader.ue()
        val frameOnly = reader.u(1)
        if (frameOnly == 0) reader.u(1) // mb_adaptive_frame_field_flag
        reader.u(1) // direct_8x8_inference_flag
        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if (reader.u(1) == 1) { // frame_cropping_flag
            cropLeft = reader.ue()
            cropRight = reader.ue()
            cropTop = reader.ue()
            cropBottom = reader.ue()
        }
        val cropUnitX: Int
        val cropUnitY: Int
        if (separatePlane || chromaFormat == 0) {
            cropUnitX = 1
            cropUnitY = 2 - frameOnly
        } else {
            cropUnitX = if (chromaFormat == 3) 1 else 2
            val subHeight = when (chromaFormat) {
                1 -> 2
                else -> 1
            }
            cropUnitY = subHeight * (2 - frameOnly)
        }
        val width = (widthMbs + 1) * 16 - (cropLeft + cropRight) * cropUnitX
        val height = (2 - frameOnly) * (heightMapUnits + 1) * 16 - (cropTop + cropBottom) * cropUnitY
        check(width in 16..8192 && height in 16..8192) { "Implausible H.264 size ${width}x$height" }
        return width to height
    }

    private fun skipScalingList(reader: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        repeat(size) {
            if (nextScale != 0) {
                nextScale = (lastScale + reader.se() + 256) % 256
            }
            if (nextScale != 0) lastScale = nextScale
        }
    }

    private fun stripEmulationPrevention(nal: ByteArray, offset: Int): ByteArray {
        val out = ByteArray(nal.size - offset)
        var written = 0
        var i = offset
        while (i < nal.size) {
            if (i + 2 < nal.size &&
                nal[i] == 0.toByte() && nal[i + 1] == 0.toByte() && nal[i + 2] == 3.toByte()
            ) {
                out[written++] = 0
                out[written++] = 0
                i += 3
            } else {
                out[written++] = nal[i++]
            }
        }
        return out.copyOf(written)
    }

    private class BitReader(private val bytes: ByteArray) {
        private var byteIndex = 0
        private var bitIndex = 0

        fun u(count: Int): Int {
            var value = 0
            repeat(count) {
                check(byteIndex < bytes.size) { "Truncated H.264 SPS" }
                val bit = (bytes[byteIndex].toInt() shr (7 - bitIndex)) and 1
                value = (value shl 1) or bit
                if (++bitIndex == 8) {
                    bitIndex = 0
                    byteIndex++
                }
            }
            return value
        }

        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) {
                zeros++
                check(zeros < 32) { "Invalid Exp-Golomb code in H.264 SPS" }
            }
            return (1 shl zeros) - 1 + if (zeros > 0) u(zeros) else 0
        }

        fun se(): Int {
            val code = ue()
            return if (code % 2 == 0) -(code / 2) else (code + 1) / 2
        }
    }
}
