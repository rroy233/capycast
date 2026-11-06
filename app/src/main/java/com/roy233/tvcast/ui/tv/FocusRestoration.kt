package com.roy233.tvcast.ui.tv

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import com.roy233.tvcast.ui.model.FocusRequest
import com.roy233.tvcast.ui.model.UiFocusAnchor

/**
 * 一个可恢复焦点目标的实际挂载状态。
 *
 * [FocusRequester] 只能在目标已进入焦点树后生效。页面切换、懒列表回收与动画都会
 * 让目标暂时离开组合树，因此恢复器不能以帧数或延时猜测时机，而是只消费已布局的目标。
 */
@Stable
class FocusTarget {
    internal val requester = FocusRequester()

    private var attached by mutableStateOf(false)
    private var laidOut by mutableStateOf(false)

    /** 目标仍在组合树中，且已经有可供焦点系统使用的布局节点。 */
    val isReady: Boolean
        get() = attached && laidOut

    internal fun onAttached() {
        attached = true
    }

    internal fun onLaidOut() {
        laidOut = true
    }

    internal fun onDetached() {
        attached = false
        laidOut = false
    }
}

/** 创建一个稳定的焦点目标；目标的挂载生命周期由 [focusTarget] 管理。 */
@Composable
internal fun rememberFocusTarget(): FocusTarget = remember { FocusTarget() }

/**
 * 将 [FocusTarget] 绑定到实际可聚焦节点。
 *
 * 必须与该节点的 `clickable` 或 `focusable` 放在同一修饰器链上，保证离开懒列表或
 * 页面时能同步撤销就绪状态。
 */
@Composable
internal fun Modifier.focusTarget(target: FocusTarget?): Modifier {
    if (target == null) return this
    DisposableEffect(target) {
        target.onAttached()
        onDispose(target::onDetached)
    }
    return focusRequester(target.requester)
        .onGloballyPositioned { target.onLaidOut() }
}

/**
 * 页面声明的一个可恢复焦点锚点。
 *
 * @property anchor 锚点标识，由页面自己定义取值域。
 * @property target 锚点对应的焦点目标及其真实挂载状态。
 * @property itemIndex 锚点位于懒列表时的条目下标，恢复前先把该条目滚入组合树。
 */
internal data class FocusBinding(
    val anchor: UiFocusAnchor,
    val target: FocusTarget,
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
 *   因此指纹一变就重新检查栈顶锚点；界面把“哪些状态决定自己的可聚焦结构”声明在这里。
 */
@Composable
internal fun RestoreFocus(
    request: FocusRequest,
    bindings: List<FocusBinding>,
    listState: LazyListState? = null,
    contentKey: Any? = null,
) {
    val matchedBinding = bindings.firstOrNull { it.anchor == request.anchor }
    var completedRequest by remember { mutableStateOf<Pair<FocusRequest, Any?>?>(null) }
    val requestKey = request to contentKey
    // 读取这个状态会让 effect 在目标完成布局或被回收时自动重启；没有任何时间假设。
    val targetReady = matchedBinding?.target?.isReady ?: false
    LaunchedEffect(request.revision, request.anchor, contentKey, targetReady) {
        val binding = bindings.firstOrNull { it.anchor == request.anchor } ?: return@LaunchedEffect
        val itemIndex = binding.itemIndex
        // 只在锚点已滚出懒列表时忽略回收事件。其他节点被重建后仍需恢复焦点。
        if (completedRequest == requestKey && itemIndex != null && listState != null &&
            listState.firstVisibleItemIndex > itemIndex
        ) return@LaunchedEffect
        FocusDiagnostics.focusRequest(request, itemIndex)
        if (itemIndex != null && listState != null) {
            listState.scrollToItem(itemIndex)
        }
        if (!binding.target.isReady) {
            FocusDiagnostics.focusAwaitingTarget(request.anchor)
            return@LaunchedEffect
        }
        val focused = runCatching { binding.target.requester.requestFocus() }.getOrDefault(false)
        FocusDiagnostics.focusResult(request.anchor, focused)
        if (focused) completedRequest = requestKey
    }
}

/**
 * 为临时表面请求其初始焦点。
 *
 * 弹窗、登录层与播放控制栏不参与页面路由锚点；它们仍必须等自身可聚焦节点真实挂载后
 * 才能调用 Compose 的 [FocusRequester.requestFocus]。用 [activation] 标记一次表面切换，
 * 能避免以延时或轮询猜测动画、布局与窗口焦点的时序。
 */
@Composable
internal fun RequestFocusWhenReady(activation: Any?, target: FocusTarget) {
    val targetReady = target.isReady
    LaunchedEffect(activation, targetReady) {
        if (targetReady) {
            val focused = runCatching { target.requester.requestFocus() }.getOrDefault(false)
            FocusDiagnostics.transientFocusResult(focused)
        }
    }
}
