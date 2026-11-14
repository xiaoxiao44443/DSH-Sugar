# DSH 升级验收

从 DSH Sugar 项目根目录运行。Sugar 在 `scripts/dsh-linux-arm64-lock.json` 中固定 DSH 的发布版本和目标依赖。该锁记录 npm 11.16.0 按 `--os=linux --cpu=arm64 --libc=glibc --omit=dev --ignore-scripts` 安装时选中的包路径、版本、官方 tarball URL 和 SHA-512。准备脚本逐包校验，再应用 `guest/` 中的适配。Sucrune 提供 Linux 用户环境和 Node。`scripts/build-android.ps1` 会在准备镜像后、调用 Gradle 前自动检查实际打包的设置按钮和诊断依赖。`-SkipGuestPreparation` 会对现有镜像执行同一检查。直接使用 Gradle 时，先运行该入口。

```sh
python scripts/check-dsh-upgrade.py
```

默认读取 `app/build/generated/sugarAssets/guest/rootfs.zip`，也可将候选 ZIP 路径作为第一个参数。检查会对照 Android 实现中的大小、SHA-256、原始点击处理函数和诊断选择器，再检查 ZIP 中诊断预设使用的包入口。设置模块变化时，先查看新版「打开配置文件」按钮的结构和点击行为，再决定如何更新适配和摘要。

升级验收时，另外把同一次构建的 `opt/dsh` 解包到本机，使用 Node 24 或该版 DSH 要求的版本启动真实 Host。

```sh
python scripts/check-dsh-upgrade.py path/to/rootfs.zip --host-dsh path/to/opt/dsh
node scripts/check-recovery-profile.mjs
```

第一条会核对候选包与解包目录中的预设入口，然后调用 [真实 Host 预设检查](../scripts/check-recovery-preset.mjs)，验证固定预设和各项工具实际激活。跨架构时，可通过 `NODE_PATH` 提供与测试宿主平台和 DSH 依赖版本匹配的原生模块。第二条单独验证临时配置保存、并发锁、版本冲突和原配置隔离。

## Android 真机必测

1. 覆盖安装后，在普通模式点「设置 → 打开配置文件」，确认进入 Sugar 编辑器并显示原配置。记录普通配置的摘要，写入测试内容前保留可还原的副本。
2. 分别进入「临时干净配置」和「内置 DSH」两种诊断模式。在默认诊断工作区使用固定诊断预设，连续发送两轮消息，记录两条不同的提示和回复。切回首页再重开同一对话，并重新加载 WebUI，确认四条消息内容、次序和数量。结束诊断会清除临时会话，应在结束前验收历史。
3. 两种诊断模式都从 DSH 自己的「打开配置文件」进入编辑器。通过该入口保存一条可识别的有效注释，再次打开核对，确认普通配置摘要相同。在侧栏新建终端，实际执行 `pwd` 并检查默认诊断工作目录，再确认能访问 `repair/`。通过导航结束诊断，回到普通模式验证原会话和配置。再次进入诊断时，上一次临时注释和对话应已清理。

解压测试 APK 随附的 `DSH-Sugar-recovery-qa.zip`，在连接 ADB 的真机上打开一个空白诊断环境，从解压目录运行 `node check-recovery-messages.cjs`（Node 22+，`adb` 在 PATH 或通过 `ADB` 指定）。换到另一种诊断模式后再运行一次。脚本会启动和关闭[本机测试模型](../scripts/recovery-test-model.cjs)，建立并清理本次端口转发；多台手机可通过 `ANDROID_SERIAL` 选择。涉及目录选择器、项目路由、文件交付或其他插件接口的上游改动，还应运行对应功能的专项验收。

WebUI 更新后，还要在真机横竖屏查看预览说明页和登录页的安全区，从 HTML 预览 iframe 边缘拖动导航，确认目录选择器仍有手机目录入口，并检查文件菜单是否出现重复操作项。这些布局和触摸行为需在实际 WebView 中验收。
