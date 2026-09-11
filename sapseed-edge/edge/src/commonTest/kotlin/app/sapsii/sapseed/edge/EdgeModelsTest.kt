package app.sapsii.sapseed.edge

import app.sapsii.sapseed.edge.model.BoundingBox
import app.sapsii.sapseed.edge.model.Detection
import app.sapsii.sapseed.edge.model.DetectionClass
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.GeoPoint
import app.sapsii.sapseed.edge.model.UrbanObservation
import app.sapsii.sapseed.edge.model.VideoFrame
import app.sapsii.sapseed.edge.pipeline.DetectionObservationFactory
import app.sapsii.sapseed.edge.storage.EventRetentionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EdgeModelsTest {
    @Test
    fun acceptsValidCoordinates() {
        val point = GeoPoint(latitude = 28.6139, longitude = 77.2090, accuracyMeters = 3.5f)

        assertEquals(28.6139, point.latitude)
    }

    @Test
    fun rejectsInvalidCoordinates() {
        assertFailsWith<IllegalArgumentException> {
            GeoPoint(latitude = 91.0, longitude = 77.2090)
        }
    }

    @Test
    fun rejectsInvertedBoundingBox() {
        assertFailsWith<IllegalArgumentException> {
            BoundingBox(left = 0.8f, top = 0.2f, right = 0.4f, bottom = 0.7f)
        }
    }

    @Test
    fun observationFactoryPreservesRawDetectionClasses() {
        var nextId = 0
        val factory = DetectionObservationFactory(
            cameraId = "front",
            idFactory = { "observation-${++nextId}" },
            minimumConfidence = 0.7f,
        )
        val bounds = BoundingBox(0.1f, 0.2f, 0.4f, 0.6f)

        val observations = factory.createObservations(
            detections = listOf(
                Detection("Pothole", 0.9f, bounds),
                Detection("pothole", 0.5f, bounds),
                Detection("car", 0.99f, bounds),
                Detection("missing_zebra_crossing", 0.99f, bounds),
            ),
            frame = TestFrame,
            location = GeoPoint(28.6139, 77.2090),
        )

        assertEquals(listOf(DetectionClass.POTHOLE, DetectionClass.CAR), observations.map { it.detectionClass })
        assertEquals(listOf("front", "front"), observations.map { it.cameraId })
        assertEquals(listOf("frame-1", "frame-1"), observations.map { it.frameId })
    }

    @Test
    fun retentionEvictsOldestObservationBeyondCountLimit() {
        val observations = mutableListOf(testObservation("1", 10), testObservation("2", 10), testObservation("3", 10))

        val evicted = EventRetentionPolicy(maxEvents = 2, maxEvidenceBytes = 1_000)
            .evictOverflow(observations)

        assertEquals(listOf("1"), evicted.map(UrbanObservation::id))
        assertEquals(listOf("2", "3"), observations.map(UrbanObservation::id))
    }

    @Test
    fun retentionEvictsOldestObservationBeyondByteLimit() {
        val observations = mutableListOf(testObservation("1", 60), testObservation("2", 60))

        val evicted = EventRetentionPolicy(maxEvents = 60, maxEvidenceBytes = 100)
            .evictOverflow(observations)

        assertEquals(listOf("1"), evicted.map(UrbanObservation::id))
        assertEquals(60, observations.single().evidence.single().sizeBytes)
    }
}

private fun testObservation(id: String, evidenceBytes: Long) = UrbanObservation(
    id = id,
    detectionClass = DetectionClass.POTHOLE,
    confidence = 0.9f,
    capturedAtEpochMilliseconds = id.toLong(),
    location = GeoPoint(0.0, 0.0),
    boundingBox = BoundingBox(0.1f, 0.2f, 0.4f, 0.6f),
    cameraId = "front",
    frameId = "frame-$id",
    evidence = listOf(EvidenceReference("$id.jpg", "image/jpeg", evidenceBytes)),
)

private object TestFrame : VideoFrame {
    override val id = "frame-1"
    override val capturedAtEpochMilliseconds = 123L
    override val width = 640
    override val height = 480
    override val rotationDegrees = 0
    override fun release() = Unit
}
