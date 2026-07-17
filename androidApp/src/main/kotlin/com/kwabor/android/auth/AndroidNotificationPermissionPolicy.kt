package com.kwabor.android.auth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal fun interface NotificationPermissionPolicy {
    fun requiresRuntimeRequest(): Boolean
}

internal class AndroidNotificationPermissionPolicy(
    private val context: Context,
) : NotificationPermissionPolicy {
    override fun requiresRuntimeRequest(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
}
