package app.sapsii.sapseed.benchmark

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BenchmarkReportStore(private val context: Context) {
    suspend fun save(report: DeviceBenchmarkReport): File = withContext(Dispatchers.IO) {
        val root = context.getExternalFilesDir("benchmarks")
            ?: File(context.filesDir, "benchmarks")
        val stamp = report.createdAt.replace(Regex("[^0-9]"), "").take(14)
        val directory = File(root, "${stamp}-${report.provider.name.lowercase()}").apply {
            check(mkdirs() || isDirectory) { "Could not create benchmark directory" }
        }

        File(directory, "samples.csv").writeText(report.toCsv())
        File(directory, "summary.json").writeText(report.toJson().toString(2))
        File(directory, "latency.svg").writeText(report.toLatencySvg())
        File(directory, "benchmark.log").writeText(report.toLog())
        directory
    }
}

private fun DeviceBenchmarkReport.toJson(): JSONObject = JSONObject().apply {
    put("createdAt", createdAt)
    put("model", model)
    put("provider", provider.name)
    put("warmupIterations", warmupIterations)
    put("measuredIterations", samples.size)
    put("sessionInitializationMs", sessionInitializationMs)
    put("device", JSONObject().apply {
        put("manufacturer", deviceManufacturer)
        put("model", deviceModel)
        put("androidVersion", androidVersion)
        put("sdkLevel", sdkLevel)
        put("availableProcessors", availableProcessors)
    })
    put("thermalStatus", JSONObject().apply {
        put("initial", initialThermalStatus ?: JSONObject.NULL)
        put("final", finalThermalStatus ?: JSONObject.NULL)
    })
    put("summary", JSONObject().apply {
        put("meanTotalMs", meanTotalMs)
        put("medianTotalMs", medianTotalMs)
        put("p95TotalMs", p95TotalMs)
        put("p99TotalMs", p99TotalMs)
        put("meanInferenceMs", meanInferenceMs)
        put("effectiveFps", effectiveFps)
        put("peakPssMegabytes", peakPssMegabytes)
    })
    put("samples", JSONArray(samples.map { sample ->
        JSONObject().apply {
            put("iteration", sample.iteration)
            put("preprocessMs", sample.preprocessMs)
            put("inferenceMs", sample.inferenceMs)
            put("postprocessMs", sample.postprocessMs)
            put("totalMs", sample.totalMs)
            put("detections", sample.detections)
            put("objects", sample.detectionSummary)
            put("pssMegabytes", sample.pssMegabytes)
        }
    }))
}

private fun DeviceBenchmarkReport.toCsv(): String = buildString {
    appendLine("iteration,preprocess_ms,inference_ms,postprocess_ms,total_ms,detections,objects,pss_mb")
    samples.forEach { sample ->
        appendLine(
            listOf(
                sample.iteration,
                sample.preprocessMs.format(),
                sample.inferenceMs.format(),
                sample.postprocessMs.format(),
                sample.totalMs.format(),
                sample.detections,
                "\"${sample.detectionSummary.replace("\"", "\"\"")}\"",
                sample.pssMegabytes.format(),
            ).joinToString(","),
        )
    }
}

private fun DeviceBenchmarkReport.toLog(): String = buildString {
    appendLine("Sapseed real-device YOLO benchmark")
    appendLine("createdAt=$createdAt")
    appendLine("device=$deviceManufacturer $deviceModel Android $androidVersion (SDK $sdkLevel)")
    appendLine("model=$model provider=$provider")
    appendLine("warmup=$warmupIterations measured=${samples.size}")
    appendLine("sessionInitializationMs=${sessionInitializationMs.format()}")
    samples.forEach { sample ->
        appendLine(
            "iteration=${sample.iteration} totalMs=${sample.totalMs.format()} " +
                    "preprocessMs=${sample.preprocessMs.format()} " +
                    "inferenceMs=${sample.inferenceMs.format()} " +
                    "postprocessMs=${sample.postprocessMs.format()} " +
                    "detections=${sample.detections} objects=${sample.detectionSummary} " +
                    "pssMb=${sample.pssMegabytes.format()}",
        )
    }
    appendLine("meanTotalMs=${meanTotalMs.format()}")
    appendLine("medianTotalMs=${medianTotalMs.format()}")
    appendLine("p95TotalMs=${p95TotalMs.format()}")
    appendLine("p99TotalMs=${p99TotalMs.format()}")
    appendLine("meanInferenceMs=${meanInferenceMs.format()}")
    appendLine("effectiveFps=${effectiveFps.format()}")
    appendLine("peakPssMb=${peakPssMegabytes.format()}")
    appendLine("thermalInitial=$initialThermalStatus thermalFinal=$finalThermalStatus")
}

private fun DeviceBenchmarkReport.toLatencySvg(): String {
    val width = 1_000.0
    val height = 520.0
    val margin = 70.0
    val plotWidth = width - margin * 2
    val plotHeight = height - margin * 2
    val maximum = samples.maxOf(DeviceBenchmarkSample::totalMs).coerceAtLeast(1.0) * 1.1
    fun x(index: Int): Double = margin + index.toDouble() / (samples.size - 1).coerceAtLeast(1) * plotWidth
    fun y(milliseconds: Double): Double = height - margin - milliseconds / maximum * plotHeight
    val totalPoints = samples.mapIndexed { index, sample -> "${x(index)},${y(sample.totalMs)}" }
        .joinToString(" ")
    val inferencePoints = samples.mapIndexed { index, sample -> "${x(index)},${y(sample.inferenceMs)}" }
        .joinToString(" ")

    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="1000" height="520" viewBox="0 0 1000 520">
          <rect width="1000" height="520" fill="#101418"/>
          <text x="70" y="34" fill="#ffffff" font-family="sans-serif" font-size="22">YOLO11n ${provider.name} latency — $deviceModel</text>
          <line x1="70" y1="450" x2="930" y2="450" stroke="#82909d"/>
          <line x1="70" y1="70" x2="70" y2="450" stroke="#82909d"/>
          <polyline points="$totalPoints" fill="none" stroke="#4fc3f7" stroke-width="3"/>
          <polyline points="$inferencePoints" fill="none" stroke="#ffb74d" stroke-width="3"/>
          <text x="75" y="92" fill="#4fc3f7" font-family="sans-serif" font-size="16">total</text>
          <text x="140" y="92" fill="#ffb74d" font-family="sans-serif" font-size="16">inference</text>
          <text x="12" y="80" fill="#ffffff" font-family="sans-serif" font-size="14">${maximum.format()} ms</text>
          <text x="18" y="450" fill="#ffffff" font-family="sans-serif" font-size="14">0 ms</text>
          <text x="455" y="500" fill="#ffffff" font-family="sans-serif" font-size="15">iteration</text>
        </svg>
    """.trimIndent()
}
