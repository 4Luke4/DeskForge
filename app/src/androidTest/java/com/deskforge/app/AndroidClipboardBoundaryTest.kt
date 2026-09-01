package com.deskforge.app

import android.content.ClipData
import android.content.ClipDescription
import android.text.SpannableString
import com.deskforge.app.model.ClipboardFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidClipboardBoundaryTest {
    @Test
    fun acceptsOneDirectPlainTextItem() {
        assertEquals(
            AndroidClipboardBoundary.ReadResult.Text("DeskForge Ж"),
            AndroidClipboardBoundary.validatePlainText(ClipData.newPlainText("test", "DeskForge Ж")),
        )
    }

    @Test
    fun rejectsRichAndOversizedText() {
        assertEquals(
            AndroidClipboardBoundary.ReadResult.Failed(ClipboardFailure.NO_PLAIN_TEXT),
            AndroidClipboardBoundary.validatePlainText(
                ClipData.newHtmlText("test", "plain", "<b>plain</b>"),
            ),
        )
        assertEquals(
            AndroidClipboardBoundary.ReadResult.Failed(ClipboardFailure.NO_PLAIN_TEXT),
            AndroidClipboardBoundary.validatePlainText(
                ClipData.newPlainText("test", SpannableString("styled")),
            ),
        )
        assertEquals(
            AndroidClipboardBoundary.ReadResult.Failed(ClipboardFailure.TEXT_TOO_LARGE),
            AndroidClipboardBoundary.validatePlainText(
                ClipData.newPlainText(
                    "test",
                    "x".repeat(AndroidClipboardBoundary.MAX_TEXT_BYTES + 1),
                ),
            ),
        )
    }

    @Test
    fun marksGuestTextSensitive() {
        val clip = AndroidClipboardBoundary.sensitivePlainText("test", "secret")
        assertTrue(clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
    }
}
