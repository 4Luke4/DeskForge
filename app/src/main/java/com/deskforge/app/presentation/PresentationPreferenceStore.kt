package com.deskforge.app.presentation

import android.content.Context
import com.deskforge.app.model.PresentationPreference

/** App-private display policy; malformed or obsolete values fail safely to the native presenter. */
class PresentationPreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun get(): PresentationPreference = preferences.getString(KEY_PRESENTATION, null)
        ?.let { stored -> PresentationPreference.entries.firstOrNull { it.name == stored } }
        ?: PresentationPreference.NATIVE

    fun set(preference: PresentationPreference) {
        preferences.edit().putString(KEY_PRESENTATION, preference.name).apply()
    }

    private companion object {
        const val FILE_NAME = "presentation-policy"
        const val KEY_PRESENTATION = "presentation"
    }
}
