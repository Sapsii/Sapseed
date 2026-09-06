package app.sapsii.sapseed.edge.android.inference

import app.sapsii.sapseed.edge.android.camera.AndroidVideoFrame
import app.sapsii.sapseed.edge.contract.InferenceEngine
import app.sapsii.sapseed.edge.model.Detection
import app.sapsii.sapseed.edge.model.VideoFrame

fun interface AndroidFrameDetector {
    suspend fun detect(frame: AndroidVideoFrame): List<Detection>
}

/** Connects the common pipeline to a model-specific TFLite, ONNX, or remote detector. */
class AndroidInferenceEngine(
    private val detector: AndroidFrameDetector,
) : InferenceEngine {
    override suspend fun detect(frame: VideoFrame): List<Detection> {
        require(frame is AndroidVideoFrame) { "AndroidInferenceEngine requires an AndroidVideoFrame" }
        return detector.detect(frame)
    }
}
