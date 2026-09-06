package app.sapsii.sapseed.edge.android.inference

import app.sapsii.sapseed.edge.model.BoundingBox
import app.sapsii.sapseed.edge.model.Detection
import kotlin.math.max
import kotlin.math.min

internal class Yolo11Postprocessor(
    private val confidenceThreshold: Float,
    private val iouThreshold: Float,
) {
    fun decode(output: Array<FloatArray>, transform: LetterboxTransform): List<Detection> {
        require(output.size == 4 + COCO_LABELS.size) {
            "Expected ${4 + COCO_LABELS.size} YOLO channels, received ${output.size}"
        }
        val anchorCount = output[0].size
        val bestClasses = IntArray(anchorCount)
        val bestConfidences = output[4].copyOf()
        for (classIndex in 1 until COCO_LABELS.size) {
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
                label = COCO_LABELS[candidate.classIndex],
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

private val COCO_LABELS = listOf(
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
    "traffic_light", "fire_hydrant", "stop_sign", "parking_meter", "bench", "bird", "cat", "dog",
    "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella",
    "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports_ball", "kite",
    "baseball_bat", "baseball_glove", "skateboard", "surfboard", "tennis_racket", "bottle",
    "wine_glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich",
    "orange", "broccoli", "carrot", "hot_dog", "pizza", "donut", "cake", "chair", "couch",
    "potted_plant", "bed", "dining_table", "toilet", "tv", "laptop", "mouse", "remote",
    "keyboard", "cell_phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book",
    "clock", "vase", "scissors", "teddy_bear", "hair_drier", "toothbrush",
)
