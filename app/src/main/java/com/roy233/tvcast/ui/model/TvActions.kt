package com.roy233.tvcast.ui.model

import com.roy233.tvcast.core.danmaku.AiShieldLevel
import com.roy233.tvcast.feature.update.UpdateChannel

/**
 * 聚合电视界面向宿主发送的用户操作回调。
 *
 * @property onLogin 请求开始登录。
 * @property onSkipLogin 跳过登录流程。
 * @property onOpenSidebar 打开侧边栏。
 * @property onCloseSidebar 关闭侧边栏。
 * @property onNavigateHome 导航到首页。
 * @property onNavigateSettings 导航到设置页。
 * @property onOpenUpdateSettings 打开应用更新二级页面。
 * @property onCloseUpdateSettings 返回设置主页。
 * @property onOpenUpdateNotes 打开当前更新的更新日志。
 * @property onCloseUpdateNotes 从更新日志返回应用更新页面。
 * @property onSelectUpdateChannel 选择更新通道。
 * @property onSetAutoUpdateCheck 设置是否自动检查更新。
 * @property onCheckForUpdates 手动检查更新。
 * @property onDownloadUpdate 下载当前可用更新。
 * @property onCancelUpdateDownload 取消当前更新下载。
 * @property onInstallUpdate 安装已经校验的更新包。
 * @property onShowHelp 显示投屏帮助。
 * @property onCloseHelp 关闭投屏帮助。
 * @property onLogout 请求退出登录。
 * @property onPlayPause 切换播放与暂停。
 * @property onSeekBy 按给定毫秒数调整播放位置。
 * @property onPreviewSeekBy 预览性地按给定毫秒数调整播放位置。
 * @property onConfirmSeek 确认当前预览位置并结束进度调整。
 * @property onSetPlaybackSpeed 设置视频播放速度。
 * @property onSetVolume 设置音量。
 * @property onShowHud 显示播放控制栏。
 * @property onHideHud 隐藏播放控制栏。
 * @property onOpenPanel 打开指定控制面板。
 * @property onClosePanel 关闭控制面板。
 * @property onSelectQuality 选择视频清晰度。
 * @property onSelectAudio 选择音轨。
 * @property onRetry 重试当前失败操作。
 * @property onDismissError 关闭错误提示。
 * @property onRequestStopCast 请求停止当前投屏。
 * @property onConfirmStopCast 确认停止当前投屏。
 * @property onCancelStopCast 取消停止投屏操作。
 * @property onClearToast 清除提示文本。
 * @property onRecast 重新投屏指定记录。
 * @property onDismissContinue 收起继续播放入口。
 * @property onSetRememberPlaybackContent 设置是否记忆最近投屏内容。
 * @property onSetSyncPlaybackHistory 设置是否同步投屏播放记录到 B 站账号。
 * @property onHomeBack 处理首页返回操作。
 */
class TvActions(
    val onLogin: () -> Unit = {},
    val onSkipLogin: () -> Unit = {},
    val onOpenSidebar: () -> Unit = {},
    val onCloseSidebar: () -> Unit = {},
    val onNavigateHome: () -> Unit = {},
    val onNavigateSettings: () -> Unit = {},
    val onOpenUpdateSettings: () -> Unit = {},
    val onCloseUpdateSettings: () -> Unit = {},
    val onOpenUpdateNotes: () -> Unit = {},
    val onCloseUpdateNotes: () -> Unit = {},
    val onSelectUpdateChannel: (UpdateChannel) -> Unit = {},
    val onSetAutoUpdateCheck: (Boolean) -> Unit = {},
    val onCheckForUpdates: () -> Unit = {},
    val onDownloadUpdate: () -> Unit = {},
    val onCancelUpdateDownload: () -> Unit = {},
    val onInstallUpdate: () -> Unit = {},
    val onShowHelp: () -> Unit = {},
    val onCloseHelp: () -> Unit = {},
    val onLogout: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onSeekBy: (Long) -> Unit = {},
    val onPreviewSeekBy: (Long) -> Unit = {},
    val onConfirmSeek: () -> Unit = {},
    val onSetPlaybackSpeed: (Float) -> Unit = {},
    val onToggleDanmaku: () -> Unit = {},
    val onSetDanmakuAiShield: (AiShieldLevel) -> Unit = {},
    val onSetDanmakuArea: (Float) -> Unit = {},
    val onSetDanmakuMaxOnScreen: (Int) -> Unit = {},
    val onSetDanmakuOpacity: (Float) -> Unit = {},
    val onSetDanmakuFontScale: (Float) -> Unit = {},
    val onSetDanmakuDuration: (Float) -> Unit = {},
    val onSetVolume: (Int) -> Unit = {},
    val onShowHud: () -> Unit = {},
    val onHideHud: () -> Unit = {},
    val onOpenPanel: (PanelKind) -> Unit = {},
    val onClosePanel: () -> Unit = {},
    val onSelectQuality: (Int) -> Unit = {},
    val onSelectAudio: (Int) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onRequestStopCast: () -> Unit = {},
    val onConfirmStopCast: () -> Unit = {},
    val onCancelStopCast: () -> Unit = {},
    val onClearToast: () -> Unit = {},
    val onRecast: (RecentCast) -> Unit = {},
    val onDismissContinue: () -> Unit = {},
    val onSetRememberPlaybackContent: (Boolean) -> Unit = {},
    val onSetSyncPlaybackHistory: (Boolean) -> Unit = {},
    val onHomeBack: () -> Unit = {},
)
