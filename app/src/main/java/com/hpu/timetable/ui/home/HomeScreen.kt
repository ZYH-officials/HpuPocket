package com.hpu.timetable.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.timetable.data.CourseInstance
import java.time.LocalDate
import java.time.LocalTime

private val DAY_CN = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val BIG_TIME = listOf("08:00", "10:00", "14:30", "16:30", "19:00")
private val BIG_END = listOf("09:40", "11:40", "16:10", "18:10", "20:40")

@Composable
fun HomeScreen(
    name: String,
    courses: List<CourseInstance>,
    currentWeek: Int,
    onOpenSchedule: () -> Unit,
    onOpenProfile: () -> Unit,
    quote: Quote? = null,
    onRefreshQuote: () -> Unit = {},
    exams: List<com.hpu.timetable.data.ExamItem> = emptyList(),
    onReloadExams: () -> Unit = {},
    onPasteExams: (String) -> Unit = {},
    onOpenExams: () -> Unit = {},
    onOpenScores: () -> Unit = {},
    onOpenRooms: () -> Unit = {},
    onOpenHomework: () -> Unit = {},
    /** 是否已经配置过作业平台 */
    hwConfigured: Boolean = false,
    /** 未完成作业数；null 表示还没统计过 */
    hwPending: Int? = null,
    hwRefreshing: Boolean = false,
    onRefreshHomework: () -> Unit = {},
) {
    var minuteTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            minuteTick++
        }
    }
    val today = remember(minuteTick) { LocalDate.now() }
    val now = remember(minuteTick) { LocalTime.now() }
    val todayDay = today.dayOfWeek.value
    val todayCourses = courses.filter { it.week == currentWeek && it.day == todayDay }.sortedBy { it.big }
    val currentCourse = todayCourses.firstOrNull { courseStart(it) <= now && courseEnd(it) > now }
    val nextCourse = todayCourses.firstOrNull { courseStart(it) > now }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(18.dp))

        Spacer(Modifier.height(20.dp))
        Text(
            greeting() + "，" + name.ifBlank { "同学" },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "第 " + currentWeek + " 周 · " + DAY_CN[todayDay - 1] + " · " + today.monthValue + " 月 " + today.dayOfMonth + " 日",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 距考试 ≤10 天时显示倒计时
        val upcoming = exams.filter { it.isScheduled }.mapNotNull { e ->
            val d = runCatching {
                java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), java.time.LocalDate.parse(e.date))
            }.getOrNull()
            if (d != null && d in 0..10) e to d else null
        }.minByOrNull { it.second }
        if (upcoming != null) {
            Spacer(Modifier.height(14.dp))
            CountdownBanner(upcoming.first, upcoming.second)
        }

        // 作业未完成提示：没配置平台不显示；统计结果是 0 也不显示
        if (hwConfigured && (hwPending == null || hwPending > 0)) {
            Spacer(Modifier.height(12.dp))
            HwPendingCard(hwPending, hwRefreshing, onRefreshHomework, onOpenHomework)
        }

        Spacer(Modifier.height(22.dp))
        // 今日课程：按当前时间标出正在上课和下一节，分钟变化时自动刷新。
        Text("今天", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        if (todayCourses.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("今天没课", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                    todayCourses.forEachIndexed { i, c ->
                        val status = when {
                            c == currentCourse -> "进行中"
                            c == nextCourse -> "下一节"
                            courseEnd(c) <= now -> "已结束"
                            else -> ""
                        }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(
                                if (c == currentCourse) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                else Color.Transparent,
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.width(3.dp).height(30.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(courseColor(c.course)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.course,
                                fontSize = 14.sp,
                                fontWeight = if (c == currentCourse) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                color = if (c == currentCourse) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                listOfNotNull(
                                    c.room.ifBlank { null },
                                    c.teacher.ifBlank { null },
                                ).joinToString(" · "),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (status.isNotBlank()) {
                                Text(
                                    status,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (c == currentCourse) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                BIG_TIME[(c.big - 1).coerceIn(0, 4)],
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (i != todayCourses.lastIndex) {
                        Box(
                            Modifier.fillMaxWidth().padding(start = 31.dp).height(0.6.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("快捷入口", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        // MD3E 按钮组：按下时该项弹性扩张、邻项压缩；放不下时自动收进溢出菜单
        ButtonGroup(
            overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            clickableItem(onClick = onOpenExams, label = "考试", weight = 1f)
            clickableItem(onClick = onOpenScores, label = "成绩", weight = 1f)
        }
        Spacer(Modifier.height(10.dp))
        ButtonGroup(
            overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            clickableItem(onClick = onOpenRooms, label = "空闲教室", weight = 1f)
            clickableItem(onClick = onOpenHomework, label = "作业", weight = 1f)
        }

        Spacer(Modifier.height(22.dp))
        Text("一言", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        QuoteCard(quote, onRefreshQuote)

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun ExamCard(
    exams: List<com.hpu.timetable.data.ExamItem>,
    onReload: () -> Unit,
    onPaste: (String) -> Unit,
) {
    var pasting by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val scheduled = exams.filter { it.isScheduled }.sortedBy { it.date }
    val unscheduled = exams.filter { !it.isScheduled }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (exams.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "暂无考试安排",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "自动",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onReload)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    "粘贴",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { input = ""; pasting = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            return@Column
        }
        scheduled.take(3).forEachIndexed { i, e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.course, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        e.type + " · " + (e.place.ifBlank { "地点待定" }),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        e.date.substringAfterLast("-").trim() + " 日",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(e.timeRange, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val daysLeft = runCatching {
                        java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), java.time.LocalDate.parse(e.date))
                    }.getOrNull()
                    if (daysLeft != null && daysLeft in 0..10) {
                        Spacer(Modifier.height(3.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (daysLeft <= 3) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                if (daysLeft == 0L) "今天考试" else "还有 " + daysLeft + " 天",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (daysLeft <= 3) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            if (i != scheduled.take(3).lastIndex || unscheduled.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().height(0.5.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        if (unscheduled.isNotEmpty()) {
            Text(
                "另有 " + unscheduled.size + " 门待安排：" + unscheduled.joinToString("、") { it.course },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (pasting) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text("粘贴考试页面 HTML") },
            text = {
                Column {
                    Text(
                        "浏览器打开「我的考试」→ 右键查看网页源代码 → 全选复制 → 粘到这里",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6,
                        label = { Text("HTML") },
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (input.isNotBlank()) onPaste(input)
                    pasting = false
                }) { Text("解析") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pasting = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CountdownBanner(e: com.hpu.timetable.data.ExamItem, days: Long) {
    val urgent = days <= 3
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (urgent) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f)
                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (days == 0L) "今天" else days.toString(),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = if (urgent) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (days == 0L) "考试" else "天后",
            fontSize = 11.sp,
            color = if (urgent) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.course,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = if (urgent) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                e.type + " · " + e.date.substringAfterLast("-") + " 日 " + e.timeRange,
                fontSize = 11.sp,
                color = (if (urgent) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer).copy(alpha = 0.75f),
            )
        }
    }
}


/** 首页「还有 N 项作业未完成」卡片：点整张卡进作业页，右侧刷新会查全部已配置平台 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HwPendingCard(
    pending: Int?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(7.dp).clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(6.dp))
                Text("作业未完成", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (pending == null) "点刷新查看" else "还有 " + pending + " 项没交",
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        if (refreshing) {
            // MD3E 加载指示器：形状之间 morphing 的动画
            LoadingIndicator(Modifier.size(24.dp))
        } else {
            TextButton(onClick = onRefresh) { Text("刷新全部平台", fontSize = 12.sp) }
        }
    }
}

/** 一条一言：正文 + 出处 + 作者 */
data class Quote(val text: String, val from: String = "", val who: String = "")

private val LOCAL_QUOTES = listOf(
    Quote("人生如逆旅，我亦是行人。", "临江仙·送钱穆父", "苏轼"),
    Quote("凡是过往，皆为序章。", "暴风雨", "莎士比亚"),
    Quote("路漫漫其修远兮，吾将上下而求索。", "离骚", "屈原"),
    Quote("Slow is smooth, smooth is fast.", "", ""),
    Quote("不要因为走得太远，而忘记为什么出发。", "", ""),
)

private val HITOKOTO_CLIENT: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()
}

suspend fun fetchHitokoto(): Quote = withContext(Dispatchers.IO) {
    runCatching {
        val client = HITOKOTO_CLIENT
        val req = Request.Builder()
            .url("https://v1.hitokoto.cn/?max_length=30")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return@use null
            val o = org.json.JSONObject(body)
            val text = o.optString("hitokoto").trim()
            if (text.isBlank()) return@use null
            Quote(
                text = text,
                from = o.optString("from").trim(),
                who = o.optString("from_who").trim().takeIf { it != "null" }.orEmpty(),
            )
        }
    }.getOrNull() ?: LOCAL_QUOTES.random()
}

/** 一言卡片：正文 + 出处 */
@Composable
private fun QuoteCard(quote: Quote?, onRefresh: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                "“",
                fontSize = 26.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            )
            Spacer(Modifier.width(6.dp))
            val quoteColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            val title = androidx.compose.ui.text.buildAnnotatedString {
                append(quote?.text ?: "正在加载…")
                withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = quoteColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                ) { append("”") }
            }
            Text(
                title,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
        val source = buildString {
            val from = quote?.from.orEmpty()
            val who = quote?.who.orEmpty()
            if (from.isNotBlank()) append("《").append(from).append("》")
            if (who.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append(who)
            }
            if (isEmpty()) append("一言")
        }
        Text(
            "—— " + source,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 18.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            "↻",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(onClick = onRefresh)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..10 -> "早上好"
    in 11..13 -> "中午好"
    in 14..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}

private fun courseColor(name: String): Color =
    com.hpu.timetable.ui.schedule.courseColor(name)

private fun courseStart(course: CourseInstance): LocalTime =
    LocalTime.parse(BIG_TIME[(course.big - 1).coerceIn(0, BIG_TIME.lastIndex)])

private fun courseEnd(course: CourseInstance): LocalTime =
    LocalTime.parse(BIG_END[(course.big + course.span - 2).coerceIn(0, BIG_END.lastIndex)])
