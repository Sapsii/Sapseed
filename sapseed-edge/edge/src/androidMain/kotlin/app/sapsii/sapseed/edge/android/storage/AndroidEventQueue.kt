package app.sapsii.sapseed.edge.android.storage

import android.content.Context
import android.util.AtomicFile
import app.sapsii.sapseed.edge.contract.EventQueue
import app.sapsii.sapseed.edge.contract.EventQueueStats
import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.EventType
import app.sapsii.sapseed.edge.model.GeoPoint
import app.sapsii.sapseed.edge.model.UrbanEvent
import app.sapsii.sapseed.edge.storage.EventRetentionPolicy
import app.sapsii.sapseed.edge.storage.evidenceSizeBytes
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
) : EventQueue {
    private val atomicFile = AtomicFile(File(context.filesDir, fileName))
    private val mutex = Mutex()

    override suspend fun enqueue(event: UrbanEvent): List<UrbanEvent> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val events = readEvents()
            if (events.any { it.id == event.id }) {
                // Preserve the already durable event and tell the caller to delete the duplicate evidence.
                return@withContext listOf(event)
            }

            events += event
            events.sortBy(UrbanEvent::occurredAtEpochMilliseconds)
            val evicted = retentionPolicy.evictOverflow(events)
            writeEvents(events)
            evicted
        }
    }

    override suspend fun pending(limit: Int): List<UrbanEvent> {
        require(limit > 0) { "Pending-event limit must be positive" }
        return mutex.withLock {
            withContext(Dispatchers.IO) { readEvents().sortedBy(UrbanEvent::occurredAtEpochMilliseconds).take(limit) }
        }
    }

    override suspend fun remove(eventId: String) {
        mutate { events -> events.removeAll { it.id == eventId } }
    }

    override suspend fun stats(): EventQueueStats = mutex.withLock {
        withContext(Dispatchers.IO) {
            val events = readEvents()
            EventQueueStats(
                eventCount = events.size,
                evidenceBytes = events.sumOf(UrbanEvent::evidenceSizeBytes),
            )
        }
    }

    private suspend fun mutate(block: (MutableList<UrbanEvent>) -> Unit) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val events = readEvents()
                block(events)
                writeEvents(events)
            }
        }
    }

    private fun readEvents(): MutableList<UrbanEvent> {
        if (!atomicFile.baseFile.exists() || atomicFile.baseFile.length() == 0L) return mutableListOf()
        val content = atomicFile.openRead().bufferedReader().use { it.readText() }
        val array = JSONArray(content)
        return MutableList(array.length()) { index -> array.getJSONObject(index).toUrbanEvent() }
    }

    private fun writeEvents(events: List<UrbanEvent>) {
        val output = atomicFile.startWrite()
        try {
            output.bufferedWriter().apply {
                write(JSONArray(events.map(UrbanEvent::toJson)).toString())
                flush()
            }
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }
}

internal fun UrbanEvent.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("deviceId", deviceId)
    put("type", type.name)
    put("confidence", confidence.toDouble())
    put("occurredAtEpochMilliseconds", occurredAtEpochMilliseconds)
    put("location", JSONObject().apply {
        put("latitude", location.latitude)
        put("longitude", location.longitude)
        location.accuracyMeters?.let { put("accuracyMeters", it.toDouble()) }
    })
    put("evidence", JSONArray(evidence.map { reference ->
        JSONObject().apply {
            put("localId", reference.localId)
            put("mediaType", reference.mediaType)
            put("sizeBytes", reference.sizeBytes)
        }
    }))
    put("attributes", JSONObject(attributes))
}

private fun JSONObject.toUrbanEvent(): UrbanEvent {
    val location = getJSONObject("location")
    val evidenceJson = getJSONArray("evidence")
    val attributesJson = getJSONObject("attributes")
    return UrbanEvent(
        id = getString("id"),
        deviceId = getString("deviceId"),
        type = EventType.valueOf(getString("type")),
        confidence = getDouble("confidence").toFloat(),
        occurredAtEpochMilliseconds = getLong("occurredAtEpochMilliseconds"),
        location = GeoPoint(
            latitude = location.getDouble("latitude"),
            longitude = location.getDouble("longitude"),
            accuracyMeters = if (location.has("accuracyMeters")) {
                location.getDouble("accuracyMeters").toFloat()
            } else {
                null
            },
        ),
        evidence = List(evidenceJson.length()) { index ->
            evidenceJson.getJSONObject(index).let { evidence ->
                EvidenceReference(
                    localId = evidence.getString("localId"),
                    mediaType = evidence.getString("mediaType"),
                    sizeBytes = evidence.optLong("sizeBytes", 0L),
                )
            }
        },
        attributes = attributesJson.keys().asSequence().associateWith(attributesJson::getString),
    )
}
