# 第三方软件声明

CapyCast 使用以下第三方开源组件。各组件的版权归其原作者所有，并分别遵循各自的许可证。

## DanmakuFlameMaster 0.9.25

- 项目：DanmakuFlameMaster
- 用途：弹幕布局、碰撞、滚动/固定弹幕渲染
- 许可证：Apache License 2.0
- 源代码：[DanmakuFlameMaster](https://github.com/ctiao/DanmakuFlameMaster)
- 许可证全文：[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)

本项目以本地 AAR 形式引用 `app/libs/DanmakuFlameMaster-0.9.25.aar`，仅在客户端绘制弹幕，不包含其示例应用代码。该 AAR 不受 CapyCast 的 MIT License 覆盖。

## 其他依赖

项目还使用 AndroidX、Jetpack Compose、Kotlin Coroutines、OkHttp、Gson、ZXing、NanoHTTPD、Coil 与 Backdrop。依赖版本可在 [`app/build.gradle.kts`](app/build.gradle.kts) 中查看；其许可证以各上游项目发布的内容为准。
