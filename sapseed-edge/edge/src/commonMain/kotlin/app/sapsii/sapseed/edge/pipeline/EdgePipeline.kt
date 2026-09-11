package app.sapsii.sapseed.edge.pipeline

import app.sapsii.sapseed.edge.contract.BatchUploadResult
import app.sapsii.sapseed.edge.contract.EvidenceStore
import app.sapsii.sapseed.edge.contract.FrameSource
import app.sapsii.sapseed.edge.contract.InferenceEngine
import app.sapsii.sapseed.edge.contract.LocationSource
import app.sapsii.sapseed.edge.contract.ObservationFactory
import app.sapsii.sapseed.edge.contract.ObservationQueue
import app.sapsii.sapseed.edge.contract.ObservationUploader
import app.sapsii.sapseed.edge.contract.UploadItemResult
import app.sapsii.sapseed.edge.model.Detection
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
) {
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
        observations.forEach { observation ->
            val evidence = evidenceStore.saveImage(observation.id, frame)
            val evicted = observationQueue.enqueue(observation.copy(evidence = observation.evidence + evidence))
            evicted.flatMap(UrbanObservation::evidence).forEach { evidenceStore.delete(it) }
        }
        return observations.size
    }

    suspend fun uploadPending(limit: Int = 20): UploadBatchSummary {
        require(limit in 1..100) { "Upload limit must be between 1 and 100" }
        val pending = observationQueue.pending(limit)
        if (pending.isEmpty()) return UploadBatchSummary(0, 0, retryScheduled = false)

        return when (val batch = observationUploader.upload(pending)) {
            BatchUploadResult.RetryLater -> UploadBatchSummary(0, 0, retryScheduled = true)
            is BatchUploadResult.Completed -> {
                var accepted = 0
                var rejected = 0
                var retryScheduled = false
                pending.forEach { observation ->
                    when (batch.resultsByObservationId[observation.id]) {
                        UploadItemResult.Accepted -> {
                            removeObservationAndEvidence(observation)
                            accepted++
                        }
                        is UploadItemResult.Rejected -> {
                            removeObservationAndEvidence(observation)
                            rejected++
                        }
                        null -> retryScheduled = true
                    }
                }
                UploadBatchSummary(accepted, rejected, retryScheduled)
            }
        }
    }

    fun close() {
        frameSource.close()
    }

    private suspend fun removeObservationAndEvidence(observation: UrbanObservation) {
        observation.evidence.forEach { evidenceStore.delete(it) }
        observationQueue.remove(observation.id)
    }
}

data class UploadBatchSummary(
    val accepted: Int,
    val rejected: Int,
    val retryScheduled: Boolean,
)
