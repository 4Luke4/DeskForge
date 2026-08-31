package com.deskforge.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.deskforge.app.engine.NativeDeskForgeEngine
import com.deskforge.app.model.DesktopViewport
import com.deskforge.app.model.RuntimeCapabilities
import com.deskforge.app.model.SessionFailure
import com.deskforge.app.model.SessionState
import com.deskforge.app.ui.DesktopSurfaceCallbacks
import com.deskforge.app.ui.DeskForgeApp
import com.deskforge.app.ui.theme.DeskForgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val workspaceViewModel: WorkspaceViewModel by viewModels()
    private lateinit var diagnosticsEngine: NativeDeskForgeEngine
    private var sessionState: SessionState = SessionState.Idle
        set(value) {
            field = value
            renderState.value = value
        }
    private val renderState = androidx.compose.runtime.mutableStateOf<SessionState>(SessionState.Idle)
    private val capabilityState = androidx.compose.runtime.mutableStateOf<RuntimeCapabilities?>(null)
    private val rootfsState = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val updateRequiredState = androidx.compose.runtime.mutableStateOf(false)
    private var sessionService: DeskForgeSessionService? = null
    private var serviceBound = false
    private var stateCollection: Job? = null
    private var currentSurface: Surface? = null
    private var currentViewport: DesktopViewport? = null
    private var downloadConfirmationShown = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denial does not block the foreground session or its in-app stop control. */ }

    private val downloadConfirmation = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { downloadConfirmationShown = false }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? DeskForgeSessionService.LocalBinder ?: return
            sessionService = localBinder.service
            serviceBound = true
            stateCollection?.cancel()
            stateCollection = lifecycleScope.launch {
                localBinder.service.state.collectLatest { state ->
                    if (state == SessionState.Idle &&
                        (workspaceViewModel.state.value.progress != null ||
                            workspaceViewModel.state.value.failure != null)
                    ) {
                        applyWorkspaceState(workspaceViewModel.state.value)
                    } else {
                        sessionState = state
                    }
                    if (state is SessionState.Starting) attachCurrentSurface()
                }
            }
            attachCurrentSurface()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            stateCollection?.cancel()
            stateCollection = null
            sessionService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        diagnosticsEngine = NativeDeskForgeEngine(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                workspaceViewModel.state.collectLatest(::applyWorkspaceState)
            }
        }

        setContent {
            DeskForgeTheme {
                DeskForgeApp(
                    sessionState = renderState.value,
                    capabilities = capabilityState.value,
                    isInstalled = rootfsState.value != null,
                    requiresUpdate = updateRequiredState.value,
                    desktopCallbacks = DesktopSurfaceCallbacks(
                        onSurfaceReady = ::onSurfaceReady,
                        onSurfaceResized = ::onSurfaceResized,
                        onSurfaceDestroyed = ::onSurfaceDestroyed,
                        onPointer = ::onPointer,
                        onKey = ::onKey,
                    ),
                    onInstall = ::installFedora,
                    onCapabilityCheck = ::inspectCapabilities,
                    onStart = ::startSession,
                    onStop = ::stopSession,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DeskForgeSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        sessionService?.detachSurface()
        if (serviceBound) unbindService(connection)
        serviceBound = false
        sessionService = null
        stateCollection?.cancel()
        stateCollection = null
        super.onStop()
    }

    private fun installFedora() {
        workspaceViewModel.install()
    }

    private fun startSession() {
        val rootfs = rootfsState.value
        if (rootfs == null) {
            sessionState = SessionState.Failed(SessionFailure.WORKSPACE_UNAVAILABLE, true)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        sessionState = SessionState.Starting
        val intent = Intent(this, DeskForgeSessionService::class.java)
            .setAction(DeskForgeSessionService.ACTION_PREPARE)
            .putExtra(DeskForgeSessionService.EXTRA_ROOTFS, rootfs)
        ContextCompat.startForegroundService(this, intent)
        attachCurrentSurface()
    }

    private fun stopSession() {
        sessionState = SessionState.Stopping
        sessionService?.stopDesktop() ?: startService(
            Intent(this, DeskForgeSessionService::class.java).setAction(DeskForgeSessionService.ACTION_STOP),
        )
    }

    private fun inspectCapabilities() {
        lifecycleScope.launch {
            capabilityState.value = withContext(Dispatchers.IO) { diagnosticsEngine.inspectCapabilities() }
        }
    }

    private fun onSurfaceReady(surface: Surface, viewport: DesktopViewport) {
        currentSurface = surface
        currentViewport = viewport
        attachCurrentSurface()
    }

    private fun onSurfaceResized(viewport: DesktopViewport) {
        currentViewport = viewport
        sessionService?.resizeDisplay(viewport)
    }

    private fun onSurfaceDestroyed() {
        sessionService?.detachSurface()
        currentSurface = null
        currentViewport = null
    }

    private fun attachCurrentSurface() {
        val surface = currentSurface ?: return
        val viewport = currentViewport ?: return
        sessionService?.attachSurface(surface, viewport)
    }

    private fun onPointer(x: Int, y: Int, buttons: Int) {
        sessionService?.sendPointer(x, y, buttons)
    }

    private fun onKey(keysym: Int, pressed: Boolean) {
        sessionService?.sendKey(keysym, pressed)
    }

    private fun applyWorkspaceState(state: WorkspaceState) {
        rootfsState.value = state.rootfsPath
        updateRequiredState.value = state.updateRequired
        if (state.failure == SessionFailure.WAITING_FOR_WIFI && !downloadConfirmationShown) {
            downloadConfirmationShown = workspaceViewModel.showDownloadConfirmation(downloadConfirmation)
        } else if (state.failure != SessionFailure.WAITING_FOR_WIFI) {
            downloadConfirmationShown = false
        }
        if (sessionState is SessionState.Starting || sessionState is SessionState.Running ||
            sessionState is SessionState.Stopping
        ) {
            return
        }
        sessionState = when {
            state.progress != null -> SessionState.Preparing(state.progress)
            state.failure != null -> SessionState.Failed(state.failure, recoverable = true)
            sessionState is SessionState.Preparing -> SessionState.Idle
            sessionState is SessionState.Failed &&
                (sessionState as SessionState.Failed).reason in INSTALL_FAILURES -> SessionState.Idle
            else -> sessionState
        }
    }

    private companion object {
        private val INSTALL_FAILURES = setOf(SessionFailure.WAITING_FOR_WIFI, SessionFailure.INSTALL_FAILED)
    }
}
