package app.sapsii.sapseed.edge.android.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
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
import app.sapsii.sapseed.edge.model.Detection
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Yolo11OnnxDetector(
    model: ByteArray,
    val executionProvider: OnnxExecutionProvider = OnnxExecutionProvider.CPU,
    confidenceThreshold: Float = 0.25f,
    iouThreshold: Float = 0.45f,
    private val inputSize: Int = 640,
    rgbaTensorWriter: RgbaTensorWriter? = null,
) : AndroidFrameDetector, YoloBenchmarkDetector {
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
    private val postprocessor = Yolo11Postprocessor(confidenceThreshold, iouThreshold)
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
            benchmark(frame).detections
        } else {
            benchmarkPrepared { preprocess(frame) }.detections
        }

    override suspend fun benchmark(frame: RgbaVideoFrame): YoloBenchmarkSample =
        benchmarkPrepared { preprocessRgba(frame) }

    private suspend fun benchmarkPrepared(prepare: () -> PreparedInput): YoloBenchmarkSample =
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
                    YoloBenchmarkSample(
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
        val source = image.toRgbBitmap(rotationDegrees)
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

private fun ImageProxy.toRgbBitmap(rotationDegrees: Int): Bitmap {
    require(format == android.graphics.ImageFormat.YUV_420_888) {
        "Expected YUV_420_888 camera input, received format $format"
    }
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val yBuffer = yPlane.buffer.duplicate()
    val uBuffer = uPlane.buffer.duplicate()
    val vBuffer = vPlane.buffer.duplicate()
    val yStart = yBuffer.position()
    val uStart = uBuffer.position()
    val vStart = vBuffer.position()
    val colors = IntArray(width * height)

    repeat(height) { row ->
        repeat(width) { column ->
            val y = (yBuffer.get(yStart + row * yPlane.rowStride + column * yPlane.pixelStride).toInt() and 0xff) - 16
            val chromaRow = row / 2
            val chromaColumn = column / 2
            val u = (uBuffer.get(uStart + chromaRow * uPlane.rowStride + chromaColumn * uPlane.pixelStride)
                .toInt() and 0xff) - 128
            val v = (vBuffer.get(vStart + chromaRow * vPlane.rowStride + chromaColumn * vPlane.pixelStride)
                .toInt() and 0xff) - 128
            val luminance = max(0, y)
            val red = ((298 * luminance + 409 * v + 128) shr 8).coerceIn(0, 255)
            val green = ((298 * luminance - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
            val blue = ((298 * luminance + 516 * u + 128) shr 8).coerceIn(0, 255)
            colors[row * width + column] = Color.rgb(red, green, blue)
        }
    }

    val unrotated = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
    if (rotationDegrees == 0) return unrotated
    return try {
        Bitmap.createBitmap(
            unrotated,
            0,
            0,
            unrotated.width,
            unrotated.height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) },
            true,
        )
    } finally {
        unrotated.recycle()
    }
}

