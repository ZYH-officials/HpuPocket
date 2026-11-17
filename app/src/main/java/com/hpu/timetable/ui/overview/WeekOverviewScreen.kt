package com.hpu.timetable.ui.overview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.timetable.data.CourseInstance
import com.hpu.timetable.data.TimetableRepository
import com.hpu.timetable.notify.SchedulePlanner
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY_CN = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 下周概览：从周概览通知点进来，单独一页看下周怎么过 */
@Composable
fun WeekOverviewScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val week = remember { TimetableRepository.currentWeek() + 1 }
    val monday = remember { TimetableRepository.mondayOf(week) }

    val courses = remember {
        runCatching {
            TimetableRepository.loadCached(ctx)?.let { TimetableRepository.mergeLab(ctx, it) }
        }.getOrNull().orEmpty().filter { it.week == week }
    }
    val exams = remember {
        TimetableRepository.loadExams(ctx).filter { e ->
            val d = runCatching { LocalDate.parse(e.date) }.getOrNull()
            d != null && !d.isBefore(monday) && !d.isAfter(monday.plusDays(6))
        }
    }

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", fontSize = 14.sp) }
        }
        Text(
            "下周概览",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        val fmt = DateTimeFormatter.ofPattern("M/d")
        Text(
            "第 $week 周 · " + monday.format(fmt) + " - " + monday.plusDays(6).format(fmt) +
                " · 共 " + courses.size + " 节",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            if (exams.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text("本周有考试", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    exams.forEach { e ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            e.course + " · " + (if (e.isScheduled) e.date + " " + e.timeRange + " · " + e.place else "时间地点待安排"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (courses.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("下周没有课", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                for (d in 1..7) {
                    val dayCourses = courses.filter { it.day == d }.sortedBy { it.big }
                    if (dayCourses.isEmpty()) continue
                    val date = monday.plusDays((d - 1).toLong())
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            DAY_CN[d - 1],
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(
                            date.format(fmt),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            dayCourses.size.toString() + " 节",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        dayCourses.forEachIndexed { i, c ->
                            CourseLine(c)
                            if (i != dayCourses.lastIndex) {
                                Box(
                                    Modifier.fillMaxWidth().padding(start = 14.dp).height(0.5.dp)
                                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun CourseLine(c: CourseInstance) {
    val start = SchedulePlanner.BIG_STARTS.getOrNull((c.big - 1).coerceIn(0, 4))
    val time = if (start != null) String.format("%02d:%02d", start.hour, start.minute) else ""
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            time,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(c.course, fontSize = 13.sp, maxLines = 1)
            val sub = listOfNotNull(
                c.room.ifBlank { null },
                c.teacher.ifBlank { null },
            ).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (c.span > 1) {
            Text(
                "连" + c.span + "节",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
