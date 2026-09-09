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
    ONNX_CPU("ONNX CPU", "yolo11n.onnx"),
    ONNX_NNAPI("ONNX NNAPI", "yolo11n.onnx"),
    LITERT_CPU("LiteRT CPU", "yolo11n.tflite"),
    LITERT_GPU("LiteRT GPU", "yolo11n.tflite"),
}

object LiveDetectorFactory {
    suspend fun create(context: Context, runtime: InferenceRuntime): YoloDetector {
        val model = withContext(Dispatchers.IO) {
            context.assets.open(runtime.modelAsset).use { it.readBytes() }
        }
        val tensorWriter = NativeRgbaTensorWriter()
        return when (runtime) {
            InferenceRuntime.ONNX_CPU -> withContext(Dispatchers.Default) {
                Yolo11OnnxDetector(
                    model,
                    OnnxExecutionProvider.CPU,
                    rgbaTensorWriter = tensorWriter,
                )
            }

            InferenceRuntime.ONNX_NNAPI -> withContext(Dispatchers.Default) {
                Yolo11OnnxDetector(
                    model,
                    OnnxExecutionProvider.NNAPI,
                    rgbaTensorWriter = tensorWriter,
                )
            }

            InferenceRuntime.LITERT_CPU -> Yolo11LiteRtDetector.create(
                model,
                LiteRtExecutionProvider.CPU,
                rgbaTensorWriter = tensorWriter,
            )

            InferenceRuntime.LITERT_GPU -> {
                val cacheDirectory = context.codeCacheDir.resolve("litert-gpu").apply {
                    check(mkdirs() || isDirectory) { "Could not create LiteRT GPU cache directory" }
                }
                Yolo11LiteRtDetector.create(
                    model,
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
