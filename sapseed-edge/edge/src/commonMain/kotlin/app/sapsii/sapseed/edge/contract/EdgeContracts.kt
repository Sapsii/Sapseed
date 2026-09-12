package app.sapsii.sapseed.edge.contract

import app.sapsii.sapseed.edge.model.Detection
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.GeoPoint
import app.sapsii.sapseed.edge.model.UrbanObservation
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

interface ObservationFactory {
    fun createObservations(
        detections: List<Detection>,
        frame: VideoFrame,
        location: GeoPoint,
    ): List<UrbanObservation>
}

interface EvidenceStore {
    /**
     * Persists the capture for [frame]. Every observation taken from that frame receives
     * the same reference, so one frame is stored and transferred once.
     */
    suspend fun saveImage(frame: VideoFrame): EvidenceReference
    suspend fun read(reference: EvidenceReference): ByteArray
    suspend fun delete(reference: EvidenceReference)
}

interface ObservationQueue {
    /** Persists [observation] and returns entries evicted by the configured storage limits. */
    suspend fun enqueue(observation: UrbanObservation): List<UrbanObservation>
    suspend fun pending(limit: Int): List<UrbanObservation>
    suspend fun remove(observationId: String)

    /** Local evidence ids still referenced by queued observations. */
    suspend fun referencedEvidenceIds(): Set<String>

    suspend fun stats(): ObservationQueueStats
}

data class ObservationQueueStats(
    val observationCount: Int,
    val evidenceBytes: Long,
)

sealed interface UploadItemResult {
    data object Accepted : UploadItemResult
    data class Rejected(val reason: String) : UploadItemResult
}

sealed interface BatchUploadResult {
    data class Completed(val resultsByObservationId: Map<String, UploadItemResult>) : BatchUploadResult
    data object RetryLater : BatchUploadResult

    data object CredentialRejected : BatchUploadResult
}

interface ObservationUploader {
    suspend fun upload(observations: List<UrbanObservation>): BatchUploadResult
}

sealed interface PresenceResult {
    data object Alive : PresenceResult
    data object CredentialRejected : PresenceResult
    data object Unavailable : PresenceResult
}

interface DevicePresenceReporter {
    suspend fun reportAlive(): PresenceResult
}

/**
 * Sends this unit's own GPS fix. Reporting a position also refreshes presence, so a unit with
 * a fix does not need a separate heartbeat.
 */
interface DeviceLocationReporter {
    suspend fun reportPosition(location: GeoPoint): PresenceResult
}
