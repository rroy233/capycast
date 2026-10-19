# 版本管理约定

项目使用语义化版本和 Android 整数版本号管理发布版本。

## 版本字段

- `versionName`：用户可见的语义化版本号。正式版格式为 `MAJOR.MINOR.PATCH`，Beta 格式为 `MAJOR.MINOR.PATCH-beta.N`，当前基线为 `0.1.0`。
- `versionCode`：Android 和更新器使用的正整数，要求每次发布新 APK 时递增。

版本值集中定义在 [`app/build.gradle.kts`](app/build.gradle.kts) 文件顶部。构建脚本会校验 `versionName` 是否符合三段式语义化版本格式，并校验 `versionCode` 为正整数。

## 开发阶段

开发阶段可以频繁提交代码，不需要为每次提交修改正式版本号。只有准备发布 APK 时，才需要更新发布版本信息。

## 发布阶段

1. 每发布一个新 APK，递增 `APP_VERSION_CODE`。
2. 根据改动范围决定是否递增 `APP_VERSION_NAME`：
   - 修复问题或兼容性调整：递增 `PATCH`。
   - 增加向后兼容的功能：递增 `MINOR`。
   - 存在不兼容变更：递增 `MAJOR`。
3. 执行构建和验证：

   ```bash
   ./gradlew :app:assembleDebug
   ```

4. 创建与 `versionName` 一致的 Git Tag，例如正式版 `1.2.3` 使用 `v1.2.3`，Beta 版 `1.2.3-beta.1` 使用 `v1.2.3-beta.1`。
5. 通过 GitHub Actions 的 **Publish GitHub Release** 工作流构建签名 APK、生成 SHA-256 清单并创建 GitHub Release。具体配置和步骤见 [`docs/GITHUB_RELEASE_SETUP.md`](docs/GITHUB_RELEASE_SETUP.md)。

如果只重新打包 APK 而不改变用户可见功能，也要递增 `versionCode`；此时可以保持 `versionName` 不变。

Beta 与正式版使用相同的 applicationId 和签名证书，必须共享一条持续递增的 `versionCode` 序列，避免用户切换通道时触发 Android 降级安装限制。
