package io.github.szzgx.smartskip.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to the observation log.
 *
 * This table grows without bound during normal use — one row per assessed
 * candidate, and splash ads animate continuously — so [trimOlderThan] exists and
 * is called periodically by the accessibility layer. A debugging tool that fills
 * the user's storage is not an acceptable debugging tool.
 */
@Dao
interface AdvertisementEventDao {

    @Insert
    suspend fun insert(event: AdvertisementEventEntity): Long

    @Query("SELECT * FROM advertisement_event ORDER BY timestamp_ms DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AdvertisementEventEntity>>

    @Query(
        """
        SELECT * FROM advertisement_event
        WHERE package_name = :packageName
        ORDER BY timestamp_ms DESC
        LIMIT :limit
        """,
    )
    fun observeRecentForPackage(packageName: String, limit: Int): Flow<List<AdvertisementEventEntity>>

    @Query("SELECT * FROM advertisement_event ORDER BY timestamp_ms DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AdvertisementEventEntity>

    @Query("SELECT COUNT(*) FROM advertisement_event")
    suspend fun count(): Int

    @Query("DELETE FROM advertisement_event WHERE id IN (SELECT id FROM advertisement_event ORDER BY timestamp_ms DESC LIMIT -1 OFFSET :keep)")
    suspend fun keepNewest(keep: Int): Int

    @Query("DELETE FROM advertisement_event WHERE timestamp_ms < :cutoffMs")
    suspend fun trimOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM advertisement_event")
    suspend fun deleteAll()
}

/**
 * Read/write access to learned rules.
 */
@Dao
interface AppRuleDao {

    @Query(
        """
        SELECT * FROM app_rule
        WHERE package_name = :packageName
          AND (activity_name = :activityName OR activity_name = '')
        """,
    )
    suspend fun rulesFor(packageName: String, activityName: String): List<AppRuleEntity>

    @Query("SELECT * FROM app_rule WHERE package_name = :packageName")
    suspend fun rulesForPackage(packageName: String): List<AppRuleEntity>

    @Query("SELECT * FROM app_rule ORDER BY last_used_ms DESC")
    fun observeAll(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rule ORDER BY last_used_ms DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AppRuleEntity>>

    @Query(
        """
        SELECT * FROM app_rule
        WHERE package_name = :packageName
          AND activity_name = :activityName
          AND structure_signature = :signature
        LIMIT 1
        """,
    )
    suspend fun find(packageName: String, activityName: String, signature: String): AppRuleEntity?

    @Insert
    suspend fun insert(rule: AppRuleEntity)

    @androidx.room.Update
    suspend fun update(rule: AppRuleEntity)

    @Query("UPDATE app_rule SET is_enabled = :enabled WHERE package_name = :packageName AND activity_name = :activityName AND structure_signature = :signature")
    suspend fun setEnabled(packageName: String, activityName: String, signature: String, enabled: Boolean)

    @Query("UPDATE app_rule SET user_confirmed = 1, user_rejected = 0, confidence = :confidence WHERE package_name = :packageName AND activity_name = :activityName AND structure_signature = :signature")
    suspend fun markUserConfirmed(packageName: String, activityName: String, signature: String, confidence: Float)

    @Query("UPDATE app_rule SET user_rejected = 1, user_confirmed = 0, is_enabled = 0, confidence = 0.0 WHERE package_name = :packageName AND activity_name = :activityName AND structure_signature = :signature")
    suspend fun markUserRejected(packageName: String, activityName: String, signature: String)

    @Query("DELETE FROM app_rule WHERE package_name = :packageName AND activity_name = :activityName AND structure_signature = :signature")
    suspend fun delete(packageName: String, activityName: String, signature: String)

    @Query("DELETE FROM app_rule")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM app_rule")
    suspend fun count(): Int
}

/**
 * Read/write access to per-package safety state.
 */
@Dao
interface PackageStateDao {

    @Query("SELECT * FROM package_state WHERE package_name = :packageName LIMIT 1")
    suspend fun find(packageName: String): PackageStateEntity?

    @Query("SELECT * FROM package_state ORDER BY last_seen_ms DESC")
    fun observeAll(): Flow<List<PackageStateEntity>>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(state: PackageStateEntity)

    @Query("UPDATE package_state SET is_auto_skip_suspended = 0, suspension_reason = NULL, suspended_at_ms = NULL, consecutive_failure_count = 0 WHERE package_name = :packageName")
    suspend fun clearSuspension(packageName: String)

    @Query("UPDATE package_state SET observation_only = :observationOnly WHERE package_name = :packageName")
    suspend fun setObservationOnly(packageName: String, observationOnly: Boolean)

    @Query("DELETE FROM package_state")
    suspend fun deleteAll()
}
