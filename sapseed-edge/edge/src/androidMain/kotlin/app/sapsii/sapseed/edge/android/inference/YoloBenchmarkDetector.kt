package app.sapsii.sapseed.edge.android.inference

import app.sapsii.sapseed.edge.android.camera.AndroidVideoFrame
import app.sapsii.sapseed.edge.model.Detection

interface YoloBenchmarkDetector : AutoCloseable {
    suspend fun benchmark(frame: AndroidVideoFrame): YoloBenchmarkSample
}

data class YoloBenchmarkSample(
    val preprocessMs: Double,
    val inferenceMs: Double,
    val postprocessMs: Double,
    val totalMs: Double,
    val detections: List<Detection>,
)

internal fun Long.elapsedMilliseconds(): Double =
    (android.os.SystemClock.elapsedRealtimeNanos() - this) / 1_000_000.0
