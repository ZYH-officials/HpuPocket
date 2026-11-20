package com.hpu.timetable.ui.whq

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.hpu.timetable.data.whq.WhqClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 生成二维码位图；失败返回 null，由界面兜底 */
private fun makeQr(text: String, sizePx: Int): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(EncodeHintType.MARGIN to 1),
    )
    val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    bmp.asImageBitmap()
}.getOrNull()

private data class RoomBalance(
    val loading: Boolean = false,
    val items: List<Pair<String, String>> = emptyList(),
    val error: String? = null,
    val recordsLoading: Boolean = false,
    val records: List<WhqClient.UsageRecord> = emptyList(),
    val recordsError: String? = null,
)

/**
 * 宿舍电量：微信扫码登录微后勤 → 展示已绑定房间的剩余电量和用电记录。
 * 暂不做购电（跳一卡通）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ElectricityScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val client = remember { WhqClient(ctx) }
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(true) }
    var loggedIn by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(WhqClient.newToken()) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    var scanStatus by remember { mutableStateOf("请使用微信扫一扫") }
    var areas by remember { mutableStateOf<List<Pair<String, List<WhqClient.Room>>>>(emptyList()) }
    var loadingRooms by remember { mutableStateOf(false) }
    var roomError by remember { mutableStateOf<String?>(null) }
    var balances by remember { mutableStateOf<Map<String, RoomBalance>>(emptyMap()) }
    var detailRoom by remember { mutableStateOf<WhqClient.Room?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    suspend fun loadAll() {
        loadingRooms = true
        roomError = null
        balances = emptyMap()
        val found = mutableListOf<Pair<String, List<WhqClient.Room>>>()
        for (area in listOf(WhqClient.AREA_SOUTH, WhqClient.AREA_NORTH)) {
            client.rooms(area).onSuccess { list ->
                if (!list.isNullOrEmpty()) found += area to list
            }.onFailure { e ->
                if (roomError == null) roomError = e.message ?: "房间列表读取失败"
            }
        }
        areas = found
        loadingRooms = false
        found.forEach { (_, rooms) ->
            rooms.forEach { room ->
                balances = balances + (room.key to RoomBalance(loading = true, recordsLoading = true))
            }
        }
        // 顺序查，每查完一间就刷新界面；单间失败不影响其它房间
        for ((_, rooms) in found) {
            for (room in rooms) {
                val r = client.balance(room)
                balances = balances + (room.key to r.fold(
                    onSuccess = { raws ->
                        RoomBalance(items = WhqClient.parseBalance(raws), recordsLoading = true)
                    },
                    onFailure = { e ->
                        RoomBalance(error = e.message ?: "电量查询失败", recordsLoading = true)
                    },
                ))
                val u = client.usage(room)
                balances = balances + (room.key to (balances[room.key] ?: RoomBalance()).copy(
                    recordsLoading = false,
                    records = u.getOrElse { emptyList() },
                    recordsError = u.exceptionOrNull()?.message,
                ))
            }
        }
    }

    // 进入先判断扫码会话是否有效
    LaunchedEffect(Unit) {
        val ok = client.loggedIn().getOrDefault(false)
        loggedIn = ok
        checking = false
    }

    // 已登录或点了刷新 → 拉房间和余额
    LaunchedEffect(loggedIn, tick) {
        if (loggedIn) loadAll()
    }

    // 二维码内容随 token 变化重画
    LaunchedEffect(token) {
        qr = null
        qr = withContext(Dispatchers.Default) { makeQr(WhqClient.qrContent(token), 720) }
    }

    // 未登录时每 2 秒轮询扫码状态，与 H5 一致
    LaunchedEffect(token, loggedIn) {
        if (loggedIn) return@LaunchedEffect
        while (true) {
            when (client.pollStatus(token).getOrNull()) {
                "success" -> {
                    scanStatus = "扫码成功"
                    loggedIn = true
                    return@LaunchedEffect
                }
                "expired" -> {
                    scanStatus = "二维码已失效，请刷新"
                    return@LaunchedEffect
                }
                "invalid_token" -> {
                    scanStatus = "二维码参数异常，请刷新"
                    return@LaunchedEffect
                }
                "pending" -> scanStatus = "等待扫码…"
                else -> scanStatus = "正在连接登录服务…"
            }
            delay(2000)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", fontSize = 14.sp) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "宿舍电量",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (loggedIn) {
                TextButton(onClick = { tick++ }) { Text("刷新", fontSize = 13.sp) }
            }
        }

        if (checking) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator(modifier = Modifier.size(40.dp))
            }
            return@Column
        }

        if (!loggedIn) {
            LoginPanel(
                qr = qr,
                status = scanStatus,
                onRefresh = {
                    token = WhqClient.newToken()
                    scanStatus = "请使用微信扫一扫"
                },
            )
            return@Column
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            if (loadingRooms && areas.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator(modifier = Modifier.size(32.dp))
                }
            }
            roomError?.let { msg ->
                Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { tick++ }) { Text("重试", fontSize = 13.sp) }
            }
            if (!loadingRooms && areas.isEmpty() && roomError == null) {
                Text(
                    "没有绑定的宿舍房间。\n请先在微后勤小程序里绑定宿舍，回来点「刷新」。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                    textAlign = TextAlign.Center,
                )
            }

            areas.forEach { (area, rooms) ->
                Text(
                    WhqClient.areaLabel(area),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 8.dp),
                )
                rooms.forEach { room ->
                    RoomCard(room.name, balances[room.key]) { detailRoom = room }
                    Spacer(Modifier.height(10.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "微后勤账号与教务独立，仅用于查询宿舍电量",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = {
                    scope.launch {
                        runCatching { client.logout() }
                        client.clearSession()
                        loggedIn = false
                        areas = emptyList()
                        balances = emptyMap()
                        token = WhqClient.newToken()
                        scanStatus = "请使用微信扫一扫"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("退出微后勤登录", fontSize = 12.sp) }
            Spacer(Modifier.height(30.dp))
        }
    }

    detailRoom?.let { room ->
        UsageDialog(
            name = room.name,
            state = balances[room.key],
            onDismiss = { detailRoom = null },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoginPanel(qr: ImageBitmap?, status: String, onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("微信扫码登录微后勤", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "和教务账号独立，只用来查宿舍电量",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Color.White)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(
                    bitmap = qr,
                    contentDescription = "微后勤登录二维码",
                    modifier = Modifier.size(220.dp),
                )
            } else {
                Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator(modifier = Modifier.size(28.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(
                "① 用微信扫一扫上面的二维码",
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "② 微信会直接打开微后勤并完成登录，无需手动确认",
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "③ 回到本页，会自动进入并加载电量",
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "扫码后不用重新打开 App，停留在本页等待即可",
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRefresh) { Text("刷新二维码", fontSize = 13.sp) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RoomCard(name: String, state: RoomBalance?, onOpenRecords: () -> Unit) {
    val days = state?.records?.let(::groupUsage).orEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        when {
            state == null || state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "电量查询中…",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error != null -> Text(
                state.error,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
            )
            state.items.isEmpty() -> Text(
                "暂无数据",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Row(Modifier.fillMaxWidth()) {
                state.items.forEachIndexed { index, (label, value) ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(48.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                        )
                    }
                    BalanceItem(label, value, Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "最近用电",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenRecords) { Text("全部记录 ›", fontSize = 12.sp) }
        }
        when {
            state == null || state.recordsLoading -> Row(
                Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoadingIndicator(modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "记录读取中…",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.recordsError != null -> Text(
                "记录读取失败：" + state.recordsError,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
            days.isEmpty() -> Text(
                "近 14 天没有用电记录",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            else -> days.take(3).forEach { day -> UsageDayRow(day, compact = true) }
        }
    }
}

@Composable
private fun BalanceItem(label: String, value: String, modifier: Modifier = Modifier) {
    val negative = value.toDoubleOrNull()?.let { it < 0 } == true
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            shortBalanceLabel(label),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                " 度",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageDayRow(day: DayUsage, compact: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = if (compact) 5.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (compact) {
            Text(
                dayLabel(day.date),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(88.dp),
            )
        } else {
            Column(Modifier.width(82.dp)) {
                Text(dayLabel(day.date).substringBefore(" "), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    dayLabel(day.date).substringAfter(" ", ""),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            usageSummary(day.rows),
            fontSize = if (compact) 13.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 20.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun shortBalanceLabel(label: String): String = when {
    label.contains("备用") -> "备用电量"
    label.contains("空调") -> "空调电量"
    label.contains("照明") -> "照明电量"
    else -> "剩余电量"
}

private fun shortMeter(meter: String): String = when {
    meter.contains("备用") -> "备用"
    meter.contains("空调") -> "空调"
    meter.contains("照明") -> "照明"
    else -> meter
}

private data class DayUsage(val date: String, val rows: List<WhqClient.UsageRecord>)

private fun groupUsage(records: List<WhqClient.UsageRecord>): List<DayUsage> =
    records.groupBy { it.date }
        .toSortedMap(compareByDescending { it })
        .mapNotNull { (date, rows) ->
            val used = rows.distinctBy { it.meter }.filter { (it.kwh.toDoubleOrNull() ?: 0.0) != 0.0 }
            if (used.isEmpty()) null else DayUsage(date, used)
        }

private fun dayLabel(date: String): String {
    val d = runCatching { java.time.LocalDate.parse(date) }.getOrNull() ?: return date
    val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    return String.format("%02d-%02d", d.monthValue, d.dayOfMonth) + " " +
        week[d.dayOfWeek.value - 1]
}

/** 同一天合并：跳过 0 度，剩下的按「照明 1.95 · 空调 3.75 度」展示 */
private fun usageSummary(rows: List<WhqClient.UsageRecord>): String {
    val parts = rows.mapNotNull { r ->
        val v = r.kwh.toDoubleOrNull() ?: return@mapNotNull null
        if (v == 0.0) null else shortMeter(r.meter) + " " + r.kwh
    }
    return if (parts.isEmpty()) "0.00 度" else parts.joinToString(" · ") + " 度"
}

private fun totalUsage(records: List<WhqClient.UsageRecord>): String =
    String.format("%.2f", records.sumOf { it.kwh.toDoubleOrNull() ?: 0.0 })

/** 某间房的完整用电记录（默认已取约两周），弹窗里可滚动 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UsageDialog(name: String, state: RoomBalance?, onDismiss: () -> Unit) {
    val days = state?.records?.let(::groupUsage).orEmpty()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用电记录", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when {
                    state == null || state.recordsLoading -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        LoadingIndicator(modifier = Modifier.size(20.dp))
                    }
                    state.recordsError != null -> Text(
                        "读取失败：" + state.recordsError,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                    days.isEmpty() -> Text(
                        "近 14 天暂无用电记录",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        Text(name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "近 14 天合计",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    totalUsage(state.records),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    " 度",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        days.forEachIndexed { index, day ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(horizontal = 12.dp),
                            ) {
                                UsageDayRow(day)
                            }
                            if (index != days.lastIndex) Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
