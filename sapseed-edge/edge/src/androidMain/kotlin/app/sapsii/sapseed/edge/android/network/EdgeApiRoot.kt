package app.sapsii.sapseed.edge.android.network

import java.net.URL

internal const val INGESTION_BATCH_PATH = "/v1/ingestion/batches"

internal fun apiRoot(ingestionEndpoint: URL): String =
    ingestionEndpoint.toString().trimEnd('/').removeSuffix(INGESTION_BATCH_PATH)

internal fun apiUrl(ingestionEndpoint: URL, path: String): URL = URL(apiRoot(ingestionEndpoint) + path)
