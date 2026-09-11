package app.sapsii.sapseed.edge.android.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import app.sapsii.sapseed.edge.contract.LocationSource
import app.sapsii.sapseed.edge.model.GeoPoint
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidLocationSource(
    context: Context,
    private val callbackExecutor: Executor = ContextCompat.getMainExecutor(context),
) : LocationSource {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    override suspend fun currentLocation(): GeoPoint? {
        if (!hasLocationPermission()) return null

        val providers = preferredProviders()
        if (providers.isEmpty()) return null
        return try {
            providers.firstNotNullOfOrNull { provider ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    currentLocation(provider)
                } else {
                    @Suppress("DEPRECATION")
                    currentLocationLegacy(provider)
                }
            } ?: providers.mapNotNull(locationManager::getLastKnownLocation)
                .maxByOrNull(Location::getTime)
                ?.toGeoPoint()
        } catch (_: SecurityException) {
            null
        }
    }

    private suspend fun currentLocation(provider: String): GeoPoint? =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            locationManager.getCurrentLocation(provider, cancellationSignal, callbackExecutor) { location ->
                if (continuation.isActive) continuation.resume(location?.toGeoPoint())
            }
        }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private suspend fun currentLocationLegacy(provider: String): GeoPoint? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location.toGeoPoint())
                }

                override fun onProviderDisabled(provider: String) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            continuation.invokeOnCancellation { locationManager.removeUpdates(listener) }
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }

    private fun preferredProviders(): List<String> = buildList {
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
}

private fun Location.toGeoPoint() = GeoPoint(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = if (hasAccuracy()) accuracy else null,
)
