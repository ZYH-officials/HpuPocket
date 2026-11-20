
package com.hpu.timetable.ui.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.timetable.data.CourseInstance
import com.hpu.timetable.data.TimetableRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
private val BIG_TIMES = listOf("08:00", "10:00", "14:30", "16:30", "19:00")
private val BIG_ENDS = listOf("09:40", "11:40", "16:10", "18:10", "20:40")
private val TIME_COL_W = 32.dp
private val HEADER_H = 22.dp
private const val TOTAL_WEEKS = 20

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(instances: List<CourseInstance>) {
    var today by remember { mutableStateOf(LocalDate.now()) }
    // 整点分钟对齐刷新：页面挂着跨天时，日期/周次也要跟着走
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
            today = LocalDate.now()
        }
    }
    val currentWeek = remember(today) { TimetableRepository.currentWeek(today) }
    val todayDay = today.dayOfWeek.value
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        initialPage = (currentWeek - 1).coerceIn(0, TOTAL_WEEKS - 1),
    ) { TOTAL_WEEKS }
    val week = pagerState.currentPage + 1
    var selected by remember { mutableStateOf<CourseInstance?>(null) }

    val todayItems = remember(currentWeek, instances) {
        instances.filter { it.week == currentWeek && it.day == todayDay }.sortedBy { it.big }
    }

    BottomSheetScaffold(
        sheetContent = {
            TodayOverview(
                items = todayItems,
                dayLabel = DAY_LABELS[todayDay - 1],
                weekItems = instances.filter { it.week == currentWeek },
                todayDay = todayDay,
            )
        },
        sheetPeekHeight = 104.dp,
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetShadowElevation = 12.dp,
        sheetDragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)),
                )
            }
        },
    ) { innerPadding ->
    Column(
        Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .statusBarsPadding()
            .padding(horizontal = 6.dp),
    ) {
            WeekHeader(
                week = week,
                isCurrent = week == currentWeek,
                dateRange = dateRangeOf(week),
                onPrev = { scope.launch { pagerState.animateScrollToPage((week - 2).coerceAtLeast(0)) } },
                onNext = { scope.launch { pagerState.animateScrollToPage(week.coerceAtMost(TOTAL_WEEKS - 1)) } },
            )

            Row(Modifier.fillMaxWidth().height(HEADER_H)) {
                Spacer(Modifier.width(TIME_COL_W))
                for (d in 1..7) {
                    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                        val isToday = week == currentWeek && d == todayDay
                        Text(
                            DAY_LABELS[d - 1],
                            fontSize = 11.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Box(Modifier.fillMaxWidth().weight(0.84f)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                val pageWeek = page + 1
                val byCell = remember(pageWeek) {
                    instances.filter { it.week == pageWeek }.groupBy { it.day to it.big }
                }
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    val rowH = maxHeight / 5f
                    val colW = (maxWidth - TIME_COL_W) / 7f

                    // 今日列底色
                    if (pageWeek == currentWeek) {
                        Box(
                            Modifier
                                .offset(x = TIME_COL_W + colW * (todayDay - 1).toFloat())
                                .width(colW)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)),
                        )
                    }

                    // 行分割线
                    for (b in 1 until 5) {
                        Box(
                            Modifier
                                .offset(y = rowH * b.toFloat())
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        )
                    }

                    // 时间标签
                    for (b in 1..5) {
                        Box(
                            Modifier
                                .width(TIME_COL_W)
                                .offset(y = rowH * (b - 1).toFloat() + 3.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Text(
                                BIG_TIMES[b - 1],
                                fontSize = 8.sp,
                                lineHeight = 9.sp,
                                maxLines = 1,
                                softWrap = false,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }

                    // 课程块：普通课占满大节整行；跨大节的课上下各错开半行
                    instances.filter { it.week == pageWeek }.forEach { item ->
                        val col = (item.day - 1).coerceIn(0, 6)
                        val cross = item.span > 1
                        val top = if (cross) {
                            rowH * (item.big - 1).toFloat() + rowH * 0.5f
                        } else {
                            rowH * (item.big - 1).toFloat()
                        }
                        val height = if (cross) {
                            rowH * (item.span.toFloat() - 0.5f)
                        } else {
                            rowH
                        }
                        Box(
                            Modifier
                                .offset(x = TIME_COL_W + colW * col.toFloat(), y = top)
                                .width(colW)
                                .height(height)
                                .padding(horizontal = 0.5.dp, vertical = 1.dp),
                        ) {
                            CourseCard(item, Modifier.fillMaxSize()) { selected = item }
                        }
                    }
                }
            }
            // 只有当前日期落在学期内时才提供「回到本周」
            if (currentWeek in 1..TOTAL_WEEKS && week != currentWeek) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage((currentWeek - 1).coerceIn(0, TOTAL_WEEKS - 1))
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 14.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text("今", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            }
            Spacer(Modifier.weight(0.10f))
        }
    }

    selected?.let { item ->
        CourseDetailDialog(item = item, all = instances, onDismiss = { selected = null })
    }
}

@Composable
private fun CourseDetailDialog(item: CourseInstance, all: List<CourseInstance>, onDismiss: () -> Unit) {
    val weeks = remember(item) {
        all.filter {
            it.course == item.course && it.day == item.day && it.big == item.big && it.room == item.room
        }.map { it.week }.distinct().sorted()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text(item.course, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                DetailRow("教师", item.teacher.ifBlank { "未填写" })
                DetailRow("地点", item.room.ifBlank { "地点待定" })
                DetailRow(
                    "时间",
                    "周" + DAY_LABELS[item.day - 1] + " " + bigRangeText(item),
                )
                DetailRow("周次", compressWeeks(weeks))
                if (item.exp.isNotBlank()) DetailRow("实验", item.exp)
            }
        },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
        Text(value, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

private fun compressWeeks(weeks: List<Int>): String {
    if (weeks.isEmpty()) return "未安排"
    val ranges = mutableListOf<Pair<Int, Int>>()
    var start = weeks.first()
    var prev = weeks.first()
    for (w in weeks.drop(1)) {
        if (w == prev + 1) {
            prev = w
        } else {
            ranges += start to prev
            start = w
            prev = w
        }
    }
    ranges += start to prev
    val text = ranges.joinToString(",") { if (it.first == it.second) "${it.first}" else "${it.first}-${it.second}" }
    return "第 $text 周"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayOverview(
    items: List<CourseInstance>,
    dayLabel: String,
    weekItems: List<CourseInstance>,
    todayDay: Int,
) {
    // 每分钟对齐刷新：距下节课倒计时、已上节数、进行中状态都跟真实时间走，
    // 不再依赖切页触发重组（之前 remember 一次就冻住了）
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = LocalTime.now()
        }
    }
    val starts = listOf(
        LocalTime.of(8, 0), LocalTime.of(10, 0), LocalTime.of(14, 30),
        LocalTime.of(16, 30), LocalTime.of(19, 0),
    )
    val ends = listOf(
        LocalTime.of(9, 40), LocalTime.of(11, 40), LocalTime.of(16, 10),
        LocalTime.of(18, 10), LocalTime.of(20, 40),
    )
    val total = items.size
    val done = items.count { ends[(it.big - 1).coerceIn(0, 4)] <= now }
    val current = items.firstOrNull {
        val i = (it.big - 1).coerceIn(0, 4)
        starts[i] <= now && now <= ends[i]
    }
    val next = items.firstOrNull { starts[(it.big - 1).coerceIn(0, 4)] > now }

    val headline = when {
        total == 0 -> "今天没课，建议原地躺平，或者去图书馆卷别人。"
        current != null -> "正在上《" + current.course + "》，手机收一收，别被老师看见。"
        done >= total -> "今天的课全上完了，剩下的时间属于你自己了，冲！"
        next != null -> {
            val mins = java.time.Duration.between(now, starts[(next.big - 1).coerceIn(0, 4)]).toMinutes()
            "还有 " + mins + " 分钟才上《" + next.course + "》，再眯一会儿吧。"
        }
        else -> "课间休息，去接杯水，回来接着肝。"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "今天 · 周" + dayLabel,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (total > 0) {
                Text(
                    total.toString() + " 节课 · 已上 " + done + " 节",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            headline,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (items.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEach { c ->
                    val i = (c.big - 1).coerceIn(0, 4)
                    val state = when {
                        ends[i] <= now -> "已上"
                        starts[i] <= now -> "进行中"
                        else -> "未上"
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (state == "进行中") MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "第" + c.big + "节 " + state,
                            fontSize = 10.sp,
                            color = if (state == "进行中") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        )
        Spacer(Modifier.height(14.dp))

        val weekTotal = weekItems.size
        val weekDone = weekItems.count { c ->
            c.day < todayDay || (c.day == todayDay && ends[(c.big - 1).coerceIn(0, 4)] <= now)
        }
        Text("本周概览", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            "共 " + weekTotal + " 节 · 已上 " + weekDone + " 节 · 还剩 " + (weekTotal - weekDone) + " 节",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (d in 1..7) {
                val count = weekItems.count { it.day == d }
                val isTodayCol = d == todayDay
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isTodayCol) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        DAY_LABELS[d - 1],
                        fontSize = 10.sp,
                        color = if (isTodayCol) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (count == 0) "·" else count.toString(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isTodayCol) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekHeader(
    week: Int,
    isCurrent: Boolean,
    dateRange: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,

) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onPrev,
            enabled = week > 1,
            modifier = Modifier.width(48.dp).height(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("‹", fontSize = 20.sp) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            // transitionSpec 的 lambda 不是 Composable 上下文，规格要在外面取好
            val weekEnter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val weekExit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            AnimatedContent(
                targetState = week,
                transitionSpec = { fadeIn(weekEnter) togetherWith fadeOut(weekExit) },
                label = "week",
            ) { w ->
                Text("第 $w 周", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
            }
            Text(dateRange, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 12.sp)
        }
        TextButton(
            onClick = onNext,
            enabled = week < TOTAL_WEEKS,
            modifier = Modifier.width(48.dp).height(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("›", fontSize = 20.sp) }
    }
}

/** "第 1 大节" / "第 1-2 大节" */
private fun bigRangeText(item: CourseInstance): String =
    if (item.span > 1) {
        val end = (item.big + item.span - 1).coerceAtMost(5)
        "第 " + item.big + "-" + end + " 大节  " + BIG_TIMES[item.big - 1] + "-" + BIG_ENDS[end - 1]
    } else {
        "第 " + item.big + " 大节  " + BIG_TIMES[item.big - 1] + "-" + BIG_ENDS[item.big - 1]
    }

@Composable
private fun Cell(
    items: List<CourseInstance>,
    isToday: Boolean,
    modifier: Modifier = Modifier,
    onClick: (CourseInstance) -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (isToday) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                else Modifier
            )
            .padding(1.5.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            val list = items.take(2)
            list.forEachIndexed { idx, item ->
                CourseCard(item, Modifier.weight(1f)) { onClick(item) }
                if (idx != list.lastIndex) Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun CourseCard(item: CourseInstance, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(courseColor(item.course))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    item.course,
                    fontSize = 11.sp,
                    lineHeight = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                (if (item.span > 1) "第" + item.big + "-" + (item.big + item.span - 1) + "节\n" else "") +
                    roomLabel(item.room),
                fontSize = 9.sp,
                lineHeight = 10.5.sp,
                color = Color.White.copy(alpha = 0.88f),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp, top = 1.dp),
            )
        }
    }
}

private fun roomLabel(room: String): String {
    if (room.isBlank()) return "地点待定"
    val noPrefix = room.substringAfterLast(' ')
    if (noPrefix.length <= 6) return noPrefix
    val m = Regex("([\\u4e00-\\u9fa5]{2,4}楼)([0-9]+.*)").find(noPrefix)
    if (m != null) {
        val building = m.groupValues[1]
        val tail = m.groupValues[2]
        val short = if (building.length > 3) building.takeLast(3) else building
        return short + tail
    }
    return noPrefix.takeLast(6)
}

/** 按课程类型分配固定色板，比按名字 hash 更协调 */
private val FALLBACK_COLORS = listOf(
    Color(0xFF6A7BC0), Color(0xFF4C9BB5), Color(0xFF7A9E4E),
    Color(0xFFC08A4A), Color(0xFFB3668A), Color(0xFF5D8FA8),
)

internal fun courseColor(name: String): Color = when {
    name.contains("高等数学") || name.contains("数学") -> Color(0xFF5A6AC4)
    name.contains("线性") -> Color(0xFF3F8FB8)
    name.contains("外语") || name.contains("英语") -> Color(0xFFC98A45)
    name.contains("思想政治") || name.contains("思想道德") || name.contains("形势") -> Color(0xFF3E9E7E)
    name.contains("体育") -> Color(0xFF2E9B8F)
    name.contains("心理") -> Color(0xFF7B62B0)
    name.contains("职业生涯") || name.contains("就业") -> Color(0xFFBB6E52)
    name.contains("计算思维") -> Color(0xFF3F76B8)
    name.contains("人工智能") -> Color(0xFFA85A88)
    name.contains("实验") -> Color(0xFF626D9E)
    else -> {
        var h = 0
        for (c in name) h = h * 31 + c.code
        FALLBACK_COLORS[((h % FALLBACK_COLORS.size) + FALLBACK_COLORS.size) % FALLBACK_COLORS.size]
    }
}

private fun dateRangeOf(week: Int): String {
    val monday = TimetableRepository.mondayOf(week)
    val sunday = monday.plusDays(6)
    val f = DateTimeFormatter.ofPattern("M/d")
    return monday.format(f) + " - " + sunday.format(f)
}