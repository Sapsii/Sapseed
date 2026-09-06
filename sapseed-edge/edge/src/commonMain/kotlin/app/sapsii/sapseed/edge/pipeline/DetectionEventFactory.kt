package app.sapsii.sapseed.edge.pipeline

import app.sapsii.sapseed.edge.contract.EventFactory
import app.sapsii.sapseed.edge.model.Detection
import app.sapsii.sapseed.edge.model.EventType
import app.sapsii.sapseed.edge.model.GeoPoint
import app.sapsii.sapseed.edge.model.UrbanEvent
import app.sapsii.sapseed.edge.model.VideoFrame

class DetectionEventFactory(
    private val deviceId: String,
    eventTypesByLabel: Map<String, EventType>,
    private val idFactory: () -> String,
    private val minimumConfidence: Float = 0.65f,
) : EventFactory {
    private val eventTypesByLabel = eventTypesByLabel.mapKeys { (label, _) -> label.normalizedLabel() }

    init {
        require(deviceId.isNotBlank()) { "Device id cannot be blank" }
        require(minimumConfidence in 0f..1f) { "Minimum confidence must be between 0 and 1" }
    }

    override fun createEvents(
        detections: List<Detection>,
        frame: VideoFrame,
        location: GeoPoint,
    ): List<UrbanEvent> = detections.mapNotNull { detection ->
        val eventType = eventTypesByLabel[detection.label.normalizedLabel()]
            ?: return@mapNotNull null
        if (detection.confidence < minimumConfidence) return@mapNotNull null

        UrbanEvent(
            id = idFactory(),
            deviceId = deviceId,
            type = eventType,
            confidence = detection.confidence,
            occurredAtEpochMilliseconds = frame.capturedAtEpochMilliseconds,
            location = location,
            attributes = buildMap {
                put("sourceFrameId", frame.id)
                put("label", detection.label)
                put("boundingBox", detection.bounds.run { "$left,$top,$right,$bottom" })
                detection.trackingId?.let { put("trackingId", it) }
            },
        )
    }
}

private fun String.normalizedLabel(): String = trim().lowercase().replace(' ', '_')
