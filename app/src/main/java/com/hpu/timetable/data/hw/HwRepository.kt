package com.hpu.timetable.data.hw

import android.content.Context
import com.hpu.timetable.data.auth.PersistCookieJar

/**
 * 作业汇总：首页那张「还有 N 项未完成」的卡片用它。
 *
 * - [refresh] 会把所有已配置的平台都查一遍，统计「未提交」条数，结果落在 SharedPreferences，
 *   这样下次开 App 首页能立刻显示，不用等网络；
 * - 一个平台都没配 / 没拿到任何数据时返回 null，首页就不显示这张卡。
 */
object HwRepository {

    private const val PREFS = "hpu_hw"
    private const val K_PENDING = "summary.pending"
    private const val K_AT = "summary.at"
    private const val K_OK = "summary.ok"

    data class Summary(
        val pending: Int,
        val total: Int,
        val platforms: Int,
        val failed: List<String>,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 上次成功统计出的未完成条数；没统计过返回 null */
    fun pendingCount(ctx: Context): Int? {
        val p = prefs(ctx)
        if (!p.getBoolean(K_OK, false)) return null
        return p.getInt(K_PENDING, 0)
    }

    fun savedAt(ctx: Context): Long = prefs(ctx).getLong(K_AT, 0L)

    /** 已配置账号的平台 */
    fun configuredPlatforms(ctx: Context): List<HwPlatform> =
        HwPlatforms.all.filter { HwStore.credentials(ctx, it.id) != null }

    /**
     * 刷新所有已配置平台，返回汇总结果。
     * 只要有一个平台成功就更新缓存；全部失败则返回失败原因，缓存保持不动。
     */
    suspend fun refresh(ctx: Context, jar: PersistCookieJar): Result<Summary> {
        val platforms = configuredPlatforms(ctx)
        if (platforms.isEmpty()) return Result.failure(IllegalStateException("还没有配置作业平台"))

        var pending = 0
        var total = 0
        var ok = 0
        val failed = ArrayList<String>()
        for (p in platforms) {
            val r = HomeworkClient(ctx, jar, p).fetchHomeworks()
            r.onSuccess { list ->
                ok++
                total += list.size
                pending += list.count { it.pending }
            }.onFailure { e ->
                failed += p.name + "：" + (e.message ?: "刷新失败")
            }
        }
        if (ok == 0) {
            return Result.failure(IllegalStateException(failed.joinToString("；")))
        }
        prefs(ctx).edit()
            .putBoolean(K_OK, true)
            .putInt(K_PENDING, pending)
            .putLong(K_AT, System.currentTimeMillis())
            .apply()
        return Result.success(Summary(pending, total, ok, failed))
    }
}
