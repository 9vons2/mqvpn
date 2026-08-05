// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether the app may read Wi-Fi network names.
 *
 * Android treats an SSID as location data — knowing which access point you are
 * on locates you — so it is redacted to "&lt;unknown ssid&gt;" without
 * ACCESS_FINE_LOCATION. Declaring the permission in the manifest is not
 * enough: it is a runtime permission, so until the user is actually asked it
 * stays denied and every Wi-Fi shows up under the generic fallback name.
 *
 * Two features depend on it, and both fail silently without it: naming a path
 * ("Starlink" rather than "Wi-Fi") and Trusted Wi-Fi, which can only match an
 * SSID it is able to read.
 */
fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

/**
 * Whether the system location toggle is on.
 *
 * A separate gate from the permission, and an equally hard one: Android
 * redacts the SSID when location is switched off no matter what the app was
 * granted. Distinguishing the two is what stops "I allowed it and it still
 * says Wi-Fi" from being a mystery.
 */
fun isLocationEnabled(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

/** Live permission state plus the call that asks for it. */
data class LocationPermissionState(
    val granted: Boolean,
    val locationOn: Boolean,
    val request: () -> Unit,
) {
    /** Names are only readable when both gates are open. */
    val canReadNames: Boolean get() = granted && locationOn
}

@Composable
fun rememberLocationPermission(): LocationPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasLocationPermission(context)) }
    var locationOn by remember { mutableStateOf(isLocationEnabled(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = hasLocationPermission(context) }

    // Re-read on resume as well as on the dialog result: the user may grant it
    // from system settings or flip the location toggle from the shade, and a
    // "only this time" grant is revoked while the app sits in the background.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasLocationPermission(context)
                locationOn = isLocationEnabled(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    return LocationPermissionState(granted, locationOn) {
        launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
