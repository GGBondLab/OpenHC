package io.github.gcbond.openhc.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.platform.media.VideoDecoder
import io.github.gcbond.openhc.projection.ProjectionSurface

/**
 * 全屏投屏页：手机画面铺满屏幕（藏起状态栏和导航栏），触摸交给 [onInput]。
 * 返回键回首页、连接保持（由调用方处理），首页可以再点「回到投屏画面」。
 */
@Composable
internal fun ProjectionScreen(
    state: ConnectionUiState,
    decoder: VideoDecoder?,
    touchResets: Int,
    onInput: (HiSightInput) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ProjectionSurface(
            decoder = decoder,
            videoWidth = state.videoWidth,
            videoHeight = state.videoHeight,
            onInput = onInput,
            modifier = Modifier.fillMaxSize(),
            fullScreen = io.github.gcbond.openhc.connection.DisplayArea.activeFullScreen,
            touchResetKey = touchResets,
        )
        if (!state.firstFrame) {
            val transition = rememberInfiniteTransition(label = "projection_wait")
            val dotAlpha by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "wait_dot",
            )
            val pillShape = RoundedCornerShape(percent = 50)
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(pillShape)
                    .background(Color(0xD9181B22))
                    .border(1.dp, Color(0x1FFFFFFF), pillShape)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .alpha(dotAlpha)
                        .clip(CircleShape)
                        .background(Color(0xFF0A84FF)),
                )
                Text(
                    stringResource(R.string.projection_waiting_frame),
                    style = UiType.topButton,
                    color = Color(0xFFF3F4F6),
                )
            }
        }
        state.decoderProblem?.let {
            val warnShape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .clip(warnShape)
                    .background(Color(0xDD181B22))
                    .border(1.dp, Color(0x55FFB340), warnShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Glyphs.Alert, contentDescription = null, tint = Color(0xFFFFB340), modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.projection_decoder_problem, it),
                    style = UiType.hint,
                    color = Color(0xFFF3F4F6),
                )
            }
        }
    }
}
