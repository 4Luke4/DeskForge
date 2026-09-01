package com.deskforge.app

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskforge.app.ui.AndroidKeysym
import com.deskforge.app.ui.DesktopSurface
import com.deskforge.app.ui.DesktopSurfaceCallbacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun commitsComposedTextOnlyAfterImeCommit() {
        val committed = mutableListOf<String>()
        lateinit var surface: DesktopSurface
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            surface = DesktopSurface(
                ApplicationProvider.getApplicationContext(),
                callbacks(committed = committed),
            )
            val connection = surface.onCreateInputConnection(EditorInfo())
            assertTrue(surface.onCheckIsTextEditor())
            assertTrue(connection.setComposingText("Ж", 1))
            assertTrue(committed.isEmpty())
            assertTrue(connection.commitText("Ж🙂", 1))
        }
        assertEquals(listOf("Ж🙂"), committed)
    }

    @Test
    fun mapsImeDeletionAndEditorActionToBoundedKeys() {
        val keys = mutableListOf<Pair<Int, Boolean>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val surface = DesktopSurface(
                ApplicationProvider.getApplicationContext(),
                callbacks(keys = keys),
            )
            val connection = surface.onCreateInputConnection(EditorInfo())
            assertTrue(connection.deleteSurroundingText(1, 1))
            assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_NONE))
        }
        assertEquals(
            listOf(
                0xff08 to true,
                0xff08 to false,
                0xffff to true,
                0xffff to false,
                0xff0d to true,
                0xff0d to false,
            ),
            keys,
        )
    }

    private fun callbacks(
        committed: MutableList<String> = mutableListOf(),
        keys: MutableList<Pair<Int, Boolean>> = mutableListOf(),
    ) = DesktopSurfaceCallbacks(
        onSurfaceViewReady = {},
        onSurfaceReady = { _, _ -> },
        onSurfaceResized = {},
        onSurfaceDestroyed = {},
        onPointer = { _, _, _ -> },
        onKey = { keysym, pressed -> keys += keysym to pressed },
        onText = { committed += it },
    )
}
