package com.deskforge.app.display

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.deskforge.app.BuildConfig

/** Owns display-protocol validation and resource accounting in an isolated UID. */
class DirectDisplayService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), ::handleMessage))
    private val nativeAvailable = runCatching { System.loadLibrary("deskforge_engine") }.isSuccess

    override fun onBind(intent: Intent?): IBinder? =
        if (BuildConfig.EXPERIMENTAL_DIRECT_DISPLAY) {
            Log.i(LOG_TAG, "Binding isolated direct-display probe service")
            messenger.binder
        } else {
            null
        }

    private fun handleMessage(message: Message): Boolean {
        if (message.what != MSG_PROBE) return false
        if (message.sendingUid <= 0) {
            reply(message, MSG_FAILED, "Invalid display-service caller")
            return true
        }
        if (!nativeAvailable) {
            reply(message, MSG_FAILED, "Direct-display native runtime unavailable")
            return true
        }

        Log.i(LOG_TAG, "Starting native direct-display capability probe")
        val result = runCatching { nativeProbe() }
            .getOrDefault("unavailable:Direct-display capability probe failed")
        Log.i(LOG_TAG, "Native direct-display capability probe completed")
        if (result.startsWith(AVAILABLE_PREFIX)) {
            reply(message, MSG_AVAILABLE, result.substringAfter(':'))
        } else {
            reply(message, MSG_FAILED, result.substringAfter(':', result))
        }
        return true
    }

    private fun reply(request: Message, status: Int, detail: String) {
        val response = Message.obtain(null, status).apply {
            data = Bundle().apply {
                putString(KEY_DETAIL, detail.take(MAX_DETAIL_LENGTH))
                putInt(KEY_SERVICE_UID, Process.myUid())
            }
        }
        runCatching { request.replyTo?.send(response) }
    }

    private external fun nativeProbe(): String

    companion object {
        const val MSG_PROBE = 1
        const val MSG_AVAILABLE = 2
        const val MSG_FAILED = 3
        const val KEY_DETAIL = "detail"
        const val KEY_SERVICE_UID = "service_uid"
        private const val AVAILABLE_PREFIX = "available:"
        private const val MAX_DETAIL_LENGTH = 256
        private const val LOG_TAG = "DeskForgeDisplay"
    }
}
