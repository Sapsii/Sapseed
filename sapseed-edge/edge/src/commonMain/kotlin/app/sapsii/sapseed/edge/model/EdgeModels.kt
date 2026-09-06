package app.sapsii.sapseed.edge.model

/** A frame whose platform-specific representation is supplied by a capture adapter. */
interface VideoFrame {
    val id: String
    val capturedAtEpochMilliseconds: Long
    val width: Int
    val height: Int
    val rotationDegrees: Int

    /** Releases the underlying camera buffer. Safe to call more than once. */
    fun release()
}

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90" }
        require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180" }
        require(accuracyMeters == null || accuracyMeters >= 0f) { "Accuracy cannot be negative" }
    }
}

data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "Bounding-box coordinates must be normalized"
        }
        require(left < right && top < bottom) { "Bounding box must have a positive area" }
    }
}

data class Detection(
    val label: String,
    val confidence: Float,
    val bounds: BoundingBox,
    val trackingId: String? = null,
) {
    init {
        require(label.isNotBlank()) { "Detection label cannot be blank" }
        require(confidence in 0f..1f) { "Confidence must be between 0 and 1" }
    }
}

enum class EventType {
    POTHOLE,
    DAMAGED_ROAD,
    MISSING_ROAD_DIVIDER,
    MISSING_ZEBRA_CROSSING,
    DAMAGED_TRAFFIC_SIGN,
    MISSING_TRAFFIC_SIGN,
    WATERLOGGING,
    TRAFFIC_CONGESTION,
    VULNERABLE_PEDESTRIAN,
    RASH_DRIVING,
    HIT_AND_RUN,
    OTHER_HAZARD,
}

data class EvidenceReference(
    val localId: String,
    val mediaType: String,
    val sizeBytes: Long,
) {
    init {
        require(localId.isNotBlank()) { "Evidence id cannot be blank" }
        require(mediaType.isNotBlank()) { "Evidence media type cannot be blank" }
        require(sizeBytes >= 0) { "Evidence size cannot be negative" }
    }
}

data class UrbanEvent(
    val id: String,
    val deviceId: String,
    val type: EventType,
    val confidence: Float,
    val occurredAtEpochMilliseconds: Long,
    val location: GeoPoint,
    val evidence: List<EvidenceReference> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "Event id cannot be blank" }
        require(deviceId.isNotBlank()) { "Device id cannot be blank" }
        require(confidence in 0f..1f) { "Confidence must be between 0 and 1" }
    }
}
