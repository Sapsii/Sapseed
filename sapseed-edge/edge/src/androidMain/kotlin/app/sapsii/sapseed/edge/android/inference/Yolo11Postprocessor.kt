package app.sapsii.sapseed.edge.android.inference

import app.sapsii.sapseed.edge.model.BoundingBox
import app.sapsii.sapseed.edge.model.Detection
import kotlin.math.max
import kotlin.math.min

internal class Yolo11Postprocessor(
    private val labels: List<String>,
    private val confidenceThreshold: Float,
    private val iouThreshold: Float,
) {
    init {
        require(labels.isNotEmpty()) { "At least one model label is required" }
    }

    fun decode(output: Array<FloatArray>, transform: LetterboxTransform): List<Detection> {
        require(output.size == 4 + labels.size) {
            "Expected ${4 + labels.size} YOLO channels, received ${output.size}"
        }
        val anchorCount = output[0].size
        val bestClasses = IntArray(anchorCount)
        val bestConfidences = output[4].copyOf()
        for (classIndex in 1 until labels.size) {
            val classScores = output[4 + classIndex]
            for (anchor in 0 until anchorCount) {
                val score = classScores[anchor]
                if (score > bestConfidences[anchor]) {
                    bestConfidences[anchor] = score
                    bestClasses[anchor] = classIndex
                }
            }
        }

        val candidates = ArrayList<Candidate>()
        repeat(anchorCount) { anchor ->
            val confidence = bestConfidences[anchor]
            if (confidence < confidenceThreshold) return@repeat
            val classIndex = bestClasses[anchor]

            val centerX = output[0][anchor]
            val centerY = output[1][anchor]
            val width = output[2][anchor]
            val height = output[3][anchor]
            val left = ((centerX - width / 2 - transform.padX) / transform.scale)
                .coerceIn(0f, transform.sourceWidth.toFloat())
            val top = ((centerY - height / 2 - transform.padY) / transform.scale)
                .coerceIn(0f, transform.sourceHeight.toFloat())
            val right = ((centerX + width / 2 - transform.padX) / transform.scale)
                .coerceIn(0f, transform.sourceWidth.toFloat())
            val bottom = ((centerY + height / 2 - transform.padY) / transform.scale)
                .coerceIn(0f, transform.sourceHeight.toFloat())
            if (right <= left || bottom <= top) return@repeat

            candidates += Candidate(
                classIndex = classIndex,
                confidence = confidence,
                bounds = BoundingBox(
                    left = left / transform.sourceWidth,
                    top = top / transform.sourceHeight,
                    right = right / transform.sourceWidth,
                    bottom = bottom / transform.sourceHeight,
                ),
            )
        }

        val selected = mutableListOf<Candidate>()
        candidates.sortedByDescending(Candidate::confidence).forEach { candidate ->
            if (selected.none {
                    it.classIndex == candidate.classIndex &&
                            intersectionOverUnion(it.bounds, candidate.bounds) > iouThreshold
                }
            ) {
                selected += candidate
            }
        }
        return selected.map { candidate ->
            Detection(
                label = labels[candidate.classIndex],
                confidence = candidate.confidence,
                bounds = candidate.bounds,
            )
        }
    }
}

internal data class LetterboxTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float,
)

private data class Candidate(
    val classIndex: Int,
    val confidence: Float,
    val bounds: BoundingBox,
)

private fun intersectionOverUnion(first: BoundingBox, second: BoundingBox): Float {
    val intersectionWidth = max(0f, min(first.right, second.right) - max(first.left, second.left))
    val intersectionHeight = max(0f, min(first.bottom, second.bottom) - max(first.top, second.top))
    val intersection = intersectionWidth * intersectionHeight
    val firstArea = (first.right - first.left) * (first.bottom - first.top)
    val secondArea = (second.right - second.left) * (second.bottom - second.top)
    return intersection / (firstArea + secondArea - intersection).coerceAtLeast(1e-6f)
}
