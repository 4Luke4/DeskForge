package com.deskforge.app.graphics

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.deskforge.app.model.GraphicsBackend
import com.deskforge.app.model.RendererPreference

/** Hosts the untrusted vtest protocol in an isolated UID with no application permissions. */
class GraphicsRendererService : Service() {
    private val stopping = AtomicBoolean()
    private var rendererThread: Thread? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper(), ::handleMessage))
    private val nativeAvailable = runCatching { System.loadLibrary("deskforge_graphics") }.isSuccess

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        stopping.set(true)
        if (nativeAvailable) nativeStop()
        rendererThread?.join(STOP_JOIN_TIMEOUT_MS)
        rendererThread = null
        super.onDestroy()
    }

    private fun handleMessage(message: Message): Boolean {
        when (message.what) {
            MSG_START -> startRenderer(message)
            MSG_STOP -> stopSelf()
            else -> return false
        }
        return true
    }

    private fun startRenderer(message: Message) {
        if (!nativeAvailable || rendererThread != null) {
            reply(message, MSG_FAILED, "Renderer runtime unavailable")
            return
        }
        val listener = message.data.getParcelable(KEY_LISTENER, ParcelFileDescriptor::class.java)
        if (listener == null || message.sendingUid <= 0) {
            listener?.close()
            reply(message, MSG_FAILED, "Invalid renderer transport")
            return
        }
        val preference = message.data.getString(KEY_PREFERENCE)
            ?.let { stored -> RendererPreference.entries.firstOrNull { it.name == stored } }
            ?: RendererPreference.AUTO
        val probe = runCatching {
            nativeProbe(
                requireVenus = preference == RendererPreference.VENUS,
                allowVenus = preference != RendererPreference.VIRGL,
            )
        }.getOrElse {
            listener.close()
            reply(message, MSG_FAILED, "Renderer self-test failed")
            return
        }
        if (!probe.startsWith(PROBE_HARDWARE_PREFIX) && !probe.startsWith(PROBE_VENUS_PREFIX)) {
            listener.close()
            reply(
                message,
                if (preference == RendererPreference.AUTO) MSG_FALLBACK else MSG_FAILED,
                probe.substringAfter(':', "Renderer self-test failed"),
            )
            return
        }

        val venusAvailable = probe.startsWith(PROBE_VENUS_PREFIX)
        if (preference == RendererPreference.VENUS && !venusAvailable) {
            listener.close()
            reply(message, MSG_FAILED, probe.substringAfter(':', "Venus is unavailable"))
            return
        }

        stopping.set(false)
        val renderServer = File(applicationInfo.nativeLibraryDir, VENUS_SERVER_FILE)
        if (venusAvailable && !renderServer.canExecute()) {
            listener.close()
            reply(message, MSG_FAILED, "Venus render server is unavailable")
            return
        }
        nativePrepare(renderServer.absolutePath)
        val descriptor = runCatching { listener.detachFd() }.getOrElse {
            listener.close()
            reply(message, MSG_FAILED, "Renderer listener could not be detached")
            return
        }
        val expectedPeerUid = message.sendingUid
        rendererThread = Thread(
            {
                val result = runCatching {
                    nativeRun(
                        descriptor,
                        expectedPeerUid,
                        preference != RendererPreference.VIRGL && venusAvailable,
                    )
                }
                    .getOrDefault("Renderer protocol failed")
                if (!stopping.get()) reply(message, MSG_FAILED, result)
                stopSelf()
            },
            "deskforge-virgl",
        ).apply { start() }
        reply(
            message,
            MSG_READY,
            probe.substringAfter(':'),
            if (venusAvailable && preference != RendererPreference.VIRGL) {
                GraphicsBackend.VENUS_ZINK
            } else {
                GraphicsBackend.VIRGL
            },
        )
    }

    private fun reply(
        request: Message,
        status: Int,
        detail: String,
        backend: GraphicsBackend? = null,
    ) {
        val response = Message.obtain(null, status).apply {
            data = Bundle().apply {
                putString(KEY_DETAIL, detail.take(MAX_DETAIL_LENGTH))
                backend?.let { putString(KEY_BACKEND, it.name) }
            }
        }
        runCatching { request.replyTo?.send(response) }
    }

    private external fun nativeProbe(requireVenus: Boolean, allowVenus: Boolean): String
    private external fun nativePrepare(renderServerPath: String)
    private external fun nativeRun(listenerFd: Int, expectedPeerUid: Int, enableVenus: Boolean): String
    private external fun nativeStop()

    companion object {
        const val MSG_START = 1
        const val MSG_STOP = 2
        const val MSG_READY = 3
        const val MSG_FALLBACK = 4
        const val MSG_FAILED = 5
        const val KEY_LISTENER = "listener"
        const val KEY_DETAIL = "detail"
        const val KEY_BACKEND = "backend"
        const val KEY_PREFERENCE = "preference"
        private const val PROBE_HARDWARE_PREFIX = "hardware:"
        private const val PROBE_VENUS_PREFIX = "venus:"
        private const val VENUS_SERVER_FILE = "libdeskforge_venus_server.so"
        private const val MAX_DETAIL_LENGTH = 256
        private const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}
