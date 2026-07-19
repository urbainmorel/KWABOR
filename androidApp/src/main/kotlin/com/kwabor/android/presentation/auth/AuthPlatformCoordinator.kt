package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.RegistrationLocationResult
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.catalog.GeoPoint
import com.kwabor.shared.domain.catalog.nearestCity
import com.kwabor.shared.presentation.auth.RegistrationIntent
import kotlinx.coroutines.launch

internal class AuthPlatformCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
) {
    fun handle(intent: AuthIntent.Platform) {
        when (intent) {
            AuthIntent.RequestLocation -> requestLocationPermission()
            is AuthIntent.LocationPermissionResult -> resolveLocation(intent.granted)
            is AuthIntent.OpenLegalDocument -> openLegalDocument(intent.type)
            AuthIntent.LegalDocumentOpenFailed -> runtime.platformState.value = runtime.platformState.value.copy(
                legalDocumentOpenFailed = true,
            )
            AuthIntent.EnableNotifications -> enableNotifications()
            AuthIntent.SkipNotifications -> skipNotifications()
            is AuthIntent.NotificationPermissionResult -> resolveNotificationPermissionResult()
        }
    }

    private fun requestLocationPermission() {
        val platformState = runtime.platformState.value
        if (
            platformState.locationPermissionRequestInFlight ||
            platformState.locationStatus == RegistrationLocationStatus.Loading
        ) {
            return
        }
        runtime.platformState.value = platformState.copy(
            locationStatus = RegistrationLocationStatus.Idle,
            locationPermissionRequestInFlight = true,
        )
        runtime.coroutineScope.launch {
            runtime.platformEffectChannel.send(AuthPlatformEffect.RequestLocationPermission)
        }
    }

    private fun resolveLocation(permissionGranted: Boolean) {
        if (!runtime.platformState.value.locationPermissionRequestInFlight) return
        runtime.platformState.value = runtime.platformState.value.copy(
            locationPermissionRequestInFlight = false,
        )
        if (!permissionGranted) {
            updateLocationStatus(RegistrationLocationStatus.PermissionDenied)
            return
        }
        if (runtime.platformState.value.locationStatus == RegistrationLocationStatus.Loading) return
        updateLocationStatus(RegistrationLocationStatus.Loading)
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            when (val result = dependencies.locationService.currentApproximateLocation()) {
                is RegistrationLocationResult.Available -> selectNearestCity(result.latitude, result.longitude)
                RegistrationLocationResult.PermissionDenied,
                is RegistrationLocationResult.PermissionFailure,
                -> updateLocationStatus(RegistrationLocationStatus.PermissionDenied)
                RegistrationLocationResult.LocationDisabled -> updateLocationStatus(
                    RegistrationLocationStatus.LocationDisabled,
                )
                RegistrationLocationResult.Unavailable,
                is RegistrationLocationResult.UnavailableFailure,
                -> updateLocationStatus(RegistrationLocationStatus.Unavailable)
            }
        }
    }

    private fun selectNearestCity(latitude: Double, longitude: Double) {
        val city = nearestCity(
            cities = runtime.registrationState.value.cities,
            location = GeoPoint(latitude = latitude, longitude = longitude),
        )
        if (city == null) {
            updateLocationStatus(RegistrationLocationStatus.OutsideBenin)
        } else {
            runtime.reduce(RegistrationIntent.SelectCity(city.id))
            updateLocationStatus(RegistrationLocationStatus.Idle)
        }
    }

    private fun updateLocationStatus(status: RegistrationLocationStatus) {
        runtime.platformState.value = runtime.platformState.value.copy(locationStatus = status)
    }

    private fun openLegalDocument(type: LegalDocumentType) {
        val state = runtime.registrationState.value
        val url = when (type) {
            LegalDocumentType.Terms -> state.termsDocument?.url
            LegalDocumentType.PrivacyPolicy -> state.privacyDocument?.url
            LegalDocumentType.UgcLicense -> state.ugcDocument?.url
        } ?: return
        runtime.platformState.value = runtime.platformState.value.copy(legalDocumentOpenFailed = false)
        runtime.coroutineScope.launch {
            runtime.platformEffectChannel.send(AuthPlatformEffect.OpenLegalDocument(url))
        }
    }

    private fun enableNotifications() {
        if (runtime.platformState.value.notificationPermissionRequestInFlight) return
        runtime.platformState.value = runtime.platformState.value.copy(
            notificationPrimingPersistenceFailed = false,
        )
        if (dependencies.notificationPermissionPolicy.requiresRuntimeRequest()) {
            runtime.platformState.value = runtime.platformState.value.copy(
                notificationPermissionRequestInFlight = true,
            )
            runtime.coroutineScope.launch {
                runtime.platformEffectChannel.send(AuthPlatformEffect.RequestNotificationPermission)
            }
        } else {
            finishNotificationPriming()
        }
    }

    private fun skipNotifications() {
        if (!runtime.platformState.value.notificationPermissionRequestInFlight) {
            finishNotificationPriming()
        }
    }

    private fun resolveNotificationPermissionResult() {
        if (!runtime.platformState.value.notificationPermissionRequestInFlight) return
        finishNotificationPriming()
    }

    private fun finishNotificationPriming() {
        if (!dependencies.notificationPrimingStore.markResolved()) {
            runtime.platformState.value = runtime.platformState.value.copy(
                notificationPermissionRequestInFlight = false,
                notificationPrimingPersistenceFailed = true,
            )
            return
        }
        runtime.platformState.value = runtime.platformState.value.copy(
            notificationPermissionRequestInFlight = false,
            notificationPrimingPersistenceFailed = false,
        )
        runtime.reduce(RegistrationIntent.FinishNotificationPriming)
        runtime.completeAuthenticatedJourney()
    }
}
