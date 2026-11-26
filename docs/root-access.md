# Android Root

在 Sugar 的连接设置中开启 Root，并在手机的 Root 管理器中允许 Sugar。连接状态显示授权检查结果，授权到期后可重新检查。

## 工作区命令

从工作区 Bash 调用 `sugar-root`。Python 可通过 `subprocess` 调用同一入口。

```sh
sugar-root --status
sugar-root -c 'id'
sugar-root --script ./phone-info.sh
```

| 参数 | 用途 |
| --- | --- |
| `--status` | 返回 Root 的启用、可用和检查状态 |
| `-c COMMAND` | 在 Android `/system/bin/sh` 中执行完整命令文本 |
| `--script FILE` | 读取工作区中的 UTF-8 shell 脚本，将本次读取的完整内容送审和执行 |
| `--help` | 显示用法 |

```python
import subprocess

result = subprocess.run(
    ["sugar-root", "-c", "id"],
    capture_output=True,
    text=True,
    check=True,
)
print(result.stdout)
```

命令及脚本最多 16 KiB。脚本支持 UTF-8 BOM 和 CRLF 换行，读取后会规范为 UTF-8 文本和 LF 换行。审批与执行使用同一份已读取内容，等待审批期间对源文件的修改在下一次调用生效。

脚本由 Android `/system/bin/sh` 执行。脚本里的 `/data`、`/storage` 等路径属于手机系统。工作区中的 `/workspace`、Linux Bash 和 Python 属于项目环境，`--script` 负责传递脚本内容。需要两边配合时，在工作区 Python 中组织步骤并调用 `sugar-root`。

标准输出、标准错误和退出码返回工作区。可使用 shell 重定向保存输出。命令使用非交互模式，标准输入为 `/dev/null`。

## 会话权限与手动终端

Agent 的命令遵循当前会话权限。

- 普通权限通过 DSH 的审批卡展示完整脚本，只有“允许一次”后才执行。
- 完全权限使用已选择的 `danger-full-access` 预设，直接执行。
- Auto、其他预设和子智能体仍需通过审批。会话禁止审批时，请求会被拒绝。
- 拒绝、取消或审批通道不可用都会结束本次请求。审批等待没有自动放行机制。

Sugar 的手动终端使用可信的用户终端入口。用户亲手输入的命令按直接授权执行，仍需启用 Sugar Root 并获得 Root 管理器授权。手动身份来自宿主创建终端的记录。

每个工作区进程持有绑定原会话、原工作区的临时设备凭据。Agent 轮次结束、会话权限改变或 Agent 释放会取消其正在审批和执行的 Root 请求。持久终端在下一轮调用时重新检查当前会话权限。关闭终端、取消调用或禁用 Sugar Root 也会结束相应请求。

取消后的系统状态应重新检查，命令中已经完成的修改会保留。

## 手机操作与 ADB

手机看屏、点击和虚拟屏操作继续使用 `mobile_use`，按返回的屏幕和驱动信息执行。Root 系统管理说明通过 `android-root` 技能提供。

`adb shell su -c ...` 使用 ADB Shell 用户的 Root 管理器授权。`sugar-root` 使用 Sugar 应用自己的授权。两者在 Root 管理器中分别管理。

## 信任范围

审批放在 DSH 宿主插件中是有意的产品取舍：复用同一套会话权限与审批界面，保留用户在完全权限模式、手动终端中直接管理手机的能力，不重复增加原生逐条确认。Root 管理器对 Sugar 的系统授权仍然独立存在。这套审批依赖宿主代码和配置可信，不用于防御能够改写共享插件或控制配置的代码；不能把提示词或审批界面本身当成对这些代码的隔离边界。

所有工作区共享的 `.dsh`、DSH 宿主插件和宿主权限服务属于可信控制面。修改这些内容会影响所有会话的宿主行为。会话审批依赖这些服务及其配置的完整性。Android Root 权限由手机 Root 管理器授予 Sugar，Sugar 再按照当前会话和终端来源执行请求。

## 开发验证

本地假设备回归覆盖命令传输、权限、取消和既有 ADB 行为。

Root CLI 在请求存续期间使用一个不阻止进程退出的 250 毫秒空计时器，让 Sucrune 中空闲的 Node 事件循环能及时处理外部取消信号；请求结束就清除。它不轮询手机、不发送网络请求，也不让 Agent 唤醒。收到取消信号后关闭设备连接，由 Sugar 结束对应的 Android Root 进程。

```sh
node scripts/check-root-permissions.cjs
node scripts/check-root-cli.cjs
node scripts/check-adb-cli-sessions.cjs
node scripts/check-guest-adb.cjs
```

真机验收应从普通会话和手动终端分别调用 `sugar-root --status` 与 `sugar-root -c 'id'`，检查普通会话审批、拒绝、取消、完全权限和终端关闭。多行脚本的审批卡应保留换行并允许滚动查看完整内容。

### 2026-11-16 真机验证

在 Android 15 的 OnePlus Ace 5 Pro、SukiSU 授权环境中完成以下检查。测试使用 Sugar 自己的应用身份；无线 ADB 未连接，无障碍服务未启用。

- 普通 Agent 会话拒绝审批后不执行；允许一次返回 UID 0；完全访问会话直接返回 UID 0。
- 工作区 Bash、Python 和 `--script` 可执行；标准输出、错误输出和退出码分别正确返回。
- 主屏与虚拟屏分别完成计算器 `1+1=2`，同时取得 UI 树、截图和动作后反馈。
- 两个虚拟屏与 Root 主屏共存，关闭一块不影响其余屏幕；最后关闭后助手进程退出。
- 原生取消、关闭 Root 开关均清理脚本进程组。工作区 `SIGTERM`、`SIGINT`、`SIGHUP` 约 0.27 秒退出，父子进程均已清理。
- 通过真实 Python 手机 SDK 打开虚拟屏时返回 `driver="root"`；`adb`、`mobile_use` 的旧工作区入口可自动更新。

此记录覆盖上述设备环境；其他 Root 管理器和系统版本仍需在对应设备上验证。
