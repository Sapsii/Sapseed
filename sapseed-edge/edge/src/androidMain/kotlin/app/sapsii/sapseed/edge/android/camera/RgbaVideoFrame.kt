package app.sapsii.sapseed.edge.android.camera

import app.sapsii.sapseed.edge.model.VideoFrame
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A frame exposed as CameraX-compatible R, G, B, A bytes for on-device inference. */
interface RgbaVideoFrame : VideoFrame {
    val rgbaBuffer: ByteBuffer
    val rgbaRowStride: Int
    val rgbaPixelStride: Int
}

/**
 * Tap for live camera pixels. The buffer aliases the camera buffer: it is valid only
 * for the duration of the call with position zero, and must be copied synchronously.
 */
fun interface RgbaFrameListener {
    fun onFrame(width: Int, height: Int, rowStride: Int, pixelStride: Int, rgba: ByteBuffer)
}

/** Decoded network frame stored as R, G, B, A bytes, matching the CameraX layout. */
class DecodedRgbaFrame internal constructor(
    override val width: Int,
    override val height: Int,
    override val rgbaBuffer: ByteBuffer,
    override val id: String = UUID.randomUUID().toString(),
    override val capturedAtEpochMilliseconds: Long = System.currentTimeMillis(),
    private val onRelease: (ByteBuffer) -> Unit = {},
) : RgbaVideoFrame {
    private val released = AtomicBoolean(false)
    override val rotationDegrees: Int = 0
    override val rgbaRowStride: Int = width * RGBA_BYTES_PER_PIXEL
    override val rgbaPixelStride: Int = RGBA_BYTES_PER_PIXEL

    override fun release() {
        if (released.compareAndSet(false, true)) onRelease(rgbaBuffer)
    }

    private companion object {
        const val RGBA_BYTES_PER_PIXEL = 4
    }
}
