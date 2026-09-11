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
        require(accuracyMeters == null || accuracyMeters in 0f..10_000f) { "Accuracy must be between 0 and 10000 metres" }
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

enum class DetectionClass(val classId: Int, val wireName: String) {
    PERSON(0, "person"),
    BICYCLE(1, "bicycle"),
    MOTORCYCLE(2, "motorcycle"),
    AUTORICKSHAW(3, "autorickshaw"),
    CAR(4, "car"),
    BUS(5, "bus"),
    TRUCK(6, "truck"),
    POTHOLE(7, "pothole"),
    LONGITUDINAL_CRACK(8, "longitudinal_crack"),
    TRANSVERSE_CRACK(9, "transverse_crack"),
    ALLIGATOR_CRACK(10, "alligator_crack"),
    DAMAGED_ROAD(11, "damaged_road"),
    WATERLOGGING(12, "waterlogging"),
    MANHOLE(13, "manhole"),
    TRAFFIC_SIGN(14, "traffic_sign"),
    TRAFFIC_LIGHT(15, "traffic_light"),
    ZEBRA_CROSSING(16, "zebra_crossing"),
    ROAD_DIVIDER(17, "road_divider"),
    ANIMAL(18, "animal"),
    SPEED_BUMP(19, "speed_bump"),
    UNSURFACED_ROAD(20, "unsurfaced_road");

    companion object {
        fun fromLabel(label: String): DetectionClass? = entries.firstOrNull { it.wireName == label.normalizedLabel() }
        fun fromClassId(classId: Int): DetectionClass? = entries.firstOrNull { it.classId == classId }
    }
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

data class UrbanObservation(
    val id: String,
    val detectionClass: DetectionClass,
    val confidence: Float,
    val capturedAtEpochMilliseconds: Long,
    val location: GeoPoint,
    val boundingBox: BoundingBox,
    val trackingId: String? = null,
    val cameraId: String,
    val frameId: String,
    val evidence: List<EvidenceReference> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "Observation id cannot be blank" }
        require(confidence in 0f..1f) { "Confidence must be between 0 and 1" }
        require(capturedAtEpochMilliseconds >= 0) { "Capture timestamp cannot be negative" }
        require(cameraId.isNotBlank()) { "Camera id cannot be blank" }
        require(frameId.isNotBlank()) { "Frame id cannot be blank" }
    }
}

private fun String.normalizedLabel(): String = trim().lowercase().replace(' ', '_')
