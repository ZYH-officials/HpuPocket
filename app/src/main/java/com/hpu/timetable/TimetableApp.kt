package com.hpu.timetable

class TimetableApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        DebugFlags.initialize(this)
        FileLog.init(this)
        // 崩溃先落盘再交给系统默认处理，导出的日志里能看到最后一次崩溃
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                FileLog.event("crash", thread.name + ": " + throwable.stackTraceToString().take(4000))
            }
            default?.uncaughtException(thread, throwable)
        }
        com.hpu.timetable.data.TimetableRepository.init(this)
    }
}

object DebugFlags {
    @Volatile var verbose: Boolean = false
        private set

    fun initialize(context: android.content.Context) {
        verbose = context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}

/**
 * 滚动文件日志：只记脱敏事件（登录结果、闹钟、崩溃），不记密码和原始响应。
 * 单文件上限 512KB，超了滚成 .1 备份，最多保留两份，导出时拼在一起。
 */
object FileLog {
    private const val MAX_BYTES = 512 * 1024L
    private lateinit var dir: java.io.File
    private val lock = Any()

    fun init(ctx: android.content.Context) {
        dir = java.io.File(ctx.filesDir, "logs").apply { mkdirs() }
    }

    private fun current() = java.io.File(dir, "app.log")
    private fun backup() = java.io.File(dir, "app.log.1")

    fun event(tag: String, message: String) {
        if (!::dir.isInitialized) return
        val line = java.time.LocalDateTime.now().toString().take(19) +
            " [" + tag + "] " + message + "\n"
        synchronized(lock) {
            runCatching {
                val f = current()
                if (f.length() + line.length > MAX_BYTES) {
                    backup().delete()
                    f.renameTo(backup())
                }
                current().appendText(line)
            }
        }
    }

    /** 旧备份在前、当前在后，供「导出日志」一次写全 */
    fun snapshot(): String = synchronized(lock) {
        runCatching {
            val sb = StringBuilder()
            if (backup().exists()) sb.append(backup().readText())
            if (current().exists()) sb.append(current().readText())
            if (sb.isEmpty()) "（暂无日志）" else sb.toString()
        }.getOrDefault("（日志读取失败）")
    }
}

/** 原始响应和个人信息只允许出现在可调试包中；event 是脱敏事件，正式包也落盘。 */
object AppLog {
    fun d(tag: String, message: String): Int =
        if (DebugFlags.verbose) {
            FileLog.event(tag, message)
            android.util.Log.d(tag, message)
        } else 0

    fun e(tag: String, message: String): Int =
        if (DebugFlags.verbose) {
            FileLog.event(tag, message)
            android.util.Log.e(tag, message)
        } else 0

    /** 脱敏事件：任何包都写文件，调用方保证不含密码/cookie/原始响应 */
    fun event(tag: String, message: String) {
        FileLog.event(tag, message)
    }
}