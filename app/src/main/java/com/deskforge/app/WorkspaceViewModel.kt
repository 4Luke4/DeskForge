package com.deskforge.app

import android.app.Application
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import com.deskforge.app.engine.FedoraAssetInstaller
import com.deskforge.app.model.SessionFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Retains one transactional Fedora installation across activity and surface recreation. */
class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val installer = FedoraAssetInstaller(application)
    private val mutableState = MutableStateFlow(readWorkspaceState())
    val state: StateFlow<WorkspaceState> = mutableState

    fun install() {
        if (mutableState.value.progress != null) return
        mutableState.update { it.copy(progress = 0f, failure = null) }
        installer.install { event ->
            mutableState.update { current ->
                when (event) {
                    is FedoraAssetInstaller.InstallEvent.Progress ->
                        current.copy(progress = event.fraction, failure = null)
                    FedoraAssetInstaller.InstallEvent.WaitingForWifi ->
                        current.copy(progress = null, failure = SessionFailure.WAITING_FOR_WIFI)
                    is FedoraAssetInstaller.InstallEvent.Installed -> WorkspaceState(
                        rootfsPath = event.rootfsPath,
                        updateRequired = false,
                    )
                    is FedoraAssetInstaller.InstallEvent.Failed ->
                        current.copy(progress = null, failure = event.reason)
                }
            }
        }
    }

    fun showDownloadConfirmation(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean =
        installer.showDownloadConfirmation(launcher)

    override fun onCleared() {
        installer.close()
        super.onCleared()
    }

    private fun readWorkspaceState(): WorkspaceState = when (val status = installer.workspaceStatus()) {
        FedoraAssetInstaller.WorkspaceStatus.Missing -> WorkspaceState()
        FedoraAssetInstaller.WorkspaceStatus.UpdateRequired -> WorkspaceState(updateRequired = true)
        is FedoraAssetInstaller.WorkspaceStatus.Installed -> WorkspaceState(rootfsPath = status.rootfsPath)
    }
}

data class WorkspaceState(
    val rootfsPath: String? = null,
    val updateRequired: Boolean = false,
    val progress: Float? = null,
    val failure: SessionFailure? = null,
)
