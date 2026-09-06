package app.sapsii.sapseed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var results: TextView
    private val benchmarkButtons = mutableListOf<Button>()
    private lateinit var cameraExecutor: ExecutorService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var benchmarkJob: Job? = null
    private var frameSource: CameraXFrameSource? = null

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            status.setText(R.string.camera_permission_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setContentView(createContentView())

        if (hasCameraPermission()) {
            startCamera()
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
        scope.cancel()
        frameSource?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
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
    }
}
