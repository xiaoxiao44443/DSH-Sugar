# GitHub 自动打包

[打开 Build Android APK](https://github.com/xiaoxiao44443/DSH-Sugar/actions/workflows/android-release.yml)

## 手动打包

在仓库 **Actions → Build Android APK → Run workflow** 中选择分支，再点运行。
成功后从该次运行底部的 **Artifacts** 下载 `DSH-Sugar-版本-arm64-v8a`，解压即可得到已签名 APK、校验和和第三方附件。产物保留 7 天；单元测试和 lint 报告单独保存。

手动运行只生成构建产物，不创建或覆盖 Release，也不自动改变 App 版本。

## 发布新版本

1. 修改 `app/build.gradle` 中的 `versionName` 和 `versionCode`。每次公开更新都递增 `versionCode`。
2. 在 `docs/releases/v版本号.md` 写更新说明；如果没有该文件，GitHub 根据提交/PR 自动生成说明。
3. 提交并推送代码，再创建对应标签，例如：

   ```sh
   git tag -a v0.2.1 -m "DSH Sugar 0.2.1"
   git push origin v0.2.1
   ```

标签必须与 `versionName` 一致，否则构建直接失败。工作流会准备环境、编译 Sucrune 和 Sugar、运行测试与 lint、签名、校验，再创建 Release 草稿。所有附件上传完成且大小、SHA256 与构建产物一致后才公开。`0.x` 和带预发布后缀的版本标记为 Pre-release。

已公开版本不会被重新上传覆盖。重跑失败的标签构建会继续处理同标签的草稿；如果已经公开，则只允许验证内容完全相同的附件。若需要修改已发布 APK，请递增版本并发新标签。

## 签名

仓库的 **Settings → Secrets and variables → Actions** 配置以下 Repository secrets：

| Secret | 用途 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 原有 keystore 文件的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名别名 |
| `ANDROID_KEY_PASSWORD` | 私钥密码 |

本仓库沿用首次公开版本的签名，以便已有用户覆盖安装。签名脚本会检查证书 SHA256、包名、版本号、ARM64 ABI、非调试构建和 16 KiB 对齐。私钥只在签名步骤临时解码，随后删除，不进入源码、缓存或构建附件。请自行备份原始 keystore；更换签名会影响已有安装的升级。

工作流只响应手动运行和版本标签，不在外部 PR 中使用签名密钥。构建 job 只读仓库；发布 job 单独使用 GitHub 提供的 `GITHUB_TOKEN` 写入 Release，无需额外个人访问令牌。

## 固定构建环境

Windows 2025 runner、Temurin JDK 17.0.20.1+1、Go 1.26.10、Node 24.22.2、Python 3.12、Zig 0.17.1；Android SDK 37.0、Build Tools 36.0.0、NDK 26.3.11579264、CMake 3.22.1。Gradle wrapper 固定为 9.3.1 并校验下载哈希。

Sucrune 从 `f9eb0a1f51c256c151fd63e6d4503c7335c8c11a` 源码构建。Linux 用户态和 DSH 使用仓库中的版本锁和下载校验；不依赖开发者电脑上的预构建 APK。缓存只加速依赖下载与 Gradle，签名文件不缓存。

`scripts/release/` 包含签名、附件打包及发布校验脚本。升级 DSH、Sucrune、Linux 工具或 Android 依赖时，需要同时复核第三方许可证与对应源码锁；附件脚本发现未审核的依赖变化会停止发布。发行附件只读取本次 APK 和固定上游来源，不收集设备上的聊天、插件、凭据或工作区文件。
