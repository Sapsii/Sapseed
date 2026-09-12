package app.sapsii.sapseed.edge.pipeline

import app.sapsii.sapseed.edge.contract.BatchUploadResult
import app.sapsii.sapseed.edge.contract.DeviceLocationReporter
import app.sapsii.sapseed.edge.contract.DevicePresenceReporter
import app.sapsii.sapseed.edge.contract.EvidenceStore
import app.sapsii.sapseed.edge.contract.FrameSource
import app.sapsii.sapseed.edge.contract.InferenceEngine
import app.sapsii.sapseed.edge.contract.LocationSource
import app.sapsii.sapseed.edge.contract.ObservationFactory
import app.sapsii.sapseed.edge.contract.ObservationQueue
import app.sapsii.sapseed.edge.contract.ObservationUploader
import app.sapsii.sapseed.edge.contract.PresenceResult
import app.sapsii.sapseed.edge.contract.UploadItemResult
import app.sapsii.sapseed.edge.model.Detection
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.UrbanObservation
import app.sapsii.sapseed.edge.model.VideoFrame

class EdgePipeline(
    private val frameSource: FrameSource,
    private val locationSource: LocationSource,
    private val inferenceEngine: InferenceEngine,
    private val observationFactory: ObservationFactory,
    private val evidenceStore: EvidenceStore,
    private val observationQueue: ObservationQueue,
    private val observationUploader: ObservationUploader,
    private val devicePresenceReporter: DevicePresenceReporter? = null,
    private val deviceLocationReporter: DeviceLocationReporter? = null,
) {
    var credentialRejected: Boolean = false
        private set

    suspend fun processNextFrame(): Int {
        val frame = frameSource.nextFrame() ?: return 0
        try {
            return recordDetections(frame, inferenceEngine.detect(frame))
        } finally {
            frame.release()
        }
    }

    /** Persists already-computed detections without running inference a second time. */
    suspend fun recordDetections(frame: VideoFrame, detections: List<Detection>): Int {
        if (detections.isEmpty()) return 0
        val location = locationSource.currentLocation() ?: return 0
        val observations = observationFactory.createObservations(detections, frame, location)
        if (observations.isEmpty()) return 0

        // One frame is one capture: every detection in it references the same image, so a
        // multi-detection frame uploads a single JPEG instead of one per detection.
        val frameEvidence = evidenceStore.saveImage(frame)
        val evicted = mutableListOf<UrbanObservation>()
        for (observation in observations) {
            evicted += observationQueue.enqueue(observation.copy(evidence = observation.evidence + frameEvidence))
        }
        deleteUnreferencedEvidence(evicted.flatMap(UrbanObservation::evidence))
        return observations.size
    }

    suspend fun uploadPending(limit: Int = 20): UploadBatchSummary {
        require(limit in 1..100) { "Upload limit must be between 1 and 100" }
        if (credentialRejected) {
            return UploadBatchSummary(0, 0, retryScheduled = false, credentialRejected = true)
        }
        val pending = observationQueue.pending(limit)
        if (pending.isEmpty()) return UploadBatchSummary(0, 0, retryScheduled = false)

        return when (val batch = observationUploader.upload(pending)) {
            BatchUploadResult.CredentialRejected -> {
                credentialRejected = true
                UploadBatchSummary(0, 0, retryScheduled = false, credentialRejected = true)
            }
            BatchUploadResult.RetryLater -> UploadBatchSummary(0, 0, retryScheduled = true)
            is BatchUploadResult.Completed -> {
                var rejected = 0
                var retryScheduled = false
                val finished = mutableListOf<UrbanObservation>()
                pending.forEach { observation ->
                    when (batch.resultsByObservationId[observation.id]) {
                        UploadItemResult.Accepted -> finished += observation
                        is UploadItemResult.Rejected -> {
                            finished += observation
                            rejected++
                        }
                        null -> retryScheduled = true
                    }
                }
                removeObservations(finished)
                UploadBatchSummary(finished.size - rejected, rejected, retryScheduled)
            }
        }
    }

    fun close() {
        frameSource.close()
    }

    private suspend fun removeObservations(observations: List<UrbanObservation>) {
        if (observations.isEmpty()) return
        observations.forEach { observationQueue.remove(it.id) }
        deleteUnreferencedEvidence(observations.flatMap(UrbanObservation::evidence))
    }

    /**
     * Removes capture files no queued observation references any more. Observations from one
     * frame share an image, so it must outlive whichever of them uploaded first.
     */
    private suspend fun deleteUnreferencedEvidence(candidates: List<EvidenceReference>) {
        val unique = candidates.distinctBy(EvidenceReference::localId)
        if (unique.isEmpty()) return
        val referenced = observationQueue.referencedEvidenceIds()
        for (reference in unique) {
            if (reference.localId !in referenced) evidenceStore.delete(reference)
        }
    }

    suspend fun reportPresence(): PresenceResult {
        val reporter = devicePresenceReporter ?: return PresenceResult.Unavailable
        val result = reporter.reportAlive()
        if (result is PresenceResult.CredentialRejected) credentialRejected = true
        return result
    }

    /**
     * Sends this unit's own fix, which also refreshes its presence. Returns false when there is no
     * fix to send or the request failed, so the caller can fall back to [reportPresence].
     */
    suspend fun reportPosition(): Boolean {
        val reporter = deviceLocationReporter ?: return false
        val location = locationSource.currentLocation() ?: return false
        val result = reporter.reportPosition(location)
        if (result is PresenceResult.CredentialRejected) credentialRejected = true
        return result is PresenceResult.Alive
    }
}

data class UploadBatchSummary(
    val accepted: Int,
    val rejected: Int,
    val retryScheduled: Boolean,
    val credentialRejected: Boolean = false,
)
