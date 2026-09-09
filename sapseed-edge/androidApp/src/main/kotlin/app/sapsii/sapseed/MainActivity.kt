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
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
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
import app.sapsii.sapseed.benchmark.BenchmarkBackend
import app.sapsii.sapseed.benchmark.BenchmarkReportStore
import app.sapsii.sapseed.benchmark.DeviceBenchmarkRunner
import app.sapsii.sapseed.benchmark.detectionSummary
import app.sapsii.sapseed.benchmark.format
import app.sapsii.sapseed.edge.android.camera.CameraXFrameSource
import app.sapsii.sapseed.edge.android.camera.RgbaFrameListener
import app.sapsii.sapseed.edge.android.camera.RgbaVideoFrame
import app.sapsii.sapseed.edge.android.camera.WirelessCameraSources
import app.sapsii.sapseed.edge.android.inference.YoloBenchmarkDetector
import java.util.Locale
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.sapsii.sapseed.edge.contract.FrameSource
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
    private lateinit var preview: PreviewView
    private lateinit var tilesScroll: ScrollView
    private lateinit var tilesGrid: GridLayout
    private lateinit var liveButton: Button
    private lateinit var cameraButton: Button
    private lateinit var status: TextView
    private lateinit var results: TextView
    private val benchmarkButtons = mutableListOf<Button>()
    private lateinit var cameraExecutor: ExecutorService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var benchmarkJob: Job? = null
    private var frameSource: FrameSource? = null
    private var broadcaster: PhoneCameraBroadcaster? = null
    private val wirelessTiles = mutableListOf<WirelessTile>()
    private val connectJobs = mutableListOf<Job>()
    private val discoveredUrls = mutableSetOf<String>()
    private var liveJob: Job? = null
    private var liveDetector: YoloBenchmarkDetector? = null
    private val detectorMutex = Mutex()
    private var lastProvider = BenchmarkBackend.LITERT_GPU
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
        benchmarkJob?.cancel()
        broadcaster?.close()
        broadcaster = null
        connectJobs.forEach { it.cancel() }
        liveJob?.cancel()
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
                    if (broadcaster == null) {
                        status.setText(R.string.camera_ready)
                    }
                    setBenchmarkButtonsEnabled(true)
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
        val tile = WirelessTile(displayName, views.root, views.preview, views.status)
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
                        runOnUiThread {
                            tile.status.text = message
                            refreshBenchmarkTarget()
                        }
                    },
                )
                tile.source = source
                frameSource = source
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
        if (wirelessTiles.isEmpty()) return
        val names = wirelessTiles.joinToString { it.name }
        val target = wirelessTiles.lastOrNull { it.source != null }
        status.text = if (target != null) {
            "${wirelessTiles.size} wireless camera(s): $names · benchmark: ${target.name}"
        } else {
            "${wirelessTiles.size} wireless camera(s): $names"
        }
        cameraButton.text = "${getString(R.string.camera_source_wireless)} (${wirelessTiles.size})"
        liveButton.isEnabled = true
        liveButton.setText(if (liveJob != null) R.string.live_detect_on else R.string.live_detect_off)
        refreshBenchmarkTarget()
    }

    private fun refreshBenchmarkTarget() {
        val target = wirelessTiles.lastOrNull { it.source != null }
        frameSource = target?.source
        setBenchmarkButtonsEnabled(target != null && target.connected && benchmarkJob == null)
    }

    private fun setLiveDetect(enabled: Boolean) {
        if (enabled) {
            if (liveJob != null || wirelessTiles.isEmpty()) return
            liveButton.isEnabled = false
            liveButton.setText(R.string.live_detect_starting)
            status.text = getString(R.string.benchmark_loading_model, lastProvider.name)
            liveJob = scope.launch {
                val detector = try {
                    DeviceBenchmarkRunner.createDetector(this@MainActivity, lastProvider)
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
                wirelessTiles.forEach { startTileLoop(it) }
                runOnUiThread { refreshWirelessUi() }
            }
        } else {
            val job = liveJob ?: return
            liveJob = null
            wirelessTiles.forEach {
                it.detectJob?.cancel()
                it.detectJob = null
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
                val sample = detectorMutex.withLock { detector.benchmark(rgba) }
                frame.release()
                count++
                val now = SystemClock.elapsedRealtime()
                if (now - tile.lastUiUpdate >= UI_UPDATE_INTERVAL_MS) {
                    val fps = count * 1000.0 / (now - windowStart).coerceAtLeast(1)
                    count = 0
                    windowStart = now
                    tile.lastUiUpdate = now
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
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(TILE_PREVIEW_HEIGHT_DP))
        }
        val status = TextView(this).apply {
            textSize = 12f
            setText(R.string.camera_connecting)
        }
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(preview)
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        tilesGrid.addView(
            root,
            GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f),
            ).apply { width = 0 },
        )
        return TileViews(root, preview, status, remove)
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
            status.text = "Broadcasting at $url — discoverable on this Wi-Fi"
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Camera broadcast failed", error)
            stopCurrentSource()
            status.text = error.message ?: getString(R.string.camera_failed)
        }
    }

    private fun stopCurrentSource() {
        benchmarkJob?.cancel()
        benchmarkJob = null
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
        setBenchmarkButtonsEnabled(false)
    }

    private fun startBenchmark(provider: BenchmarkBackend) {
        val source = frameSource ?: return
        lastProvider = provider
        setLiveDetect(false)
        benchmarkJob?.cancel()
        setBenchmarkButtonsEnabled(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = getString(R.string.benchmark_loading_model, provider.name)
        results.text = ""

        benchmarkJob = scope.launch {
            try {
                val report = DeviceBenchmarkRunner(this@MainActivity, source).run(
                    provider = provider,
                    onSample = { completed, sample ->
                        status.text = getString(
                            R.string.benchmark_progress,
                            provider.name,
                            completed,
                            MEASURED_ITERATIONS,
                        )
                        results.text = getString(
                            R.string.benchmark_live_result,
                            sample.totalMs,
                            sample.inferenceMs,
                            sample.detections.size,
                            sample.detectionSummary(),
                        )
                    },
                    measuredIterations = MEASURED_ITERATIONS,
                )
                val directory = BenchmarkReportStore(this@MainActivity).save(report)
                status.setText(R.string.benchmark_complete)
                results.text = buildString {
                    appendLine("Provider: ${report.provider}")
                    appendLine("Mean total: ${report.meanTotalMs.format()} ms")
                    appendLine("Median: ${report.medianTotalMs.format()} ms")
                    appendLine("p95: ${report.p95TotalMs.format()} ms")
                    appendLine("p99: ${report.p99TotalMs.format()} ms")
                    appendLine("Mean inference: ${report.meanInferenceMs.format()} ms")
                    appendLine("Effective FPS: ${report.effectiveFps.format()}")
                    appendLine("Peak PSS: ${report.peakPssMegabytes.format()} MiB")
                    appendLine("Thermal: ${report.initialThermalStatus} → ${report.finalThermalStatus}")
                    appendLine("Saved: ${directory.absolutePath}")
                }
                Log.i(LOG_TAG, "Benchmark saved to ${directory.absolutePath}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "Benchmark failed for $provider", error)
                status.setText(R.string.benchmark_failed)
                results.text = error.stackTraceToString()
            } finally {
                if (benchmarkJob === coroutineContext[Job]) {
                    benchmarkJob = null
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    setBenchmarkButtonsEnabled(frameSource != null)
                }
            }
        }
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
        addView(createBenchmarkButtons(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        liveButton = Button(context).apply {
            setText(R.string.live_detect_off)
            isEnabled = false
            setOnClickListener { setLiveDetect(liveJob == null) }
        }
        addView(liveButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        preview = PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, 0, 3f))
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
        addView(tilesScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 3f))
        results = TextView(context).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        addView(
            ScrollView(context).apply { addView(results) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 2f),
        )
    }

    private fun createBenchmarkButtons() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            createBenchmarkRow(
                R.string.benchmark_onnx_cpu to BenchmarkBackend.ONNX_CPU,
                R.string.benchmark_onnx_nnapi to BenchmarkBackend.ONNX_NNAPI,
            ),
        )
        addView(
            createBenchmarkRow(
                R.string.benchmark_litert_cpu to BenchmarkBackend.LITERT_CPU,
                R.string.benchmark_litert_gpu to BenchmarkBackend.LITERT_GPU,
            ),
        )
    }

    private fun createBenchmarkRow(vararg entries: Pair<Int, BenchmarkBackend>) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            entries.forEach { (label, backend) ->
                val button = Button(context).apply {
                    setText(label)
                    isEnabled = false
                    setOnClickListener { startBenchmark(backend) }
                }
                benchmarkButtons += button
                addView(button, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        }

    private fun setBenchmarkButtonsEnabled(enabled: Boolean) {
        benchmarkButtons.forEach { it.isEnabled = enabled }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private class WirelessTile(
        val name: String,
        val root: LinearLayout,
        val preview: ImageView,
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
        val status: TextView,
        val remove: Button,
    )

    companion object {
        private const val LOG_TAG = "SapseedBenchmark"
        private const val MEASURED_ITERATIONS = 30
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
        private const val TILE_COLUMNS = 2
        private const val TILE_PREVIEW_HEIGHT_DP = 200
        private const val UI_UPDATE_INTERVAL_MS = 500L
        private val BROADCAST_RESOLUTION = Size(640, 480)
    }
}
