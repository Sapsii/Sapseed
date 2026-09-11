package app.sapsii.sapseed.edge.pipeline

import app.sapsii.sapseed.edge.contract.ObservationFactory
import app.sapsii.sapseed.edge.model.Detection
import app.sapsii.sapseed.edge.model.DetectionClass
import app.sapsii.sapseed.edge.model.GeoPoint
import app.sapsii.sapseed.edge.model.UrbanObservation
import app.sapsii.sapseed.edge.model.VideoFrame

class DetectionObservationFactory(
    private val cameraId: String,
    private val idFactory: () -> String,
    private val minimumConfidence: Float = 0.65f,
) : ObservationFactory {
    init {
        require(cameraId.isNotBlank()) { "Camera id cannot be blank" }
        require(minimumConfidence in 0f..1f) { "Minimum confidence must be between 0 and 1" }
    }

    override fun createObservations(
        detections: List<Detection>,
        frame: VideoFrame,
        location: GeoPoint,
    ): List<UrbanObservation> = detections.mapNotNull { detection ->
        val detectionClass = DetectionClass.fromLabel(detection.label) ?: return@mapNotNull null
        if (detection.confidence < minimumConfidence) return@mapNotNull null

        UrbanObservation(
            id = idFactory(),
            detectionClass = detectionClass,
            confidence = detection.confidence,
            capturedAtEpochMilliseconds = frame.capturedAtEpochMilliseconds,
            location = location,
            boundingBox = detection.bounds,
            trackingId = detection.trackingId,
            cameraId = cameraId,
            frameId = frame.id,
        )
    }
}
