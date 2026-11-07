package com.roy233.tvcast.feature.danmaku.domain

/**
 * 弹幕渲染端口。
 *
 * 实现见 `presentation/DfmDanmakuEngine`。
 */
interface DanmakuRenderer {
    /** 设置视频内容区高度，用于字号换算。 */
    fun setContentHeight(heightPx: Int)

    /** 视图完成布局后初始化渲染器。 */
    fun prepare()

    /** 应用显示设置。 */
    fun applySettings(newSettings: DanmakuSettings)

    /** 替换当前分段（换片、seek、设置变化后重建）。 */
    fun replaceSegment(items: List<DanmakuItem>, level: AiShieldLevel)

    /** 追加上一个分段。 */
    fun addSegment(items: List<DanmakuItem>, level: AiShieldLevel)

    /** 暂停渲染。 */
    fun pause()

    /** 恢复渲染。 */
    fun resume()

    /** 跳到指定媒体时间。 */
    fun seekTo(mediaMs: Long)

    /** 清空屏幕上的弹幕与等待队列。 */
    fun clear()

    /** 释放渲染资源。 */
    fun release()

    /** 渲染器自检快照，用于排查时间轴与暂停状态；实现可返回空串。 */
    fun renderDiagnostics(): String = ""
}
