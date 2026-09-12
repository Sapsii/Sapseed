package app.sapsii.sapseed.edge

import app.sapsii.sapseed.edge.contract.BatchUploadResult
import app.sapsii.sapseed.edge.contract.EvidenceStore
import app.sapsii.sapseed.edge.contract.FrameSource
import app.sapsii.sapseed.edge.contract.InferenceEngine
import app.sapsii.sapseed.edge.contract.LocationSource
import app.sapsii.sapseed.edge.contract.ObservationQueue
import app.sapsii.sapseed.edge.contract.ObservationQueueStats
import app.sapsii.sapseed.edge.contract.ObservationUploader
import app.sapsii.sapseed.edge.contract.UploadItemResult
import app.sapsii.sapseed.edge.model.BoundingBox
import app.sapsii.sapseed.edge.model.Detection
import app.sapsii.sapseed.edge.model.DetectionClass
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.GeoPoint
import app.sapsii.sapseed.edge.model.UrbanObservation
import app.sapsii.sapseed.edge.model.VideoFrame
import app.sapsii.sapseed.edge.pipeline.DetectionObservationFactory
import app.sapsii.sapseed.edge.pipeline.EdgePipeline
import app.sapsii.sapseed.edge.storage.evidenceBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A frame is one capture, so every detection in it must reference the same image. These tests
 * pin that sharing and the reference rule that keeps a shared image alive until the last
 * observation using it has uploaded.
 */
class EdgeFrameEvidenceSharingTest {
    @Test
    fun severalDetectionsInOneFrameStoreOneImage() = runBlocking {
        val store = RecordingEvidenceStore()
        val queue = InMemoryQueue()
        val pipeline = pipeline(
            detector = { listOf("pothole", "car", "motorcycle") },
            store = store,
            queue = queue,
        )

        assertEquals(3, pipeline.processNextFrame())
        assertEquals(1, store.saves, "a frame must be encoded once, not once per detection")
        assertEquals(3, queue.items.size)
        assertEquals(1, queue.items.map { it.evidence.single().localId }.distinct().size)
    }

    @Test
    fun sharedImageOutlivesTheObservationThatUploadedFirst() = runBlocking {
        val image = EvidenceReference("frame-1-1000.jpg", "image/jpeg", 2048)
        val first = observation("observation-1", image)
        val second = observation("observation-2", image)
        val store = RecordingEvidenceStore()
        val queue = InMemoryQueue(mutableListOf(first, second))
        var batches = 0
        val pipeline = pipeline(
            store = store,
            queue = queue,
            uploader = object : ObservationUploader {
                override suspend fun upload(observations: List<UrbanObservation>): BatchUploadResult {
                    batches++
                    val accepted = if (batches == 1) "observation-1" else "observation-2"
                    return BatchUploadResult.Completed(
                        observations
                            .filter { it.id == accepted }
                            .associate { it.id to UploadItemResult.Accepted },
                    )
                }
            },
        )

        pipeline.uploadPending()
        assertEquals(listOf("observation-2"), queue.items.map { it.id })
        assertTrue(store.deleted.isEmpty(), "the shared image is still needed by the queued observation")

        pipeline.uploadPending()
        assertEquals(emptyList(), queue.items.map { it.id })
        assertEquals(listOf("frame-1-1000.jpg"), store.deleted)
    }

    @Test
    fun distinctImagesAreDeletedWithTheirLastObservation() = runBlocking {
        val first = observation("observation-1", EvidenceReference("frame-1-1000.jpg", "image/jpeg", 2048))
        val second = observation("observation-2", EvidenceReference("frame-2-2000.jpg", "image/jpeg", 4096))
        val store = RecordingEvidenceStore()
        val queue = InMemoryQueue(mutableListOf(first, second))
        val pipeline = pipeline(
            store = store,
            queue = queue,
            uploader = object : ObservationUploader {
                override suspend fun upload(observations: List<UrbanObservation>) = BatchUploadResult.Completed(
                    observations.associate { it.id to UploadItemResult.Accepted },
                )
            },
        )

        pipeline.uploadPending()
        assertEquals(emptyList(), queue.items.map { it.id })
        assertEquals(setOf("frame-1-1000.jpg", "frame-2-2000.jpg"), store.deleted.toSet())
    }

    @Test
    fun sharedImagesAreCountedOnceAgainstTheRetentionLimit() {
        val image = EvidenceReference("frame-1-1000.jpg", "image/jpeg", 1024)
        val observations = listOf(observation("observation-1", image), observation("observation-2", image))
        assertEquals(1024, evidenceBytes(observations))
    }

    private fun observation(id: String, image: EvidenceReference) = UrbanObservation(
        id = id,
        detectionClass = DetectionClass.POTHOLE,
        confidence = 0.9f,
        capturedAtEpochMilliseconds = 1_000L,
        location = GeoPoint(30.3398, 76.3869, 3.5f),
        boundingBox = BoundingBox(0.1f, 0.2f, 0.5f, 0.7f),
        cameraId = "builtin-primary",
        frameId = "frame-1",
        evidence = listOf(image),
    )

    private fun pipeline(
        detector: suspend (VideoFrame) -> List<String> = { emptyList() },
        store: EvidenceStore = RecordingEvidenceStore(),
        queue: ObservationQueue = InMemoryQueue(),
        uploader: ObservationUploader = object : ObservationUploader {
            override suspend fun upload(observations: List<UrbanObservation>) = BatchUploadResult.RetryLater
        },
    ): EdgePipeline {
        val frame = SharingFrame()
        var nextId = 0
        return EdgePipeline(
            frameSource = object : FrameSource {
                override suspend fun nextFrame(): VideoFrame = frame
                override fun close() = Unit
            },
            locationSource = object : LocationSource {
                override suspend fun currentLocation() = GeoPoint(30.3398, 76.3869, 3.5f)
            },
            inferenceEngine = object : InferenceEngine {
                override suspend fun detect(frame: VideoFrame) = detector(frame).map {
                    Detection(label = it, confidence = 0.9f, bounds = BoundingBox(0.1f, 0.2f, 0.5f, 0.7f))
                }
            },
            observationFactory = DetectionObservationFactory(
                cameraId = "builtin-primary",
                idFactory = { "observation-${++nextId}" },
            ),
            evidenceStore = store,
            observationQueue = queue,
            observationUploader = uploader,
        )
    }
}

private class SharingFrame : VideoFrame {
    override val id = "frame-1"
    override val capturedAtEpochMilliseconds = 1_000L
    override val width = 640
    override val height = 480
    override val rotationDegrees = 0
    override fun release() = Unit
}

private class RecordingEvidenceStore : EvidenceStore {
    var saves = 0
        private set
    val deleted = mutableListOf<String>()

    override suspend fun saveImage(frame: VideoFrame): EvidenceReference {
        saves++
        return EvidenceReference(
            localId = "${frame.id}-${frame.capturedAtEpochMilliseconds}.jpg",
            mediaType = "image/jpeg",
            sizeBytes = 2048,
        )
    }

    override suspend fun read(reference: EvidenceReference) = ByteArray(0)

    override suspend fun delete(reference: EvidenceReference) {
        deleted += reference.localId
    }
}

private class InMemoryQueue(initial: MutableList<UrbanObservation> = mutableListOf()) : ObservationQueue {
    val items = initial

    override suspend fun enqueue(observation: UrbanObservation): List<UrbanObservation> {
        if (items.any { it.id == observation.id }) return listOf(observation)
        items += observation
        return emptyList()
    }

    override suspend fun pending(limit: Int) =
        items.sortedBy(UrbanObservation::capturedAtEpochMilliseconds).take(limit)

    override suspend fun remove(observationId: String) {
        items.removeAll { it.id == observationId }
    }

    override suspend fun referencedEvidenceIds(): Set<String> = items
        .flatMapTo(mutableSetOf<String>()) { observation -> observation.evidence.map(EvidenceReference::localId) }

    override suspend fun stats() = ObservationQueueStats(items.size, evidenceBytes(items))
}
