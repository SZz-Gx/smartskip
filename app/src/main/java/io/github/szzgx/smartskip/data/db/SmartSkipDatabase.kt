package io.github.szzgx.smartskip.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The on-device database.
 *
 * There is exactly one of these, it is never exported, and — because the app holds
 * no network permission — nothing in it can leave the device through SmartSkip
 * itself.
 */
@Database(
    entities = [
        AdvertisementEventEntity::class,
        AppRuleEntity::class,
        PackageStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SmartSkipDatabase : RoomDatabase() {

    abstract fun advertisementEventDao(): AdvertisementEventDao

    abstract fun appRuleDao(): AppRuleDao

    abstract fun packageStateDao(): PackageStateDao

    companion object {
        private const val NAME = "smartskip.db"

        @Volatile
        private var instance: SmartSkipDatabase? = null

        fun get(context: Context): SmartSkipDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): SmartSkipDatabase =
            Room.databaseBuilder(context, SmartSkipDatabase::class.java, NAME)
                // No destructive migration is configured on purpose. Losing the
                // learned rules silently would be a worse outcome than a failed
                // upgrade, because the user would experience it as SmartSkip
                // suddenly tapping things it had previously learned to avoid.
                .build()
    }
}
