package app.sapsii.sapseed.edge

import app.sapsii.sapseed.edge.model.BoundingBox
import app.sapsii.sapseed.edge.model.Detection
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.EventType
import app.sapsii.sapseed.edge.model.GeoPoint
import app.sapsii.sapseed.edge.model.UrbanEvent
import app.sapsii.sapseed.edge.model.VideoFrame
import app.sapsii.sapseed.edge.pipeline.DetectionEventFactory
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
    fun eventFactoryFiltersAndMapsModelLabels() {
        val factory = DetectionEventFactory(
            deviceId = "prototype-phone",
            eventTypesByLabel = mapOf("pothole" to EventType.POTHOLE),
            idFactory = { "event-1" },
            minimumConfidence = 0.7f,
        )
        val bounds = BoundingBox(0.1f, 0.2f, 0.4f, 0.6f)

        val events = factory.createEvents(
            detections = listOf(
                Detection("Pothole", 0.9f, bounds),
                Detection("pothole", 0.5f, bounds),
                Detection("car", 0.99f, bounds),
            ),
            frame = TestFrame,
            location = GeoPoint(28.6139, 77.2090),
        )

        assertEquals(1, events.size)
        assertEquals(EventType.POTHOLE, events.single().type)
    }

    @Test
    fun retentionEvictsOldestEventBeyondCountLimit() {
        val events = mutableListOf(testEvent("1", 10), testEvent("2", 10), testEvent("3", 10))

        val evicted = EventRetentionPolicy(maxEvents = 2, maxEvidenceBytes = 1_000)
            .evictOverflow(events)

        assertEquals(listOf("1"), evicted.map(UrbanEvent::id))
        assertEquals(listOf("2", "3"), events.map(UrbanEvent::id))
    }

    @Test
    fun retentionEvictsOldestEventBeyondByteLimit() {
        val events = mutableListOf(testEvent("1", 60), testEvent("2", 60))

        val evicted = EventRetentionPolicy(maxEvents = 60, maxEvidenceBytes = 100)
            .evictOverflow(events)

        assertEquals(listOf("1"), evicted.map(UrbanEvent::id))
        assertEquals(60, events.single().evidence.single().sizeBytes)
    }
}

private fun testEvent(id: String, evidenceBytes: Long) = UrbanEvent(
    id = id,
    deviceId = "test-device",
    type = EventType.POTHOLE,
    confidence = 0.9f,
    occurredAtEpochMilliseconds = id.toLong(),
    location = GeoPoint(0.0, 0.0),
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
