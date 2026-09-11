package app.sapsii.sapseed.edge.android.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import androidx.camera.core.ImageProxy
import app.sapsii.sapseed.edge.model.VideoFrame
import kotlin.math.max

/** Produces the same upright pixel orientation used by inference, so normalized boxes align with evidence. */
internal fun VideoFrame.toUprightBitmap(): Bitmap = when (this) {
    is AndroidVideoFrame -> when (image.format) {
        ImageFormat.YUV_420_888 -> image.toUprightBitmap(rotationDegrees)
        ImageFormat.JPEG -> image.decodeJpeg().rotate(rotationDegrees)
        PixelFormat.RGBA_8888 -> (this as RgbaVideoFrame).toUprightBitmap()
        else -> error("Unsupported camera image format: ${image.format}")
    }
    is RgbaVideoFrame -> toUprightBitmap()
    else -> error("Unsupported video frame implementation: ${this::class.simpleName}")
}

internal fun ImageProxy.toUprightBitmap(rotationDegrees: Int): Bitmap {
    require(format == ImageFormat.YUV_420_888) {
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

    return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888).rotate(rotationDegrees)
}

private fun RgbaVideoFrame.toUprightBitmap(): Bitmap {
    val source = rgbaBuffer.duplicate()
    val start = source.position()
    val colors = IntArray(width * height)
    repeat(height) { row ->
        repeat(width) { column ->
            val sourceIndex = start + row * rgbaRowStride + column * rgbaPixelStride
            colors[row * width + column] = Color.argb(
                source.get(sourceIndex + 3).toInt() and 0xff,
                source.get(sourceIndex).toInt() and 0xff,
                source.get(sourceIndex + 1).toInt() and 0xff,
                source.get(sourceIndex + 2).toInt() and 0xff,
            )
        }
    }
    return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888).rotate(rotationDegrees)
}

private fun ImageProxy.decodeJpeg(): Bitmap {
    val buffer = planes.single().buffer.duplicate()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Failed to decode JPEG frame" }
}

private fun Bitmap.rotate(rotationDegrees: Int): Bitmap {
    require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
        "Unsupported camera rotation: $rotationDegrees"
    }
    if (rotationDegrees == 0) return this
    return try {
        Bitmap.createBitmap(
            this,
            0,
            0,
            width,
            height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) },
            true,
        )
    } finally {
        recycle()
    }
}
