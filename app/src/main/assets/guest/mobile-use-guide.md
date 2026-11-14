# mobile-use

手机操作脚本用 Python 编写。`from mobile_use import phone` 导入当前任务的操作对象；选好主屏或虚拟屏后，通过同一个 `phone` 看屏、点击或发桌宠气泡。

Prefer `mobile_use` for phone interaction. Use connected ADB for device diagnostics, an explicit ADB request, or an action the selected driver lacks.

When the user only asks to open or operate an app, for example “打开计算器”, call `mobile_use({})` or `mobile_use({screen:"ask"})` so they can choose the screen. An app name alone does not specify the main screen. Use `{screen:"main"}` or `{screen:"virtual"}` only when the user explicitly specified that screen. Once `phase="active"`, keep using the returned screen for this turn; a fresh workspace Bash call or terminal attaches to the selection. With both screen modes off, `phase="adb"` means default-display ADB is connected. For `phase="adb_disconnected"` ask the user to connect it in Sugar.

Keep actions on the selected screen. For virtual ADB input use `input -d ID ...` with this task's current `displayId`; use `phone.observe(image=True)` for virtual screenshots and `phone.open_app(...)` to launch apps there. Main-screen ADB screenshots can stream directly into the workspace. On Android 10 the main driver provides the UI tree; Sugar wireless ADB requires Android 11 or later. After a rejected capture or an uncertain action, observe what happened before deciding the next step.

Use ordinary project Bash or a persistent project terminal. Give the Bash `description` a brief, user-facing goal in the user's language. `mobile_use run task.py` runs a saved script. Use `mobile_use run -` with a pipe or quoted heredoc for a one-off script. `mobile_use repl` keeps Python definitions alive within one terminal. In an authorized manual terminal, `mobile_use run --screen main task.py` or `--screen virtual` explicitly selects the target. The supervised project provides CPython and the standard library. Do not install uiautomator2 or OCR for this API. Keep temporary actions in the current session; write a reusable script in the workspace when requested.

### 手机操作中的桌宠气泡

在主屏或虚拟屏开始一个有意义的步骤时，在执行该步骤的同一段 Python 里调用 `phone.status("…")`。用户可能只看得到桌宠。简单动作可以直说；有选择、取舍或卡点时，用用户的语言讲一句「为什么接下来要这么做」。理由可以来自刚看到的情况，也可以来自用户交代的目标。

用当前对话的角色口吻写过程气泡，就像对眼前的用户说一句话。沿用这段对话已有的称呼、语气和表达习惯，让选择和反应带出性格。手机操作中，Bash 的 `description` 和 `phone.wait(..., description=...)` 也可能出现在同一个气泡里，写它们时用同样的口吻。比如一位平时说话俏皮的角色，看到最后一个名额和满排的商店，可以说「就剩一个位置啦，我先给咱们的主力留着。」没有人设就用自然的日常语言。读屏失败、需用户确认时仍要说清实际情况。

例如，表单确实标出了缺项，可以说「还差收件日期，我补上再提交。」游戏里若核实商店这张卡能凑齐升星，可以说「正好差她一张，买下就能升星啦。」按当时的事实自由措辞。拿不准时说准备核实什么，把推测和已经看到的结果分清；气泡只放适合给用户看的进展。

一次脚本可以连做数步。动作反馈或观察一旦确认已经换到新阶段，就在同一脚本中**先更新气泡，再等待**。例如，确认界面已经开战，就把选光环的旧气泡换成「开打啦，等这场打完，我看看这套阵容扛住没有。」如果只知道已经离开选择页，就说「已离开选择页，我先认一下现在在哪。」每次轮询仍处于同一阶段时，可以保留原句；新选择、结果或改了计划就再更新，连续答题也可以每题一句。

等待时用 `phone.wait(until=installed, timeout=45, description="正在等安装完成，出现「打开」后就启动它。")`。让 `installed` 检查当前页面上已确认的完成信号。可选的 `description` 在调用开始时显示一次，省略则沿用当前气泡。自己写轮询时，在进入循环前用 `phone.status(...)` 交代等待目的；当信号出现，再更新下一步。一条 Bash 命令内既执行又等待时，分别写出两阶段的目的，让用户跟得上。

对预期会完成的操作，按通常需要的时间设一次复查期限。超时只说明脚本还没认出约定的信号。用返回的最近观察核对进展；一旦发现挡住的弹窗、读屏失败，或决定换办法，在继续等待或重试**之前**说明已确认的情况和下一步。举例，看到安装页出现权限弹窗后可以说「安装停在权限确认，我把它留给你选。」若还不清楚，只说接下来准备查什么。只等用户开口的陪伴可以一直安静等待。

### 陪玩与持续看屏

回复用户时（包括通过桌宠插话），短答照常说。需要展开时，第一句就用这个角色的口吻回答用户想知道的事，再写依据和细节。比如确实看到了破盾和掉血的先后顺序，可以先说「刚才是护盾先破了，主力才掉血的。」桌宠会从长回复里取一句，对话保留全文；自然开头即可，加粗可选。

如果用户说「等我打开游戏就看一眼」，可以用 `phone.wait(until=..., image=False)` 等选定屏幕的前台包名变成目标应用，命中后再截一张图判断。只需看进入游戏后的画面时，这个流程无需开启历史采样。

先看用户想怎么陪。只需在被叫时回答，可以在当前回合等用户。需要「帮我看战斗，结算提醒我」时，先确认画面上可识别的结算信号，再安排本地观察。用户想让你接手游戏时，按其授权操作。没有要求代打的观战可以全程只读。

1. 用户要求「陪我玩」「接着看」「等我问你」时，用 `mobile_use({companion:true})` 选屏并保持当前回合。用户已经选过屏就沿用这次选择。只问一次「现在屏幕上是什么」用普通 `mobile_use({})`。需要持续看屏，同时还要中途判断、发言或接手时，用 DSH Bash 的 `run_in_background: true` 启动一个只读观察脚本，记住返回的 `jobId`。让 Python 作为这条命令运行到底；在 shell 里写 `&` 会让命令先退出。
2. 在观察脚本运行时用 `job_output` 等待，或直接在前台用 `phone.wait()`。也可以答完插话后安静等用户再开口，手机和回合仍保持原来的选择。用户叫停或约定的陪伴结束时，收掉自己启动的观察脚本，调用 `mobile_use({companion:false})` 再收尾。
3. **被动等待**用 `event = phone.wait()`。用户通过桌宠语音、快捷回复或对话发来本轮插话，脚本返回 `reason == "user"`。让当前脚本退出，读取 DSH 在下一步呈现的真实用户消息，再决定回答、继续还是结束。`phone.wait(timeout=15)` 到期只把控制权交还 Python；前台脚本若继续循环，模型仍在等它。
4. **按画面唤醒**用 `phone.wait(until=predicate, interval=2, timeout=45)`。自行选条件、间隔、时间上限。可访问界面先用控件树；自绘画面可指定 `tree=False, image=True, crop=(...), max_width=...`，在谓词中用当前截图的颜色或已确认的模板判断。正常播放的帧总会变化，判断与任务有关的标志。例如同时出现结算标题和可用的继续按钮。返回的 `event["observation"]` 是触发条件的那张，可直接用它判断下一步或操作。超时后检查最后一张、放宽或更换信号，或与用户沟通。捕获失败会抛出 `MobileError`，尤其要区分权限、断线与单纯没看到目标。
5. **回看过程**是单独的选项。需要回答「刚才发生了什么」时，在事件前调用 `phone.watch(interval=2)`。手机会在该任务的内存中按间隔采样；短暂提示优先用动作的 `feedback`，采样有可能错过。最近一分钟最多保留 60 帧、6 MiB，以先达到的上限为准。可按情况降低采样频率，使用 `phone.watch(None)` 暂停新采集。普通被动等待无需开启历史。任务结束、停止或连接失效时清掉自动缓存。
6. 触发后先看时间与布局，例 `phone.history(seconds=12, limit=4, images=False)`；需要像回放一样理解画面再读 `sheet = phone.history_sheet(seconds=12, limit=4)`。拼图从左到右读完一行再向下，画面由早到晚排列。每格标有阅读序号、帧编号和距今毫秒数，例如 `1/4  #27  8200 ms ago` 是这张拼图最早的一帧。若有图，可把它临时写入当前工作区，调用 DSH 的 `read_image` 看像素，读完清理本次临时文件。要放大某一帧可以 `phone.history(frame_id=n, limit=1)` 取缓存的 WebP；细字仍模糊时用当前屏幕的原分辨率局部截图。历史帧用于解释过去；点击前要有当前观察。用户要求交付截图时用独立文件名显式保存，这些文件会保留。

**脚本可以跑多久、多久采一帧、你多久回来看一次，由你分别决定。** 例如，页面明示还要等 120 秒，观察脚本可以覆盖整段倒计时；如果随时可能结算、弹窗或失败，按最早值得重新判断的时刻回来。用 `job_output({job_id: id, wait: true, timeout_ms: 10000})` 表示这次最多等 10 秒；这个数只是例子。任务提前结束或用户插话都可以提前返回。若返回时任务仍在运行，可以在同轮另开前台 Bash，用 `phone.observe()` 看当前屏幕，必要时用 `read_image` 看整图，再用 `phone.status(...)` 讲清新发现或计划。继续观察就等同一个 ID，可重新选择下次复看的时间。

长等待先找出能证明「还在进行」的标志，再列出见过的结束分支（成功、失败、需用户处理等）。后台观察器只读屏；命中、连续丢失进行中的标志或遇到错误时，输出证据并退出，以便模型提前处理。普通日志和桌宠气泡只用来传达进展。前后台同时读屏时，尽量在已取得的树或图上判断；另一方采样可能让基于旧观察 ID 的操作失效。如果决定提前接手，调用 `job_kill({job_id: id})`，再用 `job_output` 确认脚本已结束，重新观察后再操作。保留这一轮的选屏授权；`phone.interrupt()` 会结束整项手机任务。若之前单独开启过历史采样且接下来不再需要，用 `phone.watch(None)` 停采。

下面用有控件树的页面演示。把标题、按钮和进行中标志替换为在当前应用亲眼核对过的组合。等待期间在手机本地判断；只有需要模型接手时才传图。

```python
from pathlib import Path
from uuid import uuid4
from mobile_use import phone

def watch_result():
    missing = 0
    outcome = "页面已变化，需要查看"

    def ready(s):
        nonlocal missing, outcome
        for title, action in (("挑战成功", "继续"), ("挑战结束", "前往结算")):
            if s.find(text=title, visible=True) and s.find(text=action, visible=True, enabled=True):
                outcome = title
                return True
        missing = 0 if s.find(text="战斗中", visible=True) else missing + 1
        return missing >= 2

    event = phone.wait(until=ready, interval=2, timeout=45, image=True, max_width=640,
                       description="正在看这场战斗的结果。")
    if event["reason"] == "user":
        return
    if event["reason"] == "timeout":
        outcome = "到了复查时间，我看看当前画面。"
    phone.status(outcome)
    shot = event.get("observation") or phone.observe(tree=False, image=True, max_width=640)
    path = Path("/workspace") / f".mobile-review-{uuid4().hex}.png"
    print(outcome, shot.save_image(path))

watch_result()
```

在普通 DSH Bash 中，下一步对打印出的路径调用 `read_image`，读完清理这张临时图。若用户插话，直接处理新消息。若到了复查时间，先看最后一帧再决定是否继续等；遇到已结束的任务就收尾。图上控件太小时，按需要补一张清晰的局部截图。

游戏出现陌生页面时，先看当前画面或近期拼图再决定下一步。一旦当前任务完成就给用户结果。若用户约定持续陪玩，回答插话后可自行换条件、调间隔，继续在同一轮等待。安静阶段无需反复向模型发送相同帧，也无需开启新会话或额外的 Goal。

用户问「我在玩什么、现在是什么画面」时，先看最近一张仍能代表当前画面的全屏图，用前台应用信息辅助确认。一旦能说明当前场景，就可以回答。若用户点名问任务文字、角色等细节，或这个细节会改变你的判断，再针对那一处取图。整图上的文字很小时，用 `overview.display_rect(left, top, right, bottom)` 把在整图上看到的像素范围换算成设备坐标，再传给 `phone.observe(image=True, tree=False, crop=...)`。这样取得的是下一帧的原分辨率局部；`max_width` 只限制输出尺寸，不会把小图放大。

从主动看屏切到「只等我说话」时，先执行 `phone.watch(None)`，再更新桌宠文案并调用 `phone.wait()`。只暂停等待条件不会停止上一段开启的采样。回顾画面时，帧编号只能证明采到了图；要说出当时发生了什么，需要实际查看拼图或相关帧。用户要求图片只留内存时，可以在脚本中读取帧并判断；需要把像素交给模型时，遵循本次对图片保存的要求。

### When to do several actions in one script

When the current screen is familiar and the next actions are clear, run them in one script. Keep going while the script can identify the intended controls and tell locally whether it is on track. Use a fresh observation or the last action's feedback for the next targeted gesture. Send the screen back to the model when a new choice or an unexpected result needs interpretation. Reuse that observation for the next step.

For example, on a single-choice reward screen, select the intended card. If its checkmark appears and Confirm becomes enabled, tap Confirm once in the same script. An accessible control state or a known visual cue in a small crop can be enough. If the card opens a details panel or the button stays dim, return that view. After confirming, use the next screen to verify the result. If the outcome is unclear, inspect it before submitting again.

On recurring screens, a small function in your script can recognize the stage and decide what to do next:

- For native UI, look for a distinctive resource ID or label and the relevant `visible`, `enabled` or `selected` state. A tree-only observation can answer this without sending an image.
- For a drawn UI, confirm the foreground app and orientation before reusing a reference. From a frame where the control was visible, sample its stable interior, away from rounded edges, text and animated highlights. Pair it with a nearby layout cue. For a stable icon, search its expected region at the same scale with `phone.find_template`.
- When a known transition is expected soon, poll locally at short intervals with a deadline based on normal transition and capture time. On a match, take one clear next action in the same script and verify its effect. If the deadline expires, return `timeout` and the latest screen for inspection. Return early if the window changes or the cues conflict.

Example for a native screen where the target item exposes `selected`. Use the resource IDs you observed in that app. This function only reads the observation, so you can replay it against earlier snapshots before using its answer to drive input.

```python
def reward_step(screen, panel_id, item_id, confirm_id):
    def one(resource_id):
        hits = screen.find(resource_id=resource_id, visible=True)
        return hits[0] if len(hits) == 1 else None

    panel, item, confirm = one(panel_id), one(item_id), one(confirm_id)
    if not all((panel, item, confirm)):
        return "review", None
    if item.get("selected") is True and confirm.get("enabled") is True:
        return "confirm", confirm
    if item.get("selected") is False and item.get("enabled") is True and confirm.get("enabled") is False:
        return "select", item
    return "review", None
```

Call it on the current observation, tap its returned node once if appropriate, then observe the result. Reuse this pattern in your task script when a screen recurs. For a drawn game, replace the node checks with the visual cues you actually verified.

### 用 Python 写手机操作脚本

```python
from mobile_use import phone

receipt = phone.open_app("计算器")
print(receipt["package"])
screen = phone.observe()
print([n.get("text") for n in screen.nodes if n.get("text")][:12])
matches = screen.find(text="7")
if len(matches) == 1:
    phone.tap(screen, matches[0])
    updated = phone.observe()           # carry this observation into the next step
```

- `phone.open()` returns the selected screen and its capabilities. A manually enabled Android accessibility service serves the main screen. The virtual screen uses its separate authorized driver.
- For a screenshot you will show in a reply, keep a uniquely named file in the conversation's workspace. For example, `shot.save_image(f"calculator-{uuid.uuid4().hex}.png")` after `import uuid`. Link to that exact file in the reply. Multiple conversations can write to the same workspace, so reusing `result.png` can change the picture in an older reply.
- `observe(tree=True, image=False)` returns a dict-like observation; `.id`, `.nodes` and `.find(text=..., resource_id=...)` refer to that snapshot. Fields include node id, parent id, text, description, resource id, class and bounds.
- `shot = phone.observe(tree=False, image=True)`; `shot.save_image("screen.png")` writes the returned PNG. In DSH call the `read_image` tool with this workspace file's path to send actual pixels to an image-capable model. Keep the observation ID with the screenshot. To touch a point in the actual returned image use `phone.tap(shot, x=100, y=220, space="image")`. Choose a point inside the visible control, such as the center of its label or fill. Avoid the surrounding glow and decorative border.
- `phone.long_press(screen, node, duration_ms=650)`, `phone.swipe(screen, x, y, x2, y2, duration_ms=350)`, `phone.input(screen, node, text)`, `phone.back()`, `phone.home()`, and `phone.launch(package, activity=None)` use the same selected screen. Query capability or handle `MobileError.code` for operations a driver cannot perform.
- On either screen, open an app directly by its launcher name, e.g. `phone.open_app("计算器")`. This looks up the app on the device and opens it on the selected screen in one call. A matching app already in the foreground stays in place. If you already know the package, use `phone.launch(package)`. To inspect the foreground package, use `screen.get("package")` or `screen.package` on the current observation.
- Use the localized launcher name when possible. A unique partial name of at least two characters also works. On `ambiguous_app`, `MobileError.details["candidates"]` contains matching labels, packages and activities. If the user's request identifies one, use `phone.launch(package, activity)`; otherwise ask which app they mean. On `app_not_found`, check the launcher name with the user. For tasks that specifically need Android Recent apps on the main screen, use `phone.recents()`.
- For drag-and-drop, use `phone.drag(shot, x, y, x2, y2, space="image")` with both points from the same screenshot. The finger holds at the first point for `hold_ms` (default 650 ms), moves in a straight line for `duration_ms` (default 350 ms), then lifts. Each duration accepts 1–3000 ms. For display coordinates, omit `space`. A fresh observation can confirm where the item landed.
- `phone.find_template(shot, "icon.png", tolerance=12, max_matches=8)` searches the returned image for a fixed-size PNG up to 192×192 and 32 KiB. Tolerance accepts 0–64 and result count accepts 1–32. Check `status` (`unique`, `multiple`, `not_found`, `incomplete`), `ambiguous`, and `truncated` before using the match's image coordinates with `tap(..., space="image")`.
- `ink, backdrop = phone.sample_colors(shot, [(x1, y1), (x2, y2)], radius=2)` returns RGB triples from that observation's image, including any crop or resizing. Use a small neighborhood and a tolerance calibrated from known states. A toggle's appearance and availability can change separately: a speed control may dim during an animation while its setting stays the same. Identify the control, check its state and availability, and tap once only when a change is needed. If availability is unclear, wait for a familiar interactive view and verify the setting there.
- Use the next observation to verify the expected change and choose the next action. In animated scenes check the relevant region. If the feedback image already shows the next target clearly, reuse its observation ID. Finish when the current observation answers the request.
- `phone.close()` releases the script attachment. `phone.interrupt()` stops the task.

### When the screen doesn't change as expected

- **A tutorial dims the screen.** Read the current prompt and trace its pointer or highlight in the full image before choosing a crop. The page title can describe a later goal. Include both the callout and the highlighted control in a detail view; act once and check whether the tutorial advanced.
- **A drop snaps back or a short message may explain a rejection.** On the gesture that needs checking, pass `feedback=True` to `phone.drag`, `swipe`, `tap` or `long_press`. This requests one image as the gesture finishes. Start with the full screen if you don't know where a message appears. Once known, use `feedback={"crop": (left, top, right, bottom), "max_width": 720}` in display pixels. Read the returned image before waiting or opening another panel. For example:

  ```python
  result = phone.drag(shot, x, y, x2, y2, space="image", feedback=True)
  frame = result.get("feedback")
  if frame is not None:
      frame.save_image("drop-feedback.png")  # read_image on this workspace file
  else:
      print(result.get("feedback_error"), result.get("dispatch"))
  ```

  A dispatched gesture can still be rejected by the app. A missing or rate-limited frame does not establish what the message said. Observe the current state before deciding whether another action is appropriate.
- **The action has several visible stages.** When one frame leaves the outcome unclear, keep the before image, the immediate feedback, and optionally a short burst of follow-up observations in the same script, before the next input. Inspect the useful frames together in time order. If image composition is available, a labelled strip of crops can show a banner appearing and disappearing in one model call. Preserve enough resolution to read the banner, and keep the latest original observation for any subsequent tap. Use actual timestamps where available; rate limits and encoding can leave gaps. A settled screen often needs only one observation.
- **Confirm stays disabled.** Check the selection count, checkmark or highlight. A tap on a portrait may have opened details; find the selectable card or row and verify that the button becomes enabled.
- **A setting changes back on the next screen.** Read its current value before touching a toggle. If it already shows the requested state, continue. After changing it, verify the indicator once; a second tap can undo the change.
- **An enabled button seems unresponsive.** Crop the button with its visible edges and surrounding context. Tap inside its fill using coordinates in that crop, e.g. `phone.tap(crop_shot, x=cx, y=cy, space="image")`. Recheck the screen before changing press duration or switching drivers. Coordinates from a resized picture are only valid with its matching observation.
- **The tool reports a stale observation or a transport error.** Refresh the observation on the selected display and check the foreground app. After an uncertain submission, verify its effect before issuing another input. If you still can't advance, report the visible blocker and the last verified step in the user's language.

Keep temporary screenshots and UI trees for your own inspection in the current project workspace. Read nodes directly from `screen.nodes`. When the selected route is default-display ADB, run `adb exec-out screencap -p > screen.png` or `adb ui-dump > ui.xml` in project Bash. Both stream directly into a workspace file. Reserve Android shared storage (such as `/sdcard/Download`) for files the user asks to transfer or export there.

For a cropped screenshot use `phone.observe(tree=False, image=True, crop=(left, top, right, bottom), max_width=720)`. The crop uses the target display's current orientation and pixel coordinates. Returned images carry their exact transform. Keep `space="image"` when applying coordinates from that image. In ordinary DSH command mode save the PNG and `shot.save_metadata("shot.json")` in the workspace. Call `read_image` on the PNG, then use the saved `observation_id` for the action within the same active conversation task. A newer observation, action, rotation or expiry can invalidate the saved observation.

`mobile_use stdio` accepts one JSON object per line with `id` and `method`. Methods: `open` (optional `screen`), `eval` (`code`), `run` (`path`, optional `argv`), `resume`, `interrupt`, `close`. One evaluation may run at a time. Replies include `result` or `error`, captured `text`, `attachments`, and an `actions` list (up to 512 entries; `actions_omitted` counts additional actions). The list contains method names, observation IDs, dispatch and verification states or error codes. It omits input text, images, coordinates and credentials.

Inside a stdio evaluation or script, `answer = phone.needs_decision(shot, "Which button is Next? Return x and y in the attached image.")` pauses for the host. The `needs_decision` event carries `decision_id`, `observation_id`, image transform and a PNG attachment. After a single model inference send `{"id":2,"method":"resume","decision_id":"<event id>","observation_id":"<snapshot id>","decision":{"x":84,"y":61}}`. The script receives the JSON decision and can act or hand back control. A mismatched, repeated, late or interrupted response is rejected. The default wait is 45 seconds; an observation expires at 60 seconds at the latest. A correct answer ID ties a reply to the image it was based on; it does not prove a game on the same window still shows that question. Recheck the relevant region or a stable question marker if the scene changes autonomously. Consume all events and replies by ID, including the run/eval completion after resume.
