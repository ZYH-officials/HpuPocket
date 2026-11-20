package com.hpu.timetable.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.timetable.data.Settings
import com.hpu.timetable.data.TimetableRepository
import com.hpu.timetable.ui.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

private const val PROFILE_MAX_EDGE = 1080
private const val PROFILE_MAX_PIXELS = 2_000_000

/** 「我的」页顶部横幅用的背景图：优先用户自己选的，否则用打包进来的默认图 */
fun profileBackgroundFile(ctx: android.content.Context) = File(ctx.filesDir, "profile_bg.jpg")

private fun readProfileBitmap(ctx: android.content.Context): android.graphics.Bitmap? = runCatching {
    val custom = profileBackgroundFile(ctx)
    if (custom.exists()) {
        android.graphics.BitmapFactory.decodeFile(custom.absolutePath)
    } else {
        ctx.assets.open("profile_bg.jpg").use { android.graphics.BitmapFactory.decodeStream(it) }
    }
}.getOrNull()

suspend fun saveProfileBackground(ctx: android.content.Context, uri: android.net.Uri) =
    withContext(Dispatchers.IO) {
        val source = File.createTempFile("profile-bg-source-", ".img", ctx.cacheDir)
        val output = File.createTempFile("profile-bg-output-", ".jpg", ctx.cacheDir)
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { outputStream -> input.copyTo(outputStream) }
            } ?: error("无法读取图片")

            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(source.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式不受支持" }

            var sample = 1
            while (
                maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > PROFILE_MAX_EDGE * 2 ||
                (bounds.outWidth.toLong() * bounds.outHeight.toLong()) /
                    (sample.toLong() * sample.toLong()) > PROFILE_MAX_PIXELS * 4L
            ) {
                sample *= 2
            }
            val decoded = android.graphics.BitmapFactory.decodeFile(
                source.absolutePath,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: error("图片解码失败")

            val orientation = runCatching {
                android.media.ExifInterface(source.absolutePath).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
            val oriented = applyExifOrientation(decoded, orientation)
            if (oriented !== decoded) decoded.recycle()

            val longest = maxOf(oriented.width, oriented.height)
            val scaled = if (longest > PROFILE_MAX_EDGE) {
                val ratio = PROFILE_MAX_EDGE.toFloat() / longest.toFloat()
                android.graphics.Bitmap.createScaledBitmap(
                    oriented,
                    (oriented.width * ratio).toInt().coerceAtLeast(1),
                    (oriented.height * ratio).toInt().coerceAtLeast(1),
                    true,
                ).also { if (it !== oriented) oriented.recycle() }
            } else oriented

            FileOutputStream(output).use { stream ->
                check(scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, stream)) {
                    "图片保存失败"
                }
            }
            if (!output.renameTo(profileBackgroundFile(ctx))) {
                Files.move(
                    output.toPath(),
                    profileBackgroundFile(ctx).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            if (!scaled.isRecycled) scaled.recycle()
        } finally {
            source.delete()
            output.delete()
        }
    }

private fun applyExifOrientation(
    bitmap: android.graphics.Bitmap,
    orientation: Int,
): android.graphics.Bitmap {
    val matrix = android.graphics.Matrix()
    when (orientation) {
        android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }
    return android.graphics.Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        matrix,
        true,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileScreen(
    name: String,
    account: String,
    courseCount: Int,
    onSemesterChanged: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    refreshing: Boolean = false,
) {
    val ctx = LocalContext.current
    var editingStart by remember { mutableStateOf(false) }
    var startInput by remember { mutableStateOf(TimetableRepository.semesterStart.toString()) }
    val backgroundVersion = profileBackgroundFile(ctx).lastModified()
    val bg by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = ctx,
        key2 = backgroundVersion,
    ) {
        value = withContext(Dispatchers.IO) { readProfileBitmap(ctx) }
    }
    val version = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    val background = bg

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(14.dp))

        // 头像横幅：背景图 + 底部渐变遮罩，名字压在图上看得很清
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            if (background != null) {
                Image(
                    bitmap = background.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color(0xD9000000),
                        )
                    ),
            )
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xE6FFFFFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (name.ifBlank { account }).take(1).ifBlank { "?" },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        name.ifBlank { "未登录" },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (account.isBlank()) "学号未获取" else account,
                        fontSize = 12.sp,
                        color = Color(0xE6FFFFFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            InfoRow("课程", "$courseCount 条")
            InfoRow("当前周次", "第 ${TimetableRepository.currentWeek()} 周")
            InfoRow(
                "学期起点",
                TimetableRepository.semesterStart.toString(),
                onClick = {
                    startInput = TimetableRepository.semesterStart.toString()
                    editingStart = true
                },
            )
            InfoRow("课表来源", "教务 + 实验平台")
        }

        Spacer(Modifier.height(14.dp))
        // 真 Button：expressive 主题自动给形状、按压弹簧和状态层；
        // 刷新中禁用但配色不变，避免默认禁用灰盖掉 tertiary 色
        Button(
            onClick = onRefresh,
            enabled = !refreshing,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                disabledContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                disabledContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        ) {
            if (refreshing) {
                LoadingIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text("更新中…", fontSize = 14.sp)
            } else {
                Text("更新课表", fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        NavRow("设置", "导入、通知、账号、背景图", onOpenSettings)

        Spacer(Modifier.height(20.dp))
        Text(
            "河理课表 " + version,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }

    if (editingStart) {
        AlertDialog(
            onDismissRequest = { editingStart = false },
            title = { Text("学期第 1 周周一") },
            text = {
                Column {
                    Text(
                        "格式 2026-08-31",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = startInput,
                        onValueChange = { startInput = it },
                        singleLine = true,
                        label = { Text("日期") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val d = LocalDate.parse(startInput.trim())
                        require(d.dayOfWeek == java.time.DayOfWeek.MONDAY) { "请选择第 1 周的周一日期" }
                        Settings.setSemesterStart(ctx, d)
                        TimetableRepository.init(ctx)
                        editingStart = false
                        onSemesterChanged()
                    }.onFailure {
                        android.widget.Toast.makeText(ctx,
                            if (it is java.time.format.DateTimeParseException) "请输入有效日期，例如 2026-08-31" else it.message ?: "日期保存失败",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingStart = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun NavRow(title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (onClick != null) {
            Text(" ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
