package com.cloudnest.player.lib

import android.app.Application
import android.content.Context
import android.util.Log
import com.cloudnest.player.App
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把未捕获异常写进 filesDir，下次启动弹出来给用户看/截图。
 * 目的：像"播 TS 闪退"这种只在真机上出现的问题，没有 logcat 也能定位。
 */
object CrashLog {

    private const val NAME = "last_crash.txt"
    private const val TAG = "CrashLog"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val body = buildString {
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    append("\nthread: ").append(thread.name)
                    append("\n").append(Log.getStackTraceString(error))
                }
                File(app.filesDir, NAME).writeText(body)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun peek(context: Context): String? = runCatching {
        val f = File(context.filesDir, NAME)
        if (!f.exists()) return null
        // 只保留前 4000 字，够看出异常类型和栈顶
        f.readText().take(4000).ifBlank { null }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, NAME).delete() }
        Log.i(TAG, "crash log cleared")
    }
}
