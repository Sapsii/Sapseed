package app.sapsii.sapseed.edge.android.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.camera.core.ImageProxy
import app.sapsii.sapseed.edge.android.camera.AndroidVideoFrame
import app.sapsii.sapseed.edge.android.camera.RgbaVideoFrame
import app.sapsii.sapseed.edge.android.camera.toUprightBitmap
import app.sapsii.sapseed.edge.model.Detection
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Yolo11OnnxDetector(
    model: ByteArray,
    labels: List<String>,
    val executionProvider: OnnxExecutionProvider = OnnxExecutionProvider.CPU,
    confidenceThreshold: Float = 0.25f,
    iouThreshold: Float = 0.45f,
    private val inputSize: Int = 640,
    rgbaTensorWriter: RgbaTensorWriter? = null,
) : AndroidFrameDetector, YoloDetector {
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        when (executionProvider) {
            OnnxExecutionProvider.CPU -> {
                setIntraOpNumThreads(max(1, Runtime.getRuntime().availableProcessors() / 2))
            }

            OnnxExecutionProvider.NNAPI -> addNnapi()
        }
    }
    private val session = environment.createSession(model, sessionOptions)
    private val inputName = session.inputNames.single()
    private val postprocessor = Yolo11Postprocessor(labels, confidenceThreshold, iouThreshold)
    private val rgbaPreprocessor = Yolo11RgbaPreprocessor(
        inputSize,
        TensorLayout.NCHW,
        rgbaTensorWriter,
    )
    private val inputValues = rgbaPreprocessor.inputFloats

    init {
        require(confidenceThreshold in 0f..1f)
        require(iouThreshold in 0f..1f)
    }

    override suspend fun detect(frame: AndroidVideoFrame): List<Detection> =
        if (frame.image.format == PixelFormat.RGBA_8888) {
            process(frame).detections
        } else {
            processPrepared { preprocess(frame) }.detections
        }

    override suspend fun process(frame: RgbaVideoFrame): YoloDetectionResult =
        processPrepared { preprocessRgba(frame) }

    private suspend fun processPrepared(prepare: () -> PreparedInput): YoloDetectionResult =
        withContext(Dispatchers.Default) {
            val totalStarted = SystemClock.elapsedRealtimeNanos()

            val preprocessStarted = SystemClock.elapsedRealtimeNanos()
            val prepared = prepare()
            val tensor = OnnxTensor.createTensor(
                environment,
                prepared.values,
                longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()),
            )
            val preprocessMs = preprocessStarted.elapsedMilliseconds()

            try {
                val inferenceStarted = SystemClock.elapsedRealtimeNanos()
                val result = session.run(mapOf(inputName to tensor))
                val inferenceMs = inferenceStarted.elapsedMilliseconds()

                result.use {
                    val postprocessStarted = SystemClock.elapsedRealtimeNanos()

                    @Suppress("UNCHECKED_CAST")
                    val output = (it[0] as OnnxTensor).value as Array<Array<FloatArray>>
                    val detections = postprocessor.decode(output[0], prepared.transform)
                    val postprocessMs = postprocessStarted.elapsedMilliseconds()
                    YoloDetectionResult(
                        preprocessMs = preprocessMs,
                        inferenceMs = inferenceMs,
                        postprocessMs = postprocessMs,
                        totalMs = totalStarted.elapsedMilliseconds(),
                        detections = detections,
                    )
                }
            } finally {
                tensor.close()
            }
        }

    override fun close() {
        session.close()
        sessionOptions.close()
    }

    private fun preprocess(frame: AndroidVideoFrame): PreparedInput =
        if (frame.image.format == PixelFormat.RGBA_8888) {
            preprocessRgba(frame)
        } else {
            preprocessYuv(frame.image, frame.rotationDegrees)
        }

    private fun preprocessRgba(frame: RgbaVideoFrame) = PreparedInput(
        values = inputValues,
        transform = rgbaPreprocessor.prepare(frame),
    )

    private fun preprocessYuv(image: ImageProxy, rotationDegrees: Int): PreparedInput {
        val source = image.toUprightBitmap(rotationDegrees)
        try {
            val scale = min(inputSize.toFloat() / source.width, inputSize.toFloat() / source.height)
            val scaledWidth = (source.width * scale).toInt()
            val scaledHeight = (source.height * scale).toInt()
            val padX = (inputSize - scaledWidth) / 2f
            val padY = (inputSize - scaledHeight) / 2f
            val modelBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            try {
                Canvas(modelBitmap).apply {
                    drawColor(Color.rgb(114, 114, 114))
                    drawBitmap(
                        source,
                        null,
                        RectF(padX, padY, padX + scaledWidth, padY + scaledHeight),
                        Paint(Paint.FILTER_BITMAP_FLAG),
                    )
                }
                val pixels = IntArray(inputSize * inputSize)
                modelBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
                pixels.forEachIndexed { index, color ->
                    inputValues.put(index, Color.red(color) / 255f)
                    inputValues.put(pixels.size + index, Color.green(color) / 255f)
                    inputValues.put(pixels.size * 2 + index, Color.blue(color) / 255f)
                }
                return PreparedInput(
                    values = inputValues,
                    transform = LetterboxTransform(source.width, source.height, scale, padX, padY),
                )
            } finally {
                modelBitmap.recycle()
            }
        } finally {
            source.recycle()
        }
    }

}

enum class OnnxExecutionProvider {
    CPU,
    NNAPI,
}

private data class PreparedInput(
    val values: java.nio.FloatBuffer,
    val transform: LetterboxTransform,
)

