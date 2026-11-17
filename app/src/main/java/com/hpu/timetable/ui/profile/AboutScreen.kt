package com.hpu.timetable.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 读桌面启动图标（自适应图标也能画出来），失败返回 null 由界面兜底 */
private fun loadAppIcon(ctx: android.content.Context): ImageBitmap? = runCatching {
    val drawable = ctx.packageManager.getApplicationIcon(ctx.packageName)
    val size = 192
    val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else size
    val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else size
    val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, w, h)
    drawable.draw(android.graphics.Canvas(bitmap))
    bitmap.asImageBitmap()
}.getOrNull()

/**
 * 关于页：图标 + 一句话定位，隐私/来源写成简短承诺，致谢放最后，版本号页尾小字。
 * 根容器用 Surface（深色模式下 Text 默认内容色才正确），并接管返回事件。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val version = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    val icon = remember { loadAppIcon(ctx) }

    BackHandler { onBack() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("‹ 返回", fontSize = 14.sp) }
            }

            Spacer(Modifier.height(10.dp))
            // 头部卡：真图标 + 应用名 + 一句话说清干什么
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = "应用图标",
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
                    )
                } else {
                    // 极端情况取不到图标时的兜底
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "课",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        "河理课表",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "把学校分散的网页系统，装进一个原生课表 App",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            AboutCard("数据与隐私") {
                Bullet("密码经系统 Keystore 加密，只存本机；可在登录页关闭记住密码")
                Bullet("课表、成绩等缓存只存本机，没有自建服务器，不上传任何数据")
                Bullet("退出登录清会话与缓存，卸载即全清")
                Bullet("只读查询，不代替你提交任何操作")
            }

            Spacer(Modifier.height(10.dp))
            AboutCard("数据从哪来") {
                Bullet("教务系统：课表、考试、成绩、空闲教室")
                Bullet("实验平台：实验课表与上课地点，支持网页导入兜底")
                Bullet("作业平台 smartestu.cn（第三方）：待交作业，账号单独填写")
                Bullet("一言 hitokoto.cn：首页短句，失败时用内置句")
            }

            Spacer(Modifier.height(10.dp))
            AboutCard("致谢") {
                Bullet("协议与架构思路参考开源项目 ZZUPocket（MIT）")
                TextButton(
                    onClick = { uriHandler.openUri("https://github.com/3beuml/ZZUPocket") },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.height(32.dp),
                ) { Text("github.com/3beuml/ZZUPocket", fontSize = 12.sp) }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "v$version · 数据以教务系统为准",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        "· $text",
        fontSize = 12.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}