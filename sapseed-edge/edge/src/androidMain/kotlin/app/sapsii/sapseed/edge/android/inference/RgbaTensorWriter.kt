package app.sapsii.sapseed.edge.android.inference

import java.nio.ByteBuffer

fun interface RgbaTensorWriter {
    fun write(
        source: ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceRowStride: Int,
        sourcePixelStride: Int,
        rotationDegrees: Int,
        target: ByteBuffer,
        inputSize: Int,
        channelsFirst: Boolean,
    )
}
