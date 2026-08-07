// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.mqvpn.sdk.core.MqvpnManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MqvpnModule {
    @Provides
    @Singleton
    fun provideMqvpnManager(@ApplicationContext context: Context): MqvpnManager {
        return MqvpnManager(context)
    }

    /**
     * Settings store, backed by Device Protected Storage.
     *
     * BootReceiver reads the saved config during Direct Boot — after
     * LOCKED_BOOT_COMPLETED but before the user unlocks — and
     * credential-encrypted storage is unreadable at that point. Keeping the
     * store in DE storage is what lets boot auto-start work on a device that
     * rebooted unattended.
     *
     * Trade-off: DE storage is not encrypted with the user credential. The
     * auth key already sits in plaintext on the device either way, so this
     * moves no secret across a meaningful boundary.
     */
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        val deContext = context.createDeviceProtectedStorageContext()
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) {
            deContext.preferencesDataStoreFile("demo_settings")
        }
    }
}
