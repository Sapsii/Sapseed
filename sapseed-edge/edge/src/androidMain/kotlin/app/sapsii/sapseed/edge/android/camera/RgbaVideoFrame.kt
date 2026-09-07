package app.sapsii.sapseed.edge.android.camera

import app.sapsii.sapseed.edge.model.VideoFrame
import java.nio.ByteBuffer

/** A frame exposed as CameraX-compatible A, R, G, B bytes for on-device inference. */
interface RgbaVideoFrame : VideoFrame {
    val rgbaBuffer: ByteBuffer
    val rgbaRowStride: Int
    val rgbaPixelStride: Int
}
