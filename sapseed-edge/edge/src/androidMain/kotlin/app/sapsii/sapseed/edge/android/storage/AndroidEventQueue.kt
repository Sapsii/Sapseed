package app.sapsii.sapseed.edge.android.storage

import android.content.Context
import android.util.AtomicFile
import app.sapsii.sapseed.edge.contract.ObservationQueue
import app.sapsii.sapseed.edge.contract.ObservationQueueStats
import app.sapsii.sapseed.edge.model.BoundingBox
import app.sapsii.sapseed.edge.model.DetectionClass
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.GeoPoint
import app.sapsii.sapseed.edge.model.UrbanObservation
import app.sapsii.sapseed.edge.storage.EventRetentionPolicy
import app.sapsii.sapseed.edge.storage.evidenceBytes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AndroidEventQueue(
    context: Context,
    fileName: String = "pending-events.json",
    private val retentionPolicy: EventRetentionPolicy = EventRetentionPolicy(),
) : ObservationQueue {
    private val atomicFile = AtomicFile(File(context.filesDir, fileName))
    private val mutex = Mutex()

    override suspend fun enqueue(observation: UrbanObservation): List<UrbanObservation> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val observations = readObservations()
            if (observations.any { it.id == observation.id }) {
                return@withContext listOf(observation)
            }

            observations += observation
            observations.sortBy(UrbanObservation::capturedAtEpochMilliseconds)
            val evicted = retentionPolicy.evictOverflow(observations)
            writeObservations(observations)
            evicted
        }
    }

    override suspend fun pending(limit: Int): List<UrbanObservation> {
        require(limit > 0) { "Pending-observation limit must be positive" }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                readObservations().sortedBy(UrbanObservation::capturedAtEpochMilliseconds).take(limit)
            }
        }
    }

    override suspend fun remove(observationId: String) {
        mutate { observations -> observations.removeAll { it.id == observationId } }
    }

    override suspend fun referencedEvidenceIds(): Set<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            readObservations().flatMapTo(mutableSetOf<String>()) { observation ->
                observation.evidence.map(EvidenceReference::localId)
            }
        }
    }

    override suspend fun stats(): ObservationQueueStats = mutex.withLock {
        withContext(Dispatchers.IO) {
            val observations = readObservations()
            ObservationQueueStats(
                observationCount = observations.size,
                evidenceBytes = evidenceBytes(observations),
            )
        }
    }

    private suspend fun mutate(block: (MutableList<UrbanObservation>) -> Unit) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val observations = readObservations()
                block(observations)
                writeObservations(observations)
            }
        }
    }

    private fun readObservations(): MutableList<UrbanObservation> {
        if (!atomicFile.baseFile.exists() || atomicFile.baseFile.length() == 0L) return mutableListOf()
        val array = JSONArray(atomicFile.openRead().bufferedReader().use { it.readText() })
        return mutableListOf<UrbanObservation>().apply {
            repeat(array.length()) { index -> array.getJSONObject(index).toUrbanObservationOrNull()?.let(::add) }
        }
    }

    private fun writeObservations(observations: List<UrbanObservation>) {
        val output = atomicFile.startWrite()
        try {
            output.bufferedWriter().apply {
                write(JSONArray(observations.map(UrbanObservation::toJson)).toString())
                flush()
            }
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }
}

internal fun UrbanObservation.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("detectionClassId", detectionClass.classId)
    put("detectionClassName", detectionClass.wireName)
    put("confidence", confidence.toDouble())
    put("capturedAtEpochMilliseconds", capturedAtEpochMilliseconds)
    put("location", JSONObject().apply {
        put("latitude", location.latitude)
        put("longitude", location.longitude)
        location.accuracyMeters?.let { put("accuracyMeters", it.toDouble()) }
    })
    put("boundingBox", JSONObject().apply {
        put("left", boundingBox.left.toDouble())
        put("top", boundingBox.top.toDouble())
        put("right", boundingBox.right.toDouble())
        put("bottom", boundingBox.bottom.toDouble())
    })
    trackingId?.let { put("trackingId", it) }
    put("cameraId", cameraId)
    put("frameId", frameId)
    put("evidence", JSONArray(evidence.map { reference ->
        JSONObject().apply {
            put("localId", reference.localId)
            put("mediaType", reference.mediaType)
            put("sizeBytes", reference.sizeBytes)
        }
    }))
    put("metadata", JSONObject(metadata))
}

private fun JSONObject.toUrbanObservationOrNull(): UrbanObservation? = runCatching {
    val locationJson = getJSONObject("location")
    val evidenceJson = optJSONArray("evidence") ?: JSONArray()
    val legacyAttributes = optJSONObject("attributes")
    val detectionClass = if (has("detectionClassId")) {
        DetectionClass.fromClassId(getInt("detectionClassId"))
    } else {
        legacyAttributes?.optString("label")?.let(DetectionClass::fromLabel)
    } ?: return null

    val bounds = optJSONObject("boundingBox")?.let { box ->
        BoundingBox(
            left = box.getDouble("left").toFloat(),
            top = box.getDouble("top").toFloat(),
            right = box.getDouble("right").toFloat(),
            bottom = box.getDouble("bottom").toFloat(),
        )
    } ?: legacyAttributes?.optString("boundingBox")
        ?.split(',')
        ?.takeIf { it.size == 4 }
        ?.map(String::toFloat)
        ?.let { values -> BoundingBox(values[0], values[1], values[2], values[3]) }
        ?: return null

    UrbanObservation(
        id = getString("id"),
        detectionClass = detectionClass,
        confidence = getDouble("confidence").toFloat(),
        capturedAtEpochMilliseconds = optLong("capturedAtEpochMilliseconds", optLong("occurredAtEpochMilliseconds")),
        location = GeoPoint(
            latitude = locationJson.getDouble("latitude"),
            longitude = locationJson.getDouble("longitude"),
            accuracyMeters = if (locationJson.has("accuracyMeters")) {
                locationJson.getDouble("accuracyMeters").toFloat()
            } else {
                null
            },
        ),
        boundingBox = bounds,
        trackingId = optString("trackingId").takeIf(String::isNotBlank)
            ?: legacyAttributes?.optString("trackingId")?.takeIf(String::isNotBlank),
        cameraId = optString("cameraId", "primary"),
        frameId = optString("frameId").takeIf(String::isNotBlank)
            ?: legacyAttributes?.optString("sourceFrameId")?.takeIf(String::isNotBlank)
            ?: "legacy-${getString("id")}",
        evidence = List(evidenceJson.length()) { index ->
            evidenceJson.getJSONObject(index).let { evidence ->
                EvidenceReference(
                    localId = evidence.getString("localId"),
                    mediaType = evidence.getString("mediaType"),
                    sizeBytes = evidence.optLong("sizeBytes", 0L),
                )
            }
        },
        metadata = optJSONObject("metadata")?.let { metadata ->
            metadata.keys().asSequence().associateWith(metadata::getString)
        }.orEmpty(),
    )
}.getOrNull()
