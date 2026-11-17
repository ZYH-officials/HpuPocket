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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.hpu.timetable.data.CourseInstance
import com.hpu.timetable.data.UserStore
import com.hpu.timetable.data.auth.CasClient
import com.hpu.timetable.data.auth.PersistCookieJar
import com.hpu.timetable.data.jw.JwClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginScreen(onSuccess: (List<CourseInstance>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cookieJar = remember { PersistCookieJar(ctx) }
    val cas = remember { CasClient(cookieJar) }

    var username by remember { mutableStateOf(com.hpu.timetable.data.auth.CredentialStore.username(ctx)) }
    var password by remember {
        mutableStateOf(com.hpu.timetable.data.auth.CredentialStore.load(ctx)?.second.orEmpty())
    }
    var remember by remember { mutableStateOf(com.hpu.timetable.data.auth.CredentialStore.isEnabled(ctx)) }
    var captchaInput by remember { mutableStateOf("") }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var page by remember { mutableStateOf<CasClient.LoginPage?>(null) }
    var token by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    suspend fun refreshCaptcha() {
        runCatching {
            page = cas.openLoginPage()
            val c = cas.fetchCaptcha()
            token = c.token
            val bytes = Base64.decode(c.base64, Base64.DEFAULT)
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.onFailure { error = "验证码加载失败：" + (it.message ?: "") }
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
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
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
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(112.dp, 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { scope.launch { refreshCaptcha() } },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "验证码",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("点我刷新", fontSize = 11.sp, textAlign = TextAlign.Center)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { remember = !remember },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Switch(
                checked = remember,
                onCheckedChange = {
                    remember = it
                    com.hpu.timetable.data.auth.CredentialStore.setEnabled(ctx, it)
                },
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
                val p = page ?: return@Button
                if (username.isBlank() || password.isBlank() || captchaInput.isBlank()) {
                    error = "请填写完整"; return@Button
                }
                scope.launch {
                    loading = true; error = ""; status = "正在登录…"
                    try {
                        runCatching {
                            val ok = cas.submit(username, password, captchaInput, p, token)
                            if (!ok) {
                                error = "账号、密码或验证码不对"
                                captchaInput = ""
                                refreshCaptcha()
                                return@runCatching
                            }
                            status = "登录成功，正在进教务…"
                            // 顺带把实验平台的会话也打通（后面导实验课要用）
                            cas.bootstrapLabSession()
                            status = "正在读课表…"
                            val jw = JwClient(cookieJar)
                            val result = jw.fetchCourses()
                            result.onSuccess { list ->
                                status = "已获取 " + list.size + " 条课程"
                                // 记住密码：会话过期后能静默重登（密码用 Keystore 加密）
                                if (remember) {
                                    com.hpu.timetable.data.auth.CredentialStore.setEnabled(ctx, true)
                                    com.hpu.timetable.data.auth.CredentialStore.save(ctx, username, password)
                                } else {
                                    com.hpu.timetable.data.auth.CredentialStore.clear(ctx)
                                }
                                val info = cas.fetchUserInfo()
                                UserStore.save(ctx, info?.first?.ifBlank { username } ?: username, info?.second.orEmpty())
                                onSuccess(list)
                            }.onFailure { e ->
                                error = "课表获取失败：" + (e.message ?: "")
                            }
                        }.onFailure { e ->
                            error = e.message ?: "网络异常"
                        }
                    } finally {
                        // 不管成功失败都要把转圈停掉（之前验证码错误时 return 掉了这一句）
                        loading = false
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (loading) LoadingIndicator(Modifier.size(20.dp))
            else Text("登 录", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }

        Spacer(Modifier.height(12.dp))
        if (status.isNotBlank() && error.isBlank()) {
            Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (error.isNotBlank()) {
            Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { scope.launch { refreshCaptcha() } }) { Text("刷新验证码") }
        }
    }
}