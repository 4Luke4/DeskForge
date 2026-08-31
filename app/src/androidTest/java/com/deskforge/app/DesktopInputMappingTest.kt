package com.deskforge.app

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskforge.app.ui.AndroidKeysym
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesktopInputMappingTest {
    @Test
    fun mapsHardwareNavigationAndUnicodeKeys() {
        assertEquals(0xff51, AndroidKeysym.from(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)))
        assertEquals('a'.code, AndroidKeysym.from(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
    }

    @Test
    fun leavesAndroidSystemKeysUnmapped() {
        assertEquals(null, AndroidKeysym.from(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
    }
}
