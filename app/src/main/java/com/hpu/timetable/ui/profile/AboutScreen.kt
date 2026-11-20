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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.timetable.ui.pressable

private const val REPO_URL = "https://github.com/ZYH-officials/HpuPocket"
private const val RELEASES_URL = "https://github.com/ZYH-officials/HpuPocket/releases"
private const val ISSUES_URL = "https://github.com/ZYH-officials/HpuPocket/issues"
private const val LICENSE_URL = "https://github.com/ZYH-officials/HpuPocket/blob/main/LICENSE"
private const val ZZUPOCKET_URL = "https://github.com/3beuml/ZZUPocket"

/** 读桌面启动图标（自适应图标也能画出来），失败则不显示图标 */
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
 * 关于页，结构参考 HuaweiPods：居中图标 + 版本，下面分组小标题配圆角卡片行。
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
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("‹ 返回", fontSize = 14.sp) }
            }

            Column(Modifier.padding(horizontal = 20.dp)) {
                // Hero：图标 + 应用名 + 版本，和 HuaweiPods 关于页一样放中轴上
                Column(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    icon?.let {
                        Image(
                            bitmap = it,
                            contentDescription = "应用图标",
                            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)),
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    Text("河理课表", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (version.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "v$version",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "把学校分散的网页系统，装进一个原生课表 App",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                SectionTitle("项目")
                GroupCard {
                    LinkRow("GitHub 仓库", "ZYH-officials/HpuPocket") {
                        uriHandler.openUri(REPO_URL)
                    }
                    RowDivider()
                    LinkRow("下载与更新", "在 GitHub Releases 获取新版本") {
                        uriHandler.openUri(RELEASES_URL)
                    }
                    RowDivider()
                    LinkRow("问题反馈", "在 GitHub Issues 报告问题") {
                        uriHandler.openUri(ISSUES_URL)
                    }
                    RowDivider()
                    LinkRow("开源协议", "MIT License") {
                        uriHandler.openUri(LICENSE_URL)
                    }
                }

                SectionTitle("数据与隐私")
                GroupCard {
                    BulletList(
                        listOf(
                            "密码经系统 Keystore 加密，只存本机；可在登录页关闭记住密码",
                            "课表、成绩等缓存只存本机，没有自建服务器，不上传任何数据",
                            "退出登录清会话与缓存，卸载即全清",
                            "只读查询，不代替你提交任何操作",
                        ),
                    )
                }

                SectionTitle("数据从哪来")
                GroupCard {
                    BulletList(
                        listOf(
                            "教务系统：课表、考试、成绩、空闲教室",
                            "实验平台：实验课表与上课地点，支持网页导入兜底",
                            "作业平台 smartestu.cn（第三方）：待交作业，账号单独填写",
                            "一言 hitokoto.cn：首页短句，失败时用内置句",
                        ),
                    )
                }

                SectionTitle("致谢")
                GroupCard {
                    LinkRow("ZZUPocket", "协议与架构思路参考（MIT）") {
                        uriHandler.openUri(ZZUPOCKET_URL)
                    }
                    RowDivider()
                    BulletList(
                        listOf(
                            "桌面小组件视觉参考拾光课程表",
                            "构建于 Jetpack Compose、Material 3、OkHttp",
                        ),
                    )
                }

                Text(
                    "MIT 协议开源 · 数据以教务系统为准",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 32.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 8.dp),
    )
}

/** 一组设置项共用的圆角卡片 */
@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        content()
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

/** 可点的一行：标题 + 说明 + 右侧箭头 */
@Composable
private fun LinkRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(
                summary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BulletList(items: List<String>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        items.forEach { text ->
            Text(
                "· $text",
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}