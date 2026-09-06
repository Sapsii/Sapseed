package app.sapsii.sapseed.edge.android

import android.content.Context
import app.sapsii.sapseed.edge.android.inference.AndroidFrameDetector
import app.sapsii.sapseed.edge.android.inference.AndroidInferenceEngine
import app.sapsii.sapseed.edge.android.location.AndroidLocationSource
import app.sapsii.sapseed.edge.android.network.HttpEventUploader
import app.sapsii.sapseed.edge.android.storage.AndroidEventQueue
import app.sapsii.sapseed.edge.android.storage.FileEvidenceStore
import app.sapsii.sapseed.edge.contract.FrameSource
import app.sapsii.sapseed.edge.model.EventType
import app.sapsii.sapseed.edge.pipeline.DetectionEventFactory
import app.sapsii.sapseed.edge.pipeline.EdgePipeline
import java.net.URL
import java.util.UUID

object AndroidEdgeRuntimeFactory {
    fun create(
        context: Context,
        deviceId: String,
        frameSource: FrameSource,
        detector: AndroidFrameDetector,
        ingestionEndpoint: URL,
        eventTypesByLabel: Map<String, EventType>,
        authorizationHeader: (() -> String?)? = null,
        minimumConfidence: Float = 0.65f,
    ): EdgePipeline {
        val evidenceStore = FileEvidenceStore(context)
        return EdgePipeline(
            frameSource = frameSource,
            locationSource = AndroidLocationSource(context),
            inferenceEngine = AndroidInferenceEngine(detector),
            eventFactory = DetectionEventFactory(
                deviceId = deviceId,
                eventTypesByLabel = eventTypesByLabel,
                idFactory = { UUID.randomUUID().toString() },
                minimumConfidence = minimumConfidence,
            ),
            evidenceStore = evidenceStore,
            eventQueue = AndroidEventQueue(context),
            eventUploader = HttpEventUploader(
                endpoint = ingestionEndpoint,
                evidenceStore = evidenceStore,
                authorizationHeader = authorizationHeader,
            ),
        )
    }
}
