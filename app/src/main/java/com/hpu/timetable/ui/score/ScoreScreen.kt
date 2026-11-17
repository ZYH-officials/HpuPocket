package com.hpu.timetable.ui.score

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.timetable.data.ScoreItem
import com.hpu.timetable.data.ScoreResult
import com.hpu.timetable.data.SemesterInfo
import com.hpu.timetable.data.auth.PersistCookieJar
import com.hpu.timetable.data.jw.JwClient

/** 成绩页：学期可切换，数据来自教务 person!search.action */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScoreScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val client = remember { JwClient(PersistCookieJar(ctx)) }
    var semesters by remember { mutableStateOf<List<SemesterInfo>>(emptyList()) }
    var selected by remember { mutableStateOf<SemesterInfo?>(null) }
    var result by remember { mutableStateOf<ScoreResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    // 进页面自动加载：只读，不触发登录
    LaunchedEffect(Unit) {
        com.hpu.timetable.data.auth.SessionGate
            .run(ctx, allowLogin = false, notifyGlobal = false) { client.fetchSemesters().getOrThrow() }
            .onSuccess { list ->
                semesters = list
                if (selected == null) {
                    selected = list.lastOrNull { it.isCurrent } ?: list.lastOrNull()
                }
            }
            .onFailure { e ->
                result = ScoreResult.Fail(
                    if (e is com.hpu.timetable.data.auth.SessionExpiredException)
                        "登录已过期，点刷新重登"
                    else "学期列表读取失败：" + (e.message ?: "未知错误")
                )
            }
    }

    // 刷新时若还没有学期列表，先补一次（只有刷新允许自动重登）
    LaunchedEffect(tick) {
        if (tick > 0 && semesters.isEmpty()) {
            com.hpu.timetable.data.auth.SessionGate
                .run(ctx, allowLogin = true, notifyGlobal = false) { client.fetchSemesters().getOrThrow() }
                .onSuccess { list ->
                    semesters = list
                    if (selected == null) selected = list.lastOrNull { it.isCurrent } ?: list.lastOrNull()
                }
                .onFailure { e ->
                    result = ScoreResult.Fail(
                        if (e is com.hpu.timetable.data.auth.SessionExpiredException)
                            "登录已过期，点刷新重登"
                        else "学期列表读取失败：" + (e.message ?: "未知错误")
                    )
                }
        }
    }

    // 切学期 / 手动刷新取成绩（只有刷新允许自动重登）。
    // 这里不能顺带拉学期列表：那样会在同一轮里先写 selected、再继续发成绩请求，
    // 而 selected 又是本 effect 的 key，重启后旧的一轮也会把请求发完，
    // 一次点击就打两个成绩请求，教务回「请不要过快点击」（1448 字节 actionError）。
    LaunchedEffect(selected?.id, tick) {
        val s = selected ?: return@LaunchedEffect
        val allowLogin = tick > 0
        loading = true
        result = null
        com.hpu.timetable.data.auth.SessionGate
            .run(ctx, allowLogin = allowLogin, notifyGlobal = false) { client.fetchScores(s.id).getOrThrow() }
            .onSuccess { result = it }
            .onFailure { e ->
                result = ScoreResult.Fail(
                    when (e) {
                        is com.hpu.timetable.data.auth.ReloginCooldownException ->
                            "刚重登过，" + e.waitSeconds + " 秒后再试"
                        is com.hpu.timetable.data.auth.SessionExpiredException ->
                            "登录已过期，点刷新重登"
                        else -> "成绩读取失败：" + (e.message ?: "未知错误")
                    }
                )
            }
        loading = false
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", fontSize = 14.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { tick++ }, enabled = !loading) {
                Text(
                    when {
                        com.hpu.timetable.data.auth.SessionGate.relogging -> "重登中…"
                        loading -> "刷新中…"
                        else -> "刷新"
                    },
                    fontSize = 13.sp,
                )
            }
        }
        Text(
            "成绩",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        // 学期选择器
        Box(
            Modifier
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(enabled = semesters.isNotEmpty()) { picking = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "学年学期",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    selected?.label ?: if (semesters.isEmpty()) "加载中…" else "请选择",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text("▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            when (val r = result) {
                null -> {
                    if (loading || semesters.isEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LoadingIndicator(Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (com.hpu.timetable.data.auth.SessionGate.relogging)
                                    "登录已过期，正在重登…"
                                else "正在读取成绩…",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                is ScoreResult.Ok -> if (r.items.isEmpty()) {
                    EmptyCard("本学期没有成绩")
                } else {
                    r.items.forEach { s ->
                        ScoreRow(s)
                        Spacer(Modifier.height(8.dp))
                    }
                }

                is ScoreResult.EvalRequired -> NoticeCard(
                    title = "需要先完成评教",
                    body = "先在教务完成评教，" +
                        "再点刷新。",
                )

                is ScoreResult.Fail -> NoticeCard(
                    title = "成绩没取到",
                    body = r.message,
                    action = "重试",
                    onAction = { tick++ },
                )
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = {
                Text(
                    "选择学年学期",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            },
            text = {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(semesters.reversed()) { sem ->
                        val on = sem.id == selected?.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable { selected = sem; picking = false }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                sem.label,
                                fontSize = 14.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (sem.isCurrent) {
                                Text(
                                    "当前",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                            if (on) {
                                Text("✓", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun ScoreRow(s: ScoreItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.course, fontSize = 14.sp, maxLines = 1)
            if (s.credit.isNotBlank()) {
                Text(
                    "学分 " + s.credit,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            s.score,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (s.score.toDoubleOrNull()?.let { it < 60 } == true)
                MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyCard(text: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
        )
        if (action != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                action,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
