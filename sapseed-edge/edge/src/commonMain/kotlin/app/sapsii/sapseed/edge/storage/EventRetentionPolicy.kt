package app.sapsii.sapseed.edge.storage

import app.sapsii.sapseed.edge.model.EvidenceReference
import app.sapsii.sapseed.edge.model.UrbanObservation

class EventRetentionPolicy(
    val maxEvents: Int = DEFAULT_MAX_EVENTS,
    val maxEvidenceBytes: Long = DEFAULT_MAX_EVIDENCE_BYTES,
) {
    init {
        require(maxEvents > 0) { "Maximum observation count must be positive" }
        require(maxEvidenceBytes > 0) { "Maximum evidence size must be positive" }
    }

    /** Mutates an oldest-first list until both limits are satisfied. */
    fun evictOverflow(observations: MutableList<UrbanObservation>): List<UrbanObservation> {
        val evicted = mutableListOf<UrbanObservation>()
        while (observations.size > maxEvents || evidenceBytes(observations) > maxEvidenceBytes) {
            evicted += observations.removeAt(0)
        }
        return evicted
    }

    companion object {
        const val DEFAULT_MAX_EVENTS = 60
        const val DEFAULT_MAX_EVIDENCE_BYTES = 60L * 1024 * 1024
    }
}

fun UrbanObservation.evidenceSizeBytes(): Long = evidence.sumOf { it.sizeBytes }

/**
 * Bytes actually held on disk. Observations from one frame share an image, so it is counted
 * once rather than once per referencing observation.
 */
fun evidenceBytes(observations: List<UrbanObservation>): Long =
    observations
        .flatMap(UrbanObservation::evidence)
        .distinctBy(EvidenceReference::localId)
        .sumOf(EvidenceReference::sizeBytes)
