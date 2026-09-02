package com.deskforge.app.graphics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import com.deskforge.app.BuildConfig
import com.deskforge.app.model.GraphicsBackend
import com.deskforge.app.model.GraphicsFallbackReason
import com.deskforge.app.model.GraphicsTransportSnapshot
import com.deskforge.app.model.GraphicsTransportStatus
import com.deskforge.app.model.RendererMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class GraphicsTransportController(
    context: Context,
    private val createListener: (String) -> Int,
) {
    private val applicationContext = context.applicationContext
    @Volatile
    private var connection: ServiceConnection? = null
    @Volatile
    private var service: Messenger? = null
    @Volatile
    private var startupLatch: CountDownLatch? = null
    @Volatile
    private var hadReadyRenderer = false
    @Volatile
    private var snapshot = fallback(
        GraphicsTransportStatus.UNAVAILABLE,
        GraphicsFallbackReason.RUNTIME_UNAVAILABLE,
        "Renderer has not been started",
    )

    fun start(runtimeDirectory: File, timeoutMs: Long): RendererMode {
        stop()
        hadReadyRenderer = false
        val socket = File(runtimeDirectory, BuildConfig.GRAPHICS_SOCKET_NAME)
        val rawDescriptor = createListener(socket.absolutePath)
        if (rawDescriptor < 0) {
            snapshot = fallback(
                GraphicsTransportStatus.FALLBACK,
                GraphicsFallbackReason.TRANSPORT_LOST,
                "Private graphics socket could not be created",
            )
            return snapshot.rendererMode
        }
        val listener = ParcelFileDescriptor.adoptFd(rawDescriptor)
        val latch = CountDownLatch(1)
        startupLatch = latch
        snapshot = fallback(
            GraphicsTransportStatus.STARTING,
            GraphicsFallbackReason.RUNTIME_UNAVAILABLE,
            "Renderer self-test is pending",
        )

        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (startupLatch === latch) handleReply(message)
            true
        })
        val candidate = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) {
                    listener.close()
                    return
                }
                val remote = Messenger(binder)
                service = remote
                val duplicate = runCatching { ParcelFileDescriptor.dup(listener.fileDescriptor) }
                    .getOrElse {
                        listener.close()
                        fail(
                            GraphicsFallbackReason.TRANSPORT_LOST,
                            "Renderer listener could not be transferred",
                        )
                        return
                    }
                val request = Message.obtain(null, GraphicsRendererService.MSG_START).apply {
                    data = Bundle().apply {
                        putParcelable(
                            GraphicsRendererService.KEY_LISTENER,
                            duplicate,
                        )
                    }
                    replyTo = reply
                }
                runCatching { remote.send(request) }.onFailure {
                    fail(GraphicsFallbackReason.SERVICE_UNAVAILABLE, "Renderer service did not accept startup")
                }
                duplicate.close()
                listener.close()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (connection === this) {
                    fail(GraphicsFallbackReason.TRANSPORT_LOST, "Renderer service disconnected")
                }
            }

            override fun onBindingDied(name: ComponentName) {
                if (connection === this) {
                    fail(GraphicsFallbackReason.TRANSPORT_LOST, "Renderer service process exited")
                }
            }

            override fun onNullBinding(name: ComponentName) {
                if (connection === this) {
                    fail(GraphicsFallbackReason.SERVICE_UNAVAILABLE, "Renderer service is unavailable")
                }
            }
        }
        connection = candidate
        val bound = applicationContext.bindService(
            Intent(applicationContext, GraphicsRendererService::class.java),
            candidate,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) {
            listener.close()
            fail(GraphicsFallbackReason.SERVICE_UNAVAILABLE, "Renderer service could not be bound")
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            fail(GraphicsFallbackReason.STARTUP_TIMEOUT, "Renderer startup exceeded ${timeoutMs}ms")
            stopServiceBinding()
        }
        listener.close()
        return snapshot.rendererMode
    }

    fun snapshot(): GraphicsTransportSnapshot = snapshot

    fun guestProbeFailed(detail: String): RendererMode {
        runCatching { service?.send(Message.obtain(null, GraphicsRendererService.MSG_STOP)) }
        stopServiceBinding()
        hadReadyRenderer = false
        snapshot = fallback(
            GraphicsTransportStatus.FALLBACK,
            GraphicsFallbackReason.GUEST_PROBE_FAILED,
            detail,
        )
        return snapshot.rendererMode
    }

    fun stop() {
        runCatching { service?.send(Message.obtain(null, GraphicsRendererService.MSG_STOP)) }
        stopServiceBinding()
        snapshot = snapshot.copy(status = GraphicsTransportStatus.STOPPED)
    }

    private fun handleReply(message: Message) {
        val detail = message.data.getString(GraphicsRendererService.KEY_DETAIL).orEmpty()
        snapshot = when (message.what) {
            GraphicsRendererService.MSG_READY -> GraphicsTransportSnapshot(
                status = GraphicsTransportStatus.READY,
                rendererMode = RendererMode.Accelerated(GraphicsBackend.VIRGL, detail),
            ).also { hadReadyRenderer = true }
            GraphicsRendererService.MSG_FALLBACK -> fallback(
                GraphicsTransportStatus.FALLBACK,
                GraphicsFallbackReason.SOFTWARE_HOST_RENDERER,
                detail,
            )
            else -> fallback(
                if (hadReadyRenderer) GraphicsTransportStatus.FAILED else GraphicsTransportStatus.FALLBACK,
                GraphicsFallbackReason.SELF_TEST_FAILED,
                detail.ifBlank { "Renderer service failed" },
            )
        }
        startupLatch?.countDown()
        if (message.what != GraphicsRendererService.MSG_READY) stopServiceBinding()
    }

    private fun fail(reason: GraphicsFallbackReason, detail: String) {
        snapshot = fallback(
            if (hadReadyRenderer) GraphicsTransportStatus.FAILED else GraphicsTransportStatus.FALLBACK,
            reason,
            detail,
        )
        startupLatch?.countDown()
        stopServiceBinding()
    }

    private fun stopServiceBinding() {
        val bound = connection
        connection = null
        service = null
        startupLatch = null
        if (bound != null) runCatching { applicationContext.unbindService(bound) }
    }

    private fun fallback(
        status: GraphicsTransportStatus,
        reason: GraphicsFallbackReason,
        detail: String,
    ) = GraphicsTransportSnapshot(
        status = status,
        rendererMode = RendererMode.Software(reason = reason, detail = detail),
    )
}
