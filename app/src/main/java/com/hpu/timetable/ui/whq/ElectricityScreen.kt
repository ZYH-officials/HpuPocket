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
)

/**
 * 宿舍电量：微信扫码登录微后勤 → 展示已绑定房间的剩余电量。
 * 暂不做购电（跳一卡通）和用电记录。
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
                balances = balances + (room.key to RoomBalance(loading = true))
            }
        }
        // 顺序查，每查完一间就刷新界面；单间失败不影响其它房间
        for ((_, rooms) in found) {
            for (room in rooms) {
                val r = client.balance(room)
                balances = balances + (room.key to r.fold(
                    onSuccess = { raws -> RoomBalance(items = WhqClient.parseBalance(raws)) },
                    onFailure = { e -> RoomBalance(error = e.message ?: "电量查询失败") },
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
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
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
                    RoomCard(room.name, balances[room.key])
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
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRefresh) { Text("刷新二维码", fontSize = 13.sp) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RoomCard(name: String, state: RoomBalance?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        when {
            state == null || state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "电量查询中…",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error != null -> Text(
                state.error,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
            state.items.isEmpty() -> Text(
                "暂无数据",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> state.items.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Bottom) {
                    Text(
                        label,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        " 度",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
