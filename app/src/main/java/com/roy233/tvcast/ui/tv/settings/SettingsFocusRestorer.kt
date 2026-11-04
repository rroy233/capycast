package com.roy233.tvcast.ui.tv.settings

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import com.roy233.tvcast.ui.model.SettingsFocusAnchor
import com.roy233.tvcast.ui.model.SettingsFocusRequest
import kotlinx.coroutines.delay

/** 页面声明的可恢复焦点锚点及其所在懒列表项。 */
internal data class SettingsFocusBinding(
    val anchor: SettingsFocusAnchor,
    val itemIndex: Int,
    val requester: FocusRequester,
)

/**
 * 统一恢复设置页焦点：先令目标懒列表项进入组合树，再在布局帧后请求焦点。
 *
 * 这是设置路由的唯一焦点恢复入口；页面只提供锚点映射，不推断前一页面或自行延迟。
 */
@Composable
internal fun RestoreSettingsFocus(
    request: SettingsFocusRequest,
    listState: LazyListState,
    bindings: List<SettingsFocusBinding>,
) {
    LaunchedEffect(request.revision) {
        val binding = bindings.firstOrNull { it.anchor == request.anchor } ?: return@LaunchedEffect
        listState.scrollToItem(binding.itemIndex)
        withFrameNanos { }
        repeat(FOCUS_REQUEST_ATTEMPTS) {
            if (runCatching { binding.requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(FOCUS_RETRY_INTERVAL_MS)
        }
    }
}

private const val FOCUS_REQUEST_ATTEMPTS = 4
private const val FOCUS_RETRY_INTERVAL_MS = 80L
