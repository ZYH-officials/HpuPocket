package com.hpu.timetable

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hpu.timetable.data.CourseInstance
import com.hpu.timetable.data.Settings
import com.hpu.timetable.data.TimetableRepository
import com.hpu.timetable.data.UserStore
import com.hpu.timetable.data.auth.PersistCookieJar
import com.hpu.timetable.data.jw.JwClient
import com.hpu.timetable.notify.Notify
import com.hpu.timetable.notify.NotifyScheduler
import com.hpu.timetable.ui.login.LoginScreen
import com.hpu.timetable.ui.profile.ProfileScreen
import com.hpu.timetable.ui.schedule.ScheduleScreen
import com.hpu.timetable.ui.theme.HpuTimetableTheme

sealed interface UiState {
    data object Loading : UiState
    data class Ready(val courses: List<CourseInstance>) : UiState
    data object NeedLogin : UiState
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notify.ensureChannels(this)
        readNavIntent(intent)
        setContent {
            var themeMode by remember { mutableStateOf(Settings.themeMode(this)) }
            val dark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            HpuTimetableTheme(darkTheme = dark) {
                AppRoot(
                    themeMode = themeMode,
                    onThemeChange = {
                        Settings.setThemeMode(this, it)
                        themeMode = it
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readNavIntent(intent)
    }

    private fun readNavIntent(i: Intent?) {
        val page = i?.getStringExtra(Notify.EXTRA_PAGE) ?: return
        NavRequest.page = page
        NavRequest.week = i.getIntExtra(Notify.EXTRA_WEEK, -1)
    }
}

/** 通知点击要打开的页面；跨 Activity 重建也保留 */
object NavRequest {
    var page by mutableStateOf<String?>(null)
    var week by mutableIntStateOf(-1)
}

@Composable
private fun AppRoot(themeMode: String, onThemeChange: (String) -> Unit) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<UiState>(UiState.Loading) }
    var tab by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var askLab by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf<com.hpu.timetable.ui.home.Quote?>(null) }
    var quoteTick by remember { mutableIntStateOf(0) }

    var exams by remember { mutableStateOf(TimetableRepository.loadExams(ctx)) }
    var subPage by remember { mutableStateOf<String?>(null) }
    var askExactAlarm by remember { mutableStateOf(false) }
    var sessionExpired by remember { mutableStateOf(false) }

    // 启动时不再探活、不再自动登录：只用缓存秒开。
    // 会话是否过期由用户点「更新课表 / 刷新」时才发现，那时才尝试自动重登。
    LaunchedEffect(com.hpu.timetable.data.auth.SessionGate.expired) {
        if (com.hpu.timetable.data.auth.SessionGate.expired) {
            sessionExpired = true
            com.hpu.timetable.data.auth.SessionGate.expired = false
        }
    }

    // 通知权限（Android 13+）
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // 精确闹钟要不要授权，进设置页前问一次
        if (!NotifyScheduler.canScheduleExact(ctx)) askExactAlarm = true
    }

    // 课表就绪后重排提醒、刷新桌面小组件
    LaunchedEffect(state) {
        if (state is UiState.Ready) {
            runCatching { NotifyScheduler.rescheduleAll(ctx) }
            runCatching { com.hpu.timetable.widget.ScheduleWidget.updateAll(ctx) }
        }
    }

    // 每次回到前台都补排一次：小米等机型清后台可能把闹钟清掉，
    // 只要用户打开 App 就立刻恢复，不用等下次开机
    DisposableEffect(ctx) {
        val owner = ctx as? androidx.lifecycle.LifecycleOwner
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                runCatching { NotifyScheduler.rescheduleAll(ctx) }
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    // 从通知点进来：跳对应页面。要等课表就绪再处理，否则请求会被丢掉
    LaunchedEffect(NavRequest.page, state) {
        val p = NavRequest.page ?: return@LaunchedEffect
        if (state !is UiState.Ready) return@LaunchedEffect
        when (p) {
            "exam" -> { subPage = "exam"; tab = 0 }
            "schedule" -> { subPage = null; tab = 1 }
            "overview" -> { subPage = "overview"; tab = 0 }
        }
        NavRequest.page = null
    }
    LaunchedEffect(quoteTick) {
        quote = com.hpu.timetable.ui.home.fetchHitokoto()
    }
    val scope = rememberCoroutineScope()

    var labImporting by remember { mutableStateOf(false) }
    // 直接导入失败的原因；非空时弹窗问要不要退回网页导入
    var askWebLab by remember { mutableStateOf<String?>(null) }
    var hwConfigured by remember {
        mutableStateOf(com.hpu.timetable.data.hw.HwRepository.configuredPlatforms(ctx).isNotEmpty())
    }
    var hwPending by remember {
        mutableStateOf(com.hpu.timetable.data.hw.HwRepository.pendingCount(ctx))
    }
    var hwRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(subPage) {
        if (subPage == null) {
            hwConfigured = com.hpu.timetable.data.hw.HwRepository.configuredPlatforms(ctx).isNotEmpty()
            hwPending = com.hpu.timetable.data.hw.HwRepository.pendingCount(ctx)
        }
    }


    /** 实验课表落盘 → 和当前课表合并 → 立即生效。WebView 导入走的也是这套 */
    fun applyLabJson(json: String) {
        TimetableRepository.saveLabRaw(ctx, json)
        // courses.json 只存教务课表，实验课每次显示时现合并（换学期就不会残留旧实验课）
        val base = TimetableRepository.loadCached(ctx) ?: (state as? UiState.Ready)?.courses.orEmpty()
        state = UiState.Ready(TimetableRepository.mergeLab(ctx, base))
    }

    /**
     * 直接调实验平台接口导实验课表，不再走 WebView 注入。
     * 失败时问一句要不要退回网页导入：签名密钥被学校改了的话，WebView 是唯一后路。
     */
    fun runLabImport() {
        if (labImporting) return
        labImporting = true
        android.widget.Toast.makeText(ctx, "正在导入实验课表…", android.widget.Toast.LENGTH_SHORT).show()
        scope.launch {
            val r = com.hpu.timetable.data.auth.SessionGate.run(ctx, allowLogin = true) {
                com.hpu.timetable.data.lab.LabClient(PersistCookieJar(ctx)).fetchTimetable().getOrThrow()
            }
            labImporting = false
            r.onSuccess { json ->
                applyLabJson(json)
                val n = com.hpu.timetable.data.lab.LabParser.countRaw(json)
                android.widget.Toast.makeText(
                    ctx, "实验课表已导入：" + n + " 条",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { e ->
                askWebLab = when (e) {
                    is com.hpu.timetable.data.auth.ReloginCooldownException ->
                        "刚重登过，" + e.waitSeconds + " 秒后再试"
                    is com.hpu.timetable.data.auth.SessionExpiredException -> "登录已过期，请先重登"
                    else -> e.message ?: "未知错误"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        TimetableRepository.init(ctx)
        val cached = TimetableRepository.loadCached(ctx)
        if (cached != null) {
            state = UiState.Ready(TimetableRepository.mergeLab(ctx, cached))
            return@LaunchedEffect
        }
        val jar = PersistCookieJar(ctx)
        JwClient(jar).fetchCourses()
            .onSuccess { list ->
                TimetableRepository.save(ctx, list)
                if (UserStore.account(ctx).isBlank()) {
                    com.hpu.timetable.data.auth.CasClient(jar).fetchUserInfo()?.let { (acc, name) ->
                        UserStore.save(ctx, acc, name)
                    }
                }
                state = UiState.Ready(TimetableRepository.mergeLab(ctx, list))
            }
            .onFailure { e ->
                state = UiState.NeedLogin
                if (e.message?.contains("redirect", true) == true) {
                    android.widget.Toast.makeText(
                        ctx, "登录已过期，请重登",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
    }

    // 用 Surface 而不是 Box：Surface 会把 contentColor 设成 onBackground，
    // 这样所有没显式写颜色的 Text 在深色模式下才是浅色，而不是默认的黑。
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    when (val s = state) {
        is UiState.Loading -> LoadingScreen()

        is UiState.NeedLogin -> LoginScreen { list ->
            sessionExpired = false
            com.hpu.timetable.data.auth.SessionGate.expired = false
            Settings.setLastAutoLogin(ctx, 0L)
            TimetableRepository.save(ctx, list)
            state = UiState.Ready(TimetableRepository.mergeLab(ctx, list))
            // 还没导入过实验课表时，主动问一句
            if (TimetableRepository.loadLabRaw(ctx) == null) askLab = true
        }

        // 子页面用直切（之前用 AnimatedContent 包住会导致新页面已创建但屏幕不更新）
        is UiState.Ready -> run {
            val page = if (importing) "importing" else (subPage ?: "main")
            when (page) {
        "exam" -> {
            com.hpu.timetable.ui.exam.ExamScreen(
                exams = exams,
                onBack = { subPage = null; tab = 0 },
                onUpdate = { list ->
                    exams = list
                    TimetableRepository.saveExams(ctx, list)
                },
            )
        }
        "score" -> {
            com.hpu.timetable.ui.score.ScoreScreen(
                onBack = { subPage = null; tab = 0 },
            )
        }
        "rooms" -> {
            com.hpu.timetable.ui.room.FreeRoomScreen(
                onBack = { subPage = null; tab = 0 },
            )
        }
        "homework" -> {
            com.hpu.timetable.ui.hw.HomeworkScreen(
                onBack = { subPage = null; tab = 0 },
            )
        }
        "electricity" -> {
            com.hpu.timetable.ui.whq.ElectricityScreen(
                onBack = { subPage = null; tab = 0 },
            )
        }
        "overview" -> {
            com.hpu.timetable.ui.overview.WeekOverviewScreen(
                onBack = { subPage = null; tab = 0 },
            )
        }
        "settings" -> {
            com.hpu.timetable.ui.profile.SettingsScreen(
                onBack = { subPage = null },
                onOpenAbout = { subPage = "about" },
                themeMode = themeMode,
                onThemeChange = onThemeChange,
                onImportLab = { runLabImport() },
                onImportLabWeb = { subPage = null; importing = true },
                onImportJson = { json ->
                    TimetableRepository.saveLabRaw(ctx, json)
                    if (TimetableRepository.loadLabRaw(ctx) != null) {
                        state = UiState.Ready(TimetableRepository.mergeLab(ctx, s.courses))
                    }
                },
                onPasteExam = { html ->
                    val list = com.hpu.timetable.data.jw.ExamParser.parse(html)
                    if (list.isNotEmpty()) {
                        exams = list
                        TimetableRepository.saveExams(ctx, list)
                        android.widget.Toast.makeText(
                            ctx, "已解析 " + list.size + " 条考试",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            ctx, "没解析到考试数据",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onLogout = {
                    PersistCookieJar(ctx).clear()
                    UserStore.clear(ctx)
                    ctx.filesDir.resolve("courses.json").delete()
                    ctx.filesDir.resolve("exams.json").delete()
                    exams = emptyList()
                    NotifyScheduler.cancelAll(ctx)
                    androidx.core.app.NotificationManagerCompat.from(ctx).cancelAll()
                    runCatching { com.hpu.timetable.widget.ScheduleWidget.updateAll(ctx) }
                    com.hpu.timetable.data.jw.RoomCache.clear()
                    com.hpu.timetable.data.auth.SessionGate.expired = false
                    sessionExpired = false
                    // lab_raw.json 故意保留：重登后自动合并回来
                    subPage = null
                    state = UiState.NeedLogin
                    tab = 0
                },
            )
        }
        "about" -> {
            com.hpu.timetable.ui.profile.AboutScreen(
                onBack = { subPage = "settings" },
            )
        }
        "importing" -> com.hpu.timetable.ui.lab.LabImportScreen(
            onDone = { json ->
                importing = false
                if (json != null) {
                    // 先落盘原始 JSON，退出登录后还能合并回来
                    TimetableRepository.saveLabRaw(ctx, json)
                    val base = TimetableRepository.loadCached(ctx) ?: s.courses
                    state = UiState.Ready(TimetableRepository.mergeLab(ctx, base))
                }
            },
            onCancel = { importing = false },
        )
        else -> Scaffold(
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Text("首", fontSize = 15.sp) },
                        label = { Text("首页", fontSize = 11.sp) },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Text("课", fontSize = 15.sp) },
                        label = { Text("课表", fontSize = 11.sp) },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Text("我", fontSize = 15.sp) },
                        label = { Text("我的", fontSize = 11.sp) },
                    )
                }
            },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner)) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        (fadeIn(tween(160)) + slideInHorizontally(tween(200)) { it / 10 }) togetherWith
                            fadeOut(tween(110))
                    },
                    label = "tab",
                ) { currentTab -> when (currentTab) {
                    0 -> com.hpu.timetable.ui.home.HomeScreen(
                        name = UserStore.name(ctx),
                        courses = s.courses,
                        currentWeek = TimetableRepository.currentWeek(),
                        onOpenSchedule = { tab = 1 },
                        onOpenProfile = { tab = 2 },
                        quote = quote,
                        onRefreshQuote = { quoteTick++ },
                        exams = exams,
                        onReloadExams = { subPage = "exam" },
                        onOpenExams = { subPage = "exam" },
                        onOpenScores = { subPage = "score" },
                        onOpenRooms = { subPage = "rooms" },
                        onOpenHomework = { subPage = "homework" },
                        onOpenElectricity = { subPage = "electricity" },
                        hwConfigured = hwConfigured,
                        hwPending = hwPending,
                        hwRefreshing = hwRefreshing,
                        onRefreshHomework = {
                            if (!hwRefreshing) {
                                hwRefreshing = true
                                scope.launch {
                                    com.hpu.timetable.data.hw.HwRepository
                                        .refresh(ctx, PersistCookieJar(ctx))
                                        .onSuccess { s ->
                                            hwPending = s.pending
                                            hwConfigured = true
                                            val msg = if (s.failed.isEmpty()) {
                                                "作业：" + s.pending + " 项未完成"
                                            } else {
                                                "作业：" + s.pending + " 项未完成（部分平台失败：" +
                                                    s.failed.joinToString("；") + "）"
                                            }
                                            android.widget.Toast.makeText(
                                                ctx, msg, android.widget.Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                        .onFailure { e ->
                                            android.widget.Toast.makeText(
                                                ctx,
                                                "作业刷新失败：" + (e.message ?: "未知错误"),
                                                android.widget.Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    hwRefreshing = false
                                }
                            }
                        },
                        onPasteExams = { html ->
                            val list = com.hpu.timetable.data.jw.ExamParser.parse(html)
                            if (list.isNotEmpty()) {
                                exams = list
                                TimetableRepository.saveExams(ctx, list)
                                android.widget.Toast.makeText(ctx, "已解析 " + list.size + " 条考试", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(ctx, "没解析到考试数据", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    1 -> ScheduleScreen(s.courses)
                    else -> ProfileScreen(
                        refreshing = refreshing,
                        name = UserStore.name(ctx),
                        account = UserStore.account(ctx),
                        courseCount = s.courses.size,
                        onSemesterChanged = {
                            // 学期起点变了：按缓存重新合并一遍，属于旧学期的实验课会被跳过
                            state = UiState.Ready(
                                TimetableRepository.mergeLab(
                                    ctx,
                                    TimetableRepository.loadCached(ctx) ?: s.courses,
                                )
                            )
                            NotifyScheduler.rescheduleAll(ctx)
                        },
                        onOpenSettings = { subPage = "settings" },
                        onRefresh = {
                            if (!refreshing) {
                                refreshing = true
                                scope.launch {
                                    val r = com.hpu.timetable.data.auth.SessionGate.run(ctx, allowLogin = true) {
                                        JwClient(PersistCookieJar(ctx)).fetchCourses().getOrThrow()
                                    }
                                    r.onSuccess { list ->
                                        TimetableRepository.save(ctx, list)
                                        // 顺带刷新实验课表：失败不影响教务课表更新，只在提示里说一句
                                        val lab = com.hpu.timetable.data.auth.SessionGate.run(ctx, allowLogin = true) {
                                            com.hpu.timetable.data.lab.LabClient(PersistCookieJar(ctx))
                                                .fetchTimetable().getOrThrow()
                                        }
                                        lab.onSuccess { TimetableRepository.saveLabRaw(ctx, it) }
                                        val merged = TimetableRepository.mergeLab(ctx, list)
                                        state = UiState.Ready(merged)
                                        // 用户显式更新后串行刷新考试；失败保留旧数据，不影响课表。
                                        val examResult = com.hpu.timetable.data.auth.SessionGate.run(
                                            ctx, allowLogin = false, notifyGlobal = false,
                                        ) { JwClient(PersistCookieJar(ctx)).fetchExams().getOrThrow() }
                                        examResult.onSuccess { updated ->
                                            if (updated.isNotEmpty()) {
                                                exams = updated
                                                TimetableRepository.saveExams(ctx, updated)
                                            }
                                        }
                                        val labCount = lab.getOrNull()
                                            ?.let { com.hpu.timetable.data.lab.LabParser.countRaw(it) }
                                        val tip = if (labCount != null) {
                                            "课表已更新：" + merged.size + " 条，实验课 " + labCount + " 条"
                                        } else {
                                            "课表已更新：" + merged.size + " 条（实验课表未刷新：" +
                                                (lab.exceptionOrNull()?.message ?: "未知原因") + "）"
                                        }
                                        val examTip = if (examResult.isFailure || examResult.getOrNull().isNullOrEmpty())
                                            "；考试安排未更新，保留上次数据" else ""
                                        android.widget.Toast.makeText(ctx, tip + examTip, android.widget.Toast.LENGTH_LONG).show()
                                    }.onFailure { e ->
                                        val msg = when (e) {
                                            is com.hpu.timetable.data.auth.ReloginCooldownException ->
                                                "刚重登过，" + e.waitSeconds + " 秒后再试"
                                            is com.hpu.timetable.data.auth.SessionExpiredException ->
                                                "登录已过期，请重登"
                                            else -> "更新失败：" + (e.message ?: "未知错误")
                                        }
                                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    refreshing = false
                                }
                            }
                        },
                    )
                } }
            }
        }
        }
    }
    }
    }
    if (askLab) {
        AskLabDialog(
            onYes = { askLab = false; runLabImport() },
            onNo = { askLab = false },
        )
    }
    askWebLab?.let { reason ->
        AlertDialog(
            onDismissRequest = { askWebLab = null },
            title = { Text("自动导入失败") },
            text = { Text("原因：" + reason + "\n改用网页导入？") },
            confirmButton = {
                TextButton(onClick = { askWebLab = null; subPage = null; importing = true }) { Text("网页导入") }
            },
            dismissButton = {
                TextButton(onClick = { askWebLab = null }) { Text("算了") }
            },
        )
    }
    if (askExactAlarm) {
        AlertDialog(
            onDismissRequest = { askExactAlarm = false },
            title = { Text("开启精确提醒") },
            text = {
                Text(
                    "精确提醒需要「闹钟和提醒」权限，否则" +
                        "可能晚几分钟。"
                )
            },
            confirmButton = {
                TextButton(onClick = { askExactAlarm = false; openExactAlarmSettings(ctx) }) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { askExactAlarm = false }) { Text("以后再说") }
            },
        )
    }
    if (sessionExpired) {
        AlertDialog(
            onDismissRequest = { sessionExpired = false },
            title = { Text("登录验证未通过") },
            text = {
                Text(
                    "刚才的请求未通过登录验证。可以稍后重试，或前往登录页。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sessionExpired = false
                    state = UiState.NeedLogin
                    tab = 0
                }) { Text("去登录") }
            },
            dismissButton = {
                TextButton(onClick = { sessionExpired = false }) { Text("以后再说") }
            },
        )
    }
}

@Composable
private fun AskLabDialog(onYes: () -> Unit, onNo: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onNo,
        title = { Text("导入实验课表？") },
        text = {
            Text("实验平台的实验课现在一起导入吗？")
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onYes) { Text("导入") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onNo) { Text("以后再说") }
        },
    )
}

/** 打开系统的「闹钟和提醒」授权页（Android 12+ 精确闹钟） */
private fun openExactAlarmSettings(ctx: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            ctx.startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:" + ctx.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            androidx.compose.foundation.layout.Spacer(Modifier.size(14.dp))
            Text(
                "正在读取课表…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}