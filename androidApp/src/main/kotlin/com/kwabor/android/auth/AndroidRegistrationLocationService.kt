package com.kwabor.android.auth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

internal sealed interface ApproximateLocationResult {
    data class Available(val latitude: Double, val longitude: Double) : ApproximateLocationResult

    data object PermissionDenied : ApproximateLocationResult

    data class PermissionFailure(val cause: SecurityException) : ApproximateLocationResult

    data object LocationDisabled : ApproximateLocationResult

    data object Unavailable : ApproximateLocationResult

    data class UnavailableFailure(val cause: IllegalArgumentException) : ApproximateLocationResult
}

internal fun interface ApproximateLocationService {
    suspend fun currentApproximateLocation(): ApproximateLocationResult
}

internal class AndroidApproximateLocationService(
    private val context: Context,
) : ApproximateLocationService {
    override suspend fun currentApproximateLocation(): ApproximateLocationResult {
        if (!hasCoarseLocationPermission()) {
            return ApproximateLocationResult.PermissionDenied
        }
        val locationManager = ContextCompat.getSystemService(context, LocationManager::class.java)
            ?: return ApproximateLocationResult.Unavailable
        val provider = locationManager.availableProvider() ?: return ApproximateLocationResult.LocationDisabled
        val location = try {
            withTimeoutOrNull(LOCATION_TIMEOUT_MILLISECONDS) {
                requestCurrentLocation(locationManager = locationManager, provider = provider)
            }
        } catch (exception: SecurityException) {
            return ApproximateLocationResult.PermissionFailure(exception)
        } catch (exception: IllegalArgumentException) {
            return ApproximateLocationResult.UnavailableFailure(exception)
        } ?: return ApproximateLocationResult.Unavailable
        return ApproximateLocationResult.Available(
            latitude = location.latitude,
            longitude = location.longitude,
        )
    }

    private fun hasCoarseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    private suspend fun requestCurrentLocation(locationManager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            LocationManagerCompat.getCurrentLocation(
                locationManager,
                provider,
                cancellationSignal,
                Executor(Runnable::run),
            ) { location ->
                if (continuation.isActive) {
                    continuation.resume(location)
                }
            }
        }

    private fun LocationManager.availableProvider(): String? =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { provider -> isProviderEnabled(provider) }
}

private const val LOCATION_TIMEOUT_MILLISECONDS = 12_000L
