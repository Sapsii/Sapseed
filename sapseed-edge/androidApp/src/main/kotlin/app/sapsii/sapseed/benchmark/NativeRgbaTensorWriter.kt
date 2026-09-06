package app.sapsii.sapseed.benchmark

import app.sapsii.sapseed.edge.android.inference.RgbaTensorWriter
import java.nio.ByteBuffer

class NativeRgbaTensorWriter : RgbaTensorWriter {
    override fun write(
        source: ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceRowStride: Int,
        sourcePixelStride: Int,
        rotationDegrees: Int,
        target: ByteBuffer,
        inputSize: Int,
        channelsFirst: Boolean,
    ) {
        nativeWrite(
            source,
            sourceWidth,
            sourceHeight,
            sourceRowStride,
            sourcePixelStride,
            rotationDegrees,
            target,
            inputSize,
            channelsFirst,
        )
    }

    private external fun nativeWrite(
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

    companion object {
        init {
            System.loadLibrary("sapseed_preprocess")
        }
    }
}
