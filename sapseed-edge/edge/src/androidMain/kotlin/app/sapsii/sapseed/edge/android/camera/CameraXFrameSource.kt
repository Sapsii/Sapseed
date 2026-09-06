package app.sapsii.sapseed.edge.android.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
) : VideoFrame {
    private val released = AtomicBoolean(false)

    override val width: Int = image.width
    override val height: Int = image.height
    override val rotationDegrees: Int = image.imageInfo.rotationDegrees

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
        .build()

    init {
        analysis.setAnalyzer(analyzerExecutor) { image ->
            val frame = AndroidVideoFrame(image)
            if (frames.trySend(frame).isFailure) frame.release()
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
        cameraProvider.unbind(analysis)
        frames.close()
    }
}
