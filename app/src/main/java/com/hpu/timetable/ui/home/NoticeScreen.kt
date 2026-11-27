package com.hpu.timetable.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.timetable.data.Notice
import com.hpu.timetable.ui.pressable

@Composable
fun NoticeScreen(
    notices: List<Notice>,
    readIds: Set<String>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onRead: (String) -> Unit,
) {
    BackHandler { onBack() }
    var selected by remember { mutableStateOf<Notice?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", fontSize = 14.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = !refreshing) {
                Text(if (refreshing) "刷新中…" else "刷新", fontSize = 13.sp)
            }
        }
        Text(
            "公告",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        if (notices.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(top = 80.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无公告", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(notices, key = { it.id }) { notice ->
                    NoticeRow(
                        notice = notice,
                        unread = notice.id !in readIds,
                        onClick = {
                            if (notice.id !in readIds) onRead(notice.id)
                            selected = notice
                        },
                    )
                }
            }
        }
    }

    selected?.let { notice ->
        NoticeDetailDialog(notice = notice, onDismiss = { selected = null })
    }
}

@Composable
private fun NoticeRow(notice: Notice, unread: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (unread) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                notice.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (notice.date.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    notice.date,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (notice.body.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                notice.body.replace("\n", " "),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoticeDetailDialog(notice: Notice, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val hasLink = notice.link.startsWith("http://") || notice.link.startsWith("https://")
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = {
            Text(notice.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (notice.date.isNotBlank()) {
                    Text(
                        notice.date,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    notice.body.ifBlank { "暂无内容" },
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
        dismissButton = {
            if (hasLink) {
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(notice.link),
                            )
                        )
                    }
                    onDismiss()
                }) { Text("打开链接") }
            }
        },
    )
}