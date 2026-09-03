package com.deskforge.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskforge.app.graphics.RendererPreferenceStore
import com.deskforge.app.model.RendererPreference
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RendererPreferenceStoreTest {
    @Test
    fun persistsExplicitRendererPolicyInPrivateStorage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = RendererPreferenceStore(context)

        store.set(RendererPreference.VENUS)

        assertEquals(RendererPreference.VENUS, RendererPreferenceStore(context).get())
        store.set(RendererPreference.AUTO)
    }
}
