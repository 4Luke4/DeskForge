package com.deskforge.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskforge.app.model.PresentationPreference
import com.deskforge.app.presentation.PresentationPreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresentationPreferenceStoreTest {
    @Test
    fun persistsExplicitPresentationPolicyInPrivateStorage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = PresentationPreferenceStore(context)

        try {
            store.set(PresentationPreference.RFB)
            assertEquals(PresentationPreference.RFB, PresentationPreferenceStore(context).get())
        } finally {
            store.set(PresentationPreference.NATIVE)
        }
    }

    @Test
    fun malformedStoredPolicyFailsSafelyToNative() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("presentation-policy", android.content.Context.MODE_PRIVATE)

        try {
            preferences.edit().putString("presentation", "unknown").apply()
            assertEquals(PresentationPreference.NATIVE, PresentationPreferenceStore(context).get())
        } finally {
            preferences.edit().clear().apply()
        }
    }
}
