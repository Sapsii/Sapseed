package app.sapsii.sapseed.edge.android.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import java.nio.ByteBuffer
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import app.sapsii.sapseed.edge.contract.FrameSource
import app.sapsii.sapseed.edge.model.VideoFrame
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

class AndroidVideoFrame internal constructor(
    val image: ImageProxy,
    override val id: String = UUID.randomUUID().toString(),
    override val capturedAtEpochMilliseconds: Long = System.currentTimeMillis(),
) : RgbaVideoFrame {
    private val released = AtomicBoolean(false)

    override val width: Int = image.width
    override val height: Int = image.height
    override val rotationDegrees: Int = image.imageInfo.rotationDegrees
    override val rgbaBuffer: ByteBuffer get() = image.planes.single().buffer.slice()
    override val rgbaRowStride: Int get() = image.planes.single().rowStride
    override val rgbaPixelStride: Int get() = image.planes.single().pixelStride

    override fun release() {
        if (released.compareAndSet(false, true)) image.close()
    }
}

class CameraXFrameSource(
    private val cameraProvider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    analyzerExecutor: Executor,
    previewSurfaceProvider: Preview.SurfaceProvider? = null,
    cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    outputImageFormat: Int = ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888,
    outputImageRotationEnabled: Boolean = false,
    private val frameListener: RgbaFrameListener? = null,
    targetResolution: Size? = null,
) : FrameSource {
    private val frames: Channel<VideoFrame> = Channel(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = VideoFrame::release,
    )
    private val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(outputImageFormat)
        .setOutputImageRotationEnabled(outputImageRotationEnabled)
        .apply {
            if (targetResolution != null) {
                setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                targetResolution,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
            }
        }
        .build()

    private var lastTapErrorMs = 0L

    init {
        analysis.setAnalyzer(analyzerExecutor) { image ->
            frameListener?.let { listener ->
                try {
                    val plane = image.planes.single()
                    val bytes = plane.buffer.duplicate()
                    bytes.rewind()
                    listener.onFrame(image.width, image.height, plane.rowStride, plane.pixelStride, bytes)
                } catch (error: Throwable) {
                    // Never let a tap bug kill the analyzer thread: that would
                    // silently stop all downstream frames with no further error.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTapErrorMs > TAP_ERROR_LOG_INTERVAL_MS) {
                        lastTapErrorMs = now
                        Log.e(LOG_TAG, "Frame tap dropped a frame", error)
                    }
                }
                // Broadcast path: the tap is the consumer, so release the proxy
                // immediately. KEEP_ONLY_LATEST otherwise blocks delivery until
                // the previous proxy is closed, starving every subsequent frame.
                image.close()
            } ?: run {
                val frame = AndroidVideoFrame(image)
                if (frames.trySend(frame).isFailure) frame.release()
            }
        }

        val useCases = buildList {
            previewSurfaceProvider?.let { surfaceProvider ->
                add(Preview.Builder().build().apply { setSurfaceProvider(surfaceProvider) })
            }
            add(analysis)
        }
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, *useCases.toTypedArray())
    }

    override suspend fun nextFrame(): VideoFrame? = frames.receiveCatching().getOrNull()

    override fun close() {
        analysis.clearAnalyzer()
        cameraProvider.unbindAll()
        frames.close()
    }

    private companion object {
        const val LOG_TAG = "SapseedCamera"
        const val TAP_ERROR_LOG_INTERVAL_MS = 2_000L
    }
}
