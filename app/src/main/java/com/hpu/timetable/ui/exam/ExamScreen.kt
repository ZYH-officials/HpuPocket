package com.hpu.timetable.ui.exam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.timetable.data.ExamItem
import com.hpu.timetable.data.auth.PersistCookieJar
import com.hpu.timetable.data.jw.JwClient
import kotlinx.coroutines.launch

/** 考试安排页（从首页快捷入口进入） */
@Composable
fun ExamScreen(
    exams: List<ExamItem>,
    onBack: () -> Unit,
    onUpdate: (List<ExamItem>) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun refresh(allowLogin: Boolean) {
        if (refreshing) return
        refreshing = true
        notice = null
        try {
            com.hpu.timetable.data.auth.SessionGate.run(ctx, allowLogin, notifyGlobal = false) {
                JwClient(PersistCookieJar(ctx)).fetchExams().getOrThrow()
            }.onSuccess { list ->
                if (list.isNotEmpty()) onUpdate(list)
                else notice = if (exams.isEmpty()) "本次未获取到考试安排" else "本次未获取到考试安排，仍显示上次数据"
            }.onFailure { e ->
                notice = when (e) {
                    is com.hpu.timetable.data.auth.SessionExpiredException -> "登录验证未通过，请点刷新重试"
                    is com.hpu.timetable.data.auth.RateLimitedException -> "教务请求较频繁，请稍后重试"
                    else -> "考试安排加载失败，请稍后重试"
                }
            }
        } finally { refreshing = false }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh(false) }

    // 子页面没有导航栈，不接管返回事件的话侧滑/返回键会直接退出 App
    androidx.activity.compose.BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", fontSize = 14.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    scope.launch { refresh(true) }
                },
                enabled = !refreshing,
            ) {
                Text(
                    when {
                        com.hpu.timetable.data.auth.SessionGate.relogging -> "重登中…"
                        refreshing -> "刷新中…"
                        else -> "刷新"
                    },
                    fontSize = 13.sp,
                )
            }
        }
        Text(
            "考试安排",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            notice?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp))
            }
            if (exams.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (refreshing) "正在加载考试安排…" else "尚无可显示的考试安排", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Spacer(Modifier.height(10.dp))
                exams.forEach { e ->
                    val days = runCatching {
                        java.time.temporal.ChronoUnit.DAYS.between(
                            java.time.LocalDate.now(), java.time.LocalDate.parse(e.date),
                        )
                    }.getOrNull()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                e.course,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            if (days != null && days in 0..10) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (days <= 3) MaterialTheme.colorScheme.errorContainer
                                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                        )
                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        if (days == 0L) "今天" else "还有 " + days + " 天",
                                        fontSize = 10.sp,
                                        color = if (days <= 3) MaterialTheme.colorScheme.onErrorContainer
                                        else MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            e.type,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (e.isScheduled) e.date + " " + e.timeRange + " · " + e.place
                            else "时间地点待安排",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

private fun toast(ctx: android.content.Context, msg: String) {
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
}