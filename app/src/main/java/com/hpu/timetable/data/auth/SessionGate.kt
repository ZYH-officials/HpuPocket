package com.hpu.timetable.data.auth

import com.hpu.timetable.data.catching
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hpu.timetable.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 请求打到了登录页：会话已过期 */
class SessionExpiredException : IOException("登录已过期")

/** 教务在限流（页面含「请不要过快点击」）：重登没用，要等 */
class RateLimitedException : IOException("教务限流，请稍后再试")

/** 刚试过自动重登且失败了，冷却期内不再重试 */
class ReloginCooldownException(val waitSeconds: Long) : IOException("刚试过自动重登，稍后再试")

/**
 * 会话守门：把「请求 → 发现过期 → 自动重登 → 重试一次」收敛到一处。
 *
 * 判定不靠额外探活请求，而是看业务请求的响应是不是登录页——
 * 正常情况零额外开销，只有真过期才会多走登录流程。
 *
 * allowLogin 控制是否允许发起登录：
 * - 用户显式动作（点更新课表 / 刷新 / 换日期换楼）传 true
 * - 进页面自动加载传 false，只把结果告诉界面，绝不偷偷登录
 */
object SessionGate {

    /** 置 true 表示"需要用户去登录"，由界面弹提示 */
    var expired: Boolean by mutableStateOf(false)

    private fun toast(ctx: Context, msg: String, long: Boolean = false) {
        Handler(Looper.getMainLooper()).post {
            catching {
                android.widget.Toast.makeText(
                    ctx, msg,
                    if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /** 正在自动重登（界面用它显示"正在重新登录…"） */
    var relogging: Boolean by mutableStateOf(false)

    /** 自动重登失败后冷却 60 秒，避免连点刷新把学校限流打出来 */
    private const val COOLDOWN_MS = 60 * 1000L

    /** 响应是不是教务登录页 */
    fun isLoginPage(html: String): Boolean =
        html.contains("name=\"password\"") || html.contains("checkLogin(") ||
            (html.contains("loginExt") && html.contains("账号密码登录"))

    /** 不是登录页就放行，是就抛异常交给 run 处理 */
    fun ensureLoggedIn(html: String) {
        if (isLoginPage(html)) throw SessionExpiredException()
    }

    suspend fun <T> run(
        ctx: Context,
        allowLogin: Boolean,
        notifyGlobal: Boolean = true,
        block: suspend () -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        fun markExpired() {
            // notifyGlobal=false 的页面自己会显示提示，避免弹窗 + 卡片双份打扰
            if (notifyGlobal) expired = true
        }
        val first = catching { block() }
        val err = first.exceptionOrNull()
        // 限流跟会话无关，重登只会更加激怒教务，直接原样返回
        if (err is RateLimitedException) return@withContext first
        if (err !is SessionExpiredException) return@withContext first

        if (!allowLogin) {
            // 进页面自动加载不打扰：只在页面内提示，不弹全局「登录已过期」
            return@withContext first
        }

        val creds = CredentialStore.load(ctx)
        if (creds == null) {
            com.hpu.timetable.AppLog.d("HPU_SESSION", "expired but no saved credentials")
            markExpired()
            return@withContext first
        }

        val now = System.currentTimeMillis()
        val last = Settings.lastAutoLogin(ctx)
        if (last > 0 && now - last < COOLDOWN_MS) {
            val wait = (COOLDOWN_MS - (now - last)) / 1000
            com.hpu.timetable.AppLog.d("HPU_SESSION", "relogin skipped, cooldown " + wait + "s")
            markExpired()
            return@withContext Result.failure(ReloginCooldownException(wait))
        }

        com.hpu.timetable.AppLog.d("HPU_SESSION", "relogin start")
        com.hpu.timetable.AppLog.event("session", "自动重登开始")
        toast(ctx, "登录已过期，正在自动重登…")
        relogging = true
        val ok = try {
            catching { CasClient(PersistCookieJar(ctx)).autoLogin(creds.first, creds.second) }
                .getOrDefault(false)
        } finally {
            relogging = false
        }
        com.hpu.timetable.AppLog.d("HPU_SESSION", "relogin=" + ok)
        com.hpu.timetable.AppLog.event("session", "自动重登结果=" + ok)
        Settings.setLastAutoLogin(ctx, if (ok) 0L else now)
        if (!ok) {
            toast(ctx, "自动重登失败，请手动登录", long = true)
            markExpired()
            return@withContext Result.failure(SessionExpiredException())
        }
        toast(ctx, "已重新登录")

        expired = false
        kotlinx.coroutines.delay(400) // 给教务一点时间把会话落好，立刻重试容易被判过快点击
        catching { block() } // 重登成功后补一次原请求
    }
}
