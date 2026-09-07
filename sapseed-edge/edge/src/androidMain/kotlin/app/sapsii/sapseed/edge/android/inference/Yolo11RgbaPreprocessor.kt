package app.sapsii.sapseed.edge.android.inference

import app.sapsii.sapseed.edge.android.camera.RgbaVideoFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

internal enum class TensorLayout {
    NCHW,
    NHWC,
}

internal class Yolo11RgbaPreprocessor(
    private val inputSize: Int,
    private val layout: TensorLayout,
    private val tensorWriter: RgbaTensorWriter? = null,
) {
    val inputBytes: ByteBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    val inputFloats: FloatBuffer = inputBytes.asFloatBuffer()

    fun prepare(frame: RgbaVideoFrame): LetterboxTransform {
        val rotationDegrees = frame.rotationDegrees
        require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
            "Unsupported camera rotation: $rotationDegrees"
        }
        val rotatedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) frame.height else frame.width
        val rotatedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) frame.width else frame.height
        val scale = min(inputSize.toFloat() / rotatedWidth, inputSize.toFloat() / rotatedHeight)
        val scaledWidth = (rotatedWidth * scale).toInt()
        val scaledHeight = (rotatedHeight * scale).toInt()
        val padX = (inputSize - scaledWidth) / 2f
        val padY = (inputSize - scaledHeight) / 2f
        require(frame.rgbaPixelStride >= 4) { "Expected four-byte RGBA camera pixels" }
        val buffer = frame.rgbaBuffer.slice()
        val transform = LetterboxTransform(rotatedWidth, rotatedHeight, scale, padX, padY)
        tensorWriter?.let { writer ->
            writer.write(
                source = buffer,
                sourceWidth = frame.width,
                sourceHeight = frame.height,
                sourceRowStride = frame.rgbaRowStride,
                sourcePixelStride = frame.rgbaPixelStride,
                rotationDegrees = rotationDegrees,
                target = inputBytes,
                inputSize = inputSize,
                channelsFirst = layout == TensorLayout.NCHW,
            )
            inputBytes.rewind()
            inputFloats.rewind()
            return transform
        }

        val pixelCount = inputSize * inputSize
        val padding = 114f / 255f

        for (outputY in 0 until inputSize) {
            for (outputX in 0 until inputSize) {
                val outputIndex = outputY * inputSize + outputX
                val insideImage = outputX >= padX && outputX < padX + scaledWidth &&
                        outputY >= padY && outputY < padY + scaledHeight
                if (!insideImage) {
                    putPixel(outputIndex, pixelCount, padding, padding, padding)
                    continue
                }

                val rotatedX = ((outputX - padX) / scale).toInt().coerceIn(0, rotatedWidth - 1)
                val rotatedY = ((outputY - padY) / scale).toInt().coerceIn(0, rotatedHeight - 1)
                val sourceX: Int
                val sourceY: Int
                when (rotationDegrees) {
                    0 -> {
                        sourceX = rotatedX
                        sourceY = rotatedY
                    }

                    90 -> {
                        sourceX = rotatedY
                        sourceY = frame.height - 1 - rotatedX
                    }

                    180 -> {
                        sourceX = frame.width - 1 - rotatedX
                        sourceY = frame.height - 1 - rotatedY
                    }

                    else -> {
                        sourceX = frame.width - 1 - rotatedY
                        sourceY = rotatedX
                    }
                }
                val sourceIndex = sourceY * frame.rgbaRowStride + sourceX * frame.rgbaPixelStride
                putPixel(
                    outputIndex = outputIndex,
                    pixelCount = pixelCount,
                    red = (buffer.get(sourceIndex + 1).toInt() and 0xff) / 255f,
                    green = (buffer.get(sourceIndex + 2).toInt() and 0xff) / 255f,
                    blue = (buffer.get(sourceIndex + 3).toInt() and 0xff) / 255f,
                )
            }
        }
        inputBytes.rewind()
        inputFloats.rewind()
        return transform
    }

    private fun putPixel(
        outputIndex: Int,
        pixelCount: Int,
        red: Float,
        green: Float,
        blue: Float,
    ) {
        when (layout) {
            TensorLayout.NCHW -> {
                inputFloats.put(outputIndex, red)
                inputFloats.put(pixelCount + outputIndex, green)
                inputFloats.put(pixelCount * 2 + outputIndex, blue)
            }

            TensorLayout.NHWC -> {
                val base = outputIndex * 3
                inputFloats.put(base, red)
                inputFloats.put(base + 1, green)
                inputFloats.put(base + 2, blue)
            }
        }
    }
}
