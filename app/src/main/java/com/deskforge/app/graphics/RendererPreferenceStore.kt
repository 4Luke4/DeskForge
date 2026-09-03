package com.deskforge.app.graphics

import android.content.Context
import com.deskforge.app.model.RendererPreference

/** App-private renderer policy; malformed or obsolete values fail safely to Auto. */
class RendererPreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun get(): RendererPreference = preferences.getString(KEY_RENDERER, null)
        ?.let { stored -> RendererPreference.entries.firstOrNull { it.name == stored } }
        ?: RendererPreference.AUTO

    fun set(preference: RendererPreference) {
        preferences.edit().putString(KEY_RENDERER, preference.name).apply()
    }

    private companion object {
        const val FILE_NAME = "renderer-policy"
        const val KEY_RENDERER = "renderer"
    }
}
