package com.hpu.timetable.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.timetable.data.Settings
import com.hpu.timetable.data.TimetableRepository
import com.hpu.timetable.notify.Notify
import com.hpu.timetable.notify.NotifyScheduler
import com.hpu.timetable.notify.SchedulePlanner
import com.hpu.timetable.ui.pressable

/**
 * 设置页：课表/考试的导入手段和退登录都收在这里。
 * 每一项都带一句说明，避免「点了不知道会发生什么」。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    themeMode: String = "system",
    onThemeChange: (String) -> Unit = {},
    onImportLab: () -> Unit,
    onImportLabWeb: () -> Unit,
    onImportJson: (String) -> Unit,
    onPasteExam: (String) -> Unit,
    onLogout: () -> Unit,
    onOpenAbout: () -> Unit = {},
) {
    var pastingLab by remember { mutableStateOf(false) }
    var labInput by remember { mutableStateOf("") }
    var pastingExam by remember { mutableStateOf(false) }
    var examInput by remember { mutableStateOf("") }
    var confirmingLogout by remember { mutableStateOf(false) }
    var pickingTheme by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 从相册选一张图，复制到 filesDir 当「我的」页背景
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { saveProfileBackground(ctx, uri) }
                    .onSuccess { toast(ctx, "背景图已更新") }
                    .onFailure { toast(ctx, "换图失败：" + (it.message ?: "未知错误")) }
            }
        }
    }

    // 导出日志：用户选保存位置，写入脱敏日志快照
    val logExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        ctx.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(com.hpu.timetable.FileLog.snapshot().toByteArray())
                        } ?: error("无法写入文件")
                    }
                }.onSuccess { toast(ctx, "日志已导出") }
                    .onFailure { toast(ctx, "导出失败：" + (it.message ?: "未知错误")) }
            }
        }
    }

    // 通知设置
    var classOn by remember { mutableStateOf(Settings.classReminderEnabled(ctx)) }
    var lead by remember { mutableIntStateOf(Settings.classLeadMinutes(ctx)) }
    var weekOn by remember { mutableStateOf(Settings.weekSummaryEnabled(ctx)) }
    var weekDay by remember { mutableIntStateOf(Settings.weekSummaryDay(ctx)) }
    var weekMinute by remember { mutableIntStateOf(Settings.weekSummaryMinute(ctx)) }
    var pickingLead by remember { mutableStateOf(false) }
    var pickingWeekTime by remember { mutableStateOf(false) }

    fun replan() = runCatching { NotifyScheduler.rescheduleAll(ctx) }

    // 从系统授权页回来时刷新「白名单」状态显示
    var bgTick by remember { mutableIntStateOf(0) }
    DisposableEffect(ctx) {
        val owner = ctx as? androidx.lifecycle.LifecycleOwner
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) bgTick++
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
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
            "设置",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(6.dp))

            SectionTitle("通知")
            SwitchRow(
                title = "上课提醒",
                desc = "课前提醒，显示课程和教室",
                checked = classOn,
                onChange = { on ->
                    classOn = on
                    Settings.setClassReminderEnabled(ctx, on)
                    replan()
                },
            )
            AnimatedVisibility(
                visible = classOn,
                enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                    expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                    shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()),
            ) {
                SettingRow(
                    title = "提前时间",
                    value = lead.toString() + " 分钟",
                    onClick = { pickingLead = true },
                )
            }
            SwitchRow(
                title = "每周课表概览",
                desc = "周末提醒下周的课和考试",
                checked = weekOn,
                onChange = { on ->
                    weekOn = on
                    Settings.setWeekSummaryEnabled(ctx, on)
                    replan()
                },
            )
            AnimatedVisibility(
                visible = weekOn,
                enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                    expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                    shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()),
            ) {
                SettingRow(
                    title = "提醒时间",
                    value = weekDayLabel(weekDay) + " " + minuteLabel(weekMinute),
                    onClick = { pickingWeekTime = true },
                )
            }

            // 保活两件套：闹钟本身是精确的，但小米等 ROM 的省电策略会延迟/吞掉广播，
            // 不进白名单、不开自启，提醒就可能迟到甚至丢
            val batteryOk = remember(bgTick) { isIgnoringBattery(ctx) }
            SettingRow(
                title = "电池优化白名单",
                desc = if (batteryOk) "闹钟可准时响" else "允许后锁屏也能准时提醒",
                value = if (batteryOk) "已允许" else "去允许",
                onClick = { requestIgnoreBattery(ctx) },
            )
            SettingRow(
                title = "自启动管理",
                desc = "清后台后提醒不丢",
                onClick = { openAutoStartSettings(ctx) },
            )

            Spacer(Modifier.height(14.dp))
            SectionTitle("外观")
            SettingRow(
                title = "深色模式",
                desc = when (themeMode) {
                    "light" -> "浅色"
                    "dark" -> "深色"
                    else -> "跟随系统"
                },
                onClick = { pickingTheme = true },
            )
            SettingRow(
                title = "更换背景图",
                desc = "用在「我的」页顶部",
                onClick = { bgPicker.launch("image/*") },
            )
            SettingRow(
                title = "恢复默认背景",
                onClick = {
                    runCatching { profileBackgroundFile(ctx).delete() }
                    toast(ctx, "已恢复默认背景")
                },
            )

            SectionTitle("实验课表")
            val labState = TimetableRepository.labSemesterState(ctx)
            val labSavedAt = TimetableRepository.labSavedSemester(ctx)
            SettingRow(
                title = "自动导入",
                desc = when (labState) {
                    TimetableRepository.LabSemester.NONE -> "未导入"
                    TimetableRepository.LabSemester.CURRENT -> "已导入（本学期）"
                    TimetableRepository.LabSemester.STALE ->
                        "已导入，但属于 " + labSavedAt + " 学期，需重新导入"
                },
                onClick = onImportLab,
            )
            SettingRow(
                title = "网页导入",
                desc = "自动导入失败时用",
                onClick = onImportLabWeb,
            )
            SettingRow(
                title = "粘贴 JSON",
                desc = "自动导入失败时用",
                onClick = { labInput = ""; pastingLab = true },
            )

            Spacer(Modifier.height(14.dp))
            SectionTitle("考试安排")
            SettingRow(
                title = "粘贴页面源码",
                desc = "自动获取失败时用",
                onClick = { examInput = ""; pastingExam = true },
            )

            Spacer(Modifier.height(14.dp))
            SectionTitle("诊断")
            SettingRow(
                title = "导出日志",
                desc = "出问题时导出排查",
                onClick = {
                    val stamp = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                    logExporter.launch("hpupocket-log-$stamp.txt")
                },
            )

            // 开发者选项只在 dev 分支开放（BuildConfig.DEV_OPTIONS）
            if (com.hpu.timetable.BuildConfig.DEV_OPTIONS) {
                Spacer(Modifier.height(14.dp))
                SectionTitle("开发者选项")
                SettingRow(
                    title = "测试周概览通知",
                    onClick = {
                        val s = SchedulePlanner.nextWeekSummary(ctx)
                        if (s == null) toast(ctx, "下周没课，正式推送时不会发") else {
                            Notify.postWeekSummary(ctx, s)
                            toast(ctx, "已发送")
                        }
                    },
                )
                SettingRow(
                    title = "测试上课提醒通知",
                    onClick = {
                        val slot = SchedulePlanner.upcomingClasses(ctx).firstOrNull()
                        if (slot != null) {
                            Notify.postClassReminder(ctx, slot.course, slot.start.toLocalTime(), lead, slot.course.week)
                        } else {
                            val demo = com.hpu.timetable.data.CourseInstance(
                                week = 1, day = 1, big = 1, course = "示例课程",
                                teacher = "示例老师", room = "示例教室 101", exp = "",
                            )
                            Notify.postClassReminder(ctx, demo, java.time.LocalTime.of(8, 0), lead, 1)
                        }
                        toast(ctx, "已发送")
                    },
                )
                SettingRow(
                    title = "模拟会话过期",
                    desc = "清会话，保留缓存和密码",
                    onClick = {
                        com.hpu.timetable.data.auth.PersistCookieJar(ctx).clear()
                        Settings.setLastSessionCheck(ctx, 0L) // 已无启动检测，保留以兼容旧数据
                        toast(ctx, "已清除，重开 App 生效")
                    },
                )
                SettingRow(
                    title = "测试验证码识别",
                    onClick = {
                        scope.launch {
                            val text = runCatching {
                                val cas = com.hpu.timetable.data.auth.CasClient(
                                    com.hpu.timetable.data.auth.PersistCookieJar(ctx)
                                )
                                cas.openLoginPage()
                                val cap = cas.fetchCaptcha()
                                val bytes = android.util.Base64.decode(
                                    cap.base64.split(",").last(), android.util.Base64.DEFAULT
                                )
                                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                bmp?.let { com.hpu.timetable.data.auth.CaptchaSolver.solve(it) }
                            }.getOrNull()
                            com.hpu.timetable.AppLog.d("HPU_CAPTCHA", "solve=" + text)
                            toast(ctx, if (text != null) "识别结果：$text" else "没认出来，会退回手动")
                        }
                    },
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionTitle("关于")
            SettingRow(
                title = "关于河理课表",
                desc = "版本与数据来源",
                onClick = onOpenAbout,
            )

            Spacer(Modifier.height(14.dp))
            SectionTitle("账号")
            SettingRow(
                title = "退出登录",
                desc = "清除登录状态和课表缓存",
                danger = true,
                onClick = { confirmingLogout = true },
            )

            Spacer(Modifier.height(30.dp))
        }
    }

    if (pastingLab) {
        AlertDialog(
            onDismissRequest = { pastingLab = false },
            title = { Text("粘贴实验平台课表 JSON", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        "实验平台课表页 → F12 → Network → listStuYearterm → 复制响应",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = labInput,
                        onValueChange = { labInput = it },
                        label = { Text("JSON") },
                        singleLine = false,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (labInput.isNotBlank()) onImportJson(labInput.trim())
                    pastingLab = false
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { pastingLab = false }) { Text("取消") } },
        )
    }

    if (pastingExam) {
        AlertDialog(
            onDismissRequest = { pastingExam = false },
            title = { Text("粘贴考试页面 HTML", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        "教务「我的考试」→ 查看网页源代码 → 全选复制",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = examInput,
                        onValueChange = { examInput = it },
                        label = { Text("HTML") },
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (examInput.isNotBlank()) onPasteExam(examInput)
                    pastingExam = false
                }) { Text("解析") }
            },
            dismissButton = { TextButton(onClick = { pastingExam = false }) { Text("取消") } },
        )
    }

    if (pickingLead) {
        AlertDialog(
            onDismissRequest = { pickingLead = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = { Text("提前多久提醒", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 20, 30).forEach { min ->
                        val on = min == lead
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (on) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable {
                                    lead = min
                                    Settings.setClassLeadMinutes(ctx, min)
                                    replan()
                                    pickingLead = false
                                }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                min.toString() + " 分钟",
                                fontSize = 14.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (on) Text("✓", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingLead = false }) { Text("关闭") } },
        )
    }

    if (pickingWeekTime) {
        AlertDialog(
            onDismissRequest = { pickingWeekTime = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = { Text("概览提醒时间", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(6, 7).forEach { d ->
                            val on = d == weekDay
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .clickable {
                                        weekDay = d
                                        Settings.setWeekSummarySchedule(ctx, d, weekMinute)
                                        replan()
                                    }
                                    .padding(vertical = 11.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    weekDayLabel(d),
                                    fontSize = 13.sp,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("几点提醒", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items((6..22).toList()) { h ->
                            val m = h * 60
                            val on = m == weekMinute
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .clickable {
                                        weekMinute = m
                                        Settings.setWeekSummarySchedule(ctx, weekDay, m)
                                        replan()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    minuteLabel(m),
                                    fontSize = 14.sp,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                if (on) Text("✓", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingWeekTime = false }) { Text("关闭") } },
        )
    }

    if (pickingTheme) {
        AlertDialog(
            onDismissRequest = { pickingTheme = false },
            title = { Text("深色模式") },
            text = {
                Column {
                    listOf(
                        "system" to "跟随系统",
                        "light" to "浅色",
                        "dark" to "深色",
                    ).forEach { (mode, label) ->
                        TextButton(onClick = {
                            onThemeChange(mode)
                            pickingTheme = false
                        }) {
                            Text(if (mode == themeMode) "✓ " + label else label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingTheme = false }) { Text("关闭") }
            },
        )
    }

    if (confirmingLogout) {
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            title = { Text("退出登录？") },
            text = { Text("清除登录状态和课表缓存，实验课表数据保留。") },
            confirmButton = {
                TextButton(onClick = { confirmingLogout = false; onLogout() }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { confirmingLogout = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    desc: String? = null,
    value: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (danger) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (danger) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    desc,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Text(
                value,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(2.dp))
            Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 带开关的设置项 */
@Composable
private fun SwitchRow(
    title: String,
    desc: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .pressable(onClick = { onChange(!checked) })
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    desc,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun weekDayLabel(day: Int): String = if (day == 6) "周六" else "周日"

private fun minuteLabel(minute: Int): String =
    String.format("%02d:%02d", (minute / 60).coerceIn(0, 23), (minute % 60).coerceIn(0, 59))

private fun toast(ctx: android.content.Context, msg: String) {
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
}

/** 是否已在电池优化白名单里 */
private fun isIgnoringBattery(ctx: android.content.Context): Boolean =
    ctx.getSystemService(android.os.PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false

/** 弹系统「忽略电池优化」授权框；个别机型没有这个框就退回系统设置页 */
private fun requestIgnoreBattery(ctx: android.content.Context) {
    val ok = runCatching {
        ctx.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:" + ctx.packageName),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
    if (!ok) {
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast(ctx, "请手动到系统设置里允许后台运行") }
    }
}

/** 按品牌直达各家「自启动管理」页；认不出的机型退回应用详情页 */
private fun openAutoStartSettings(ctx: android.content.Context) {
    val brand = android.os.Build.BRAND.lowercase()
    val maker = android.os.Build.MANUFACTURER.lowercase()
    val candidates = when {
        brand in listOf("xiaomi", "redmi", "poco") || maker == "xiaomi" -> listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
        brand in listOf("huawei", "honor") || maker in listOf("huawei", "honor") -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        )
        brand in listOf("oppo", "oneplus", "realme") || maker in listOf("oppo", "oneplus", "realme") -> listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        )
        brand in listOf("vivo", "iqoo") || maker == "vivo" -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManagerActivity",
        )
        else -> emptyList() // 三星/Pixel 等没有自启动概念，直接退应用详情页
    }
    for ((pkg, cls) in candidates) {
        val ok = runCatching {
            ctx.startActivity(
                android.content.Intent().setClassName(pkg, cls)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (ok) return
    }
    runCatching {
        ctx.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + ctx.packageName),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { toast(ctx, "请手动到系统设置里允许自启动") }
}



