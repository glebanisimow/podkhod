package app.podkhod.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import app.podkhod.domain.workout.SupersetRestMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val keepScreenOn: Boolean = true,
    val oneHandMode: Boolean = false,
    val restSound: Boolean = true,
    val restVibration: Boolean = true,
    val supersetRestAfterEach: Boolean = false,
    /** Off shows the first frame only, for battery or slow phones. */
    val animations: Boolean = true,
    /** Light warm-up sets before accessories, 0..2. */
    val accessoryWarmupSets: Int = 1,
    /** No exercise in the program gets fewer work sets than this; [MIN_SETS_RANGE]. */
    val minSets: Int = 3,
) {
    val supersetRestMode: SupersetRestMode
        get() = if (supersetRestAfterEach) SupersetRestMode.AFTER_EACH_EXERCISE else SupersetRestMode.AFTER_ROUND
}

val MIN_SETS_RANGE = 2..4

/**
 * [store] is one per process. On Android it is the file referenced by res/xml backup rules:
 * datastore/settings.preferences_pb.
 */
class SettingsRepository(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val d = AppSettings()
            AppSettings(
                keepScreenOn = prefs[KEEP_SCREEN_ON] ?: d.keepScreenOn,
                oneHandMode = prefs[ONE_HAND_MODE] ?: d.oneHandMode,
                restSound = prefs[REST_SOUND] ?: d.restSound,
                restVibration = prefs[REST_VIBRATION] ?: d.restVibration,
                supersetRestAfterEach = prefs[SUPERSET_REST_AFTER_EACH] ?: d.supersetRestAfterEach,
                animations = prefs[ANIMATIONS] ?: d.animations,
                accessoryWarmupSets = (prefs[ACCESSORY_WARMUP_SETS] ?: d.accessoryWarmupSets).coerceIn(0, MAX_ACCESSORY_WARMUP),
                minSets = (prefs[MIN_SETS] ?: d.minSets).coerceIn(MIN_SETS_RANGE),
            )
        }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setKeepScreenOn(value: Boolean) = set(KEEP_SCREEN_ON, value)
    suspend fun setOneHandMode(value: Boolean) = set(ONE_HAND_MODE, value)
    suspend fun setRestSound(value: Boolean) = set(REST_SOUND, value)
    suspend fun setRestVibration(value: Boolean) = set(REST_VIBRATION, value)
    suspend fun setSupersetRestAfterEach(value: Boolean) = set(SUPERSET_REST_AFTER_EACH, value)
    suspend fun setAnimations(value: Boolean) = set(ANIMATIONS, value)

    suspend fun setAccessoryWarmupSets(value: Int) {
        store.edit { it[ACCESSORY_WARMUP_SETS] = value.coerceIn(0, MAX_ACCESSORY_WARMUP) }
    }

    suspend fun setMinSets(value: Int) {
        store.edit { it[MIN_SETS] = value.coerceIn(MIN_SETS_RANGE) }
    }

    /** The minimum the active program was last raised to; differs from [AppSettings.minSets] until it is applied. */
    suspend fun appliedMinSets(): Int? = store.data.first()[APPLIED_MIN_SETS]

    suspend fun setAppliedMinSets(value: Int) {
        store.edit { it[APPLIED_MIN_SETS] = value }
    }

    /** Writes every setting at once, for restoring a backup. */
    suspend fun restore(
        keepScreenOn: Boolean,
        oneHandMode: Boolean,
        restSound: Boolean,
        restVibration: Boolean,
        supersetRestAfterEach: Boolean,
        animations: Boolean,
        accessoryWarmupSets: Int,
        minSets: Int,
    ) {
        store.edit {
            it[KEEP_SCREEN_ON] = keepScreenOn
            it[ONE_HAND_MODE] = oneHandMode
            it[REST_SOUND] = restSound
            it[REST_VIBRATION] = restVibration
            it[SUPERSET_REST_AFTER_EACH] = supersetRestAfterEach
            it[ANIMATIONS] = animations
            it[ACCESSORY_WARMUP_SETS] = accessoryWarmupSets.coerceIn(0, MAX_ACCESSORY_WARMUP)
            it[MIN_SETS] = minSets.coerceIn(MIN_SETS_RANGE)
            // The restored program is raised to the restored minimum on the next start.
            it.remove(APPLIED_MIN_SETS)
        }
    }

    private suspend fun set(key: Preferences.Key<Boolean>, value: Boolean) {
        store.edit { it[key] = value }
    }

    private companion object {
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val ONE_HAND_MODE = booleanPreferencesKey("one_hand_mode")
        val REST_SOUND = booleanPreferencesKey("rest_sound")
        val REST_VIBRATION = booleanPreferencesKey("rest_vibration")
        val SUPERSET_REST_AFTER_EACH = booleanPreferencesKey("superset_rest_after_each")
        val ANIMATIONS = booleanPreferencesKey("animations")
        val ACCESSORY_WARMUP_SETS = intPreferencesKey("accessory_warmup_sets")
        val MIN_SETS = intPreferencesKey("min_sets")
        val APPLIED_MIN_SETS = intPreferencesKey("applied_min_sets")
        const val MAX_ACCESSORY_WARMUP = 2
    }
}
