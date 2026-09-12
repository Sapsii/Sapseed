package app.sapsii.sapseed.edge

import app.sapsii.sapseed.edge.contract.BatchUploadResult
import app.sapsii.sapseed.edge.contract.DeviceLocationReporter
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
import app.sapsii.sapseed.edge.model.Detection
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.GeoPoint
import app.sapsii.sapseed.edge.model.UrbanObservation
import app.sapsii.sapseed.edge.model.VideoFrame
import app.sapsii.sapseed.edge.pipeline.EdgePipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Each unit reports its own fix, and a reported fix also refreshes presence. The pipeline reports
 * whether that happened so the caller knows when a heartbeat is still required.
 */
class EdgePipelineLocationTest {
    @Test
    fun reportedFixCountsAsPresence() = runBlocking {
        val reported = mutableListOf<GeoPoint>()
        val pipeline = pipeline(
            location = { GeoPoint(30.3519, 76.3642, 4f) },
            locationReporter = { point ->
                reported += point
                PresenceResult.Alive
            },
        )

        assertTrue(pipeline.reportPosition(), "a delivered fix refreshes presence on its own")
        assertEquals(listOf(GeoPoint(30.3519, 76.3642, 4f)), reported)
        assertFalse(pipeline.credentialRejected)
    }

    @Test
    fun unitWithoutAFixNeedsAHeartbeat() = runBlocking {
        var fixes = 0
        val pipeline = pipeline(
            location = { null },
            locationReporter = { fixes++; PresenceResult.Alive },
        )

        assertFalse(pipeline.reportPosition(), "without a fix the caller must fall back to a heartbeat")
        assertEquals(0, fixes)
    }

    @Test
    fun missingReporterIsNotFatal() = runBlocking {
        val pipeline = pipeline(location = { GeoPoint(30.3519, 76.3642, 4f) }, locationReporter = null)
        assertFalse(pipeline.reportPosition())
        assertFalse(pipeline.credentialRejected)
    }

    @Test
    fun rejectedFixReportStopsUploads() = runBlocking {
        val pipeline = pipeline(
            location = { GeoPoint(30.3519, 76.3642, 4f) },
            locationReporter = { PresenceResult.CredentialRejected },
        )

        assertFalse(pipeline.reportPosition())
        assertTrue(pipeline.credentialRejected, "a deactivated unit must stop reporting")
    }

    @Test
    fun unreachablePlatformKeepsTheUnitAlive() = runBlocking {
        val pipeline = pipeline(
            location = { GeoPoint(30.3519, 76.3642, 4f) },
            locationReporter = { PresenceResult.Unavailable },
        )

        assertFalse(pipeline.reportPosition())
        assertFalse(pipeline.credentialRejected, "a network failure is not a deactivation")
    }

    private fun pipeline(
        location: suspend () -> GeoPoint?,
        locationReporter: (suspend (GeoPoint) -> PresenceResult)?,
    ): EdgePipeline = EdgePipeline(
        frameSource = object : FrameSource {
            override suspend fun nextFrame(): VideoFrame? = null
            override fun close() = Unit
        },
        locationSource = object : LocationSource {
            override suspend fun currentLocation() = location()
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
            override suspend fun saveImage(frame: VideoFrame) =
                EvidenceReference(localId = "frame.jpg", mediaType = "image/jpeg", sizeBytes = 1)
            override suspend fun read(reference: EvidenceReference) = byteArrayOf()
            override suspend fun delete(reference: EvidenceReference) = Unit
        },
        observationQueue = object : ObservationQueue {
            override suspend fun enqueue(observation: UrbanObservation) = emptyList<UrbanObservation>()
            override suspend fun pending(limit: Int) = emptyList<UrbanObservation>()
            override suspend fun remove(observationId: String) = Unit
            override suspend fun referencedEvidenceIds() = emptySet<String>()
            override suspend fun stats() = ObservationQueueStats(0, 0)
        },
        observationUploader = object : ObservationUploader {
            override suspend fun upload(observations: List<UrbanObservation>) = BatchUploadResult.RetryLater
        },
        devicePresenceReporter = object : DevicePresenceReporter {
            override suspend fun reportAlive() = PresenceResult.Alive
        },
        deviceLocationReporter = locationReporter?.let { reporter ->
            object : DeviceLocationReporter {
                override suspend fun reportPosition(location: GeoPoint) = reporter(location)
            }
        },
    )
}
