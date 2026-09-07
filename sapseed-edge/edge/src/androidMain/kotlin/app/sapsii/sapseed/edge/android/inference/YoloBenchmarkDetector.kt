package app.sapsii.sapseed.edge.android.inference

import app.sapsii.sapseed.edge.android.camera.RgbaVideoFrame
import app.sapsii.sapseed.edge.model.Detection

interface YoloBenchmarkDetector : AutoCloseable {
    suspend fun benchmark(frame: RgbaVideoFrame): YoloBenchmarkSample
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
