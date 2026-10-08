package app.podkhod.data.profile

import app.podkhod.platform.nowMillis
import app.podkhod.data.db.AppDatabase
import app.podkhod.domain.plan.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProfileRepository(
    db: AppDatabase,
    private val clock: () -> Long = ::nowMillis,
) {
    private val dao = db.profileDao()

    val hasProfile: Flow<Boolean> = dao.observeProfile().map { it != null }

    /** Re-emits on every save: the profile row's updated_at changes in the same transaction as its lists. */
    val profile: Flow<Profile?> = dao.observeProfile().map { if (it == null) null else load() }

    suspend fun current(): Profile? = if (dao.profile() == null) null else load()

    suspend fun save(profile: Profile) {
        val existing = dao.profile()
        val colors = dao.plates().associate { it.weightKg to it.color }
        val now = clock()
        val rows = ProfileMapper.toRows(profile, existing?.createdAt ?: now, now, colors)
        dao.save(rows.profile, rows.plates, rows.kettlebells, rows.dumbbells, rows.baselines)
    }

    private suspend fun load(): Profile? {
        val p = dao.profile() ?: return null
        return ProfileMapper.toProfile(ProfileRows(p, dao.plates(), dao.kettlebells(), dao.dumbbells(), dao.baselines()))
    }
}
