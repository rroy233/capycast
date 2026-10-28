package com.roy233.tvcast.feature.update.model

/**
 * 表示用户选择的发布通道。
 *
 * @property displayName 界面展示的通道名称。
 * @property apiValue 版本服务查询参数中使用的通道名称。
 * @property manifestValues 允许出现在更新清单中的通道名称；Beta 通道也接受正式版清单。
 */
enum class UpdateChannel(
    val displayName: String,
    val apiValue: String,
    val manifestValues: Set<String>,
) {
    /** 面向日常使用的稳定版本。 */
    STABLE("正式版", "release", setOf("stable")),

    /** 在正式版基础上额外接受更高 versionCode 的预发布版本。 */
    BETA("Beta 版", "beta", setOf("beta", "stable")),
}
