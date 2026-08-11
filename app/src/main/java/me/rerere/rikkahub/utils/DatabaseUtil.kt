package me.rerere.rikkahub.utils

import android.database.CursorWindow
import android.util.Log
import androidx.room.RoomDatabase

private const val TAG = "DatabaseUtil"

object DatabaseUtil {
    /**
     * 把 WAL 中的改动写回数据库主文件。
     *
     * 自动备份只包含数据库主文件(WAL/SHM 跨设备还原会与主文件错位, 已在备份规则里排除),
     * 而 Room 开启了 WRITE_AHEAD_LOGGING 且不会主动 checkpoint, 未回写的最新消息就不会
     * 进入备份。应用退到后台时调一次, 保证备份里的聊天记录是完整的。
     */
    fun checkpoint(database: RoomDatabase) {
        try {
            database.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "checkpoint failed", e)
        }
    }

    fun setCursorWindowSize(size: Int) {
        try {
            val field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            val oldValue = field.get(null) as Int
            field.set(null, size)
            Log.i(TAG, "setCursorWindowSize: set $oldValue to $size")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 已fork io.requery.android.database 修改了window size，避免无法反射修改final字段
//        try {
//            val field =
//                io.requery.android.database.CursorWindow::class.java.getDeclaredField("sDefaultCursorWindowSize")
//            field.isAccessible = true
//            val oldValue = field.get(null) as Int
//            field.set(null, size)
//            Log.i(TAG, "setCursorWindowSize: set $oldValue to $size")
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
    }
}
