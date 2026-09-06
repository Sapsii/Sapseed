package app.sapsii.sapseed.edge.pipeline

import app.sapsii.sapseed.edge.contract.EventFactory
import app.sapsii.sapseed.edge.contract.EventQueue
import app.sapsii.sapseed.edge.contract.EventUploader
import app.sapsii.sapseed.edge.contract.EvidenceStore
import app.sapsii.sapseed.edge.contract.FrameSource
import app.sapsii.sapseed.edge.contract.InferenceEngine
import app.sapsii.sapseed.edge.contract.LocationSource
import app.sapsii.sapseed.edge.contract.UploadResult
import app.sapsii.sapseed.edge.model.UrbanEvent

class EdgePipeline(
    private val frameSource: FrameSource,
    private val locationSource: LocationSource,
    private val inferenceEngine: InferenceEngine,
    private val eventFactory: EventFactory,
    private val evidenceStore: EvidenceStore,
    private val eventQueue: EventQueue,
    private val eventUploader: EventUploader,
) {
    suspend fun processNextFrame(): Int {
        val frame = frameSource.nextFrame() ?: return 0
        try {
            val detections = inferenceEngine.detect(frame)
            if (detections.isEmpty()) return 0

            val location = locationSource.currentLocation() ?: return 0
            val events = eventFactory.createEvents(detections, frame, location)
            if (events.isEmpty()) return 0

            events.forEach { event ->
                val evidence = evidenceStore.saveImage(event.id, frame)
                val evicted = eventQueue.enqueue(event.copy(evidence = event.evidence + evidence))
                evicted.flatMap(UrbanEvent::evidence).forEach { evidenceStore.delete(it) }
            }
            return events.size
        } finally {
            frame.release()
        }
    }

    suspend fun uploadPending(limit: Int = 20): UploadBatchResult {
        require(limit > 0) { "Upload limit must be positive" }

        var accepted = 0
        var rejected = 0
        var retryScheduled = false
        for (event in eventQueue.pending(limit)) {
            when (eventUploader.upload(event)) {
                UploadResult.Accepted -> {
                    removeEventAndEvidence(event)
                    accepted++
                }

                is UploadResult.Rejected -> {
                    removeEventAndEvidence(event)
                    rejected++
                }

                UploadResult.RetryLater -> {
                    retryScheduled = true
                    break
                }
            }
        }
        return UploadBatchResult(accepted, rejected, retryScheduled)
    }

    fun close() {
        frameSource.close()
    }

    private suspend fun removeEventAndEvidence(event: UrbanEvent) {
        event.evidence.forEach { evidenceStore.delete(it) }
        eventQueue.remove(event.id)
    }
}

data class UploadBatchResult(
    val accepted: Int,
    val rejected: Int,
    val retryScheduled: Boolean,
)
