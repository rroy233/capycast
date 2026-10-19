# 参与贡献

感谢你帮助改进 CapyCast。Bug 修复、设备兼容性改进、文档完善和经过充分说明的新功能都很受欢迎。

## 开始之前

1. 对较大的功能或行为变更，请先创建 Issue，说明使用场景和实现方向，便于提前确认范围。
2. Fork 仓库并从 `main` 创建分支。
3. 保持改动聚焦；重构与功能修改尽量拆分为不同的 Pull Request。

本地开发需要 JDK 17、Android SDK 36 和 Android SDK Build Tools 36.0.0。可使用 Android Studio 打开项目，或在命令行执行：

```bash
./gradlew :app:lintDebug :app:assembleDebug
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 开发约定

- 延续现有 Kotlin 与 Jetpack Compose 代码风格，优先使用清晰、职责单一的实现。
- 涉及投屏协议、账号权益、播放链路或更新流程时，请保留必要日志，同时避免记录 Cookie、二维码内容、访问令牌等敏感信息。
- UI 改动需确保仅使用电视遥控器即可完成操作，并检查焦点移动、返回键和小尺寸电视画面的表现。
- 不提交签名文件、账号凭据、本地配置、构建产物或真实用户日志。
- 用户可见行为发生变化时，同步更新 README 或相关文档。

## 提交 Pull Request

提交前请确认：

- `./gradlew :app:lintDebug :app:assembleDebug` 执行成功。
- 已在真实设备或合适的 Android TV 模拟器上验证主要路径。
- PR 描述包含改动目的、实现摘要、验证方式和潜在影响。
- UI 改动附上当前版本的截图或录屏；无需沿用仓库中的早期设计稿。
- 相关 Issue 已通过 `Closes #编号` 或 `Refs #编号` 关联。

维护者可能会要求补充设备信息、日志或拆分改动。合并前请处理完评审意见并保持分支可构建。

