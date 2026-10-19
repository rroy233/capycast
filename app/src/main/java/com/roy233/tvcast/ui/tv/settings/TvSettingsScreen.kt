package com.roy233.tvcast.ui.tv.settings

import com.roy233.tvcast.ui.tv.home.WhiteButton

import android.graphics.Bitmap
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.model.AudioOption
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.ui.model.RecentCast
import com.roy233.tvcast.ui.model.SettingsPage
import com.roy233.tvcast.ui.model.TrackOption
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvError
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.designsystem.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay

/**
 * 显示账号、设备和播放设置页面。
 *
 * @param state 当前界面状态。
 * @param actions 设置页面可执行的操作回调。
 */
@Composable
internal fun SettingsScreen(state: TvUiState, actions: TvActions) {
    if (state.settingsPage == SettingsPage.UPDATE) {
        UpdateSettingsScreen(state, actions)
        return
    }
    val doneFocus = remember { FocusRequester() }
    val view = LocalView.current
    LaunchedEffect(state.sidebarVisible) {
        if (!state.sidebarVisible) {
            var guard = 0
            while (!view.hasFocus() && guard++ < 15) delay(100)
            delay(220)
            for (i in 0 until 8) {
                if (runCatching { doneFocus.requestFocus() }.getOrDefault(false)) break
                delay(180)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 500.dp
        val hPad = if (compact) 32.dp else 80.dp
        val vPad = if (compact) 20.dp else 52.dp
        val titleSize = if (compact) 36.sp else 48.sp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = hPad, vertical = vPad)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = "设置",
                    style = TvType.title.copy(fontSize = titleSize),
                    modifier = Modifier.weight(1f),
                )
                WhiteButton(
                    text = "完成",
                    onClick = actions.onNavigateHome,
                    focusRequester = doneFocus,
                    compact = true,
                )
            }

            Spacer(Modifier.height(if (compact) 18.dp else 28.dp))

            SettingsSection("账号") {
                SettingsInfoRow("账号", state.accountName)
                SettingsInfoRow("会员", if (state.vip) "大会员" else "普通账号")
                FocusableRow(
                    title = "退出登录",
                    onClick = actions.onLogout,
                    enabled = state.loggedIn,
                    destructive = true,
                    showChevron = false,
                    rowHeight = 50.dp,
                )
            }
            SettingsSection("设备") {
                SettingsInfoRow("设备名称", "${state.deviceName} · ${state.roomName}")
                SettingsInfoRow("网络", "${state.network} · ${state.ip}")
                SettingsInfoRow("投屏地址", state.dlnaUrl)
            }
            SettingsSection("播放") {
                SettingsInfoRow("默认画质", "账号最高")
                SettingsInfoRow("通道", state.castMode)
                SettingsInfoRow("版本", state.version)
            }
            SettingsSection("应用") {
                FocusableRow(
                    title = "应用更新",
                    value = updateSummary(state),
                    onClick = actions.onOpenUpdateSettings,
                )
            }
        }
    }
}

/** 返回应用更新入口展示的摘要文本。 */
private fun updateSummary(state: TvUiState): String = when (state.update.status) {
    com.roy233.tvcast.feature.update.UpdateStatus.AVAILABLE ->
        "发现 ${state.update.availableUpdate?.versionName.orEmpty()}"
    com.roy233.tvcast.feature.update.UpdateStatus.READY_TO_INSTALL -> "可安装"
    com.roy233.tvcast.feature.update.UpdateStatus.DOWNLOADING -> "下载中"
    else -> "${state.update.channel.displayName}"
}

/** 显示一组带标题的设置项。 */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    BasicText(
        text = title,
        style = TvType.caption.copy(
            color = TvColors.textTertiary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        ),
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp, start = 2.dp),
    )
    Column { content() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.07f))
    )
}

/** 显示设置项的标题和值。 */
@Composable
private fun SettingsInfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = title,
            style = TvType.body.copy(color = TvColors.textSecondary, fontSize = 21.sp),
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        BasicText(
            text = value,
            style = TvType.caption.copy(color = TvColors.textTertiary, fontSize = 17.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

