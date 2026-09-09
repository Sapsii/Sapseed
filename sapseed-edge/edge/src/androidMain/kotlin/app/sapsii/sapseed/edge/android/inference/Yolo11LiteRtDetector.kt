package app.sapsii.sapseed.edge.android.inference

import android.os.SystemClock
import app.sapsii.sapseed.edge.android.camera.AndroidVideoFrame
import app.sapsii.sapseed.edge.android.camera.RgbaVideoFrame
import app.sapsii.sapseed.edge.model.Detection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegateFactory

class Yolo11LiteRtDetector private constructor(
    private val model: ByteArray,
    val executionProvider: LiteRtExecutionProvider,
    confidenceThreshold: Float,
    iouThreshold: Float,
    inputSize: Int,
    rgbaTensorWriter: RgbaTensorWriter?,
    private val gpuSerializationDirectory: String?,
    private val gpuModelToken: String,
) : AndroidFrameDetector, YoloDetector {
    private val inferenceDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Sapseed-LiteRT-${executionProvider.name}").apply { priority = Thread.MAX_PRIORITY }
    }.asCoroutineDispatcher()
    private val preprocessor = Yolo11RgbaPreprocessor(
        inputSize,
        TensorLayout.NHWC,
        rgbaTensorWriter,
    )
    private val postprocessor = Yolo11Postprocessor(confidenceThreshold, iouThreshold)
    private val output = Array(1) { Array(YOLO_CHANNELS) { FloatArray(YOLO_ANCHORS) } }
    private var interpreter: Interpreter? = null

    override suspend fun detect(frame: AndroidVideoFrame): List<Detection> = process(frame).detections

    override suspend fun process(frame: RgbaVideoFrame): YoloDetectionResult = withContext(inferenceDispatcher) {
        val runtime = checkNotNull(interpreter) { "LiteRT detector is not initialized" }
        val totalStarted = SystemClock.elapsedRealtimeNanos()

        val preprocessStarted = SystemClock.elapsedRealtimeNanos()
        val transform = preprocessor.prepare(frame)
        val preprocessMs = preprocessStarted.elapsedMilliseconds()

        val inferenceStarted = SystemClock.elapsedRealtimeNanos()
        runtime.run(preprocessor.inputBytes, output)
        val inferenceMs = inferenceStarted.elapsedMilliseconds()

        val postprocessStarted = SystemClock.elapsedRealtimeNanos()
        val detections = postprocessor.decode(output[0], transform)
        val postprocessMs = postprocessStarted.elapsedMilliseconds()

        YoloDetectionResult(
            preprocessMs = preprocessMs,
            inferenceMs = inferenceMs,
            postprocessMs = postprocessMs,
            totalMs = totalStarted.elapsedMilliseconds(),
            detections = detections,
        )
    }

    override fun close() {
        runBlocking {
            withContext(inferenceDispatcher) {
                interpreter?.close()
                interpreter = null
            }
        }
        inferenceDispatcher.close()
    }

    private fun initialize() {
        val options = Interpreter.Options()
        when (executionProvider) {
            LiteRtExecutionProvider.CPU -> {
                options.setNumThreads(SNAPDRAGON_8_GEN_3_CPU_THREADS)
                options.setUseXNNPACK(true)
            }

            LiteRtExecutionProvider.GPU -> {
                val gpuOptions = GpuDelegateFactory.Options()
                    .setPrecisionLossAllowed(true)
                    .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                    .setForceBackend(GpuDelegateFactory.Options.GpuBackend.OPENCL)
                gpuSerializationDirectory?.let { directory ->
                    gpuOptions.setSerializationParams(directory, gpuModelToken)
                }
                options.addDelegateFactory(GpuDelegateFactory(gpuOptions))
            }
        }
        val modelBuffer = ByteBuffer.allocateDirect(model.size)
            .order(ByteOrder.nativeOrder())
            .put(model)
        modelBuffer.rewind()
        interpreter = Interpreter(modelBuffer, options).also { runtime ->
            val inputShape = runtime.getInputTensor(0).shape()
            val outputShape = runtime.getOutputTensor(0).shape()
            require(inputShape.contentEquals(intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3))) {
                "Expected LiteRT input [1,$INPUT_SIZE,$INPUT_SIZE,3], received ${inputShape.contentToString()}"
            }
            require(outputShape.contentEquals(intArrayOf(1, YOLO_CHANNELS, YOLO_ANCHORS))) {
                "Expected LiteRT output [1,$YOLO_CHANNELS,$YOLO_ANCHORS], received ${outputShape.contentToString()}"
            }
        }
    }

    companion object {
        private const val INPUT_SIZE = 640
        private const val YOLO_CHANNELS = 84
        private const val YOLO_ANCHORS = 8400
        private const val SNAPDRAGON_8_GEN_3_CPU_THREADS = 4

        suspend fun create(
            model: ByteArray,
            executionProvider: LiteRtExecutionProvider,
            confidenceThreshold: Float = 0.25f,
            iouThreshold: Float = 0.45f,
            inputSize: Int = INPUT_SIZE,
            rgbaTensorWriter: RgbaTensorWriter? = null,
            gpuSerializationDirectory: String? = null,
            gpuModelToken: String = "yolo11n-fp32-static-v1",
        ): Yolo11LiteRtDetector {
            require(inputSize == INPUT_SIZE) { "This LiteRT model requires ${INPUT_SIZE}x$INPUT_SIZE input" }
            val detector = Yolo11LiteRtDetector(
                model = model,
                executionProvider = executionProvider,
                confidenceThreshold = confidenceThreshold,
                iouThreshold = iouThreshold,
                inputSize = inputSize,
                rgbaTensorWriter = rgbaTensorWriter,
                gpuSerializationDirectory = gpuSerializationDirectory,
                gpuModelToken = gpuModelToken,
            )
            try {
                withContext(detector.inferenceDispatcher) { detector.initialize() }
                return detector
            } catch (error: Throwable) {
                detector.inferenceDispatcher.close()
                throw error
            }
        }
    }
}

enum class LiteRtExecutionProvider {
    CPU,
    GPU,
}
