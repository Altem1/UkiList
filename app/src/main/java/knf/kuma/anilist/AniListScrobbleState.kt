package knf.kuma.anilist

import androidx.annotation.Keep
import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Estado de sincronización de un episodio con AniList. */
@Keep
@Entity(tableName = "anilist_scrobble", primaryKeys = ["slug", "episode"])
data class AniListScrobbleState(
    val slug: String,
    val episode: Int,
    val aid: Int,
    val name: String,
    val status: Int,
    val reason: String? = null,
    val updated: Long = System.currentTimeMillis()
) {
    companion object {
        const val PENDING = 0
        const val SYNCED = 1
        const val FAILED = 2
    }
}

@Dao
interface AniListScrobbleDao {
    @Query("SELECT * FROM anilist_scrobble WHERE slug = :slug")
    fun bySlugLive(slug: String): LiveData<List<AniListScrobbleState>>

    @Query("SELECT * FROM anilist_scrobble WHERE status = 2 ORDER BY updated DESC")
    fun failedLive(): LiveData<List<AniListScrobbleState>>

    @Query("SELECT count(*) FROM anilist_scrobble WHERE status = 2")
    fun failedCountLive(): LiveData<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun set(state: AniListScrobbleState)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun addIgnore(states: List<AniListScrobbleState>)

    @Query("SELECT episode FROM anilist_scrobble WHERE slug = :slug")
    fun episodes(slug: String): List<Int>

    /** AniList guarda progreso: si el episodio N quedó sincronizado, también los anteriores. */
    @Query("UPDATE anilist_scrobble SET status = 1, reason = NULL WHERE slug = :slug AND episode <= :episode")
    fun markSyncedUpTo(slug: String, episode: Int)

    @Query("DELETE FROM anilist_scrobble WHERE slug = :slug AND episode = :episode")
    fun remove(slug: String, episode: Int)

    @Query("DELETE FROM anilist_scrobble WHERE aid = :aid AND episode > :episode")
    fun removeAbove(aid: Int, episode: Int)
}
