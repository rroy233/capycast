# 第三方软件声明

CapyCast 使用以下第三方开源组件。各组件的版权归其原作者所有，并分别遵循各自的许可证。

## libVLC 3.6.5

- 项目：VideoLAN VLC / VLC-Android
- 用途：音视频解复用、解码与播放
- 许可证：libVLC 引擎及大多数播放模块采用 GNU Lesser General Public License 2.1 or later（LGPL-2.1-or-later）；其余模块以对应上游声明为准
- 源代码：[VLC-Android 3.6.5](https://code.videolan.org/videolan/vlc-android/-/tree/3.6.5)
- 许可证说明：[VideoLAN 关于 libVLC 与播放模块采用 LGPL 的说明](https://www.videolan.org/press/lgpl-modules.html)
- 许可证全文：[GNU LGPL 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)

本项目以本地 AAR 形式引用 libVLC。libVLC 及其包含的第三方模块不受 CapyCast 的 MIT License 覆盖；分发构建时应保留相关版权、许可证与源代码获取信息。

## DanmakuFlameMaster 0.9.25

- 项目：DanmakuFlameMaster
- 用途：弹幕布局、碰撞、滚动/固定弹幕渲染
- 许可证：Apache License 2.0
- 源代码：[DanmakuFlameMaster](https://github.com/ctiao/DanmakuFlameMaster)
- 许可证全文：[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)

本项目以本地 AAR 形式引用 `app/libs/DanmakuFlameMaster-0.9.25.aar`，仅在客户端绘制弹幕，不包含其示例应用代码。该 AAR 不受 CapyCast 的 MIT License 覆盖。

## 其他依赖

项目还使用 AndroidX、Jetpack Compose、Kotlin Coroutines、OkHttp、Gson、ZXing、NanoHTTPD、Coil 与 Backdrop。依赖版本可在 [`app/build.gradle.kts`](app/build.gradle.kts) 中查看；其许可证以各上游项目发布的内容为准。
