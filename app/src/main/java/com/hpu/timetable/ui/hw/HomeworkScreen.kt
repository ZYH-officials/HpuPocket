package com.hpu.timetable.ui.hw

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.timetable.data.UserStore
import com.hpu.timetable.data.auth.PersistCookieJar
import com.hpu.timetable.data.hw.HwItem
import com.hpu.timetable.data.hw.HwPlatform
import com.hpu.timetable.data.hw.HwPlatforms
import com.hpu.timetable.data.hw.HwStore
import com.hpu.timetable.data.hw.HomeworkClient
import kotlinx.coroutines.launch

/** 一个平台的加载状态 */
private data class HwState(
    val loading: Boolean = false,
    val error: String? = null,
    val items: List<HwItem>? = null,
)

/**
 * 作业页：按平台分组展示。
 *
 * 现在只接了「数你最灵」，但结构按多平台做：
 *  - 每个平台一套独立的学号/密码（[HwStore] 按平台 id 分开加密存）；
 *  - 右上角「平台账号」可以改任意平台的账号密码；
 *  - 右上角「刷新」先问刷哪个平台还是全部，成功/失败都有 toast；
 *  - 点平台标题展开/收起它自己的作业。
 */
@Composable
fun HomeworkScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { onBack() }
    val jar = remember { PersistCookieJar(ctx) }
    val scope = rememberCoroutineScope()
    val platforms = HwPlatforms.all

    var states by remember { mutableStateOf<Map<String, HwState>>(emptyMap()) }
    var expanded by remember { mutableStateOf(platforms.map { it.id }.toSet()) }
    var showConfig by remember { mutableStateOf(false) }
    var showRefreshPick by remember { mutableStateOf(false) }

    fun update(id: String, change: (HwState) -> HwState) {
        val next = states.toMutableMap()
        next[id] = change(next[id] ?: HwState())
        states = next
    }

    fun refreshPlatform(p: HwPlatform) {
        val client = HomeworkClient(ctx, jar, p)
        if (!client.hasCredentials()) {
            update(p.id) { HwState(error = "还没有配置这个平台的账号密码") }
            return
        }
        update(p.id) { it.copy(loading = true, error = null) }
        expanded = expanded + p.id
        scope.launch {
            client.fetchHomeworks()
                .onSuccess { list ->
                    update(p.id) { HwState(items = list) }
                    toast(ctx, p.name + " 刷新成功：" + list.size + " 条")
                }
                .onFailure { e ->
                    update(p.id) { h -> h.copy(loading = false, error = e.message ?: "刷新失败") }
                    toast(ctx, p.name + " 刷新失败：" + (e.message ?: "未知错误"))
                }
        }
    }

    fun refreshAll() = platforms.forEach { refreshPlatform(it) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", fontSize = 14.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { if (platforms.size == 1) refreshPlatform(platforms.first()) else showRefreshPick = true }) { Text("刷新", fontSize = 13.sp) }
            TextButton(onClick = { showConfig = true }) { Text("平台账号", fontSize = 13.sp) }
        }

        Text(
            "作业",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        Text(
            "作业与批改结果",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(platforms) { p ->
                PlatformSection(
                    platform = p,
                    state = states[p.id] ?: HwState(),
                    configured = HwStore.credentials(ctx, p.id) != null,
                    expanded = p.id in expanded,
                    onToggle = {
                        expanded = if (p.id in expanded) expanded - p.id else expanded + p.id
                    },
                    onRetry = { refreshPlatform(p) },
                    onConfigure = { showConfig = true },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showRefreshPick) {
        AlertDialog(
            onDismissRequest = { showRefreshPick = false },
            title = { Text("刷新哪个平台") },
            text = {
                Column {
                    TextButton(onClick = {
                        showRefreshPick = false
                        refreshAll()
                    }) { Text("全部平台") }
                    platforms.forEach { p ->
                        TextButton(onClick = {
                            showRefreshPick = false
                            refreshPlatform(p)
                        }) { Text(p.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRefreshPick = false }) { Text("取消") }
            },
        )
    }

    if (showConfig) {
        ConfigDialog(
            platforms = platforms,
            ctx = ctx,
            jar = jar,
            scope = scope,
            onDismiss = { showConfig = false },
            onSaved = { p ->
                expanded = expanded + p.id
                refreshPlatform(p)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlatformSection(
    platform: HwPlatform,
    state: HwState,
    configured: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onConfigure: () -> Unit,
) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        // 平台标题行：点一下展开/收起
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(platform.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        !configured -> "未配置账号"
                        state.loading -> "刷新中…"
                        state.error != null -> "刷新失败"
                        state.items != null -> state.items.size.toString() + " 项作业"
                        else -> "尚未加载"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(if (expanded) "▴" else "▾", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (!expanded) return@Column

        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            when {
                !configured -> Notice(
                    "未配置 " + platform.name + " 的账号",
                    "平台账号",
                    onConfigure,
                )

                state.loading -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoadingIndicator(Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("正在查询…", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                state.error != null -> Notice(state.error, "重试", onRetry)

                state.items == null -> Notice("尚未加载作业", "加载作业", onRetry)

                state.items.isEmpty() -> Column {
                    Text("当前没有作业", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onRetry) { Text("刷新", fontSize = 12.sp) }
                }

                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.items.forEach { h -> HwCard(h) }
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, action: String?, onAction: () -> Unit) {
    Column {
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onAction) { Text(action, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun HwCard(h: HwItem) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                h.title.ifBlank { "（未命名作业）" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (h.score.isNotBlank()) {
                Text(h.score, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(4.dp))
        val sub = listOfNotNull(
            h.course.takeIf { it.isNotBlank() },
            h.status.takeIf { it.isNotBlank() },
            h.deadline.takeIf { it.isNotBlank() }?.let { "截止 " + it },
        ).joinToString(" · ")
        Text(
            sub.ifBlank { "—" },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfigDialog(
    platforms: List<HwPlatform>,
    ctx: android.content.Context,
    jar: PersistCookieJar,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
    onSaved: (HwPlatform) -> Unit,
) {
    val accounts = remember {
        mutableStateMapOf<String, String>().apply {
            platforms.forEach { p ->
                put(p.id, HwStore.account(ctx, p.id).ifBlank {
                    com.hpu.timetable.data.UserStore.account(ctx)
                })
            }
        }
    }
    val passwords = remember { mutableStateMapOf<String, String>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置作业平台") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "各平台密码自己设，和统一认证不一定相同；" +
                        "密码加密存在本机。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                platforms.forEach { p ->
                    Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = accounts[p.id].orEmpty(),
                        onValueChange = { accounts[p.id] = it },
                        label = { Text("学号") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = passwords[p.id].orEmpty(),
                        onValueChange = { passwords[p.id] = it },
                        label = { Text(if (HwStore.credentials(ctx, p.id) != null) "密码（留空表示不改）" else "密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        TextButton(
                            enabled = busy[p.id] != true,
                            onClick = {
                                val acc = accounts[p.id].orEmpty().trim()
                                val pwd = passwords[p.id].orEmpty()
                                val saved = HwStore.credentials(ctx, p.id)
                                val usePwd = if (pwd.isNotBlank()) pwd else saved?.second.orEmpty()
                                if (acc.isBlank() || usePwd.isBlank()) {
                                    toast(ctx, "学号和密码都要填")
                                    return@TextButton
                                }
                                busy[p.id] = true
                                scope.launch {
                                    HomeworkClient(ctx, jar, p).login(acc, usePwd)
                                        .onSuccess {
                                            passwords[p.id] = ""
                                            toast(ctx, p.name + " 已登录")
                                            onSaved(p)
                                        }
                                        .onFailure { e ->
                                            toast(ctx, p.name + " 登录失败：" + (e.message ?: "未知错误"))
                                        }
                                    busy[p.id] = false
                                }
                            },
                        ) { Text(if (busy[p.id] == true) "登录中…" else "保存并登录", fontSize = 13.sp) }
                        TextButton(onClick = {
                            HwStore.clear(ctx, p.id)
                            passwords[p.id] = ""
                            toast(ctx, p.name + " 已清除")
                        }) { Text("清除", fontSize = 13.sp) }
                    }
                }
        }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun toast(ctx: android.content.Context, msg: String) {
    runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
}
