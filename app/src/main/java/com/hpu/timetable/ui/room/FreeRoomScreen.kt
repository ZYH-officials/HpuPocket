package com.hpu.timetable.ui.room

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.timetable.data.Building
import com.hpu.timetable.data.RoomDay
import com.hpu.timetable.data.auth.PersistCookieJar
import com.hpu.timetable.data.jw.JwClient
import java.time.LocalDate

private val WEEK_CN = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val SLOT_LABELS = listOf("1-2", "3-4", "6-7", "8-9", "10-11")
private val SLOT_TIMES = listOf(
    "08:00", "10:00", "14:30", "16:30", "19:00",
)

/** 空闲教室：先选楼，再看那天这栋楼每间教室哪几节空 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FreeRoomScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val client = remember { JwClient(PersistCookieJar(ctx)) }
    var buildings by remember { mutableStateOf<List<Building>>(emptyList()) }
    var building by remember { mutableStateOf<Building?>(null) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var rooms by remember { mutableStateOf<List<RoomDay>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    // 本次请求是否绕过缓存：只有点「刷新」才置 true，换楼/换日期都用 10 分钟内的缓存
    var force by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    fun errText(e: Throwable): String = when (e) {
        is com.hpu.timetable.data.auth.ReloginCooldownException ->
            "刚重登过，" + e.waitSeconds + " 秒后再试"
        is com.hpu.timetable.data.auth.SessionExpiredException ->
            "登录已过期，点刷新重登"
        is com.hpu.timetable.data.auth.RateLimitedException ->
            "教务限流，请 30 秒后再试"
        else -> e.message ?: "未知错误"
    }

    // 一次刷新 = 串行做完「楼列表 → 房间数据」，只用一个 effect，避免两个 effect 互相覆盖。
    // 换教学楼 / 换日期都是改状态 + tick++，统一从这里重跑。
    LaunchedEffect(tick) {
        loading = true
        error = null
        rooms = null
        val allowLogin = tick > 0
        val bypassCache = force
        force = false

        if (buildings.isEmpty()) {
            val r = com.hpu.timetable.data.auth.SessionGate
                .run(ctx, allowLogin = allowLogin, notifyGlobal = false) {
                    client.fetchBuildings().getOrThrow()
                }
            r.onSuccess { list ->
                buildings = list
                if (building == null) {
                    building = list.firstOrNull { it.name.contains("3号教学楼") } ?: list.firstOrNull()
                }
            }.onFailure { e ->
                error = errText(e)
                loading = false
                return@LaunchedEffect
            }
        }

        val b = building
        if (b == null) {
            error = "没取到教学楼列表，点刷新重试"
            loading = false
            return@LaunchedEffect
        }

        com.hpu.timetable.data.auth.SessionGate
            .run(ctx, allowLogin = true, notifyGlobal = false) {
                client.fetchRoomDay(b.id, date, force = bypassCache).getOrThrow()
            }
            .onSuccess { rooms = it }
            .onFailure { e -> error = errText(e) }
        loading = false
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", fontSize = 14.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { force = true; tick++ }, enabled = !loading) {
                Text(if (loading) "查询中…" else "刷新", fontSize = 13.sp)
            }
        }
        Text(
            "空闲教室",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        // 教学楼
        Row(
            Modifier
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(enabled = buildings.isNotEmpty()) { picking = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("教学楼", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(
                building?.name ?: when {
                    loading && buildings.isEmpty() -> "加载中…"
                    error != null && buildings.isEmpty() -> "加载失败"
                    buildings.isEmpty() -> "暂不可用"
                    else -> "请选择"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text("▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 日期
        Row(
            Modifier
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { date = date.minusDays(1); tick++ },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("‹", fontSize = 20.sp) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    date.monthValue.toString() + " 月 " + date.dayOfMonth + " 日 · " +
                        WEEK_CN[date.dayOfWeek.value - 1],
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "按 5 个大节",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { date = date.plusDays(1); tick++ },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("›", fontSize = 20.sp) }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            when {
                loading -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 36.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoadingIndicator(Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("正在查询…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                error != null -> NoticeCard("查询失败", error ?: "", { tick++ })

                rooms != null && rooms!!.isEmpty() -> NoticeCard(
                    "这天没有空闲教室",
                    "换个日期或楼看看",
                    null,
                )

                rooms != null -> Matrix(rooms!!)
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
                    "选择教学楼",
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
                    items(buildings) { b ->
                        val on = b.id == building?.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable { building = b; picking = false; tick++ }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                b.name,
                                fontSize = 14.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (on) Text("✓", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun Matrix(rooms: List<RoomDay>) {
    // 表头
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            "教室",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(2.3f),
        )
        SLOT_LABELS.forEachIndexed { i, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                Text(
                    SLOT_TIMES[i],
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)))
    Spacer(Modifier.height(4.dp))

    rooms.forEach { r ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(2.3f)) {
                // 教室名里的括号备注（如「高级型智慧教室」）挪到副标题，主标题只留房号
                val note = if (r.name.contains("（")) r.name.substringAfter("（").removeSuffix("）") else ""
                Text(r.name.substringBefore("（"), fontSize = 11.sp, maxLines = 1)
                val sub = listOfNotNull(
                    r.capacity.takeIf { it.isNotBlank() && it != "0" }?.let { it + " 座" },
                    note.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(
                        sub,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            r.free.forEach { free ->
                Box(Modifier.weight(1f).padding(horizontal = 2.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (free) Color(0xFF5FB878).copy(alpha = 0.85f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            )
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp).clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF5FB878).copy(alpha = 0.85f))
        )
        Spacer(Modifier.width(5.dp))
        Text("空闲", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier.size(10.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
        )
        Spacer(Modifier.width(5.dp))
        Text("有课", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "共 " + rooms.size + " 间 · 只列有空的教室",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NoticeCard(title: String, body: String, onRetry: (() -> Unit)?) {
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
        if (onRetry != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRetry) { Text("重试", fontSize = 12.sp) }
        }
    }
}
