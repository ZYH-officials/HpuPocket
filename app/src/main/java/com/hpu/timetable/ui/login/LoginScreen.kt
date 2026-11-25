package com.hpu.timetable.ui.login

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.timetable.data.CourseInstance
import com.hpu.timetable.data.TimetableRepository
import com.hpu.timetable.data.UserStore
import com.hpu.timetable.data.auth.CasClient
import com.hpu.timetable.data.auth.CredentialStore
import com.hpu.timetable.data.auth.PersistCookieJar
import com.hpu.timetable.data.jw.JwClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 一轮验证码：page/token/图片必须一起发布，缺一不可提交 */
private data class CaptchaRound(
    val page: CasClient.LoginPage,
    val token: String,
    val bitmap: Bitmap,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginScreen(onSuccess: (List<CourseInstance>?, String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cookieJar = remember { PersistCookieJar(ctx) }
    val cas = remember { CasClient(cookieJar) }
    val authMutex = remember { Mutex() }

    var username by remember { mutableStateOf(CredentialStore.username(ctx)) }
    var password by remember { mutableStateOf(CredentialStore.load(ctx)?.second.orEmpty()) }
    var remember by remember { mutableStateOf(CredentialStore.isEnabled(ctx)) }
    var captchaInput by remember { mutableStateOf("") }
    var round by remember { mutableStateOf<CaptchaRound?>(null) }
    var directEntry by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    suspend fun loadRoundLocked() {
        refreshing = true
        captchaInput = ""
        round = null
        error = ""
        status = ""
        try {
            when (val opened = cas.openLoginPage()) {
                is CasClient.OpenLoginResult.Form -> {
                    directEntry = false
                    val c = cas.fetchCaptcha()
                    val bytes = Base64.decode(c.base64.substringAfter(','), Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("验证码图片解码失败")
                    // 三项都拿到才发布，避免旧 page 配新 token
                    round = CaptchaRound(opened.page, c.token, bmp)
                }

                CasClient.OpenLoginResult.AlreadyAuthenticated -> {
                    directEntry = true
                    status = "统一认证仍处于登录状态，可直接进入"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            round = null
            directEntry = false
            error = "验证码加载失败：" + (e.message ?: "未知错误")
        } finally {
            refreshing = false
        }
    }

    suspend fun refreshCaptcha() {
        authMutex.withLock {
            if (loading) return@withLock
            loadRoundLocked()
        }
    }

    /**
     * CAS 已经确认成功：先落账号/密码记忆/登录状态，再尝试同步课表。
     * 同步失败只影响课程数据，不再把用户留在登录页。
     */
    suspend fun completeLogin(
        account: String,
        passwordText: String,
        rememberPassword: Boolean,
        profile: Pair<String, String>? = null,
    ) {
        val saved = CredentialStore.load(ctx)
        if (rememberPassword) {
            if (passwordText.isNotBlank()) {
                CredentialStore.setEnabled(ctx, true)
                CredentialStore.save(ctx, account, passwordText)
            } else if (saved?.first != account) {
                // 本次拿不到密码，又和已保存账号不一致，不能让旧账号继续自动重登
                CredentialStore.setEnabled(ctx, false)
            }
        } else {
            CredentialStore.setEnabled(ctx, false)
        }

        val oldAccount = UserStore.account(ctx)
        val oldName = UserStore.name(ctx)
        val switched = oldAccount.isNotBlank() && oldAccount != account
        if (switched) {
            TimetableRepository.clearForAccountSwitch(ctx)
            com.hpu.timetable.data.jw.RoomCache.clear()
        }
        val initialName = if (!switched && oldName.isNotBlank()) oldName else account
        UserStore.markLoggedIn(ctx, account, initialName)

        val info = when {
            profile != null -> profile
            initialName == account -> withTimeoutOrNull(4_000) { cas.fetchUserInfo() }
            else -> null
        }
        if (info != null && info.first == account && info.second.isNotBlank()) {
            UserStore.setName(ctx, info.second)
        }

        status = "登录成功，正在同步课表…"
        val sync = JwClient(cookieJar).fetchCourses()
        onSuccess(sync.getOrNull(), sync.exceptionOrNull()?.message)
    }

    LaunchedEffect(Unit) { refreshCaptcha() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("河南理工大学", fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Text(
            "统一身份认证登录",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("学号") },
            singleLine = true,
            enabled = !loading && !refreshing,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            enabled = !loading && !refreshing,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = captchaInput,
                onValueChange = { captchaInput = it },
                label = { Text("验证码（算式结果）") },
                singleLine = true,
                enabled = !loading && !refreshing && !directEntry,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(112.dp, 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = !loading && !refreshing && !directEntry) {
                        scope.launch { refreshCaptcha() }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val r = round
                when {
                    directEntry -> Text("已登录", fontSize = 12.sp)
                    refreshing -> Text("刷新中…", fontSize = 11.sp, textAlign = TextAlign.Center)
                    r != null -> Image(
                        bitmap = r.bitmap.asImageBitmap(),
                        contentDescription = "验证码",
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> Text("点我刷新", fontSize = 11.sp, textAlign = TextAlign.Center)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !loading) { remember = !remember },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = remember,
                onCheckedChange = {
                    remember = it
                    CredentialStore.setEnabled(ctx, it)
                },
                enabled = !loading,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text("记住密码", fontSize = 13.sp)
                Text(
                    "过期后自动重登，密码加密保存",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        Button(
            onClick = {
                if (loading || refreshing) return@Button
                if (directEntry) {
                    val accountSnapshot = username.trim()
                    scope.launch {
                        authMutex.withLock {
                            if (loading) return@withLock
                            loading = true
                            error = ""
                            status = "正在确认登录状态…"
                            try {
                                val profile = withTimeoutOrNull(4_000) { cas.fetchUserInfo() }
                                val portalAccount = profile?.first?.takeIf { it.isNotBlank() }
                                val account = portalAccount ?: accountSnapshot
                                if (account.isBlank()) {
                                    error = "无法确认当前登录账号，请填写学号后重试"
                                    status = ""
                                } else {
                                    // 门户账号和输入不一致时，不用输入框里的密码覆盖该账号的凭据
                                    val passwordToSave =
                                        if (portalAccount == null || portalAccount == accountSnapshot) password
                                        else ""
                                    completeLogin(account, passwordToSave, remember, profile)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "登录失败：" + (e.message ?: "未知错误")
                            } finally {
                                loading = false
                            }
                        }
                    }
                    return@Button
                }

                val roundSnapshot = round
                if (roundSnapshot == null) {
                    error = "验证码未就绪，请刷新验证码"
                    return@Button
                }
                val accountSnapshot = username.trim()
                val passwordSnapshot = password
                val captchaSnapshot = captchaInput.trim()
                val rememberSnapshot = remember
                if (accountSnapshot.isBlank() || passwordSnapshot.isBlank() || captchaSnapshot.isBlank()) {
                    error = "请填写完整"
                    return@Button
                }

                scope.launch {
                    authMutex.withLock {
                        if (loading) return@withLock
                        loading = true
                        error = ""
                        status = "正在登录…"
                        // 提交即消费本轮表单和验证码，无论结果如何都不再复用
                        round = null
                        captchaInput = ""
                        try {
                            val ok = try {
                                cas.submit(
                                    accountSnapshot,
                                    passwordSnapshot,
                                    captchaSnapshot,
                                    roundSnapshot.page,
                                    roundSnapshot.token,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                loadRoundLocked()
                                if (round != null) {
                                    error = "登录请求失败：" + (e.message ?: "网络异常")
                                }
                                return@withLock
                            }
                            if (!ok) {
                                loadRoundLocked()
                                if (round != null) error = "账号、密码或验证码不对"
                                return@withLock
                            }
                            completeLogin(accountSnapshot, passwordSnapshot, rememberSnapshot)
                        } finally {
                            loading = false
                        }
                    }
                }
            },
            enabled = !loading && !refreshing && (directEntry || round != null),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (loading) LoadingIndicator(Modifier.size(20.dp))
            else Text(
                if (directEntry) "进 入" else "登 录",
                fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(12.dp))
        if (status.isNotBlank() && error.isBlank()) {
            Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (error.isNotBlank()) {
            Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            if (!directEntry) {
                TextButton(
                    onClick = { scope.launch { refreshCaptcha() } },
                    enabled = !loading && !refreshing,
                ) { Text("刷新验证码") }
            }
        }
    }
}