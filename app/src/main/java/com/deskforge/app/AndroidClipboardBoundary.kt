package com.deskforge.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.text.Spanned
import com.deskforge.app.model.ClipboardFailure
import java.nio.charset.StandardCharsets

/** Explicit plain-text-only boundary between Android and the untrusted Linux guest. */
internal object AndroidClipboardBoundary {
    const val MAX_TEXT_BYTES = 1024 * 1024

    sealed interface ReadResult {
        data class Text(val value: String) : ReadResult
        data class Failed(val reason: ClipboardFailure) : ReadResult
    }

    fun readPlainText(clipboard: ClipboardManager): ReadResult {
        val clip: ClipData
        try {
            clip = clipboard.primaryClip
                ?: return ReadResult.Failed(ClipboardFailure.NO_PLAIN_TEXT)
        } catch (_: RuntimeException) {
            return ReadResult.Failed(ClipboardFailure.ANDROID_CLIPBOARD_FAILED)
        }
        return validatePlainText(clip)
    }

    internal fun validatePlainText(clip: ClipData): ReadResult {
        val description = clip.description
        if (description.mimeTypeCount != 1 ||
            description.getMimeType(0) != ClipDescription.MIMETYPE_TEXT_PLAIN ||
            clip.itemCount != 1
        ) {
            return ReadResult.Failed(ClipboardFailure.NO_PLAIN_TEXT)
        }
        val item = clip.getItemAt(0)
        val directText = item.text
        val text = directText?.toString()
        if (text == null || directText is Spanned || item.htmlText != null || item.uri != null ||
            item.intent != null
        ) {
            return ReadResult.Failed(ClipboardFailure.NO_PLAIN_TEXT)
        }
        if (text.toByteArray(StandardCharsets.UTF_8).size > MAX_TEXT_BYTES) {
            return ReadResult.Failed(ClipboardFailure.TEXT_TOO_LARGE)
        }
        return ReadResult.Text(text)
    }

    fun sensitivePlainText(label: String, text: String): ClipData =
        ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
}
