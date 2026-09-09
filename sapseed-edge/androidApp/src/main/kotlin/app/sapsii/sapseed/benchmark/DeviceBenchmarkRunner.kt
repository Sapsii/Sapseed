package app.sapsii.sapseed.benchmark

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.sapsii.sapseed.edge.android.camera.RgbaVideoFrame
import app.sapsii.sapseed.edge.android.inference.LiteRtExecutionProvider
import app.sapsii.sapseed.edge.android.inference.OnnxExecutionProvider
import app.sapsii.sapseed.edge.android.inference.Yolo11LiteRtDetector
import app.sapsii.sapseed.edge.android.inference.Yolo11OnnxDetector
import app.sapsii.sapseed.edge.android.inference.YoloBenchmarkDetector
import app.sapsii.sapseed.edge.android.inference.YoloBenchmarkSample
import app.sapsii.sapseed.edge.contract.FrameSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BenchmarkBackend(internal val modelAsset: String) {
    ONNX_CPU("yolo11n.onnx"),
    ONNX_NNAPI("yolo11n.onnx"),
    LITERT_CPU("yolo11n.tflite"),
    LITERT_GPU("yolo11n.tflite"),
}

class DeviceBenchmarkRunner(
    private val context: Context,
    private val frameSource: FrameSource,
) {
    suspend fun run(
        provider: BenchmarkBackend,
        warmupIterations: Int = 5,
        measuredIterations: Int = 30,
        onSample: suspend (completed: Int, sample: YoloBenchmarkSample) -> Unit = { _, _ -> },
    ): DeviceBenchmarkReport {
        require(warmupIterations >= 0)
        require(measuredIterations > 0)

        val sessionStarted = SystemClock.elapsedRealtimeNanos()
        val detector = createDetector(context, provider)
        val sessionInitializationMs = sessionStarted.elapsedMilliseconds()
        val powerManager = context.getSystemService(PowerManager::class.java)
        val initialThermalStatus = powerManager.currentThermalStatusCompat()

        return try {
            repeat(warmupIterations) { index ->
                val frame = nextRgbaFrame()
                try {
                    detector.benchmark(frame)
                    Log.i(LOG_TAG, "provider=$provider warmup=${index + 1}/$warmupIterations")
                } finally {
                    frame.release()
                }
            }

            val samples = ArrayList<DeviceBenchmarkSample>(measuredIterations)
            repeat(measuredIterations) { index ->
                val frame = nextRgbaFrame()
                try {
                    val timing = detector.benchmark(frame)
                    val sample = DeviceBenchmarkSample(
                        iteration = index + 1,
                        preprocessMs = timing.preprocessMs,
                        inferenceMs = timing.inferenceMs,
                        postprocessMs = timing.postprocessMs,
                        totalMs = timing.totalMs,
                        detections = timing.detections.size,
                        detectionSummary = timing.detectionSummary(),
                        pssMegabytes = Debug.getPss() / 1024.0,
                    )
                    samples += sample
                    Log.i(
                        LOG_TAG,
                        "provider=$provider iteration=${sample.iteration} " +
                                "totalMs=${sample.totalMs.format()} inferenceMs=${sample.inferenceMs.format()} " +
                                "detections=${sample.detections} objects=${sample.detectionSummary} " +
                                "pssMb=${sample.pssMegabytes.format()}",
                    )
                    onSample(index + 1, timing)
                } finally {
                    frame.release()
                }
            }

            DeviceBenchmarkReport(
                createdAt = currentUtcTimestamp(),
                model = provider.modelAsset,
                provider = provider,
                warmupIterations = warmupIterations,
                sessionInitializationMs = sessionInitializationMs,
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                sdkLevel = Build.VERSION.SDK_INT,
                availableProcessors = Runtime.getRuntime().availableProcessors(),
                initialThermalStatus = initialThermalStatus,
                finalThermalStatus = powerManager.currentThermalStatusCompat(),
                samples = samples,
            )
        } finally {
            withContext(Dispatchers.Default) { detector.close() }
        }
    }

    private suspend fun nextRgbaFrame(): RgbaVideoFrame {
        val frame = frameSource.nextFrame() ?: error("Camera frame source closed")
        return frame as? RgbaVideoFrame
            ?: error("YOLO benchmark requires an RGBA frame source")
    }

    companion object {
        private const val LOG_TAG = "SapseedBenchmark"

        /** Loads the model and builds a detector without running any frames. Shared by benchmark runs and live multi-camera detection. */
        suspend fun createDetector(context: Context, provider: BenchmarkBackend): YoloBenchmarkDetector {
            val model = withContext(Dispatchers.IO) {
                context.assets.open(provider.modelAsset).use { it.readBytes() }
            }
            val tensorWriter = NativeRgbaTensorWriter()
            val gpuSerializationDirectory = context.codeCacheDir.resolve("litert-gpu").apply {
                check(mkdirs() || isDirectory) { "Could not create LiteRT GPU cache directory" }
            }
            return when (provider) {
                BenchmarkBackend.ONNX_CPU -> withContext(Dispatchers.Default) {
                    Yolo11OnnxDetector(
                        model,
                        OnnxExecutionProvider.CPU,
                        rgbaTensorWriter = tensorWriter,
                    )
                }

                BenchmarkBackend.ONNX_NNAPI -> withContext(Dispatchers.Default) {
                    Yolo11OnnxDetector(
                        model,
                        OnnxExecutionProvider.NNAPI,
                        rgbaTensorWriter = tensorWriter,
                    )
                }

                BenchmarkBackend.LITERT_CPU -> Yolo11LiteRtDetector.create(
                    model,
                    LiteRtExecutionProvider.CPU,
                    rgbaTensorWriter = tensorWriter,
                )

                BenchmarkBackend.LITERT_GPU -> Yolo11LiteRtDetector.create(
                    model,
                    LiteRtExecutionProvider.GPU,
                    rgbaTensorWriter = tensorWriter,
                    gpuSerializationDirectory = gpuSerializationDirectory.absolutePath,
                )
            }
        }
    }
}

data class DeviceBenchmarkSample(
    val iteration: Int,
    val preprocessMs: Double,
    val inferenceMs: Double,
    val postprocessMs: Double,
    val totalMs: Double,
    val detections: Int,
    val detectionSummary: String,
    val pssMegabytes: Double,
)

data class DeviceBenchmarkReport(
    val createdAt: String,
    val model: String,
    val provider: BenchmarkBackend,
    val warmupIterations: Int,
    val sessionInitializationMs: Double,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: String,
    val sdkLevel: Int,
    val availableProcessors: Int,
    val initialThermalStatus: Int?,
    val finalThermalStatus: Int?,
    val samples: List<DeviceBenchmarkSample>,
) {
    val meanTotalMs: Double get() = samples.map(DeviceBenchmarkSample::totalMs).average()
    val medianTotalMs: Double get() = percentile(50)
    val p95TotalMs: Double get() = percentile(95)
    val p99TotalMs: Double get() = percentile(99)
    val meanInferenceMs: Double get() = samples.map(DeviceBenchmarkSample::inferenceMs).average()
    val effectiveFps: Double get() = 1_000.0 / meanTotalMs
    val peakPssMegabytes: Double get() = samples.maxOf(DeviceBenchmarkSample::pssMegabytes)

    private fun percentile(percent: Int): Double {
        val values = samples.map(DeviceBenchmarkSample::totalMs).sorted()
        val index = (ceil(percent / 100.0 * values.size).toInt() - 1).coerceIn(values.indices)
        return values[index]
    }
}

internal fun Double.format(): String = "%.2f".format(Locale.US, this)

internal fun YoloBenchmarkSample.detectionSummary(): String =
    detections
        .sortedByDescending { it.confidence }
        .take(10)
        .joinToString(separator = "; ") { detection ->
            val box = detection.bounds
            "${detection.label}:${detection.confidence.toDouble().format()}" +
                    "@[${box.left.toDouble().format()},${box.top.toDouble().format()}," +
                    "${box.right.toDouble().format()},${box.bottom.toDouble().format()}]"
        }
        .ifEmpty { "none" }

private fun Long.elapsedMilliseconds(): Double =
    (SystemClock.elapsedRealtimeNanos() - this) / 1_000_000.0

private fun PowerManager.currentThermalStatusCompat(): Int? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) currentThermalStatus else null

private fun currentUtcTimestamp(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
