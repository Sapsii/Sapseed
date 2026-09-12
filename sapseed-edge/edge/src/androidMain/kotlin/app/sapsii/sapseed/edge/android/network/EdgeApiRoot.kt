package app.sapsii.sapseed.edge.android.network

import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal const val INGESTION_BATCH_PATH = "/v1/ingestion/batches"
internal const val DEVICE_HEARTBEAT_PATH = "/v1/devices/heartbeat"

internal fun apiRoot(ingestionEndpoint: URL): String =
    ingestionEndpoint.toString().trimEnd('/').removeSuffix(INGESTION_BATCH_PATH)

internal fun apiUrl(ingestionEndpoint: URL, path: String): URL = URL(apiRoot(ingestionEndpoint) + path)

internal fun Long.toIsoTimestamp(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(this))
