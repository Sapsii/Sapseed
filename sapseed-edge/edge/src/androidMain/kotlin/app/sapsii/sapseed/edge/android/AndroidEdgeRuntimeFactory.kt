package app.sapsii.sapseed.edge.android

import android.content.Context
import app.sapsii.sapseed.edge.android.inference.AndroidFrameDetector
import app.sapsii.sapseed.edge.android.inference.AndroidInferenceEngine
import app.sapsii.sapseed.edge.android.location.AndroidLocationSource
import app.sapsii.sapseed.edge.android.network.HttpDevicePresence
import app.sapsii.sapseed.edge.android.network.HttpEventUploader
import app.sapsii.sapseed.edge.android.storage.AndroidEventQueue
import app.sapsii.sapseed.edge.android.storage.FileEvidenceStore
import app.sapsii.sapseed.edge.contract.FrameSource
import app.sapsii.sapseed.edge.pipeline.DetectionObservationFactory
import app.sapsii.sapseed.edge.pipeline.EdgePipeline
import java.net.URL
import java.util.UUID

object AndroidEdgeRuntimeFactory {
    fun create(
        context: Context,
        frameSource: FrameSource,
        detector: AndroidFrameDetector,
        ingestionEndpoint: URL,
        authorizationHeader: () -> String,
        softwareVersion: String,
        modelVersion: String,
        instanceExternalId: String? = null,
        cameraId: String = "primary",
        softwareName: String = "sapseed-edge-android",
        modelName: String = "yolo11n",
        modelRuntime: String? = null,
        tripId: String? = null,
        minimumConfidence: Float = 0.65f,
    ): EdgePipeline {
        val evidenceStore = FileEvidenceStore(context)
        return EdgePipeline(
            frameSource = frameSource,
            locationSource = AndroidLocationSource(context),
            inferenceEngine = AndroidInferenceEngine(detector),
            observationFactory = DetectionObservationFactory(
                cameraId = cameraId,
                idFactory = { UUID.randomUUID().toString() },
                minimumConfidence = minimumConfidence,
            ),
            evidenceStore = evidenceStore,
            observationQueue = AndroidEventQueue(context),
            observationUploader = HttpEventUploader(
                endpoint = ingestionEndpoint,
                authorizationHeader = authorizationHeader,
                evidenceStore = evidenceStore,
                softwareName = softwareName,
                softwareVersion = softwareVersion,
                modelName = modelName,
                modelVersion = modelVersion,
                modelRuntime = modelRuntime,
                tripId = tripId,
            ),
            devicePresenceReporter = HttpDevicePresence(
                ingestionEndpoint = ingestionEndpoint,
                authorizationHeader = authorizationHeader,
                instanceExternalId = instanceExternalId,
            ),
        )
    }
}
