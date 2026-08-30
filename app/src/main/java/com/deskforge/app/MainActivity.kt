package com.deskforge.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.deskforge.app.engine.FedoraAssetInstaller
import com.deskforge.app.engine.NativeDeskForgeEngine
import com.deskforge.app.model.RuntimeCapabilities
import com.deskforge.app.model.SessionState
import com.deskforge.app.ui.DeskForgeApp
import com.deskforge.app.ui.theme.DeskForgeTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var engine: NativeDeskForgeEngine
    private lateinit var installer: FedoraAssetInstaller
    private var sessionState: SessionState by mutableStateOf(SessionState.Idle)
    private var capabilities: RuntimeCapabilities? by mutableStateOf(null)
    private var rootfsPath: String? by mutableStateOf(null)
    private var microphoneEnabled: Boolean by mutableStateOf(false)

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> microphoneEnabled = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        engine = NativeDeskForgeEngine(this)
        installer = FedoraAssetInstaller(this)
        rootfsPath = existingRootfsPath()
        engine.activeSessionState()?.let { activeState -> sessionState = activeState }

        setContent {
            DeskForgeTheme {
                DeskForgeApp(
                    sessionState = sessionState,
                    capabilities = capabilities,
                    isInstalled = rootfsPath != null,
                    microphoneEnabled = microphoneEnabled,
                    onInstall = ::installFedora,
                    onCapabilityCheck = ::inspectCapabilities,
                    onStart = ::startSession,
                    onStop = ::stopSession,
                    onMicrophoneChanged = ::updateMicrophoneEnabled,
                )
            }
        }
    }

    override fun onDestroy() {
        installer.close()
        super.onDestroy()
    }

    private fun installFedora() {
        sessionState = SessionState.Preparing(0f)
        installer.install { event ->
            runOnUiThread {
                sessionState = when (event) {
                    is FedoraAssetInstaller.InstallEvent.Progress -> SessionState.Preparing(event.fraction)
                    FedoraAssetInstaller.InstallEvent.WaitingForWifi ->
                        SessionState.Failed("Waiting for Wi-Fi approval in Google Play", recoverable = true)
                    is FedoraAssetInstaller.InstallEvent.Installed -> {
                        rootfsPath = event.rootfsPath
                        SessionState.Idle
                    }
                    is FedoraAssetInstaller.InstallEvent.Failed ->
                        SessionState.Failed(event.message, recoverable = true)
                }
            }
        }
    }

    private fun startSession() {
        val path = rootfsPath
        if (path == null) {
            sessionState = SessionState.Failed("Install Fedora before starting a session", recoverable = true)
            return
        }
        sessionState = SessionState.Starting
        lifecycleScope.launch {
            sessionState = withContext(Dispatchers.IO) {
                engine.startSession(path, microphoneEnabled)
            }
        }
    }

    private fun stopSession() {
        sessionState = SessionState.Stopping
        lifecycleScope.launch {
            sessionState = withContext(Dispatchers.IO) { engine.stopSession() }
        }
    }

    private fun inspectCapabilities() {
        lifecycleScope.launch {
            capabilities = withContext(Dispatchers.IO) { engine.inspectCapabilities() }
        }
    }

    private fun updateMicrophoneEnabled(enabled: Boolean) {
        if (!enabled) {
            microphoneEnabled = false
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            microphoneEnabled = true
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun existingRootfsPath(): String? {
        val rootfs = File(filesDir, "distros/fedora-xfce-44/rootfs")
        return rootfs.takeIf {
            // Both the launch command and atomic provenance marker are required for activation.
            File(it, "usr/bin/startxfce4").isFile && File(it, ".deskforge-source-sha256").isFile
        }?.absolutePath
    }
}
