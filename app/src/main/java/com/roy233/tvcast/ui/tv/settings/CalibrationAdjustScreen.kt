package com.roy233.tvcast.ui.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.tv.TvKeyHandler
import com.roy233.tvcast.ui.tv.player.PlaybackSeekAccelerator

/** SurfaceView 在宿主层绘制四角标记，此层只绘制遥控器操作提示。 */
@Composable
internal fun CalibrationAdjustScreen(state: TvUiState, actions: TvActions, interactive: Boolean = true) {
    val focus = remember { FocusRequester() }
    val seekAccelerator = remember { PlaybackSeekAccelerator() }
    LaunchedEffect(interactive) { if (interactive) focus.requestFocus() }
    val inputModifier = if (interactive) Modifier
        .onPreviewKeyEvent { TvKeyHandler.handle(it, state, actions, true, seekAccelerator) }
        .focusRequester(focus)
        .focusable() else Modifier
    Box(
        modifier = Modifier.fillMaxSize().then(inputModifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(24.dp))
                .padding(horizontal = 38.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                text = "画面校准",
                style = androidx.compose.ui.text.TextStyle(
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CornerIndicator(1, state.calibration.corner)
                CornerIndicator(2, state.calibration.corner)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CornerIndicator(4, state.calibration.corner)
                CornerIndicator(3, state.calibration.corner)
            }
            Spacer(Modifier.height(22.dp))
            BasicText(
                text = "方向键移动当前角，让角上的两条标记线刚好贴住可见边缘",
                style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 19.sp, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = "确认键切换下一个角 · 返回键取消",
                style = androidx.compose.ui.text.TextStyle(
                    color = Color.LightGray,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

@Composable
private fun CornerIndicator(number: Int, selected: Int) {
    val active = number == selected
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(if (active) Color(0xFFB6F2B7) else Color(0xFF353A40), CircleShape)
            .border(2.dp, if (active) Color.White else Color.LightGray, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = number.toString(),
            style = androidx.compose.ui.text.TextStyle(
                color = if (active) Color.Black else Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}
