package org.kiss.data.settings

import com.russhwolf.settings.Settings

/**
 * App-wide key/value settings backed by the platform store
 * (localStorage on JS, SharedPreferences on Android, Preferences on JVM).
 */
fun createAppSettings(): Settings = Settings()