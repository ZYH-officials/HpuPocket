package com.hpu.timetable.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 按下去轻微缩小 + 正常点击回调。
 * 用同一个 interactionSource 同时驱动「按压动画」和「水波纹」，两者不会打架。
 * 动画用 MD3 Expressive 的官方弹簧（fastSpatialSpec），松手有回弹。
 */
@Composable
fun Modifier.pressable(onClick: () -> Unit, scale: Float = 0.97f): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val value by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "press",
    )
    return this
        .graphicsLayer {
            scaleX = value
            scaleY = value
        }
        .clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            onClick = onClick,
        )
}
