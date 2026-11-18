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

## 普通主页面拖动

插件管理、插件市场、自动化任务及第三方主面板共用 `main` slot。Sugar 从 `[data-slot="main"]` 的父级 MainPanel、再上一层 CenterColumn 定位稳定页面盒；升级时核对这层结构，不按插件 ID 添加手势分支。

普通面板参与的拖动只预览内存中的静态副本，松手确认后才切换真实路由。DSH 切换 main entry 会卸载局部组件，不能先切页再在取消时切回，否则搜索、选中项和展开状态可能重置。副本不保留输入值，未缓存或不能安全捕获时保持原页到提交。抽屉、文档 dock 和 Sugar 设置继续使用各自容器策略。

运行 `node scripts/check-mobile-navigation.cjs`、`node scripts/check-page-motion.cjs`、`node scripts/check-edge-nav.cjs --pane-deck` 和 `node scripts/check-edge-nav.cjs --shell-history`。真机分别进入上述三类页面，检查往返、拖过四分之一再反向拖回、取消、滚动后的预览，以及拖动中页面选择和输入内容不变。浏览器回归也覆盖未知第三方 panel ID 和缺少元素级 View Transition 的情况。

## 0.2.2-rc.5 适配记录

本次固定 npm 官方发布的 328 个 DSH rc.5 包，保留其余依赖版本与 Linux ARM64 glibc 选择，共 671 个安装路径。使用 npm 11.16.0 的完整锁执行 `npm ci --os=linux --cpu=arm64 --libc=glibc --omit=dev --ignore-scripts` 验证后，记录各 tarball 的 SHA-512。

适配逻辑保持不变，只更新三处编译摘要：设置模块显示版本、客户端发布 commit、远程接口的源码位置行号。28 个工作区/插件创建补丁的摘要与替换锚点均在最终镜像中核对；插件管理和配置解析补丁仍可严格应用。发布配套依赖清单同步更新，Sucrune、Node、工具链和第三方许可证保持不变。

rc.5 的搜索自动选择由 DSH 自身处理。用户显式保存的提供方不重置；搜索服务端点独立于模型提供方地址，Sugar 不自动复制凭据或改写 Base URL。rc.4 的拒答错误码仍经失败轮次结束事件收尾，无需改动手机任务和桌宠通知桥接。

本机验收：最终镜像的设置/恢复入口、真实 Host 普通/恢复预设、恢复配置隔离、工作区路径/子智能体/终端/沙箱、手机操作插件，以及四项前端导航回归通过。

真机验收（2026-11-18，PKR110 / Android 15）：同签名覆盖安装后确认运行 rc.5；主配置、会话文件名集合和工作区元数据摘要一致。会话历史、工作区文件及目录选择服务可读。临时配置和内置 DSH 两种恢复模式各完成两轮本地测试模型对话，重开与重载后的历史一致，结束后临时会话清理完成。插件管理、插件市场、自动化任务三页的取消保留 DOM 与往返导航通过。通用的关于页显示安装包名称/图标/版本，源码入口经手机默认浏览器打开仓库。
