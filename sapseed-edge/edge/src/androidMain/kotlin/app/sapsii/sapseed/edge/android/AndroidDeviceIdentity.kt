package app.sapsii.sapseed.edge.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import java.security.MessageDigest
import java.util.Locale

object AndroidDeviceIdentity {
    const val HASH_LENGTH = 6
    const val DEFAULT_DEVICE_NAME = "SAPSEED-EDGE"

    fun externalId(context: Context): String =
        "${normalizeName(deviceName(context))}-${hardwareHash(context)}"

    @SuppressLint("HardwareIds")
    fun deviceName(context: Context): String {
        val configured = runCatching {
            Settings.Global.getString(context.contentResolver, "device_name")
        }.getOrNull()
        if (!configured.isNullOrBlank()) return configured

        val model = Build.MODEL.takeIf { !it.isNullOrBlank() && !it.equals("unknown", ignoreCase = true) }
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase(Locale.US) }
        return when {
            model == null -> DEFAULT_DEVICE_NAME
            manufacturer.isNullOrBlank() -> model
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }

    fun hardwareHash(context: Context): String {
        val seed = hardwareSerial(context) ?: androidId(context) ?: buildFingerprint()
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(HASH_LENGTH)
    }

    fun serialSource(context: Context): String = when {
        hardwareSerial(context) != null -> "imei"
        androidId(context) != null -> "android-id"
        else -> "build-fingerprint"
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun hardwareSerial(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@runCatching null
        context.getSystemService(TelephonyManager::class.java)
            ?.imei
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
    }.getOrNull()

    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
    }.getOrNull()

    private fun buildFingerprint(): String =
        listOf(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.FINGERPRINT).joinToString("|")

    private fun normalizeName(name: String): String {
        val normalized = name.trim().uppercase(Locale.US).replace(Regex("[^A-Z0-9._-]"), "-").trim('-')
        return normalized.ifEmpty { DEFAULT_DEVICE_NAME }
    }
}
