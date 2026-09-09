package app.sapsii.sapseed

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import app.sapsii.sapseed.edge.android.camera.RgbaFrameListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns this phone into a discoverable IP camera for other Sapseed edge units.
 *
 * Serves the CameraX feed two ways: `multipart/x-mixed-replace` JPEG at `/stream`
 * and hardware-encoded Annex-B H.264 at `/video/h264`, and advertises
 * `_sapseedcam._tcp` via mDNS with a TXT attribute steering discovery to the H.264
 * path. Edge units probe the URL and dispatch on content type, so `video/h264`
 * streams decode with MediaCodec at full rate: no per-frame JPEG, ~30 fps.
 */
class PhoneCameraBroadcaster(
    context: Context,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val latest = LatestFrame()
    private val closed = AtomicBoolean(false)
    private val h264 = H264AnnexBEncoder(latest, closed)
    private val serverExecutor = Executors.newCachedThreadPool { task ->
        Thread(task, "Sapseed-Broadcast").apply { isDaemon = true }
    }
    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val servedFrames = AtomicLong(0)
    private val viewers = AtomicInteger(0)

    val frameListener = RgbaFrameListener { width, height, rowStride, pixelStride, rgba ->
        latest.offer(width, height, rowStride, pixelStride, rgba)
    }

    /** Starts the HTTP server and mDNS advertisement; returns the stream URL. */
    fun start(): String {
        val socket = try {
            ServerSocket(PREFERRED_PORT)
        } catch (_: BindException) {
            ServerSocket(0)
        }
        serverSocket = socket
        serverExecutor.execute(::acceptLoop)
        h264.start()
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = socket.localPort
            setAttribute("stream", "/video/h264")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                Log.i(LOG_TAG, "Broadcasting ${registered.serviceName} on port ${socket.localPort}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(LOG_TAG, "Broadcast registration failed ($errorCode)")
                onStatus("Camera broadcast is up but not discoverable ($errorCode)")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        Log.i(LOG_TAG, "Broadcast server listening on port ${socket.localPort}")
        val streamUrl = "http://${localIpAddress()}:${socket.localPort}/video/h264"
        serverExecutor.execute { monitorLoop(streamUrl) }
        return streamUrl
    }

    private fun monitorLoop(streamUrl: String) {
        while (!closed.get()) {
            val beforeH264 = h264.servedFrames()
            try {
                Thread.sleep(MONITOR_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (closed.get()) return
            val h264Fps = (h264.servedFrames() - beforeH264) * 1000 / MONITOR_INTERVAL_MS
            onStatus("Broadcasting H.264 at $streamUrl · $h264Fps fps · ${viewers.get()} viewer(s)")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
        h264.close()
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverExecutor.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            try {
                val client = serverSocket?.accept() ?: break
                serverExecutor.execute { serve(client) }
            } catch (_: IOException) {
                break
            }
        }
    }

    private fun serve(client: Socket) {
        Log.i(LOG_TAG, "Viewer connected: ${client.remoteSocketAddress}")
        viewers.incrementAndGet()
        try {
            client.use {
                it.soTimeout = CLIENT_TIMEOUT_MS
                val requestPath = consumeRequestHead(it)
                if (requestPath.contains("h264")) {
                    serveH264(it)
                    return
                }
                val out = it.getOutputStream()
                out.write(RESPONSE_HEAD.toByteArray())
                out.flush()
                val jpeg = ByteArrayOutputStream(JPEG_BUFFER_BYTES)
                var preview: Bitmap? = null
                while (!closed.get()) {
                    val started = System.currentTimeMillis()
                    val frame = latest.take()
                    if (frame == null) {
                        try {
                            Thread.sleep(IDLE_SLEEP_MS)
                        } catch (_: InterruptedException) {
                            return
                        }
                        continue
                    }
                    if (preview == null || preview.width != frame.width || preview.height != frame.height) {
                        preview?.recycle()
                        preview = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                    }
                    preview.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                    jpeg.reset()
                    preview.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
                    val bytes = jpeg.toByteArray()
                    out.write(PART_HEAD_PREFIX.toByteArray())
                    out.write(bytes.size.toString().toByteArray())
                    out.write(PART_HEAD_SUFFIX.toByteArray())
                    out.write(bytes)
                    out.write(CRLF_BYTES)
                    out.flush()
                    servedFrames.incrementAndGet()
                    val elapsed = System.currentTimeMillis() - started
                    if (elapsed < FRAME_INTERVAL_MS) {
                        try {
                            Thread.sleep(FRAME_INTERVAL_MS - elapsed)
                        } catch (_: InterruptedException) {
                            return
                        }
                    }
                }
                preview?.recycle()
            }
        } catch (_: IOException) {
            // Viewer disconnected; its thread ends here.
        } finally {
            viewers.decrementAndGet()
            Log.i(LOG_TAG, "Viewer disconnected: ${client.remoteSocketAddress}")
        }
    }

    private fun serveH264(client: Socket) {
        val out = client.getOutputStream()
        out.write(H264_RESPONSE_HEAD.toByteArray())
        out.flush()
        h264.streamTo(out)
    }

    private fun consumeRequestHead(client: Socket): String {
        val head = StringBuilder()
        val input = client.getInputStream()
        var requestPath = "/"
        var firstLine = true
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) throw IOException("Viewer disconnected")
            head.append(byte.toChar())
            if (head.length > MAX_REQUEST_HEAD_BYTES) throw IOException("Oversized HTTP request")
            if (firstLine && head.contains("\r\n")) {
                firstLine = false
                val fields = head.split(" ")
                if (fields.size >= 2) requestPath = fields[1]
            }
        }
        return requestPath
    }

    private fun localIpAddress(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val addresses = interfaces.nextElement().inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress &&
                    address is Inet4Address &&
                    address.isSiteLocalAddress
                ) {
                    return address.hostAddress ?: continue
                }
            }
        }
        return LOOPBACK_ADDRESS
    }

    private class Snapshot(val width: Int, val height: Int, val pixels: IntArray)

    private class LatestFrame {
        private var width = 0
        private var height = 0
        private var pixels = IntArray(0)
        private var fresh = false

        @Synchronized
        fun offer(width: Int, height: Int, rowStride: Int, pixelStride: Int, rgba: ByteBuffer) {
            if (pixels.size < width * height) pixels = IntArray(width * height)
            val capacity = rgba.capacity()
            var out = 0
            for (y in 0 until height) {
                var index = y * rowStride
                for (x in 0 until width) {
                    if (index < 0 || index + PIXEL_BYTES > capacity) return
                    val red = rgba.get(index).toInt() and 0xff
                    val green = rgba.get(index + 1).toInt() and 0xff
                    val blue = rgba.get(index + 2).toInt() and 0xff
                    pixels[out++] = ALPHA_MASK or (red shl 16) or (green shl 8) or blue
                    index += pixelStride
                }
            }
            this.width = width
            this.height = height
            fresh = true
        }

        @Synchronized
        fun take(): Snapshot? {
            if (!fresh) return null
            fresh = false
            return Snapshot(width, height, pixels.clone())
        }
    }

    private class Entry(val seq: Long, val bytes: ByteArray, val sync: Boolean)

    private class Cursor(var seq: Long)

    /** Hardware H.264 encoder producing Annex-B chunks that every viewer replays. */
    private class H264AnnexBEncoder(
        private val latest: LatestFrame,
        private val closed: AtomicBoolean,
    ) {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        private var inputSurface: Surface? = null
        private val inputBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        private val entries = ArrayDeque<Entry>()
        private val cursors = mutableListOf<Cursor>()
        private val info = MediaCodec.BufferInfo()
        @Volatile private var csd: ByteArray? = null
        @Volatile private var nextSeq = 0L
        @Volatile private var running = false
        private var served = 0L

        fun start() {
            if (running) return
            running = true
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = codec.createInputSurface()
                codec.start()
                Log.i(LOG_TAG, "H.264 encoder using hardware Surface input")
                Thread(
                    {
                        runCatching { encodeLoop() }
                            .onFailure { Log.e(LOG_TAG, "H.264 encode loop stopped", it) }
                    },
                    "Sapseed-H264-Encode",
                ).apply { isDaemon = true; start() }
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "H.264 encoder unavailable; viewers get MJPEG", error)
                running = false
            }
        }

        fun close() {
            runCatching { codec.stop() }
            runCatching { inputSurface?.release() }
            inputSurface = null
            runCatching { codec.release() }
        }

        fun servedFrames(): Long = served

        /** Streams SPS/PPS plus encoded chunks to one viewer, keeping per-viewer cursors so viewers never split chunks. */
        fun streamTo(out: OutputStream) {
            val cursor = Cursor(-1L)
            synchronized(cursors) { cursors.add(cursor) }
            try {
                var startedOnKeyframe = false
                while (!closed.get()) {
                    val chunk = synchronized(entries) {
                        while (entries.isNotEmpty()) {
                            val head = entries.first()
                            if (head.seq <= cursor.seq) {
                                val min = cursors.minOfOrNull { it.seq } ?: 0L
                                if (head.seq <= min) {
                                    entries.removeFirst()
                                    continue
                                }
                                return@synchronized null
                            }
                            return@synchronized head
                        }
                        null
                    }
                    if (chunk == null) {
                        try {
                            Thread.sleep(5)
                        } catch (_: InterruptedException) {
                            return
                        }
                        continue
                    }
                    if (!startedOnKeyframe) {
                        cursor.seq = chunk.seq
                        if (!chunk.sync) continue
                        startedOnKeyframe = true
                    }
                    csd?.let { headers ->
                        if (chunk.sync) out.write(headers)
                    }
                    out.write(chunk.bytes)
                    out.flush()
                    cursor.seq = chunk.seq
                    served++
                }
            } finally {
                synchronized(cursors) { cursors.remove(cursor) }
            }
        }

        private fun encodeLoop() {
            while (!closed.get()) {
                val snapshot = latest.take()
                if (snapshot == null || snapshot.width != WIDTH || snapshot.height != HEIGHT) {
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                        return
                    }
                    continue
                }
                inputBitmap.setPixels(snapshot.pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                val surface = inputSurface ?: return
                val canvas = runCatching { surface.lockHardwareCanvas() }
                    .getOrElse { surface.lockCanvas(null) }
                try {
                    canvas.drawBitmap(inputBitmap, 0f, 0f, null)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drainOutputs()
            }
        }

        private fun drainOutputs() {
            while (true) {
                when (val index = codec.dequeueOutputBuffer(info, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> captureCsd()
                    else -> {
                        if (index < 0) return
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(bytes)
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (isConfig) {
                                if (csd == null) {
                                    csd = bytes
                                }
                            } else {
                                val sync = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                val seq = nextSeq++
                                synchronized(entries) {
                                    if (entries.size >= MAX_QUEUED_CHUNKS) entries.removeFirst()
                                    entries.addLast(Entry(seq, bytes, sync))
                                }
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }

        private fun captureCsd() {
            if (csd != null) return
            val format = codec.outputFormat
            val csd0 = format.getByteBuffer("csd-0") ?: return
            val csd1 = format.getByteBuffer("csd-1")
            csd0.rewind()
            val first = ByteArray(csd0.remaining())
            csd0.get(first)
            val bytes = if (csd1 != null) {
                csd1.rewind()
                val second = ByteArray(csd1.remaining())
                csd1.get(second)
                first + second
            } else {
                first
            }
            csd = bytes
            Log.i(LOG_TAG, "H.264 encoder ready; csd ${bytes.size} bytes")
        }


    }

    private companion object {
        const val LOG_TAG = "SapseedNsd"
        const val SERVICE_TYPE = "_sapseedcam._tcp."
        const val SERVICE_NAME = "SapseedCam"
        const val PREFERRED_PORT = 8080
        const val BOUNDARY = "sapseedframe"
        const val RESPONSE_HEAD = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        const val PART_HEAD_PREFIX = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: "
        const val PART_HEAD_SUFFIX = "\r\n\r\n"
        val CRLF_BYTES = "\r\n".toByteArray()
        const val FRAME_INTERVAL_MS = 100L
        const val IDLE_SLEEP_MS = 50L
        const val CLIENT_TIMEOUT_MS = 15_000
        const val MAX_REQUEST_HEAD_BYTES = 8_192
        const val JPEG_BUFFER_BYTES = 512 * 1024
        const val JPEG_QUALITY = 75
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val MONITOR_INTERVAL_MS = 2_000L
        const val PIXEL_BYTES = 4
        const val ALPHA_MASK = -0x1000000
        const val H264_RESPONSE_HEAD = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: video/h264\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        const val WIDTH = 640
        const val HEIGHT = 480
        const val FRAME_RATE = 30
        const val BIT_RATE = 4_000_000
        const val I_FRAME_INTERVAL = 2
        const val MAX_QUEUED_CHUNKS = 300
    }
}
