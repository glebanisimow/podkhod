package app.podkhod.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

@Entity(tableName = "app_meta")
data class AppMetaEntity(
    @PrimaryKey @ColumnInfo(name = "meta_key") val key: String,
    @ColumnInfo(name = "meta_value") val value: String,
)

@Dao
interface AppMetaDao {
    @Query("SELECT meta_value FROM app_meta WHERE meta_key = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entity: AppMetaEntity)
}

class AppMetaRepository(private val dao: AppMetaDao) {

    suspend fun recordFirstLaunchIfMissing(nowMillis: Long = app.podkhod.platform.nowMillis()) {
        if (dao.get(KEY_FIRST_LAUNCH_AT) == null) {
            dao.put(AppMetaEntity(KEY_FIRST_LAUNCH_AT, nowMillis.toString()))
        }
    }

    suspend fun firstLaunchAt(): Long? = dao.get(KEY_FIRST_LAUNCH_AT)?.toLongOrNull()

    companion object {
        const val KEY_FIRST_LAUNCH_AT = "first_launch_at"
    }
}
