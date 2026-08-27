package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import me.rerere.rikkahub.data.db.entity.GenMediaEntity

@Dao
interface GenMediaDAO {
    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    fun getAll(): PagingSource<Int, GenMediaEntity>

    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    suspend fun getAllMedia(): List<GenMediaEntity>

    @Insert
    suspend fun insert(media: GenMediaEntity)

    @Query("SELECT * FROM genmediaentity WHERE path = :path LIMIT 1")
    suspend fun findByPath(path: String): GenMediaEntity?

    /** Room serializes this transaction so foreground recovery and Worker cannot duplicate a row. */
    @Transaction
    suspend fun insertIfAbsent(media: GenMediaEntity): Boolean {
        if (findByPath(media.path) != null) return false
        insert(media)
        return true
    }

    @Query("DELETE FROM genmediaentity WHERE id = :id")
    suspend fun delete(id: Int)
}
