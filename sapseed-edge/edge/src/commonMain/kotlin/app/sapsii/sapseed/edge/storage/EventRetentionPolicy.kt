package app.sapsii.sapseed.edge.storage

import app.sapsii.sapseed.edge.model.UrbanEvent

class EventRetentionPolicy(
    val maxEvents: Int = DEFAULT_MAX_EVENTS,
    val maxEvidenceBytes: Long = DEFAULT_MAX_EVIDENCE_BYTES,
) {
    init {
        require(maxEvents > 0) { "Maximum event count must be positive" }
        require(maxEvidenceBytes > 0) { "Maximum evidence size must be positive" }
    }

    /** Mutates an oldest-first list until both limits are satisfied. */
    fun evictOverflow(events: MutableList<UrbanEvent>): List<UrbanEvent> {
        val evicted = mutableListOf<UrbanEvent>()
        var evidenceBytes = events.sumOf(UrbanEvent::evidenceSizeBytes)
        while (events.size > maxEvents || evidenceBytes > maxEvidenceBytes) {
            val oldest = events.removeAt(0)
            evidenceBytes -= oldest.evidenceSizeBytes()
            evicted += oldest
        }
        return evicted
    }

    companion object {
        const val DEFAULT_MAX_EVENTS = 60
        const val DEFAULT_MAX_EVIDENCE_BYTES = 60L * 1024 * 1024
    }
}

fun UrbanEvent.evidenceSizeBytes(): Long = evidence.sumOf { it.sizeBytes }
