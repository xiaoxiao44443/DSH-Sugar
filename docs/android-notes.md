# Android 使用与开发记录

打开 App 后，启动页会准备本机 Linux 用户环境、启动 DSH，再自动进入 DSH 自带的 WebUI。首版面向 Android 10 及以上的 ARM64 手机。DSH 以一个常驻进程运行，打开聊天页面或重建 Activity 都会复用当前实例。

## 在手机上使用 DSH

- 在 Sugar 设置列表点「手机操作」。主屏操作使用系统无障碍服务，可以切换应用、读取当前窗口和执行手势。Android 11 及以上还可截图。「显示触点」控制点击后是否短暂画出圆环。
- 普通手机操作会在当前对话弹出 Sugar 的屏幕选择控件。明确指定主屏、虚拟屏或 ADB 时使用指定方式。主屏和虚拟屏开关都关闭时，普通操作会使用已连接的本机 ADB。切换对话会显示对应任务的控件。虚拟屏操作需要 Android 14 及以上、启用虚拟屏开关并在 Sugar 中连接本机无线 ADB。一轮操作完成后，该对话的虚拟屏保留画面；下一次选择虚拟屏可继续使用同一块屏。点悬浮控件的「查看画面」可预览，在悬浮控件或屏幕列表点「关闭屏幕」可结束该屏。
- 已选屏的对话可在项目 Bash 运行 `mobile_use run my_task.py`。Python 中使用 `from mobile_use import phone` 取得同一目标屏幕。`mobile_use repl` 保留交互变量，`mobile_use stdio` 提供逐行 JSON 接口。具体操作和返回格式见内置的 [mobile-use 技能](../app/src/main/assets/guest/mobile-use-guide.md)。
- 在「Sugar 设置 → 概览 → 界面语言」可选择中文、English 或日本語。切换后立即生效，重新打开时保留选择。DSH WebUI 的语言由 DSH 自己设置。
- 打开 App 后，前台服务会启动 DSH。Android 13 及以上首次打开时请允许通知。切回桌面后，点常驻通知即可返回 DSH。ColorOS 15.0.2 的「智能优化后台运行」可能在通知仍显示时冻结任务。需要持续运行时，可在「DSH Sugar 应用详情 → 耗电管理」选择「完全允许后台行为」。系统在内存紧张时仍可回收进程；通过系统「强行停止」可结束服务。
- Android 11 及以上可在「Sugar 设置 → 无线 ADB」连接运行 Sugar 的手机。点「通知栏配对」后，在系统无线调试页选择「使用配对码配对设备」，保持窗口打开，从 Sugar 通知输入六位码。Sugar 向系统提交的配对设备名称是 `DSH-Sugar`。已配对时点「连接本机」即可。如果无线调试处于关闭状态，Sugar 会打开系统设置并等待。打开开关后会自动查找端口并连接；等待期间可在 Sugar 或通知中取消。也可手动输入配对端口和连接端口。配对记录保存在本机，连接操作由用户发起。
- 各工作区的 Agent 随时可查询 `adb_status`；每条对话首次执行 ADB 命令前，通过 DSH 的 `skill({name:"android-adb"})` 加载用法，之后可在 Bash 里执行 `adb`。已有的文本工具 `adb_shell` 也可继续使用。连接即授权，点「断开」会立即撤销访问。Sugar 进程重启后保留配对身份，需再次手动连接。`adb_shell` 工具限文本输出，单次超时 15 秒，超时会结束当前命令通道。标准输出与错误输出合计最多返回 64 KiB。运行时常驻通知和 `adb_status` 都显示 Sugar 当前的本机连接状态。
- 项目 Bash 和侧栏终端内置 `adb`。用 `adb devices` 或 `adb get-state` 检查连接。手动终端可在 Sugar 连接后直接操作；Agent 对话先读取 `android-adb`。每个终端沿用本机配对，工作区里无需启动 adb server。
- `adb shell pm list packages` 返回设备命令的输出和退出码。`adb exec-out screencap -p > screenshot.png` 将原始 PNG 写入当前工作区。图片和文件按字节传输。同一次无线连接支持多个终端或对话同时运行命令。例如，一边传文件，一边查询设备状态。取消单条命令只关闭它自己的通道，手动断开连接会结束全部在途操作。
- `adb push ./app.apk /sdcard/Download/app.apk` 上传文件，`adb pull /sdcard/Download/report.txt ./report.txt` 下载文件。本地相对路径从当前工作目录解析。本版支持单个普通文件和一次性设备命令；运行 `adb help` 可查看参数。
- 「Sugar 设置 → 消息通知」可设置轮次完成时是否提醒，默认仅在 App 处于后台时发送。待回答的问题和待审批的权限各有独立开关，默认在前台和后台都提醒。通知首行显示对话名，下一行显示提醒原因。点按会打开对应对话，可在对话里回答或审批。
- 导航收起时，从任一侧边向内快扫即可展开。按住边缘再向内拖动可预览相邻的历史页，拖过中线后松手切换，拖回可取消。向外滑动收起导航。导航条展开后，也可以从条上向对侧快甩，松手后会继续滑到另一侧。从左侧或右侧滑动都回到刚才的页面，再从任一侧滑动即可前进回来。主动打开第三个页面后，它和上一个页面成为新的两页。返回和前进按钮可按完整访问顺序浏览对话、右侧文件栏、文件标签和设置页。主页打开当前工作区的新对话首页。点左上角按钮可打开 DSH 工作区菜单。打开右侧文件栏或切换文件标签会记入页面历史，后退和前进可回到对应的对话或文件。默认保持底部系统导航栏可见，并将两侧握柄居中的 200 dp 高区域留给 Sugar。在保护区之外，部分手机会接管最外缘触摸，只向应用发送普通返回。也可以从屏边稍向内起滑，或在「设置 → 手势」开启「整条侧边交给 Sugar」。该选项暂时收起底部导航栏，底部上滑可唤回。
- 侧轨的「设置」打开 Sugar 设置。首页与返回按钮固定。每个页面都可以声明侧轨按钮，未声明或列表为空时逐级继承父页面。概览、外观和手势共用设置导航。「外观 → 配色测试」下的六个独立色页共用六枚色块按钮，可点色块或上下滑动切换，每一页都进入导航历史。点侧轨按钮切页时，圆形揭幕、对开、斜擦、变焦和推入五种动效洗牌轮换。开启「减少动效」会跳过这些转场。「随背景」按导航旁一条纵向区域的整体颜色着色，文字、时钟和图标随底色切换深浅。外观也支持随机色、磨砂和 0–100% 背景透明度。手势页可调整停靠位置及减少动效。横竖屏均采用全高图标导航。
- 在聊天输入框点「添加文件或调用指令 → 文件」会打开 Android 文档选择器。选中的附件出现在当前草稿，可以移除后再发送。
- DSH WebUI 的「设置 → 打开配置文件」提供 DSH Sugar 内置编辑器与「用其他应用编辑」。支持 Android 文本编辑请求的应用能打开一份私有的临时副本。返回 Sugar 后确认「保存」，会校验 YAML 并检查文件版本。取消会保留当前配置。实际文件是 `/home/sugar/.dsh/profiles/web/cordis.patch.yml`。
- 账号的「登录」会打开手机默认浏览器中的 DeepSeek 授权页。DSH 保留原来的登录流程和本机回调。
- Android 的状态栏和手势栏透明覆盖 WebView。页面背景和弹层遮罩可以延伸到屏幕边缘，导航控件和输入区域按系统安全区布局。启动实测见[真机冷启动记录](android15-dsh-startup.md)。

## 项目结构

| 路径 | 职责 |
| --- | --- |
| `app/src/main/assets/pets` | 内置桌宠的名称和六状态动画。新增或重画套装见[桌宠制作指南](desktop-pets.md)。 |
| `shared/src/commonMain` | Kotlin Multiplatform 启动状态、旧回调隔离、本机工作区地址及导航边界 |
| `shared/src/commonTest` | 重试、浏览器就绪和同源导航测试 |
| `app/src/main/kotlin/app/dsh/sugar` | Android 启动页、重试、系统 WebView 和返回导航 |
| `app/src/main/kotlin/app/dsh/sugar/runtime` | 在 App 私有目录安装 Linux 环境，调用 Sucrune，验证 DSH 登录后将 Cookie 交给 WebView |
| `guest`、`scripts/prepare-guest.py` | 固定版本的 DSH、WebUI、ARM64 Node 与 Linux 用户工具的打包，以及本机登录信息交接 |

KMP 模块声明 Android、JVM、iOS 真机及 Apple Silicon 模拟器目标。当前随项目交付的 App 和本地 Linux 执行后端均为 Android 实现。

## 构建 Android APK

在 Windows 上准备 JDK 17、Android SDK 37、Python 3.11+、Git 和 Zig 0.17.1，并设置 `JAVA_HOME` 和 `ANDROID_HOME`。默认目录布局为相邻的 `DSH-Sugar/` 和 `Sucrune/`。

1. 在 Sucrune 生成与其源码匹配的 ARM64 validation APK。准备该项目的 `linux-rootfs`、`linux-bash`、`linux-stty`、`linux-node-pty` 四层资源以及 `.build/linux-test-assets/linux-toolbox/profile.bin`。
2. 在 DSH-Sugar 根目录运行构建脚本。它校验固定版本的 DSH 软件包，在打包时应用 `guest/plugin-management.patch`，运行 KMP 的 JVM 测试、组装 APK 并执行 Android lint。

   ```powershell
   .\scripts\build-android.ps1 -SucruneRoot ..\Sucrune -Python python
   ```

   构建会用 Zig 从固定版 DSH 的 C 源码编译 ARM64 Landlock 启动器。可用 `-Zig <路径>` 指定编译器，直接运行 Gradle 时可传 `-PsugarZig=<路径>`。

   已有固定 npm tarball 缓存时，可加 `-Tarballs <缓存目录> -Offline`。构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

   构建脚本会检查最终 rootfs 中的配置按钮适配与诊断预设依赖。`-SkipGuestPreparation` 也会检查复用的镜像。升级 DSH 时还要用真实 Host 激活预设，并在手机上验证配置隔离和会话持久化。见[升级验收](dsh-upgrades.md)。

3. 使用 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 安装，再从手机桌面打开 **DSH Sugar**。包名为 `app.dsh.sugar`。

## 首次启动与数据

首次启动从 APK 解包并校验用户环境。DSH 0.2.2-rc.3 的基础 rootfs 展开后约占 942 MB（898 MiB）。附加工具层、APK 和用户数据需要额外空间。下次启动使用已安装环境。系统目录与 `/home/sugar`、`/workspace` 分开，DSH 的设置、会话和工作文件保存在 App 私有数据中。

Sucrune 通过 `resident = true` 启动上游 `dsh web`。服务监听 `127.0.0.1` 的动态端口，宿主完成登录交换后才让 WebView 打开首页。首次使用模型时在 DSH 中配置供应商。APK 不含模型密钥。

应用使用一份 DSH Host 和 Web profile。每个工作区按需创建自己的 Sucrune 环境，同一项目的多个对话复用它。各项目通过同一个插件管理器管理全局插件。现在的运行时最多并行 4 个 Sucrune 执行，Host 占 1 个；其余名额可供项目使用。iOS 宿主仍在规划中。[项目、工作区与文件](projects-and-files.md)

## 已验证

2026-11-07 在 Android 15（API 35）ARM64 真机完成首次安装、镜像校验、DSH 自动启动、浏览器登录交换和 WebUI 渲染。首次使用页显示“登录”和“添加 API Key”，网页同时注册了“新建会话”和“发送消息”等控件。覆盖安装后复用原 Sucrune 环境，再点桌面入口仍只有一个活动执行。此轮未使用模型密钥发送消息。

修复默认工作区时，在出现问题的原安装上直接升级，确认 WebUI 自动选中“默认工作区”，DSH 登记的默认 ID 指向沙箱中的 `/workspace/deepseek-harness/default-workspace`。中英文 WebUI 可运行 `node scripts/check-default-workspace.cjs <WebView转发端口>` 复验选择状态。修复版通过 KMP 测试与 Android lint。验证用 debug APK 的 SHA-256 为 `1d760a6b6717b53001285b979833a5a788c27b4ebfa03f70fb05baeb49fbcaa9`。

同一台 Android 15 真机在旧版从 DSH 侧边栏新建终端时复现了 `forkpty(3) failed`。覆盖安装修复版后，从同一入口打开 Bash，确认标准输入、输出和错误都连接到 PTY，并在终端读到了 Bash 计算的结果。打开会话后可用 `node scripts/check-sidebar-terminal.cjs <WebView转发端口>` 复验；脚本会关闭自己新建的测试标签。

语音输入已在同一台 Android 15 真机复验。WebUI 在用户同意录音权限后可以打开音轨，SenseVoiceSmall（INT8）能从已有模型缓存进入就绪状态。通过插件的转写接口输入合成语音，英语和自动语言模式下的普通话均返回了预期词语。

同机保留 DSH 进程，重新加载 WebUI 后点「插件」，以 5 个插件开关出现为终点。旧版三轮为 3822、3953、3737 毫秒，优化版为 571、575、571 毫秒。同一 WebUI 返回已打开过的列表为 66、67、69 毫秒。正常读取只请求包列表与组件状态，可见组件的图标和说明由包列表提供。其他调用方仍可读取完整信息。

「自定义插件索引」旧版一次开启、关闭分别等 5.30 和 3.31 秒，保存后还需重启 DSH。原热加载组件在 Sucrune 中遇到 `watch ENOSYS`。Android 镜像为 DSH 精确监听的三个配置文件使用两秒间隔的状态检查。插件管理操作仍直接触发重载。在同一 DSH 进程里测试两轮，从点击到开关反映新状态并可操作，开启用时 592、575 毫秒，关闭用时 1549、1486 毫秒。四次操作均返回 `applied`，开启后「发现」页可见、两个组件均运行，关闭后该页和两个组件均移除。
