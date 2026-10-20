package app.podkhod.ui.settings

import kotlinx.coroutines.IO
import app.podkhod.ui.format.RuPlural
import app.podkhod.platform.DocumentWriter
import app.podkhod.platform.OpenedDocument
import app.podkhod.platform.PickedFolder
import app.podkhod.platform.rememberCreateDocument
import app.podkhod.platform.rememberNotificationAccess
import app.podkhod.platform.rememberOpenDocument
import app.podkhod.platform.rememberPickFolder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.podkhod.resources.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import app.podkhod.data.backup.BackupFile
import app.podkhod.data.backup.BackupFormat
import app.podkhod.data.backup.BackupPreview
import app.podkhod.data.backup.BackupRead
import app.podkhod.data.backup.BackupRepository
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.ui.format.Format
import app.podkhod.ui.format.ruPlural
import app.podkhod.data.media.ImportResult
import app.podkhod.data.media.MediaRepository
import app.podkhod.data.plan.GeneratorConfigStore
import app.podkhod.data.plan.PlanRepository
import app.podkhod.data.settings.AppSettings
import app.podkhod.data.settings.MIN_SETS_RANGE
import app.podkhod.data.settings.SettingsRepository
import app.podkhod.ui.components.ScreenHeader
import app.podkhod.ui.components.SingleChoiceChips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** A parsed backup waiting for the user's confirmation. */
data class PendingRestore(val file: BackupFile, val preview: BackupPreview, val currentSessions: Int)

/** Texts of backup results; the view model has no access to resources. */
class BackupTexts(
    val exported: (Int) -> String,
    val exportFailed: (String) -> String,
    val imported: (Int) -> String,
    val importFailed: (String) -> String,
    val activeWorkout: String,
    val undone: String,
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val media: MediaRepository,
    private val catalog: CatalogRepository,
    private val configs: GeneratorConfigStore,
    private val backups: BackupRepository,
    private val plans: PlanRepository,
) : ViewModel() {
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore.asStateFlow()

    private val _undoAt = MutableStateFlow<Long?>(null)
    val undoAt: StateFlow<Long?> = _undoAt.asStateFlow()

    init {
        launch { _undoAt.value = backups.undoSnapshotAt() }
    }

    fun exportBackup(writer: DocumentWriter, texts: BackupTexts) = launch {
        _backupMessage.value = runCatching {
            val file = backups.export()
            writer.write(BackupFormat.encode(file))
            texts.exported(file.tables["workout_session"].orEmpty().size)
        }.getOrElse { texts.exportFailed(it.message.orEmpty()) }
    }

    fun readBackup(document: OpenedDocument, texts: BackupTexts) = launch {
        val text = document.readText()
        if (text == null) {
            _backupMessage.value = texts.importFailed(document.name)
            return@launch
        }
        when (val r = backups.read(text)) {
            is BackupRead.Ok -> _pendingRestore.value = PendingRestore(r.file, r.preview, backups.currentSessions())
            is BackupRead.Invalid -> _backupMessage.value = texts.importFailed(r.problems.take(MAX_SHOWN_PROBLEMS).joinToString(" "))
        }
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }

    fun confirmRestore(texts: BackupTexts) = launch {
        val pending = _pendingRestore.value ?: return@launch
        _pendingRestore.value = null
        _backupMessage.value = runCatching { texts.imported(backups.restore(pending.file)) }.getOrElse { it.toBackupText(texts) }
        _undoAt.value = backups.undoSnapshotAt()
        _customConfig.value = configs.isCustom
    }

    fun undoRestore(texts: BackupTexts) = launch {
        _backupMessage.value = runCatching {
            backups.undoRestore()
            texts.undone
        }.getOrElse { it.toBackupText(texts) }
        _undoAt.value = backups.undoSnapshotAt()
        _customConfig.value = configs.isCustom
    }

    private fun Throwable.toBackupText(texts: BackupTexts): String =
        if (message == BackupRepository.ACTIVE_WORKOUT) texts.activeWorkout else texts.importFailed(message.orEmpty())

    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _configMessage = MutableStateFlow<String?>(null)
    val configMessage: StateFlow<String?> = _configMessage.asStateFlow()

    private val _customConfig = MutableStateFlow(configs.isCustom)
    val customConfig: StateFlow<Boolean> = _customConfig.asStateFlow()

    fun setAccessoryWarmupSets(value: Int) = launch {
        repo.setAccessoryWarmupSets(value)
        plans.refreshAdaptivePlan()
    }

    fun setMinSets(value: Int) = launch {
        repo.setMinSets(value)
        plans.applyMinSets()
    }

    fun exportConfig(writer: DocumentWriter, done: String) = launch {
        writer.write(configs.export())
        _configMessage.value = done
    }

    fun importConfig(document: OpenedDocument, ok: String, errors: (String) -> String) = launch {
        val text = document.readText()
        val problems = if (text == null) listOf(document.name) else configs.import(text)
        _configMessage.value = if (problems.isEmpty()) ok else errors(problems.take(MAX_SHOWN_PROBLEMS).joinToString("; "))
        _customConfig.value = configs.isCustom
        if (problems.isEmpty()) plans.refreshAdaptivePlan()
    }

    fun resetConfig() = launch {
        configs.reset()
        plans.refreshAdaptivePlan()
        _customConfig.value = false
        _configMessage.value = null
    }

    val userMediaCount: StateFlow<Int> = media.userFiles.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _importResult = MutableStateFlow<ImportResult?>(null)
    val importResult: StateFlow<ImportResult?> = _importResult.asStateFlow()

    fun setAnimations(value: Boolean) = launch { repo.setAnimations(value) }

    fun importMedia(folder: PickedFolder) = launch {
        _importResult.value = runCatching { media.importFolder(folder, catalog.ids()) }
            .getOrElse { ImportResult(0, listOf(folder.name)) }
    }

    fun clearMedia() = launch {
        media.clearUserMedia()
        _importResult.value = null
    }

    fun setKeepScreenOn(value: Boolean) = launch { repo.setKeepScreenOn(value) }
    fun setOneHandMode(value: Boolean) = launch { repo.setOneHandMode(value) }
    fun setRestSound(value: Boolean) = launch { repo.setRestSound(value) }
    fun setRestVibration(value: Boolean) = launch { repo.setRestVibration(value) }
    fun setSupersetRestAfterEach(value: Boolean) = launch {
        repo.setSupersetRestAfterEach(value)
        plans.refreshAdaptivePlan()
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val MAX_SHOWN_PROBLEMS = 3
    }
}

@Composable
fun SettingsScreen(
    onOpenAbout: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenPlateCalc: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    updateSection: @Composable () -> Unit = {},
) {
    val backupTexts = backupTexts()
    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()
    val pendingRestore by viewModel.pendingRestore.collectAsStateWithLifecycle()
    val undoAt by viewModel.undoAt.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val configMessage by viewModel.configMessage.collectAsStateWithLifecycle()
    val customConfig by viewModel.customConfig.collectAsStateWithLifecycle()
    val exportedText = stringResource(Res.string.settings_config_exported)
    val okText = stringResource(Res.string.settings_config_ok)
    val errorsTemplate = stringResource(Res.string.settings_config_errors, "%s")
    val exportConfig = rememberCreateDocument("application/json") { writer ->
        if (writer != null) viewModel.exportConfig(writer, exportedText)
    }
    val importConfig = rememberOpenDocument { document ->
        if (document != null) viewModel.importConfig(document, okText) { errorsTemplate.replace("%s", it) }
    }
    val notifications = rememberNotificationAccess()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("screen_settings"),
    ) {
        ScreenHeader(stringResource(Res.string.settings_title))
        SectionTitle(stringResource(Res.string.settings_section_program))
        LinkRow(
            title = stringResource(Res.string.settings_profile),
            subtitle = stringResource(Res.string.settings_profile_hint),
            modifier = Modifier.testTag("settings_profile"),
            onClick = onOpenProfile,
        )
        HorizontalDivider()
        LinkRow(
            title = stringResource(Res.string.settings_plate_calc),
            subtitle = stringResource(Res.string.settings_plate_calc_hint),
            modifier = Modifier.testTag("settings_plate_calc"),
            onClick = onOpenPlateCalc,
        )
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(stringResource(Res.string.settings_accessory_warmup, settings.accessoryWarmupSets), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(Res.string.settings_accessory_warmup_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceChips(listOf(0, 1, 2), settings.accessoryWarmupSets, { it.toString() }, viewModel::setAccessoryWarmupSets, tag = "accessory_warmup")
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(stringResource(Res.string.settings_min_sets, settings.minSets), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(Res.string.settings_min_sets_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceChips(MIN_SETS_RANGE.toList(), settings.minSets, { it.toString() }, viewModel::setMinSets, tag = "min_sets")
        }
        HorizontalDivider()
        LinkRow(
            title = stringResource(Res.string.settings_config_export),
            subtitle = stringResource(Res.string.settings_config_export_hint),
            modifier = Modifier.testTag("settings_config_export"),
        ) { exportConfig("podkhod_generator.json") }
        HorizontalDivider()
        LinkRow(
            title = stringResource(Res.string.settings_config_import),
            subtitle = buildString {
                append(stringResource(Res.string.settings_config_import_hint))
                if (customConfig) append("\n").append(stringResource(Res.string.settings_config_custom))
                configMessage?.let { append("\n").append(it) }
            },
            modifier = Modifier.testTag("settings_config_import"),
        ) { importConfig() }
        if (customConfig) {
            HorizontalDivider()
            LinkRow(title = stringResource(Res.string.settings_config_reset), onClick = viewModel::resetConfig)
        }
        HorizontalDivider()
        BackupSection(viewModel, backupTexts, backupMessage, undoAt)
        HorizontalDivider()
        SectionTitle(stringResource(Res.string.settings_section_workout))
        ToggleRow(stringResource(Res.string.settings_keep_screen_on), settings.keepScreenOn, viewModel::setKeepScreenOn)
        HorizontalDivider()
        ToggleRow(
            title = stringResource(Res.string.settings_one_hand),
            subtitle = stringResource(Res.string.settings_one_hand_hint),
            checked = settings.oneHandMode,
            onCheckedChange = viewModel::setOneHandMode,
        )
        HorizontalDivider()
        ToggleRow(stringResource(Res.string.settings_rest_sound), settings.restSound, viewModel::setRestSound)
        HorizontalDivider()
        ToggleRow(stringResource(Res.string.settings_rest_vibration), settings.restVibration, viewModel::setRestVibration)
        HorizontalDivider()
        ToggleRow(
            title = stringResource(Res.string.settings_superset_rest),
            subtitle = stringResource(Res.string.settings_superset_rest_hint),
            checked = settings.supersetRestAfterEach,
            onCheckedChange = viewModel::setSupersetRestAfterEach,
        )
        HorizontalDivider()
        SectionTitle(stringResource(Res.string.settings_section_media))
        ToggleRow(
            title = stringResource(Res.string.settings_animations),
            subtitle = stringResource(Res.string.settings_animations_hint),
            checked = settings.animations,
            onCheckedChange = viewModel::setAnimations,
        )
        HorizontalDivider()
        val userCount by viewModel.userMediaCount.collectAsStateWithLifecycle()
        val importResult by viewModel.importResult.collectAsStateWithLifecycle()
        val pickFolder = rememberPickFolder { folder ->
            if (folder != null) viewModel.importMedia(folder)
        }
        LinkRow(
            title = stringResource(Res.string.settings_media_import),
            subtitle = buildString {
                append(stringResource(Res.string.settings_media_import_hint))
                if (userCount > 0) append("\n").append(stringResource(Res.string.settings_media_count, userCount))
                importResult?.let { append("\n").append(stringResource(Res.string.settings_media_result, it.imported, it.skipped.size)) }
            },
            modifier = Modifier.testTag("settings_media_import"),
        ) { pickFolder() }
        if (userCount > 0) {
            HorizontalDivider()
            LinkRow(title = stringResource(Res.string.settings_media_clear), onClick = viewModel::clearMedia)
        }
        HorizontalDivider()
        SectionTitle(stringResource(Res.string.settings_section_other))
        updateSection()
        LinkRow(
            title = stringResource(Res.string.settings_notifications),
            subtitle = stringResource(Res.string.settings_notifications_hint),
            onClick = notifications.openSettings,
        )
        HorizontalDivider()
        LinkRow(title = stringResource(Res.string.settings_about), modifier = Modifier.testTag("settings_about"), onClick = onOpenAbout)
    }
    pendingRestore?.let { p ->
        AlertDialog(
            modifier = Modifier.testTag("restore_dialog"),
            onDismissRequest = viewModel::cancelRestore,
            title = { Text(stringResource(Res.string.backup_import_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.backup_import_body,
                        Format.moment(p.preview.createdAt),
                        p.preview.appVersion,
                        ruPlural(RuPlural.SESSIONS, p.preview.sessions),
                        ruPlural(RuPlural.SETS, p.preview.sets),
                        stringResource(if (p.preview.hasProfile) Res.string.backup_import_profile else Res.string.backup_import_no_profile),
                        ruPlural(RuPlural.SESSIONS, p.currentSessions),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRestore(backupTexts) }, modifier = Modifier.testTag("restore_confirm")) {
                    Text(stringResource(Res.string.backup_import_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelRestore) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

@Composable
private fun backupTexts(): BackupTexts {
    val exported = stringResource(Res.string.backup_exported, PLACEHOLDER_N)
    val exportFailed = stringResource(Res.string.backup_export_failed, PLACEHOLDER_S)
    val imported = stringResource(Res.string.backup_imported, PLACEHOLDER_N)
    val importFailed = stringResource(Res.string.backup_import_failed, PLACEHOLDER_S)
    val active = stringResource(Res.string.backup_active_workout)
    val undone = stringResource(Res.string.backup_undone)
    return remember(exported, exportFailed, imported, importFailed, active, undone) {
        BackupTexts(
            exported = { n -> exported.replace(PLACEHOLDER_N.toString(), n.toString()) },
            exportFailed = { m -> exportFailed.replace(PLACEHOLDER_S, m) },
            imported = { n -> imported.replace(PLACEHOLDER_N.toString(), n.toString()) },
            importFailed = { m -> importFailed.replace(PLACEHOLDER_S, m) },
            activeWorkout = active,
            undone = undone,
        )
    }
}

private const val PLACEHOLDER_N = 987654321
private const val PLACEHOLDER_S = "\u0000"

@Composable
private fun BackupSection(viewModel: SettingsViewModel, texts: BackupTexts, message: String?, undoAt: Long?) {
    val exportBackup = rememberCreateDocument("application/json") { writer ->
        if (writer != null) viewModel.exportBackup(writer, texts)
    }
    val importBackup = rememberOpenDocument { document ->
        if (document != null) viewModel.readBackup(document, texts)
    }
    SectionTitle(stringResource(Res.string.settings_section_data))
    LinkRow(
        title = stringResource(Res.string.settings_backup_export),
        subtitle = stringResource(Res.string.settings_backup_export_hint),
        modifier = Modifier.testTag("settings_backup_export"),
    ) { exportBackup("podkhod-backup-${Format.isoToday()}.json") }
    HorizontalDivider()
    LinkRow(
        title = stringResource(Res.string.settings_backup_import),
        subtitle = buildString {
            append(stringResource(Res.string.settings_backup_import_hint))
            message?.let { append("\n").append(it) }
        },
        modifier = Modifier.testTag("settings_backup_import"),
    ) { importBackup() }
    if (undoAt != null) {
        HorizontalDivider()
        LinkRow(
            title = stringResource(Res.string.settings_backup_undo),
            subtitle = stringResource(Res.string.settings_backup_undo_hint, Format.moment(undoAt)),
            modifier = Modifier.testTag("settings_backup_undo"),
        ) { viewModel.undoRestore(texts) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun LinkRow(title: String, subtitle: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .then(modifier),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
