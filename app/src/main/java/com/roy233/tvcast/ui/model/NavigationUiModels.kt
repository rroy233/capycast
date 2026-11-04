package com.roy233.tvcast.ui.model

/** 表示电视端当前选中的业务页面。 */
enum class TvDestination { HOME, SETTINGS }

/** 表示设置页当前打开的层级。 */
enum class SettingsPage { ROOT, UPDATE, UPDATE_NOTES }

/** 设置页可恢复的稳定焦点锚点。页面只声明锚点，不自行判断前序路由。 */
enum class SettingsFocusAnchor {
    ROOT_PLATFORM,
    ROOT_UPDATE_ENTRY,
    UPDATE_BACK,
    UPDATE_RELEASE_NOTES,
    UPDATE_NOTES_INITIAL,
}

/** 一次可被页面消费的焦点恢复命令。 */
data class SettingsFocusRequest(
    val anchor: SettingsFocusAnchor,
    val revision: Long,
)

/** 路由栈中的一层设置子页面，以及退出该页后父页面应恢复的焦点。 */
data class SettingsRouteEntry(
    val page: SettingsPage,
    val returnFocus: SettingsFocusAnchor?,
)

/**
 * 设置页的纯 UI 路由栈。
 *
 * 子页入栈时声明进入焦点与返回锚点；出栈时统一把返回锚点投递给父页面。新增子页
 * 只需登记自身路由与锚点，不在页面内复制返回时机、延迟或焦点请求逻辑。
 */
data class SettingsNavigationState(
    val stack: List<SettingsRouteEntry> = listOf(SettingsRouteEntry(SettingsPage.ROOT, null)),
    val focusRequest: SettingsFocusRequest = SettingsFocusRequest(SettingsFocusAnchor.ROOT_PLATFORM, 0L),
) {
    val currentPage: SettingsPage
        get() = stack.last().page

    val canNavigateBack: Boolean
        get() = stack.size > 1

    fun reset(): SettingsNavigationState = copy(
        stack = listOf(SettingsRouteEntry(SettingsPage.ROOT, null)),
        focusRequest = nextRequest(SettingsFocusAnchor.ROOT_PLATFORM),
    )

    fun push(
        page: SettingsPage,
        entryFocus: SettingsFocusAnchor,
        returnFocus: SettingsFocusAnchor,
    ): SettingsNavigationState = copy(
        stack = stack + SettingsRouteEntry(page, returnFocus),
        focusRequest = nextRequest(entryFocus),
    )

    fun pop(): SettingsNavigationState {
        if (!canNavigateBack) return this
        val closingRoute = stack.last()
        return copy(
            stack = stack.dropLast(1),
            focusRequest = nextRequest(requireNotNull(closingRoute.returnFocus)),
        )
    }

    fun requestCurrentFocus(): SettingsNavigationState = copy(
        focusRequest = nextRequest(defaultFocusFor(currentPage)),
    )

    /**
     * 为播放状态投影恢复路由。
     *
     * 投影只携带当前页，不保留临时返回栈；已处于该页时保持现状，避免重复投影
     * 产生新的焦点请求。
     */
    fun replaceWith(page: SettingsPage): SettingsNavigationState =
        if (currentPage == page) this else routesFor(page)

    private fun routesFor(page: SettingsPage): SettingsNavigationState = when (page) {
        SettingsPage.ROOT -> reset()
        SettingsPage.UPDATE -> reset().push(
            page = SettingsPage.UPDATE,
            entryFocus = SettingsFocusAnchor.UPDATE_BACK,
            returnFocus = SettingsFocusAnchor.ROOT_UPDATE_ENTRY,
        )
        SettingsPage.UPDATE_NOTES -> reset()
            .push(
                page = SettingsPage.UPDATE,
                entryFocus = SettingsFocusAnchor.UPDATE_BACK,
                returnFocus = SettingsFocusAnchor.ROOT_UPDATE_ENTRY,
            )
            .push(
                page = SettingsPage.UPDATE_NOTES,
                entryFocus = SettingsFocusAnchor.UPDATE_NOTES_INITIAL,
                returnFocus = SettingsFocusAnchor.UPDATE_RELEASE_NOTES,
            )
    }

    private fun nextRequest(anchor: SettingsFocusAnchor) =
        SettingsFocusRequest(anchor, focusRequest.revision + 1)
}

private fun defaultFocusFor(page: SettingsPage): SettingsFocusAnchor = when (page) {
    SettingsPage.ROOT -> SettingsFocusAnchor.ROOT_PLATFORM
    SettingsPage.UPDATE -> SettingsFocusAnchor.UPDATE_BACK
    SettingsPage.UPDATE_NOTES -> SettingsFocusAnchor.UPDATE_NOTES_INITIAL
}
