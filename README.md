<div align="center">
  <img src="docs/images/capycast-logo-full.png" width="220" alt="CapyCast Logo">

  <h1>CapyCast</h1>

  <p>一款为 Android TV 打造的极简 Bilibili 投屏播放器。</p>

  <p>
    <a href="https://github.com/rroy233/capycast/actions/workflows/verify.yml"><img src="https://github.com/rroy233/capycast/actions/workflows/verify.yml/badge.svg" alt="Build Status"></a>
    <img src="https://img.shields.io/badge/Android%20TV-5.0%2B-3DDC84?logo=android&logoColor=white" alt="Android TV 5.0+">
    <img src="https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20armeabi--v7a-5965E0" alt="Supported ABIs">
    <img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin and Jetpack Compose">
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="MIT License"></a>
  </p>
</div>

支持从 B 站手机 App 发起投屏；应用内不提供内容浏览、推荐或订阅信息流，适合家庭、宿舍、教室等场景。



## 功能亮点

- **按账号权益播放**：支持电视端扫码登录，也可访客使用；
- **尽可能呈现高画质**：大会员支持播放 4K、8K、HDR、Dolby 画质档位与 Dolby Atmos 音轨；
- **自动优选播放链路**：从候选播放地址中优先选择官方链路，减少劣质 PCDN 源带来的影响。
- **手机与遥控器均可控制**：支持播放、暂停、停止、调整进度和音量；电视遥控器可完成常用操作并切换画质、音轨。
- **专为电视交互设计**：大屏焦点导航、播放 HUD、最近投屏与二维码登录均可仅用遥控器操作。



## 三步开始投屏

1. 在电视上安装并打开 CapyCast，可按需扫码登录 B 站账号。
2. 让手机与电视连接同一局域网，在 B 站手机 App 中打开视频并点击「投屏」。
3. 选择电视画面上显示的设备名称，等待播放开始。

> 如果手机找不到电视，请确认两台设备位于同一局域网，并关闭路由器的 AP 隔离、访客网络隔离等限制局域网设备互访的功能。



## 安装

前往 [GitHub Releases](https://github.com/rroy233/capycast/releases) 下载 `capycast-<版本号>.apk`，通过 U 盘、电视文件管理器或 ADB 侧载安装。发布页提供的单个 APK 同时包含两种受支持的 ARM 架构；Android 首次侧载时可能需要为安装工具开启「允许安装未知应用」。

| 通道 | 适用场景 | 下载入口 |
| --- | --- | --- |
| 正式版 | 日常使用，优先稳定性 | [下载最新正式版](https://github.com/rroy233/capycast/releases/latest) |
| Beta 版 | 提前体验新功能，可接受已知问题 | [查看全部版本](https://github.com/rroy233/capycast/releases) |

安装后可在「设置 → 应用更新」中切换更新通道并配置自动检查。



## 设备要求

| 项目 | 要求 |
| --- | --- |
| 目标设备 | Android TV、Google TV 与兼容 Android 电视盒子；手机和平板不在适配范围内 |
| 系统 | Android 5.0（API 21）及以上 |
| 处理器架构 | `arm64-v8a`、`armeabi-v7a` |
| 操作方式 | Android TV 遥控器或兼容方向键输入设备 |
| 网络 | 手机与电视处于允许设备互访的同一局域网 |
| 手机端 | 可正常使用投屏功能的 B 站手机 App |

高分辨率、HDR、Dolby 与特定编码能否正常播放，最终由片源、账号权益和设备硬件解码能力共同决定。



## 排查播放问题

如果某台电视出现无法播放、黑屏、只有声音或播放中途停止，可在同一局域网内用电脑或手机浏览器访问电视上的诊断地址：

| 地址 | 内容 |
| --- | --- |
| `http://<电视 IP>:8098/log` | 最近的运行日志，包含投屏、取流、播放器事件和错误 |
| `http://<电视 IP>:8098/playback` | 播放诊断报告：设备解码能力、轨道选择、播放时间线、统计和 libVLC 原生日志 |
| `http://<电视 IP>:8098/device` | 设备支持的视频/音频解码器、HDR 与显示能力 |
| `http://<电视 IP>:8098/state` | 当前设备、登录状态和播放摘要 |

诊断地址在应用启动后自动开启；电视 IP 可在「设置」页的网络信息中查看。播放地址中的签名参数、Cookie 和投屏载荷会自动脱敏，报告可以放心附在 Issue 中。提交问题时请说明电视品牌型号、Android 版本、复现步骤，并附上 `/playback` 的内容。

`/playback` 会优先读取 libVLC 原生日志文件；若当前 libVLC 构建不支持文件日志，会自动读取本进程的 `VLC` logcat。具备 adb 的开发者也可以在电视上执行 `adb logcat -s VLC` 获取同样的原生解码与渲染日志。



## 反馈与贡献

遇到问题或有改进建议，请通过 [Issue 表单](https://github.com/rroy233/capycast/issues/new/choose) 提交：

- Bug 报告请附上应用版本、电视型号、Android 版本、复现步骤与 `/playback` 诊断输出，并确认内容中不含 Cookie、二维码、账号标识等敏感信息。
- 功能建议请说明使用场景、期望行为以及它能解决的问题。

欢迎提交 Pull Request。开始前请阅读 [贡献指南](CONTRIBUTING.md)，确保构建检查通过，并在 PR 中说明改动范围与验证方式。



## 开源协议

CapyCast 自有代码采用 [MIT License](LICENSE)。项目使用的 libVLC 3.6.5 及其他第三方组件分别遵循其原始许可证，详见 [第三方软件声明](THIRD_PARTY_NOTICES.md)。

> CapyCast 是独立的社区项目，与哔哩哔哩及 VideoLAN 无隶属或合作关系。请在遵守相关服务条款与当地法律的前提下使用。
