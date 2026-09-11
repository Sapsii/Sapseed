package app.sapsii.sapseed.edge

import app.sapsii.sapseed.edge.contract.BatchUploadResult
import app.sapsii.sapseed.edge.contract.EvidenceStore
import app.sapsii.sapseed.edge.contract.FrameSource
import app.sapsii.sapseed.edge.contract.InferenceEngine
import app.sapsii.sapseed.edge.contract.LocationSource
import app.sapsii.sapseed.edge.contract.ObservationQueue
import app.sapsii.sapseed.edge.contract.ObservationQueueStats
import app.sapsii.sapseed.edge.contract.ObservationUploader
import app.sapsii.sapseed.edge.model.BoundingBox
import app.sapsii.sapseed.edge.model.Detection
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.GeoPoint
import app.sapsii.sapseed.edge.model.UrbanObservation
import app.sapsii.sapseed.edge.model.VideoFrame
import app.sapsii.sapseed.edge.pipeline.DetectionObservationFactory
import app.sapsii.sapseed.edge.pipeline.EdgePipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class EdgePipelineEvidenceTest {
    @Test
    fun capturedFrameIsPersistedAndLinkedToItsObservation() = runBlocking {
        val frame = TestEvidenceFrame()
        var savedFrame: VideoFrame? = null
        var queued: UrbanObservation? = null
        val evidence = EvidenceReference("observation-1.jpg", "image/jpeg", 123)
        val pipeline = EdgePipeline(
            frameSource = object : FrameSource {
                override suspend fun nextFrame(): VideoFrame = frame
                override fun close() = Unit
            },
            locationSource = object : LocationSource {
                override suspend fun currentLocation() = GeoPoint(30.3398, 76.3869, 3.5f)
            },
            inferenceEngine = object : InferenceEngine {
                override suspend fun detect(frame: VideoFrame) = listOf(
                    Detection("pothole", 0.93f, BoundingBox(0.1f, 0.2f, 0.5f, 0.7f)),
                )
            },
            observationFactory = DetectionObservationFactory(
                cameraId = "front",
                idFactory = { "observation-1" },
            ),
            evidenceStore = object : EvidenceStore {
                override suspend fun saveImage(observationId: String, frame: VideoFrame): EvidenceReference {
                    assertEquals("observation-1", observationId)
                    savedFrame = frame
                    return evidence
                }
                override suspend fun read(reference: EvidenceReference) = byteArrayOf()
                override suspend fun delete(reference: EvidenceReference) = Unit
            },
            observationQueue = object : ObservationQueue {
                override suspend fun enqueue(observation: UrbanObservation): List<UrbanObservation> {
                    queued = observation
                    return emptyList()
                }
                override suspend fun pending(limit: Int) = emptyList<UrbanObservation>()
                override suspend fun remove(observationId: String) = Unit
                override suspend fun stats() = ObservationQueueStats(queued?.let { 1 } ?: 0, queued?.evidence?.sumOf { it.sizeBytes } ?: 0)
            },
            observationUploader = object : ObservationUploader {
                override suspend fun upload(observations: List<UrbanObservation>) = BatchUploadResult.RetryLater
            },
        )

        assertEquals(1, pipeline.processNextFrame())
        assertEquals(frame, savedFrame)
        assertEquals(listOf(evidence), queued?.evidence)
        assertEquals(frame.id, queued?.frameId)
        assertTrue(frame.released)
    }
}

private class TestEvidenceFrame : VideoFrame {
    override val id = "frame-1"
    override val capturedAtEpochMilliseconds = 123L
    override val width = 640
    override val height = 480
    override val rotationDegrees = 90
    var released = false
    override fun release() { released = true }
}
