package app.sapsii.sapseed.edge.contract

import app.sapsii.sapseed.edge.model.Detection
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.GeoPoint
import app.sapsii.sapseed.edge.model.UrbanEvent
import app.sapsii.sapseed.edge.model.VideoFrame

interface FrameSource {
    suspend fun nextFrame(): VideoFrame?
    fun close()
}

interface LocationSource {
    suspend fun currentLocation(): GeoPoint?
}

interface InferenceEngine {
    suspend fun detect(frame: VideoFrame): List<Detection>
}

interface EventFactory {
    fun createEvents(
        detections: List<Detection>,
        frame: VideoFrame,
        location: GeoPoint,
    ): List<UrbanEvent>
}

interface EvidenceStore {
    suspend fun saveImage(eventId: String, frame: VideoFrame): EvidenceReference
    suspend fun read(reference: EvidenceReference): ByteArray
    suspend fun delete(reference: EvidenceReference)
}

interface EventQueue {
    /** Persists [event] and returns entries evicted by the configured storage limits. */
    suspend fun enqueue(event: UrbanEvent): List<UrbanEvent>
    suspend fun pending(limit: Int): List<UrbanEvent>
    suspend fun remove(eventId: String)
    suspend fun stats(): EventQueueStats
}

data class EventQueueStats(
    val eventCount: Int,
    val evidenceBytes: Long,
)

sealed interface UploadResult {
    data object Accepted : UploadResult
    data object RetryLater : UploadResult
    data class Rejected(val reason: String) : UploadResult
}

interface EventUploader {
    suspend fun upload(event: UrbanEvent): UploadResult
}
