package com.roy233.tvcast.ui.model

/** 表示电视端当前选中的业务页面。 */
enum class TvDestination { HOME, SETTINGS }

/**
 * 界面可恢复的焦点锚点。
 *
 * 锚点是焦点唯一的目标标识：每个界面只声明自己能接住哪些锚点，谁能获得焦点
 * 由路由栈顶的锚点决定，界面自身不判断时机。
 */
enum class UiFocusAnchor {
    /** 首页主入口。 */
    HOME_PRIMARY,

    /** 左侧导航菜单的两个条目。 */
    MENU_HOME,
    MENU_SETTINGS,

    /** 设置主页的平台卡片与软件信息入口。 */
    SETTINGS_PLATFORM,
    SETTINGS_UPDATE_ENTRY,

    /** 应用更新页的返回按钮与更新日志入口。 */
    SETTINGS_UPDATE_BACK,
    SETTINGS_UPDATE_RELEASE_NOTES,

    /** 更新日志页的首个内容块。 */
    SETTINGS_UPDATE_NOTES_INITIAL,
}

/**
 * 当前栈顶的焦点请求。
 *
 * 路由栈每次变化都写入一个新的 [revision] 与栈顶锚点；界面对照自身声明的锚点
 * 认领，未命中的界面不会请求焦点，因此不可见或正在退场的界面抢不走焦点。
 */
data class FocusRequest(val anchor: UiFocusAnchor, val revision: Long = 0L) {
    /** 产生一次指向 [anchor] 的新请求。 */
    fun moveTo(anchor: UiFocusAnchor): FocusRequest = FocusRequest(anchor, revision + 1)
}

/** 返回某个业务页自身的默认锚点。 */
fun pageAnchorFor(destination: TvDestination): UiFocusAnchor = when (destination) {
    TvDestination.HOME -> UiFocusAnchor.HOME_PRIMARY
    TvDestination.SETTINGS -> UiFocusAnchor.SETTINGS_PLATFORM
}

/**
 * 目的页之上依次叠加的浮层。
 *
 * 浮层与页面、菜单共用同一条层级规则：只有栈顶接收按键并持有焦点。枚举顺序即
 * 优先级，界面只需要读 [TvUiState.topOverlay]，不再各自维护一套判定链。
 */
enum class UiOverlay {
    CALIBRATION,
    STOP_CAST_CONFIRM,
    ERROR,
    PANEL,
    HELP,
    LOGIN_DIALOG,
}

/** 表示设置页当前打开的层级。 */
enum class SettingsPage { ROOT, UPDATE, UPDATE_NOTES }

/** 返回某个设置页自身的默认锚点。 */
fun settingsAnchorFor(page: SettingsPage): UiFocusAnchor = when (page) {
    SettingsPage.ROOT -> UiFocusAnchor.SETTINGS_PLATFORM
    SettingsPage.UPDATE -> UiFocusAnchor.SETTINGS_UPDATE_BACK
    SettingsPage.UPDATE_NOTES -> UiFocusAnchor.SETTINGS_UPDATE_NOTES_INITIAL
}

/**
 * 左侧导航菜单的条目。条目只声明展示文案、目的页与自身锚点。
 */
enum class MenuEntry(
    val destination: TvDestination,
    val title: String,
    val anchor: UiFocusAnchor,
) {
    HOME(TvDestination.HOME, "Bilibili 投屏", UiFocusAnchor.MENU_HOME),
    SETTINGS(TvDestination.SETTINGS, "设置", UiFocusAnchor.MENU_SETTINGS),
}

/** 菜单条目的固定顺序，也是上下键遍历的范围。 */
val MENU_ENTRIES: List<MenuEntry> = MenuEntry.entries.toList()

/** 返回当前页面对应的菜单条目，用于菜单打开时的初始焦点。 */
fun menuEntryFor(destination: TvDestination): MenuEntry =
    MENU_ENTRIES.first { it.destination == destination }

/** 返回锚点对应的菜单条目；非菜单锚点返回 null。 */
fun menuEntryOf(anchor: UiFocusAnchor): MenuEntry? =
    MENU_ENTRIES.firstOrNull { it.anchor == anchor }

/** 按上下方向移动菜单焦点，两端收敛。 */
fun MenuEntry.moved(step: Int): MenuEntry =
    MENU_ENTRIES[(ordinal + step).coerceIn(0, MENU_ENTRIES.lastIndex)]

/** 路由栈中的一层设置子页面，以及退出该页后父页面应恢复的锚点。 */
data class SettingsRouteEntry(
    val page: SettingsPage,
    val returnFocus: UiFocusAnchor?,
)

/**
 * 设置页的纯 UI 路由栈。
 *
 * 栈只记录层级与每层的返回锚点；栈顶应当获得哪个锚点由 [topAnchor] 给出，
 * 焦点请求本身只存在于 [TvUiState.focusRequest]，避免出现第二个焦点来源。
 */
data class SettingsNavigationState(
    val stack: List<SettingsRouteEntry> = listOf(SettingsRouteEntry(SettingsPage.ROOT, null)),
) {
    val currentPage: SettingsPage
        get() = stack.last().page

    val canNavigateBack: Boolean
        get() = stack.size > 1

    /** 当前栈顶应当获得的锚点。 */
    val topAnchor: UiFocusAnchor
        get() = settingsAnchorFor(currentPage)

    /** 当前子页退出后父页应恢复的锚点。 */
    val returnAnchor: UiFocusAnchor?
        get() = stack.last().returnFocus

    fun reset(): SettingsNavigationState =
        copy(stack = listOf(SettingsRouteEntry(SettingsPage.ROOT, null)))

    fun push(page: SettingsPage, returnFocus: UiFocusAnchor): SettingsNavigationState =
        copy(stack = stack + SettingsRouteEntry(page, returnFocus))

    fun pop(): SettingsNavigationState =
        if (canNavigateBack) copy(stack = stack.dropLast(1)) else this

    /**
     * 为播放状态投影恢复路由。
     *
     * 投影只携带当前页；已处于该页时保持现状，避免重复投影重置层级。
     */
    fun replaceWith(page: SettingsPage): SettingsNavigationState =
        if (currentPage == page) this else routesFor(page)

    private fun routesFor(page: SettingsPage): SettingsNavigationState = when (page) {
        SettingsPage.ROOT -> reset()
        SettingsPage.UPDATE -> reset().push(
            page = SettingsPage.UPDATE,
            returnFocus = UiFocusAnchor.SETTINGS_UPDATE_ENTRY,
        )
        SettingsPage.UPDATE_NOTES -> reset()
            .push(
                page = SettingsPage.UPDATE,
                returnFocus = UiFocusAnchor.SETTINGS_UPDATE_ENTRY,
            )
            .push(
                page = SettingsPage.UPDATE_NOTES,
                returnFocus = UiFocusAnchor.SETTINGS_UPDATE_RELEASE_NOTES,
            )
    }
}
