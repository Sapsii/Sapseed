package app.sapsii.sapseed

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.os.SystemClock
import android.util.Log
import android.widget.GridLayout
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import app.sapsii.sapseed.inference.InferenceRuntime
import app.sapsii.sapseed.inference.LiveDetectorFactory
import app.sapsii.sapseed.edge.android.camera.CameraXFrameSource
import app.sapsii.sapseed.edge.android.camera.RgbaFrameListener
import app.sapsii.sapseed.edge.android.camera.RgbaVideoFrame
import app.sapsii.sapseed.edge.android.camera.WirelessCameraSources
import app.sapsii.sapseed.edge.android.inference.AndroidFrameDetector
import app.sapsii.sapseed.edge.android.inference.YoloDetector
import app.sapsii.sapseed.edge.android.AndroidEdgeRuntimeFactory
import app.sapsii.sapseed.edge.android.AndroidDeviceIdentity
import app.sapsii.sapseed.edge.pipeline.EdgePipeline
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.sapsii.sapseed.edge.contract.FrameSource
import app.sapsii.sapseed.edge.contract.PresenceResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** `<device name from About phone>-<imei hash 6>`, the id the platform provisions. */
    private val deviceExternalId: String by lazy {
        AndroidDeviceIdentity.externalId(applicationContext)
    }

    private lateinit var preview: PreviewView
    private lateinit var mobileOverlay: DetectionOverlayView
    private lateinit var tilesScroll: ScrollView
    private lateinit var tilesGrid: GridLayout
    private lateinit var liveButton: Button
    private lateinit var cameraButton: Button
    private lateinit var runtimeButton: Button
    private lateinit var status: TextView
    private lateinit var cameraExecutor: ExecutorService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var frameSource: FrameSource? = null
    private var broadcaster: PhoneCameraBroadcaster? = null
    private val wirelessTiles = mutableListOf<WirelessTile>()
    private val connectJobs = mutableListOf<Job>()
    private val discoveredUrls = mutableSetOf<String>()
    private var liveJob: Job? = null
    private var mobileDetectJob: Job? = null
    private var mobilePipeline: EdgePipeline? = null
    private var liveDetector: YoloDetector? = null
    private val detectorMutex = Mutex()
    private var selectedRuntime = InferenceRuntime.LITERT_GPU
    private var discovery: Esp32CameraDiscovery? = null

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startMobileCamera()
        } else {
            status.setText(R.string.camera_permission_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setContentView(createContentView())

        if (hasCameraPermission()) {
            startMobileCamera()
        } else {
            permissionRequest.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    override fun onDestroy() {
        broadcaster?.close()
        broadcaster = null
        connectJobs.forEach { it.cancel() }
        liveJob?.cancel()
        mobileDetectJob?.cancel()
        wirelessTiles.forEach { it.source?.close() }
        runCatching { liveDetector?.close() }
        liveDetector = null
        discovery?.close()
        scope.cancel()
        frameSource?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun startMobileCamera(
        frameListener: RgbaFrameListener? = null,
        stopFirst: Boolean = true,
        broadcastSession: Boolean = false,
    ) {
        if (!hasCameraPermission()) {
            permissionRequest.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        if (stopFirst) stopCurrentSource()
        cameraButton.setText(R.string.camera_source_mobile)
        preview.visibility = android.view.View.VISIBLE
        tilesScroll.visibility = android.view.View.GONE
        liveButton.isEnabled = false
        liveButton.setText(R.string.live_detect_off)
        status.setText(R.string.starting_camera)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                runCatching {
                    CameraXFrameSource(
                        cameraProvider = providerFuture.get(),
                        lifecycleOwner = this,
                        analyzerExecutor = cameraExecutor,
                        previewSurfaceProvider = preview.surfaceProvider,
                        outputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888,
                        outputImageRotationEnabled = !broadcastSession,
                        frameListener = frameListener,
                        targetResolution = if (broadcastSession) BROADCAST_RESOLUTION else null,
                    )
                }.onSuccess { source ->
                    frameSource = source
                    mobileOverlay.clear()
                    if (broadcaster == null) {
                        status.setText(R.string.camera_ready)
                        refreshWirelessUi()
                    }
                }.onFailure { error ->
                    Log.e(LOG_TAG, "Camera startup failed", error)
                    broadcaster?.close()
                    broadcaster = null
                    status.setText(R.string.camera_failed)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun showCameraMenu() {
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_source_title)
            .setItems(
                arrayOf(
                    getString(R.string.camera_source_mobile),
                    getString(R.string.camera_source_discover),
                    getString(R.string.camera_source_manual),
                    getString(R.string.camera_source_broadcast),
                ),
            ) { _, which ->
                when (which) {
                    0 -> startMobileCamera()
                    1 -> discoverWirelessCamera()
                    2 -> showManualCameraDialog()
                    3 -> startBroadcast()
                }
            }
            .show()
    }

    private fun discoverWirelessCamera() {
        discovery?.close()
        discoveredUrls.clear()
        status.setText(R.string.camera_discovering)
        val activeDiscovery = Esp32CameraDiscovery(
            context = this,
            onCameraFound = { name, url ->
                runOnUiThread {
                    if (discovery !== null && discoveredUrls.add(url)) {
                        connectWirelessCamera(url, name)
                    }
                }
            },
            onError = { message -> runOnUiThread { status.text = message } },
        )
        discovery = activeDiscovery
        activeDiscovery.start()
        scope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (discovery === activeDiscovery) {
                activeDiscovery.close()
                discovery = null
                if (wirelessTiles.isEmpty()) {
                    status.setText(R.string.camera_discovery_timeout)
                }
            }
        }
    }

    private fun showManualCameraDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.camera_ip_hint)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_manual_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.camera_connect) { _, _ ->
                connectWirelessCamera(input.text.toString(), getString(R.string.camera_source_wireless))
            }
            .show()
    }

    private fun connectWirelessCamera(address: String, displayName: String) {
        preview.visibility = android.view.View.GONE
        tilesScroll.visibility = android.view.View.VISIBLE
        val views = createTileViews(displayName)
        val tile = WirelessTile(displayName, views.root, views.preview, views.overlay, views.status)
        views.remove.setOnClickListener { removeTile(tile) }
        wirelessTiles += tile
        refreshWirelessUi()

        val job = scope.launch {
            try {
                val source = WirelessCameraSources.connect(
                    address = address,
                    onPreviewFrame = { bitmap ->
                        tile.preview.post { tile.preview.setImageBitmap(bitmap) }
                    },
                    onConnectionChanged = { connected, message ->
                        tile.connected = connected
                        runOnUiThread { tile.status.text = message }
                    },
                )
                tile.source = source
                runOnUiThread { refreshWirelessUi() }
                if (liveJob != null) startTileLoop(tile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "Wireless camera connect failed", error)
                val wasLast = wirelessTiles.size == 1
                runOnUiThread {
                    removeTile(tile)
                    if (wasLast) status.text = error.message ?: getString(R.string.camera_failed)
                }
            } finally {
                connectJobs.removeAll { it === tile.connectJob }
                tile.connectJob = null
            }
        }
        tile.connectJob = job
        connectJobs += job
    }

    private fun removeTile(tile: WirelessTile) {
        tile.connectJob?.cancel()
        tile.connectJob = null
        tile.detectJob?.cancel()
        tile.detectJob = null
        tile.source?.close()
        tile.source = null
        wirelessTiles.remove(tile)
        tilesGrid.removeView(tile.root)
        if (wirelessTiles.isEmpty()) {
            startMobileCamera()
        } else {
            refreshWirelessUi()
        }
    }

    private fun refreshWirelessUi() {
        if (wirelessTiles.isEmpty()) {
            runtimeButton.isEnabled = liveJob == null
            liveButton.isEnabled = frameSource != null && broadcaster == null
            liveButton.setText(if (liveJob != null) R.string.live_detect_on else R.string.live_detect_off)
            return
        }
        val names = wirelessTiles.joinToString { it.name }
        status.text = "${wirelessTiles.size} wireless camera(s): $names"
        cameraButton.text = "${getString(R.string.camera_source_wireless)} (${wirelessTiles.size})"
        runtimeButton.isEnabled = liveJob == null
        liveButton.isEnabled = true
        liveButton.setText(if (liveJob != null) R.string.live_detect_on else R.string.live_detect_off)
    }

    private fun setLiveDetect(enabled: Boolean) {
        if (enabled) {
            if (liveJob != null || (wirelessTiles.isEmpty() && frameSource == null)) return
            runtimeButton.isEnabled = false
            liveButton.isEnabled = false
            liveButton.setText(R.string.live_detect_starting)
            status.text = getString(R.string.live_detect_loading_model, selectedRuntime.displayName)
            liveJob = scope.launch {
                val detector = try {
                    LiveDetectorFactory.create(this@MainActivity, selectedRuntime)
                } catch (cancelled: CancellationException) {
                    liveJob = null
                    throw cancelled
                } catch (error: Throwable) {
                    Log.e(LOG_TAG, "Live detect unavailable", error)
                    liveJob = null
                    runOnUiThread {
                        status.text = error.message ?: getString(R.string.camera_failed)
                        refreshWirelessUi()
                    }
                    return@launch
                }
                liveDetector = detector
                if (wirelessTiles.isEmpty()) startMobileLoop() else wirelessTiles.forEach { startTileLoop(it) }
                runOnUiThread { refreshWirelessUi() }
                while (isActive) delay(1_000)
            }
        } else {
            val job = liveJob ?: return
            liveJob = null
            runtimeButton.isEnabled = true
            mobileDetectJob?.cancel()
            mobileDetectJob = null
            mobilePipeline = null
            mobileOverlay.clear()
            wirelessTiles.forEach {
                it.detectJob?.cancel()
                it.detectJob = null
                it.overlay.clear()
            }
            val detector = liveDetector
            liveDetector = null
            job.cancel()
            refreshWirelessUi()
            if (detector != null) {
                scope.launch(Dispatchers.Default) { runCatching { detector.close() } }
            }
        }
    }

    private fun startMobileLoop() {
        val source = frameSource ?: return
        val detector = liveDetector ?: return
        if (mobileDetectJob?.isActive == true) return

        val endpoint = BuildConfig.SAPSEED_API_URL.trim()
        val authorization = BuildConfig.SAPSEED_DEVICE_AUTHORIZATION.trim()
        Log.i(LOG_TAG, "Edge unit $deviceExternalId (serial source ${AndroidDeviceIdentity.serialSource(applicationContext)})")
        mobilePipeline = if (endpoint.isNotEmpty() && authorization.isNotEmpty()) {
            runCatching {
                AndroidEdgeRuntimeFactory.create(
                    context = applicationContext,
                    frameSource = source,
                    detector = detector as AndroidFrameDetector,
                    ingestionEndpoint = URL("${endpoint.trimEnd('/')}/v1/ingestion/batches"),
                    authorizationHeader = { authorization },
                    softwareVersion = BuildConfig.VERSION_NAME,
                    modelVersion = "sapseed",
                    modelRuntime = selectedRuntime.displayName,
                    cameraId = "builtin-primary",
                    minimumConfidence = LIVE_CONFIDENCE_THRESHOLD,
                )
            }.onFailure { Log.e(LOG_TAG, "Observation pipeline unavailable", it) }.getOrNull()
        } else {
            null
        }

        mobileDetectJob = scope.launch(Dispatchers.Default) {
            var count = 0
            var queued = 0
            var windowStart = SystemClock.elapsedRealtime()
            var lastUiUpdate = 0L
            var nextRecordAt = windowStart
            var nextUploadAt = windowStart + UPLOAD_INTERVAL_MS
            var nextPresenceAt = windowStart
            while (isActive) {
                val frame = source.nextFrame() ?: break
                val rgba = frame as? RgbaVideoFrame
                if (rgba == null) {
                    frame.release()
                    continue
                }
                val sample = try {
                    detectorMutex.withLock { detector.process(rgba) }
                } catch (error: Throwable) {
                    frame.release()
                    throw error
                }
                val now = SystemClock.elapsedRealtime()
                try {
                    if (now >= nextRecordAt) {
                        queued += mobilePipeline?.recordDetections(frame, sample.detections) ?: 0
                        nextRecordAt = now + OBSERVATION_INTERVAL_MS
                    }
                } catch (error: Throwable) {
                    Log.e(LOG_TAG, "Could not queue detections", error)
                } finally {
                    frame.release()
                }

                count++
                if (now - lastUiUpdate >= UI_UPDATE_INTERVAL_MS) {
                    val fps = count * 1000.0 / (now - windowStart).coerceAtLeast(1)
                    count = 0
                    windowStart = now
                    lastUiUpdate = now
                    val summary = sample.detections.sortedByDescending { it.confidence }.take(3)
                        .joinToString(" ") { detection ->
                            "${detection.label}:${"%.2f".format(Locale.US, detection.confidence)}"
                        }.ifEmpty { "no objects" }
                    val uploadState = when {
                        mobilePipeline == null -> " · upload not configured"
                        mobilePipeline?.credentialRejected == true -> " · CREDENTIAL REJECTED"
                        else -> " · queued $queued"
                    }
                    val line = "$deviceExternalId · " + "${"%.1f".format(Locale.US, fps)} fps · " +
                        "${"%.0f".format(Locale.US, sample.totalMs)} ms · $summary$uploadState"
                    runOnUiThread {
                        mobileOverlay.show(sample.detections, rgba.width, rgba.height)
                        status.text = line
                    }
                }
                if (now >= nextUploadAt) {
                    try {
                        val uploaded = mobilePipeline?.uploadPending()
                        if (uploaded != null && uploaded.accepted > 0) queued = (queued - uploaded.accepted).coerceAtLeast(0)
                        if (uploaded?.credentialRejected == true) {
                            Log.w(LOG_TAG, "Device credential rejected: this unit was deactivated or its secret was rotated")
                        }
                    } catch (error: Throwable) {
                        Log.w(LOG_TAG, "Pending observation upload deferred", error)
                    }
                    nextUploadAt = SystemClock.elapsedRealtime() + UPLOAD_INTERVAL_MS
                }
                if (now >= nextPresenceAt) {
                    try {
                        if (mobilePipeline?.reportPresence() is PresenceResult.CredentialRejected) {
                            Log.w(LOG_TAG, "Heartbeat rejected: this unit was deactivated or its secret was rotated")
                        }
                    } catch (error: Throwable) {
                        Log.w(LOG_TAG, "Presence report deferred", error)
                    }
                    nextPresenceAt = SystemClock.elapsedRealtime() + PRESENCE_INTERVAL_MS
                }
            }
        }
        mobileDetectJob?.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                Log.e(LOG_TAG, "Built-in camera live detection stopped", error)
                runOnUiThread {
                    if (liveJob != null) setLiveDetect(false)
                    status.text = "Live detection failed: ${error.message ?: error::class.java.simpleName}"
                }
            }
        }
    }

    private fun startTileLoop(tile: WirelessTile) {
        val detector = liveDetector ?: return
        if (tile.detectJob?.isActive == true) return
        if (tile.source == null) return
        tile.detectJob = scope.launch(Dispatchers.Default) {
            var count = 0
            var windowStart = SystemClock.elapsedRealtime()
            while (isActive) {
                val frame = tile.source?.nextFrame() ?: break
                val rgba = frame as? RgbaVideoFrame
                if (rgba == null) {
                    frame.release()
                    continue
                }
                val sample = detectorMutex.withLock { detector.process(rgba) }
                frame.release()
                count++
                val now = SystemClock.elapsedRealtime()
                if (now - tile.lastUiUpdate >= UI_UPDATE_INTERVAL_MS) {
                    val fps = count * 1000.0 / (now - windowStart).coerceAtLeast(1)
                    count = 0
                    windowStart = now
                    tile.lastUiUpdate = now
                    runOnUiThread { tile.overlay.show(sample.detections, rgba.width, rgba.height) }
                    val summary = sample.detections.sortedByDescending { it.confidence }.take(3)
                        .joinToString(" ") { detection ->
                            "${detection.label}:${"%.2f".format(Locale.US, detection.confidence)}"
                        }.ifEmpty { "no objects" }
                    val line = "${"%.1f".format(Locale.US, fps)} fps · " +
                        "${"%.0f".format(Locale.US, sample.totalMs)} ms · $summary"
                    runOnUiThread { tile.status.text = line }
                }
            }
        }
    }

    private fun createTileViews(name: String): TileViews {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply {
            text = name
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val remove = Button(this).apply {
            text = "✕"
            textSize = 12f
            minimumWidth = 0
        }
        header.addView(title)
        header.addView(remove, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val overlay = DetectionOverlayView(this)
        val previewStack = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(TILE_PREVIEW_HEIGHT_DP))
            addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val status = TextView(this).apply {
            textSize = 12f
            setText(R.string.camera_connecting)
        }
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(previewStack)
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        tilesGrid.addView(
            root,
            GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f),
            ).apply { width = 0 },
        )
        return TileViews(root, preview, overlay, status, remove)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun startBroadcast() {
        stopCurrentSource()
        if (!hasCameraPermission()) {
            permissionRequest.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        preview.visibility = android.view.View.VISIBLE
        tilesScroll.visibility = android.view.View.GONE
        val active = PhoneCameraBroadcaster(this) { message ->
            runOnUiThread { status.text = message }
        }
        broadcaster = active
        // No extra stop here: startBroadcast already stopped, and another
        // stopCurrentSource would close `active` before it starts.
        startMobileCamera(active.frameListener, stopFirst = false, broadcastSession = true)
        try {
            val url = active.start()
            cameraButton.setText(R.string.camera_source_broadcasting)
            status.text = "Broadcasting at $url, discoverable on this Wi-Fi"
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Camera broadcast failed", error)
            stopCurrentSource()
            status.text = error.message ?: getString(R.string.camera_failed)
        }
    }

    private fun stopCurrentSource() {
        setLiveDetect(false)
        connectJobs.forEach { it.cancel() }
        connectJobs.clear()
        broadcaster?.close()
        broadcaster = null
        wirelessTiles.forEach {
            it.connectJob?.cancel()
            it.detectJob?.cancel()
            it.source?.close()
        }
        wirelessTiles.clear()
        tilesGrid.removeAllViews()
        frameSource?.close()
        frameSource = null
    }

    private fun showRuntimeMenu() {
        if (liveJob != null) return
        val runtimes = InferenceRuntime.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.runtime_title)
            .setItems(runtimes.map { it.displayName }.toTypedArray()) { _, which ->
                selectedRuntime = runtimes[which]
                runtimeButton.text = getString(R.string.runtime_selected, selectedRuntime.displayName)
            }
            .show()
    }

    private fun createContentView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 24, 24, 24)
        addView(
            TextView(context).apply {
                text = getString(R.string.edge_title)
                textSize = 25f
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        cameraButton = Button(context).apply {
            setText(R.string.camera_source_mobile)
            setOnClickListener { showCameraMenu() }
        }
        addView(cameraButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        status = TextView(context).apply {
            setText(R.string.waiting_for_permission)
            textSize = 15f
        }
        addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        runtimeButton = Button(context).apply {
            text = getString(R.string.runtime_selected, selectedRuntime.displayName)
            setOnClickListener { showRuntimeMenu() }
        }
        addView(runtimeButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        liveButton = Button(context).apply {
            setText(R.string.live_detect_off)
            isEnabled = false
            setOnClickListener { setLiveDetect(liveJob == null) }
        }
        addView(liveButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        preview = PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        mobileOverlay = DetectionOverlayView(context)
        addView(
            FrameLayout(context).apply {
                addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(mobileOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )
        tilesScroll = ScrollView(context).apply {
            visibility = android.view.View.GONE
        }
        tilesGrid = GridLayout(context).apply {
            columnCount = TILE_COLUMNS
        }
        tilesScroll.addView(
            tilesGrid,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        addView(tilesScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private class WirelessTile(
        val name: String,
        val root: LinearLayout,
        val preview: ImageView,
        val overlay: DetectionOverlayView,
        val status: TextView,
    ) {
        var source: FrameSource? = null
        var connected: Boolean = false
        var connectJob: Job? = null
        var detectJob: Job? = null
        var lastUiUpdate: Long = 0L
    }

    private data class TileViews(
        val root: LinearLayout,
        val preview: ImageView,
        val overlay: DetectionOverlayView,
        val status: TextView,
        val remove: Button,
    )

    companion object {
        private const val LOG_TAG = "SapseedApp"
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
        private const val TILE_COLUMNS = 2
        private const val TILE_PREVIEW_HEIGHT_DP = 200
        private const val UI_UPDATE_INTERVAL_MS = 500L
        private const val UPLOAD_INTERVAL_MS = 2_000L
        private const val OBSERVATION_INTERVAL_MS = 2_000L

        private const val PRESENCE_INTERVAL_MS = 60_000L
        private const val LIVE_CONFIDENCE_THRESHOLD = 0.25f
        private val BROADCAST_RESOLUTION = Size(640, 480)
    }
}
