package com.roy233.tvcast.ui.tv

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import com.roy233.tvcast.ui.model.FocusRequest
import com.roy233.tvcast.ui.model.UiFocusAnchor
import kotlinx.coroutines.delay

/**
 * 页面声明的一个可恢复焦点锚点。
 *
 * @property anchor 锚点标识，由页面自己定义取值域。
 * @property requester 锚点对应的焦点请求对象。
 * @property itemIndex 锚点位于懒列表时的条目下标，恢复前先把该条目滚入组合树。
 */
internal data class FocusBinding(
    val anchor: UiFocusAnchor,
    val requester: FocusRequester,
    val itemIndex: Int? = null,
)

/**
 * 统一的焦点恢复入口：只有当栈顶锚点属于本界面时才请求焦点。
 *
 * 界面只声明自己能接住的锚点，因此不可见或正在退场的界面永远不会被请求，
 * 也就不可能出现“焦点被离场组件抢走”。
 *
 * @param request 栈顶焦点请求，revision 或锚点变化都会重新恢复。
 * @param bindings 本界面能接住的锚点。
 * @param listState 目标位于懒列表时提供，用于先把目标滚入组合树。
 * @param contentKey 本界面可聚焦内容的指纹。内容变化会替换掉持有焦点的节点，
 *   因此指纹一变就重新恢复栈顶锚点；界面把“哪些状态决定自己的可聚焦结构”声明在这里。
 */
@Composable
internal fun RestoreFocus(
    request: FocusRequest,
    bindings: List<FocusBinding>,
    listState: LazyListState? = null,
    contentKey: Any? = null,
) {
    LaunchedEffect(request.revision, request.anchor, contentKey) {
        val binding = bindings.firstOrNull { it.anchor == request.anchor } ?: return@LaunchedEffect
        val itemIndex = binding.itemIndex
        FocusDiagnostics.focusRequest(request, itemIndex)
        if (itemIndex != null && listState != null) {
            listState.scrollToItem(itemIndex)
        }
        // 目标条目刚被滚入组合树时还没有布局节点，等一帧再请求。
        withFrameNanos { }
        repeat(FOCUS_REQUEST_ATTEMPTS) { attempt ->
            val focused = runCatching { binding.requester.requestFocus() }.getOrDefault(false)
            if (focused) {
                FocusDiagnostics.focusResult(request.anchor, attempt + 1, true)
                return@LaunchedEffect
            }
            delay(FOCUS_RETRY_INTERVAL_MS)
        }
        FocusDiagnostics.focusResult(request.anchor, FOCUS_REQUEST_ATTEMPTS, false)
    }
}

private const val FOCUS_REQUEST_ATTEMPTS = 4
private const val FOCUS_RETRY_INTERVAL_MS = 80L
