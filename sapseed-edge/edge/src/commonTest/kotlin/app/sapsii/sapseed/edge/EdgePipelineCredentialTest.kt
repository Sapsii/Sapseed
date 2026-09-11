package app.sapsii.sapseed.edge

import app.sapsii.sapseed.edge.contract.BatchUploadResult
import app.sapsii.sapseed.edge.contract.DevicePresenceReporter
import app.sapsii.sapseed.edge.contract.EvidenceStore
import app.sapsii.sapseed.edge.contract.FrameSource
import app.sapsii.sapseed.edge.contract.InferenceEngine
import app.sapsii.sapseed.edge.contract.LocationSource
import app.sapsii.sapseed.edge.contract.ObservationFactory
import app.sapsii.sapseed.edge.contract.ObservationQueue
import app.sapsii.sapseed.edge.contract.ObservationQueueStats
import app.sapsii.sapseed.edge.contract.ObservationUploader
import app.sapsii.sapseed.edge.contract.PresenceResult
import app.sapsii.sapseed.edge.model.BoundingBox
import app.sapsii.sapseed.edge.model.Detection
import app.sapsii.sapseed.edge.model.DetectionClass
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.GeoPoint
import app.sapsii.sapseed.edge.model.UrbanObservation
import app.sapsii.sapseed.edge.model.VideoFrame
import app.sapsii.sapseed.edge.pipeline.EdgePipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class EdgePipelineCredentialTest {
    @Test
    fun rejectedCredentialStopsFurtherUploads() = runBlocking {
        var uploadAttempts = 0
        val pipeline = pipeline(
            uploader = rejectingUploader { uploadAttempts++ },
        )

        val first = pipeline.uploadPending()
        assertTrue(first.credentialRejected)
        assertFalse(first.retryScheduled)
        assertTrue(pipeline.credentialRejected)

        val second = pipeline.uploadPending()
        assertTrue(second.credentialRejected)
        assertEquals(1, uploadAttempts, "a rejected credential must not be retried")
    }

    @Test
    fun transientFailureKeepsRetrying() = runBlocking {
        var uploadAttempts = 0
        val pipeline = pipeline(
            uploader = object : ObservationUploader {
                override suspend fun upload(observations: List<UrbanObservation>): BatchUploadResult {
                    uploadAttempts++
                    return BatchUploadResult.RetryLater
                }
            },
        )

        val first = pipeline.uploadPending()
        assertTrue(first.retryScheduled)
        assertFalse(first.credentialRejected)
        assertFalse(pipeline.credentialRejected)
        pipeline.uploadPending()
        assertEquals(2, uploadAttempts, "transient failures must keep retrying")
    }

    @Test
    fun acceptedBatchClearsTheQueueItem() = runBlocking {
        val pipeline = pipeline(
            uploader = object : ObservationUploader {
                override suspend fun upload(observations: List<UrbanObservation>) = BatchUploadResult.Completed(
                    observations.associate { it.id to app.sapsii.sapseed.edge.contract.UploadItemResult.Accepted },
                )
            },
        )

        val summary = pipeline.uploadPending()
        assertEquals(1, summary.accepted)
        assertFalse(summary.credentialRejected)
    }

    @Test
    fun presenceReportsAliveWhileTheUnitIsHealthy() = runBlocking {
        val pipeline = pipeline(presence = reporter { PresenceResult.Alive })
        assertEquals(PresenceResult.Alive, pipeline.reportPresence())
        assertFalse(pipeline.credentialRejected)
    }

    @Test
    fun presenceDetectsARevokedUnit() = runBlocking {
        val pipeline = pipeline(presence = reporter { PresenceResult.CredentialRejected })
        assertEquals(PresenceResult.CredentialRejected, pipeline.reportPresence())
        assertTrue(pipeline.credentialRejected, "a revoked credential must stop uploads as well")
    }

    @Test
    fun presenceFailureIsNotTerminal() = runBlocking {
        val pipeline = pipeline(presence = reporter { PresenceResult.Unavailable })
        assertEquals(PresenceResult.Unavailable, pipeline.reportPresence())
        assertFalse(pipeline.credentialRejected, "a network failure is not a deactivation")
    }

    @Test
    fun missingPresenceReporterIsNotFatal() = runBlocking {
        val pipeline = pipeline(presence = null)
        assertEquals(PresenceResult.Unavailable, pipeline.reportPresence())
        assertFalse(pipeline.credentialRejected)
    }

    private fun rejectingUploader(onAttempt: () -> Unit) = object : ObservationUploader {
        override suspend fun upload(observations: List<UrbanObservation>): BatchUploadResult {
            onAttempt()
            return BatchUploadResult.CredentialRejected
        }
    }

    private fun reporter(result: () -> PresenceResult) = object : DevicePresenceReporter {
        override suspend fun reportAlive() = result()
    }

    private fun pipeline(
        uploader: ObservationUploader = object : ObservationUploader {
            override suspend fun upload(observations: List<UrbanObservation>) = BatchUploadResult.Completed(emptyMap())
        },
        presence: DevicePresenceReporter? = null,
    ): EdgePipeline {
        val observation = UrbanObservation(
            id = "observation-1",
            detectionClass = DetectionClass.POTHOLE,
            confidence = 0.9f,
            capturedAtEpochMilliseconds = 1_000L,
            location = GeoPoint(30.3398, 76.3869, 3.5f),
            boundingBox = BoundingBox(0.1f, 0.2f, 0.5f, 0.7f),
            cameraId = "builtin-primary",
            frameId = "frame-1",
        )
        return EdgePipeline(
            frameSource = object : FrameSource {
                override suspend fun nextFrame(): VideoFrame? = null
                override fun close() = Unit
            },
            locationSource = object : LocationSource {
                override suspend fun currentLocation() = GeoPoint(30.3398, 76.3869, 3.5f)
            },
            inferenceEngine = object : InferenceEngine {
                override suspend fun detect(frame: VideoFrame) = emptyList<Detection>()
            },
            observationFactory = object : ObservationFactory {
                override fun createObservations(
                    detections: List<Detection>,
                    frame: VideoFrame,
                    location: GeoPoint,
                ) = emptyList<UrbanObservation>()
            },
            evidenceStore = object : EvidenceStore {
                override suspend fun saveImage(observationId: String, frame: VideoFrame) =
                    EvidenceReference(localId = "evidence-1", mediaType = "image/jpeg", sizeBytes = 1)
                override suspend fun read(reference: EvidenceReference) = byteArrayOf()
                override suspend fun delete(reference: EvidenceReference) = Unit
            },
            observationQueue = object : ObservationQueue {
                override suspend fun enqueue(observation: UrbanObservation) = emptyList<UrbanObservation>()
                override suspend fun pending(limit: Int) = listOf(observation)
                override suspend fun remove(observationId: String) = Unit
                override suspend fun stats() = ObservationQueueStats(1, 1)
            },
            observationUploader = uploader,
            devicePresenceReporter = presence,
        )
    }
}
