package com.example.memostream.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("select * from folders where deletedAt is null order by pinned desc, ord asc, name asc")
    fun activeFlow(): Flow<List<Folder>>

    @Query("select * from folders where deletedAt is null order by pinned desc, ord asc, name asc")
    suspend fun active(): List<Folder>

    @Query("select * from folders")
    suspend fun all(): List<Folder>

    @Query("select * from folders where id = :id")
    suspend fun byId(id: Long): Folder?

    @Query("select * from folders where uid = :uid")
    suspend fun byUid(uid: String): Folder?

    @Query("select coalesce(max(ord), -1) from folders where deletedAt is null")
    suspend fun maxOrder(): Int

    @Insert
    suspend fun insert(folder: Folder): Long

    @Update
    suspend fun update(folder: Folder)

    @Query("update folders set name = :name, updatedAt = :now where id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("update folders set pinned = :pinned, updatedAt = :now where id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, now: Long)

    @Query("update folders set deletedAt = :now, updatedAt = :now where id = :id")
    suspend fun softDelete(id: Long, now: Long)
}

@Dao
interface NoteDao {
    @Query("select * from notes where deletedAt is null order by createdAt asc")
    fun activeFlow(): Flow<List<Note>>

    @Query("select * from notes where deletedAt is not null order by deletedAt desc")
    fun trashFlow(): Flow<List<Note>>

    @Query("select * from notes where deletedAt is not null order by deletedAt desc")
    suspend fun trash(): List<Note>

    @Query("select * from notes")
    suspend fun all(): List<Note>

    @Query("select * from notes where deletedAt is null")
    suspend fun active(): List<Note>

    @Query("select * from notes where id = :id")
    suspend fun byId(id: Long): Note?

    @Query("select * from notes where uid = :uid")
    suspend fun byUid(uid: String): Note?

    @Insert
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Query("delete from notes where id = :id")
    suspend fun deleteRow(id: Long)

    @Query("update notes set content = :content, blobIds = :blobIds, editedAt = :now, updatedAt = :now where id = :id")
    suspend fun edit(id: Long, content: String, blobIds: List<Long>, now: Long)

    @Query("update notes set pinned = :pinned, updatedAt = :now where id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, now: Long)

    @Query("update notes set folderId = :folderId, updatedAt = :now where id = :id")
    suspend fun move(id: Long, folderId: Long, now: Long)

    @Query("update notes set deletedAt = :now, updatedAt = :now where id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("update notes set deletedAt = null, updatedAt = :now where id = :id")
    suspend fun restore(id: Long, now: Long)

    @Query("select * from notes where deletedAt is not null and deletedAt < :cutoff")
    suspend fun staleTrash(cutoff: Long): List<Note>
}

@Dao
interface BlobDao {
    @Query("select * from blobs")
    fun allFlow(): Flow<List<BlobRecord>>

    @Query("select * from blobs")
    suspend fun all(): List<BlobRecord>

    @Query("select * from blobs where id = :id")
    suspend fun byId(id: Long): BlobRecord?

    @Query("select * from blobs where sha256 = :sha256")
    suspend fun bySha(sha256: String): BlobRecord?

    @Insert
    suspend fun insert(record: BlobRecord): Long

    @Query("update blobs set refCount = :count where id = :id")
    suspend fun setRefCount(id: Long, count: Int)

    @Query("update blobs set thumbSize = :size where id = :id")
    suspend fun setThumbSize(id: Long, size: Long)

    @Query("update blobs set width = :width, height = :height where id = :id")
    suspend fun setDimensions(id: Long, width: Int, height: Int)

    @Query("delete from blobs where id = :id")
    suspend fun delete(id: Long)
}
