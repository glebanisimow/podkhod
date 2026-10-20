package app.podkhod.update

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import app.podkhod.BuildConfig
import app.podkhod.R
import kotlinx.coroutines.CancellationException
import org.koin.androidx.compose.koinViewModel

@Composable
fun AppUpdateSection(viewModel: UpdateViewModel = koinViewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) viewModel.install()
        else viewModel.report(UpdateProblem.INSTALL_PERMISSION)
    }
    // A pending download may finish in the background; open system UI only when this screen is resumed.
    LaunchedEffect(state.installFile) {
        val file = state.installFile ?: return@LaunchedEffect
        try {
            lifecycle.withResumed { }
            if (!viewModel.canLaunchInstall()) return@LaunchedEffect
            lifecycle.withResumed {
                viewModel.consumeInstallRequest()
                if (!context.packageManager.canRequestPackageInstalls()) {
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newRawUri("Podkhod update", uri)
                    })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            viewModel.report(UpdateProblem.INSTALL_FAILED)
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).testTag("settings_update")) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.update_installed, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            when {
                BuildConfig.DEBUG -> stringResource(R.string.update_debug)
                state.phase == UpdatePhase.CURRENT -> stringResource(R.string.update_current)
                state.phase == UpdatePhase.CHECKING -> stringResource(R.string.update_checking)
                state.phase == UpdatePhase.DOWNLOADING -> stringResource(R.string.update_downloading, state.progress)
                state.phase == UpdatePhase.READY -> stringResource(R.string.update_ready)
                state.phase == UpdatePhase.AVAILABLE -> stringResource(
                    R.string.update_available, state.manifest!!.versionName, state.manifest!!.versionCode,
                    (state.manifest!!.sizeBytes / 1024 / 1024).coerceAtLeast(1),
                )
                else -> stringResource(R.string.update_hint)
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.problem?.let {
            Text(stringResource(it.messageResource()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (state.busy) {
            if (state.phase == UpdatePhase.DOWNLOADING) {
                LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        Button(
            onClick = {
                when (state.phase) {
                    UpdatePhase.AVAILABLE -> viewModel.download()
                    UpdatePhase.READY -> viewModel.install()
                    else -> viewModel.check()
                }
            },
            enabled = !BuildConfig.DEBUG && !state.busy,
            modifier = Modifier.testTag("update_action"),
        ) {
            Text(stringResource(when (state.phase) {
                UpdatePhase.AVAILABLE -> R.string.update_download
                UpdatePhase.READY -> R.string.update_install
                else -> R.string.update_check
            }))
        }
        if (state.phase == UpdatePhase.AVAILABLE || state.phase == UpdatePhase.READY) {
            TextButton(onClick = viewModel::check) { Text(stringResource(R.string.update_check_again)) }
        }
    }
    HorizontalDivider()
}

private fun UpdateProblem.messageResource(): Int = when (this) {
    UpdateProblem.NETWORK -> R.string.update_error_network
    UpdateProblem.NOT_PUBLISHED -> R.string.update_error_not_published
    UpdateProblem.RATE_LIMIT -> R.string.update_error_rate_limit
    UpdateProblem.INVALID_APK -> R.string.update_error_invalid
    UpdateProblem.INCOMPATIBLE -> R.string.update_error_incompatible
    UpdateProblem.STORAGE -> R.string.update_error_storage
    UpdateProblem.ACTIVE_WORKOUT -> R.string.update_error_workout
    UpdateProblem.INSTALL_PERMISSION -> R.string.update_error_permission
    UpdateProblem.INSTALL_FAILED -> R.string.update_error_install
}
