# GitHub 发布配置

本项目通过 GitHub Actions 构建签名 APK，并将公开 GitHub Release 同时作为正式版和 Beta 版的分发源。应用固定查询仓库 `rroy233/capycast`。

## 首次配置

1. 在 GitHub 创建 `rroy233/capycast`，将当前 Git 仓库推送到 `main` 分支。
2. 在仓库 Settings → Environments 中创建 `production` 与 `beta`。两者都添加以下 Environment secrets：
   - `ANDROID_KEYSTORE_BASE64`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
3. `ANDROID_KEYSTORE_BASE64` 使用本机 `.android-keys` 目录中的发布证书文件进行 Base64 编码后填写。证书文件、密码和 `keystore.properties` 均不提交到仓库。
4. 将 `production` 环境设置为需要人工审批；`beta` 保持公开发布即可。

GitHub Actions 生成的 APK、`SHA256SUMS` 与 `capycast-update.json` 都会作为公开 Release 附件上传。应用先读取更新清单，再校验 APK 的 SHA-256、包名和版本号。

## 日常发布

1. 在 [`app/build.gradle.kts`](../app/build.gradle.kts) 更新 `APP_VERSION_NAME` 和 `APP_VERSION_CODE`。如需自定义发布说明，可在 [`release-notes`](../release-notes) 添加同名 Markdown 文件；缺失时 Workflow 会使用 GitHub 自动生成的发布说明。
2. 将发布提交合并到 `main`。
3. 在 Actions 中运行 **Publish GitHub Release**，选择与版本号一致的通道：
   - `0.2.0-beta.1` 选择 `beta`；Workflow 会创建预发布 Release 与 Tag `v0.2.0-beta.1`。
   - `0.2.0` 选择 `stable`；Workflow 会创建正式 Release 与 Tag `v0.2.0`。
4. Workflow 会拒绝已存在的 Tag，以及不大于历史发布中最大值的 `versionCode`。

同一个 applicationId 和签名证书用于两个通道，因此 Beta 与正式版共享一条持续递增的 `versionCode` 序列。例如：`0.1.0 (2)`、`0.2.0-beta.1 (3)`、`0.2.0-beta.2 (4)`、`0.2.0 (5)`。
