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

internal sealed interface RegistrationLocationResult {
    data class Available(val latitude: Double, val longitude: Double) : RegistrationLocationResult

    data object PermissionDenied : RegistrationLocationResult

    data class PermissionFailure(val cause: SecurityException) : RegistrationLocationResult

    data object LocationDisabled : RegistrationLocationResult

    data object Unavailable : RegistrationLocationResult

    data class UnavailableFailure(val cause: IllegalArgumentException) : RegistrationLocationResult
}

internal fun interface RegistrationLocationService {
    suspend fun currentApproximateLocation(): RegistrationLocationResult
}

internal class AndroidRegistrationLocationService(
    private val context: Context,
) : RegistrationLocationService {
    override suspend fun currentApproximateLocation(): RegistrationLocationResult {
        if (!hasCoarseLocationPermission()) {
            return RegistrationLocationResult.PermissionDenied
        }
        val locationManager = ContextCompat.getSystemService(context, LocationManager::class.java)
            ?: return RegistrationLocationResult.Unavailable
        val provider = locationManager.availableProvider() ?: return RegistrationLocationResult.LocationDisabled
        val location = try {
            withTimeoutOrNull(LOCATION_TIMEOUT_MILLISECONDS) {
                requestCurrentLocation(locationManager = locationManager, provider = provider)
            }
        } catch (exception: SecurityException) {
            return RegistrationLocationResult.PermissionFailure(exception)
        } catch (exception: IllegalArgumentException) {
            return RegistrationLocationResult.UnavailableFailure(exception)
        } ?: return RegistrationLocationResult.Unavailable
        return RegistrationLocationResult.Available(
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
