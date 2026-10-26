package knf.kuma.anilist

import androidx.annotation.Keep
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Relación entre un anime de animeav1 (aid/slug) y su Media ID en AniList. */
@Keep
@Entity(tableName = "anilist_mapping", indices = [Index("anilistId")])
data class AniListMapping(
    @PrimaryKey val aid: Int,
    val slug: String,
    val anilistId: Int
)

@Dao
interface AniListMappingDao {
    @Query("SELECT * FROM anilist_mapping WHERE aid = :aid")
    fun byAid(aid: Int): AniListMapping?

    @Query("SELECT * FROM anilist_mapping WHERE anilistId = :anilistId LIMIT 1")
    fun byAniList(anilistId: Int): AniListMapping?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun add(mapping: AniListMapping)
}
