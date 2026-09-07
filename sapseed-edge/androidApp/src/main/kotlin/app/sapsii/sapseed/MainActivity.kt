package app.sapsii.sapseed

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
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
import app.sapsii.sapseed.edge.android.camera.MjpegFrameSource
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
    private lateinit var wirelessPreview: ImageView
    private lateinit var cameraButton: Button
    private lateinit var status: TextView
    private lateinit var results: TextView
    private val benchmarkButtons = mutableListOf<Button>()
    private lateinit var cameraExecutor: ExecutorService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var benchmarkJob: Job? = null
    private var frameSource: FrameSource? = null
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
        discovery?.close()
        scope.cancel()
        frameSource?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun startMobileCamera() {
        if (!hasCameraPermission()) {
            permissionRequest.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        stopCurrentSource()
        cameraButton.setText(R.string.camera_source_mobile)
        preview.visibility = android.view.View.VISIBLE
        wirelessPreview.visibility = android.view.View.GONE
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
                        outputImageRotationEnabled = true,
                    )
                }.onSuccess { source ->
                    frameSource = source
                    status.setText(R.string.camera_ready)
                    setBenchmarkButtonsEnabled(true)
                }.onFailure { error ->
                    Log.e(LOG_TAG, "Camera startup failed", error)
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
                ),
            ) { _, which ->
                when (which) {
                    0 -> startMobileCamera()
                    1 -> discoverWirelessCamera()
                    2 -> showManualCameraDialog()
                }
            }
            .show()
    }

    private fun discoverWirelessCamera() {
        discovery?.close()
        status.setText(R.string.camera_discovering)
        val activeDiscovery = Esp32CameraDiscovery(
            context = this,
            onCameraFound = { name, url ->
                runOnUiThread {
                    if (discovery !== null) {
                        discovery?.close()
                        discovery = null
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
                status.setText(R.string.camera_discovery_timeout)
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
        stopCurrentSource()
        preview.visibility = android.view.View.GONE
        wirelessPreview.visibility = android.view.View.VISIBLE
        wirelessPreview.setImageDrawable(null)
        cameraButton.text = displayName
        status.setText(R.string.camera_connecting)

        runCatching {
            MjpegFrameSource(
                streamUrl = address,
                onPreviewFrame = ::showWirelessPreview,
                onConnectionChanged = { connected, message ->
                    runOnUiThread {
                        status.text = message
                        setBenchmarkButtonsEnabled(connected && frameSource != null)
                    }
                },
            )
        }.onSuccess { source ->
            frameSource = source
        }.onFailure { error ->
            status.text = error.message ?: getString(R.string.camera_failed)
            setBenchmarkButtonsEnabled(false)
        }
    }

    private fun showWirelessPreview(bitmap: Bitmap) {
        wirelessPreview.post { wirelessPreview.setImageBitmap(bitmap) }
    }

    private fun stopCurrentSource() {
        benchmarkJob?.cancel()
        benchmarkJob = null
        frameSource?.close()
        frameSource = null
        setBenchmarkButtonsEnabled(false)
    }

    private fun startBenchmark(provider: BenchmarkBackend) {
        val source = frameSource ?: return
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
        preview = PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, 0, 3f))
        wirelessPreview = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = android.view.View.GONE
        }
        addView(wirelessPreview, LinearLayout.LayoutParams(MATCH_PARENT, 0, 3f))
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

    companion object {
        private const val LOG_TAG = "SapseedBenchmark"
        private const val MEASURED_ITERATIONS = 30
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
    }
}
