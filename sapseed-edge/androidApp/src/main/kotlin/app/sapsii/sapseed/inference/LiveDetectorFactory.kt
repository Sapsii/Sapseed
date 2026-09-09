package app.sapsii.sapseed.inference

import android.content.Context
import app.sapsii.sapseed.edge.android.inference.LiteRtExecutionProvider
import app.sapsii.sapseed.edge.android.inference.OnnxExecutionProvider
import app.sapsii.sapseed.edge.android.inference.Yolo11LiteRtDetector
import app.sapsii.sapseed.edge.android.inference.Yolo11OnnxDetector
import app.sapsii.sapseed.edge.android.inference.YoloDetector
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class InferenceRuntime(val displayName: String, val modelAsset: String) {
    ONNX_CPU("ONNX CPU", "sapseed.onnx"),
    ONNX_NNAPI("ONNX NNAPI", "sapseed.onnx"),
    LITERT_CPU("LiteRT CPU", "sapseed.tflite"),
    LITERT_GPU("LiteRT GPU", "sapseed.tflite"),
}

object LiveDetectorFactory {
    private const val LABELS_ASSET = "sapseed.labels"

    suspend fun create(context: Context, runtime: InferenceRuntime): YoloDetector {
        val model = withContext(Dispatchers.IO) {
            context.assets.open(runtime.modelAsset).use { it.readBytes() }
        }
        val labels = withContext(Dispatchers.IO) {
            context.assets.open(LABELS_ASSET).bufferedReader().useLines { lines ->
                lines.map(String::trim).filter(String::isNotEmpty).toList()
            }
        }
        require(labels.isNotEmpty()) { "$LABELS_ASSET contains no labels" }
        val tensorWriter = NativeRgbaTensorWriter()
        return when (runtime) {
            InferenceRuntime.ONNX_CPU -> withContext(Dispatchers.Default) {
                Yolo11OnnxDetector(
                    model,
                    labels,
                    OnnxExecutionProvider.CPU,
                    rgbaTensorWriter = tensorWriter,
                )
            }

            InferenceRuntime.ONNX_NNAPI -> withContext(Dispatchers.Default) {
                Yolo11OnnxDetector(
                    model,
                    labels,
                    OnnxExecutionProvider.NNAPI,
                    rgbaTensorWriter = tensorWriter,
                )
            }

            InferenceRuntime.LITERT_CPU -> Yolo11LiteRtDetector.create(
                model,
                labels,
                LiteRtExecutionProvider.CPU,
                rgbaTensorWriter = tensorWriter,
            )

            InferenceRuntime.LITERT_GPU -> {
                val cacheDirectory = context.codeCacheDir.resolve("litert-gpu").apply {
                    check(mkdirs() || isDirectory) { "Could not create LiteRT GPU cache directory" }
                }
                Yolo11LiteRtDetector.create(
                    model,
                    labels,
                    LiteRtExecutionProvider.GPU,
                    rgbaTensorWriter = tensorWriter,
                    gpuSerializationDirectory = cacheDirectory.absolutePath,
                    gpuModelToken = model.sha256Token(),
                )
            }
        }
    }
}

private fun ByteArray.sha256Token(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
