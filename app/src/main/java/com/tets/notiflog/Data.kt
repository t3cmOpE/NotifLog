package com.tets.notiflog

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notifs", indices = [Index("postTime"), Index("pkg")])
data class NotifEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val appName: String,
    val title: String?,     // nama chat / judul notifikasi
    val sender: String?,    // pengirim (untuk grup)
    val text: String?,
    val postTime: Long,
    val mediaPath: String?  // path file gambar/media yang tersimpan
)

data class AppCount(val pkg: String, val appName: String, val count: Int)

@Dao
interface NotifDao {
    @Insert suspend fun insert(n: NotifEntity)

    @Query(
        """SELECT * FROM notifs
           WHERE (:pkg IS NULL OR pkg = :pkg)
             AND (title LIKE '%' || :q || '%' OR text LIKE '%' || :q || '%' OR sender LIKE '%' || :q || '%')
           ORDER BY postTime DESC"""
    )
    fun observe(q: String, pkg: String?): Flow<List<NotifEntity>>

    @Query("SELECT pkg, MAX(appName) AS appName, COUNT(*) AS count FROM notifs GROUP BY pkg ORDER BY count DESC")
    fun apps(): Flow<List<AppCount>>

    @Query(
        """SELECT COUNT(*) FROM notifs
           WHERE pkg = :pkg AND IFNULL(title,'') = IFNULL(:title,'')
             AND IFNULL(text,'') = IFNULL(:text,'')
             AND ABS(postTime - :time) < :window"""
    )
    suspend fun dupCount(pkg: String, title: String?, text: String?, time: Long, window: Long): Int

    @Query("SELECT mediaPath FROM notifs WHERE id = :id") suspend fun mediaOf(id: Long): String?
    @Query("DELETE FROM notifs WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM notifs") suspend fun clear()
}

@Database(entities = [NotifEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): NotifDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "notiflog.db")
                .build().also { inst = it }
        }
    }
}
