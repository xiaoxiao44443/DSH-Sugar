"""Exercise the project Python/stdio entrypoints against the actual framed wire contract."""
import base64
import json
import os
from pathlib import Path
import socketserver
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest

MODULE = Path(__file__).resolve().parents[1] / "app/src/main/assets/guest/mobile_use.py"


class Peer(socketserver.StreamRequestHandler):
    def handle(self):
        req = json.loads(self.rfile.readline(65537))
        assert req["op"] == "mobileUse" and req["token"] == "test-only"
        self.server.calls.append(req)
        method = req["method"]
        if method == "open":
            if self.server.starting > 0:
                self.server.starting -= 1
                body = {"ok": False, "error": {"code": "starting", "message": "Creating screen"}}
            else:
                body = {"ok": True, "sessionId": "opaque-1", "screen": req["args"].get("screen", "main")}
        else:
            assert req["sessionId"] == "opaque-1"
            body = {"ok": True}
            if method in self.server.action_errors:
                body = {"ok": False, "error": self.server.action_errors[method]}
            elif method in self.server.action_replies:
                body = self.server.action_replies[method]
            elif method == "check" and self.server.check_error:
                body = {"ok": False,"error":{"code":self.server.check_error,"message":"no longer current"}}
            elif method == "companion_wait":
                if self.server.wait_reason == "timeout":
                    time.sleep(req["args"]["timeout_ms"] / 1000)
                body["reason"] = self.server.wait_reason
            elif method == "observe":
                body.update(observation_id="s1", tree={"nodes": [{"id": "n1", "text": "Next"}]},
                            **self.server.observation_fields)
                if req["args"]["image"]:
                    body["image"] = {"mime_type": "image/png", "width": 8, "height": 4,
                                     "offset_x": 21, "offset_y": 30, "scale_x": 2.5, "scale_y": 1.75,
                                     "data": base64.b64encode(b"image bytes").decode()}
            elif method == "sample_colors":
                args = req["args"]
                if args["observation_id"] not in ("s1", "s2"):
                    body = {"ok": False, "error": {"code": "stale_observation", "message": "Observe this screen again."}}
                else:
                    body.update(observation_id=args["observation_id"], colors=[[x,y,17] for x,y in args["points"]])
            elif method == "tap" and req["args"]["observation_id"] == "starting-test":
                body = {"ok": False, "error": {"code": "starting", "message": "Action not ready"}}
            elif method in ("tap", "drag", "swipe", "long_press") and req["args"]["observation_id"] not in ("s1", "s2"):
                body = {"ok": False, "error": {"code": "stale_observation", "message": "stale"}}
            elif method in ("tap", "input", "drag", "swipe", "long_press", "recents"):
                body.update(dispatch="sent", verification="unknown")
                if "feedback" in req["args"]:
                    if self.server.feedback_error:
                        body["feedback_error"] = {"code": self.server.feedback_error, "message": "Capture failed"}
                    else:
                        body["feedback"] = {"ok": True, "observation_id": "s2", "feedback_delay_ms": 35,
                                            "image": {"mime_type": "image/png", "width": 40, "height": 30,
                                                      "data": base64.b64encode(b"feedback frame").decode()}}
            elif method == "open_app":
                body = self.server.open_app_reply
        payload = json.dumps(body).encode()
        for part in [payload[:8], payload[8:]]:
            self.wfile.write(struct.pack(">BI", 1, len(part)) + part)
        self.wfile.write(struct.pack(">BII", 3, 4, 0))


class MobileUseTest(unittest.TestCase):
    def setUp(self):
        self.server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Peer)
        self.server.calls = []
        self.server.starting = 0
        self.server.observation_fields = {}
        self.server.check_error = None
        self.server.feedback_error = None
        self.server.action_errors = {}
        self.server.action_replies = {}
        self.server.wait_reason = "timeout"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.env = {**os.environ, "SUGAR_ADB_PORT": str(self.server.server_address[1]), "SUGAR_ADB_TOKEN": "test-only"}

    def test_wait_publishes_one_description_before_polling(self):
        source = """
from mobile_use import phone
checks = []
def visible(screen):
    checks.append(screen.id)
    return len(checks) == 3
event = phone.wait(until=visible, timeout=8, interval=0.25, description="等安装完成后打开")
assert event["reason"] == "condition" and len(checks) == 3
"""
        cmd = [sys.executable, str(MODULE), "run", "-"]
        proc = subprocess.run(cmd, input=source.encode(), env=self.env, capture_output=True, timeout=10)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        calls = self.server.calls
        methods = [r["method"] for r in calls]
        self.assertEqual(methods[:3], ["open", "companion_status", "companion_wait"])
        self.assertEqual(calls[1]["args"], {"text": "等安装完成后打开"})
        self.assertEqual(methods.count("companion_status"), 1)
        self.assertEqual(methods.count("observe"), 3)
        self.assertGreaterEqual(methods.count("companion_wait"), 3)
        self.assertEqual(set(methods), {"open", "companion_status", "companion_wait", "observe", "close"})

    def test_crop_from_a_resized_and_offset_screenshot(self):
        source = """
from mobile_use import MobileError
overview = phone.observe(tree=False, image=True, crop=(21,30,41,37), max_width=8)
assert overview.display_rect(0,0,8,4) == (21,30,41,37)
detail = phone.observe(tree=False, image=True, crop=overview.display_rect(1,1,7,3))
for rect in ((-1,0,1,1), (0,0,9,4), (1,0,1,1), (0,0,1,float("nan"))):
    try: overview.display_rect(*rect)
    except ValueError: pass
    else: raise AssertionError(rect)
try: phone.observe().display_rect(0,0,1,1)
except MobileError as error: assert error.code == "image_unavailable"
else: raise AssertionError("accepted a tree-only observation")
"""
        for screen in ("main", "virtual"):
            with self.subTest(screen=screen):
                start = len(self.server.calls)
                result = subprocess.run([sys.executable, str(MODULE), "run", "--screen", screen, "-"],
                                        input=source.encode(), env=self.env, capture_output=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)
                calls = self.server.calls[start:]
                self.assertEqual([r["method"] for r in calls], ["open", "observe", "observe", "observe", "close"])
                self.assertEqual(calls[2]["args"], {"tree":False, "image":True,
                                                    "crop":{"left":23, "top":31, "right":39, "bottom":36}})

    def test_wait_immediate_returns_and_invalid_arguments(self):
        cmd = [sys.executable, str(MODULE), "run", "-"]
        for reason in ("user", "timeout"):
            self.server.wait_reason = reason
            for caption in (None, "在等你回复"):
                with self.subTest(reason=reason, caption=caption):
                    start = len(self.server.calls)
                    args = "timeout=0" if caption is None else f"timeout=0, description={caption!r}"
                    script = f"from mobile_use import phone\nassert phone.wait({args}) == {{'reason': {reason!r}}}"
                    proc = subprocess.run(cmd, input=script.encode(), env=self.env, capture_output=True, timeout=10)
                    self.assertEqual(proc.returncode, 0, proc.stderr)
                    methods = [r["method"] for r in self.server.calls[start:]]
                    self.assertEqual(methods, ["open"] + (["companion_status"] if caption else []) + ["companion_wait", "close"])
        self.server.calls.clear()
        script = """
from mobile_use import phone
for kwargs in ({"timeout": -1}, {"until": 0}, {"interval": 0}, {"description": " "},
               {"description": 42}, {"description": "a" * 241}):
    try:
        phone.wait(**{"description": "Waiting for a reply", **kwargs})
    except ValueError:
        pass
    else:
        raise AssertionError(kwargs)
"""
        proc = subprocess.run(cmd, input=script.encode(), env=self.env, capture_output=True, timeout=10)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self.server.calls, [])

    def test_saved_script(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            (folder / "saved.py").write_text(
                'from mobile_use import phone, MobileError\n'
                'assert input() == "script input"\n'
                'o=phone.observe(image=True)\n'
                'o.save_image("artifact.png")\n'
                'phone.tap(o,o.find(text="Next")[0])\n'
                'try: phone.tap("expired",x=1,y=2)\n'
                'except MobileError as exc: assert exc.code == "stale_observation"\n'
                'else: raise AssertionError("Stale target accepted")\n', encoding="utf-8")
            cmd = [sys.executable, str(MODULE), "run", "--screen", "virtual", "saved.py"]
            subprocess.run(cmd, input=b'script input\n', cwd=tmp, env=self.env, check=True, timeout=10)
            self.assertEqual((folder / "artifact.png").read_bytes(), b"image bytes")
        self.assertEqual([c["method"] for c in self.server.calls], ["open","observe","tap","tap","close"])
        self.assertEqual(self.server.calls[0]["args"], {"screen": "virtual"})
        self.assertEqual(self.server.calls[2]["args"], {"observation_id": "s1", "node_id": "n1"})

    def test_stdin_script_and_observation_package(self):
        source = (
            "import sys\n"
            "from mobile_use import phone as imported_phone\n"
            "assert imported_phone is phone and __name__ == '__main__'\n"
            "assert __file__ == '<stdin>' and sys.argv == ['-', 'arg']\n"
            "screen = phone.observe()\n"
            "assert screen.package == screen.get('package')\n"
            "try: screen.package = 'changed'\n"
            "except AttributeError: pass\n"
            "else: raise AssertionError('package must be read-only')\n"
            "print(screen.package)\n"
        )
        for target, fields in (("main", {"package": "test.main"}), ("virtual", {"package": "test.virtual"}),
                               ("main", {}), ("virtual", {"package": None})):
            with self.subTest(target=target, fields=fields):
                self.server.observation_fields = fields
                start = len(self.server.calls)
                proc = subprocess.run([sys.executable, str(MODULE), "run", "--screen", target, "-", "arg"],
                                      input=source, env=self.env, capture_output=True, text=True, timeout=10)
                self.assertEqual(proc.returncode, 0, proc.stderr)
                self.assertEqual(proc.stdout.strip(), str(fields.get("package")))
                calls = self.server.calls[start:]
                self.assertEqual([r["method"] for r in calls], ["open", "observe", "close"])
                self.assertEqual(calls[0]["args"], {"screen": target})

        failed = subprocess.run([sys.executable, str(MODULE), "run", "-"], env=self.env, capture_output=True,
                                input=b"phone.open()\nraise ValueError('boom')\n", timeout=10)
        self.assertNotEqual(failed.returncode, 0)
        self.assertIn(b'File "<stdin>", line 2', failed.stderr)
        self.assertEqual([r["method"] for r in self.server.calls[-2:]], ["open", "close"])

    def test_stdio_run_keeps_protocol_input_separate(self):
        with tempfile.TemporaryDirectory() as tmp, subprocess.Popen(
            [sys.executable, str(MODULE), "stdio"], env=self.env, cwd=tmp, text=True,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE
        ) as proc:
            # RPC paths refer to files even when named "-", and script input sees EOF.
            Path(tmp, "-").write_text("import sys\nprint(sys.stdin.buffer.read())\n", encoding="utf-8")
            watchdog = threading.Timer(8, proc.kill)
            watchdog.start()
            try:
                for request in ({"id": 1, "method": "run", "path": "-"}, {"id": 2, "method": "eval", "code": "2+2"}):
                    proc.stdin.write(json.dumps(request) + "\n")
                    proc.stdin.flush()
                    reply = json.loads(proc.stdout.readline())
                    self.assertTrue(reply["ok"], reply)
                    self.assertEqual(reply["id"], request["id"])
                    if request["id"] == 1:
                        self.assertEqual(reply["text"].strip(), "b''")
                    else:
                        self.assertEqual(reply["result"], 4)
            finally:
                proc.stdin.close()
                try:
                    proc.wait(timeout=5)
                finally:
                    watchdog.cancel()

    def test_open_app_by_label_on_selected_screen(self):
        env = {**self.env, "PYTHONIOENCODING": "utf-8"}
        for screen, dispatch in (("main", "already_foreground"), ("virtual", "sent")):
            with self.subTest(screen=screen), subprocess.Popen(
                [sys.executable, str(MODULE), "stdio"], env=env, encoding="utf-8",
                stdin=subprocess.PIPE, stdout=subprocess.PIPE
            ) as proc:
                def call(method, **args):
                    proc.stdin.write(json.dumps(dict(id=1, method=method, **args)) + "\n")
                    proc.stdin.flush()
                    return json.loads(proc.stdout.readline())

                start = len(self.server.calls)
                self.assertTrue(call("open", screen=screen)["ok"])
                self.server.open_app_reply = {"ok": True, "package": "test.calc", "label": "计算器",
                                              "dispatch": dispatch, "verification": "verified"}
                reply = call("eval", code='phone.open_app("计算器")')
                self.assertEqual(reply["result"], self.server.open_app_reply)
                self.assertEqual(reply["actions"], [{"method": "open_app", "dispatch": dispatch,
                                                     "verification": "verified"}])
                invalid = call("eval", code='phone.open_app(" ")')
                self.assertEqual(invalid["error"]["code"], "invalid_argument")
                self.assertEqual(invalid["actions"], [])
                candidates = [{"label": "计算器", "package": "test.calc", "activity": "test.calc.Main"},
                              {"label": "计算器", "package": "test.other", "activity": "test.other.Main"}]
                for error in ({"code": "ambiguous_app", "message": "Choose an app", "candidates": candidates},
                              {"code": "app_not_found", "message": "App not found"}):
                    self.server.open_app_reply = {"ok": False, "error": error}
                    denied = call("eval", code='phone.open_app("计算器")')
                    self.assertEqual(denied["error"], error)
                    self.assertEqual(denied["actions"], [{"method": "open_app", "error": error["code"]}])
                self.assertTrue(call("close")["ok"])
                proc.stdin.close()
                self.assertEqual(proc.wait(timeout=5), 0)
                requests = self.server.calls[start:]
                self.assertEqual([r["method"] for r in requests], ["open", "open_app", "open_app", "open_app", "close"])
                self.assertEqual(requests[0]["args"], {"screen": screen})
                self.assertTrue(all(r["args"] == {"name": "计算器"} for r in requests[1:-1]))

    def test_launch_task_provenance_is_data_and_keeps_selected_virtual_screen(self):
        for origin in ("borrowed", "created"):
            with self.subTest(origin=origin), subprocess.Popen(
                [sys.executable, str(MODULE), "stdio"], env=self.env, text=True,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE
            ) as proc:
                def call(method, **args):
                    proc.stdin.write(json.dumps(dict(id=1, method=method, **args)) + "\n")
                    proc.stdin.flush()
                    return json.loads(proc.stdout.readline())

                start = len(self.server.calls)
                self.assertTrue(call("open", screen="virtual")["ok"])
                receipt = {"ok": True, "package": "test.calc", "display_id": 7,
                           "task_id": 73, "user_id": 0, "task_origin": origin, "dispatch": "sent", "verification": "verified"}
                if origin == "borrowed":
                    receipt["source_display_id"] = 0
                self.server.action_replies = {"launch": receipt, "open_app": {**receipt, "label": "Calculator"}}
                for method, code in (("launch", 'phone.launch("test.calc")'),
                                     ("open_app", 'phone.open_app("Calculator")')):
                    reply = call("eval", code=code)
                    self.assertEqual(reply["result"], self.server.action_replies[method])
                    self.assertEqual(reply["actions"], [{"method": method, "dispatch": "sent", "verification": "verified"}])
                self.assertTrue(call("close")["ok"])
                proc.stdin.close()
                self.assertEqual(proc.wait(timeout=5), 0)
                requests = self.server.calls[start:]
                self.assertEqual([r["method"] for r in requests], ["open", "launch", "open_app", "close"])
                self.assertEqual([r["args"] for r in requests],
                                 [{"screen": "virtual"}, {"package": "test.calc"}, {"name": "Calculator"}, {}])
                self.assertTrue(all(r["sessionId"] == "opaque-1" for r in requests[1:]))

    def test_virtual_task_conflicts_do_not_reopen_or_switch_screen(self):
        with subprocess.Popen([sys.executable, str(MODULE), "stdio"], env=self.env, text=True,
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE) as proc:
            def call(method, **args):
                proc.stdin.write(json.dumps(dict(id=1, method=method, **args)) + "\n")
                proc.stdin.flush()
                return json.loads(proc.stdout.readline())

            self.assertTrue(call("open", screen="virtual")["ok"])
            expected = ["open"]
            for method, code in (("launch", 'phone.launch("test.calc")'),
                                 ("open_app", 'phone.open_app("Calculator")')):
                for error_code in ("app_on_other_display", "ambiguous_task", "app_busy", "task_origin_unknown"):
                    error = {"code": error_code, "message": "The app task cannot be borrowed safely."}
                    self.server.action_errors = {method: error}
                    result = call("eval", code=code)
                    self.assertEqual(result["error"], error)
                    self.assertEqual(result["actions"], [{"method": method, "error": error_code}])
                    expected.append(method)
                    self.assertEqual([r["method"] for r in self.server.calls], expected)
            self.assertTrue(call("close")["ok"])
            proc.stdin.close()
            self.assertEqual(proc.wait(timeout=5), 0)
        self.assertEqual([r["method"] for r in self.server.calls], expected + ["close"])
        self.assertEqual(self.server.calls[0]["args"], {"screen": "virtual"})
        self.assertTrue(all(r["sessionId"] == "opaque-1" for r in self.server.calls[1:]))

    def test_observation_metadata_preserves_nested_window_geometry_without_pixels(self):
        complete = {"display_id": 0, "window_id": 17, "task_id": 73, "package": "test.calc",
                    "bounds": [21, 30, 421, 630], "content_bounds": [21, 62, 421, 630],
                    "focused": True, "layer": 9, "bounds_source": "accessibility_window",
                    "geometry_signature": "display-0-window-17-v1"}
        minimal = {"display_id": 4, "window_id": 8, "package": "test.calc",
                   "bounds": [0, 0, 1080, 2400], "focused": False, "layer": 1,
                   "bounds_source": "accessibility_window", "geometry_signature": "display-4-window-8-v1"}
        for screen, window, provenance in (
            ("main", complete, {}),
            ("virtual", minimal, {"task_origin": "borrowed", "task_id": 73, "user_id": 0, "source_display_id": 0}),
            ("virtual", minimal, {"task_origin": "created", "task_id": 74, "user_id": 0}),
            ("main", None, {}),
        ):
            with self.subTest(screen=screen, window=window), tempfile.TemporaryDirectory() as tmp:
                fields = {"display": screen, "display_id": 0 if screen == "main" else 4, "width": 1080, "height": 2400,
                          "rotation": 0, "timestamp_ms": 1000, "image_timestamp_ms": 1001}
                if window is not None:
                    fields.update(window_id=window["window_id"], window=window)
                fields.update(provenance)
                self.server.observation_fields = fields
                source = ("import json\nshot = phone.observe(image=True)\n"
                          f"assert shot.get('window') == {window!r}\n"
                          f"assert shot.get('window_id') == {fields.get('window_id')!r}\n"
                          "shot.save_metadata('shot.json')\nprint(json.dumps(shot.metadata()))\n")
                start = len(self.server.calls)
                proc = subprocess.run([sys.executable, str(MODULE), "run", "--screen", screen, "-"],
                                      input=source, cwd=tmp, env=self.env, capture_output=True, text=True, timeout=10)
                self.assertEqual(proc.returncode, 0, proc.stderr)
                saved = json.loads(Path(tmp, "shot.json").read_text(encoding="utf-8"))
                self.assertEqual(saved, json.loads(proc.stdout))
                self.assertEqual(saved, {**fields, "observation_id": "s1", "image": {
                    "mime_type": "image/png", "width": 8, "height": 4,
                    "offset_x": 21, "offset_y": 30, "scale_x": 2.5, "scale_y": 1.75}})
                self.assertNotIn("tree", saved)
                self.assertNotIn("data", saved["image"])
                self.assertEqual([r["method"] for r in self.server.calls[start:]], ["open", "observe", "close"])

    def test_moved_window_stale_observation_is_not_retried(self):
        window = {"display_id": 0, "window_id": 17, "package": "test.calc", "bounds": [21, 30, 421, 630],
                  "focused": True, "layer": 9, "bounds_source": "accessibility_window", "geometry_signature": "before"}
        self.server.observation_fields = {"window_id": 17, "window": window}
        error = {"code": "stale_observation", "message": "The window moved. Observe again before acting.",
                 "window_id": 17, "geometry_signature": "after", "bounds": [41, 50, 441, 650]}
        self.server.action_errors = {"tap": error}
        with subprocess.Popen([sys.executable, str(MODULE), "stdio"], env=self.env, text=True,
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE) as proc:
            def call(method, **args):
                proc.stdin.write(json.dumps(dict(id=1, method=method, **args)) + "\n")
                proc.stdin.flush()
                return json.loads(proc.stdout.readline())

            self.assertTrue(call("eval", code="shot = phone.observe(image=True)")["ok"])
            reply = call("eval", code='phone.tap(shot, x=1, y=2, space="image")')
            self.assertFalse(reply["ok"], reply)
            self.assertEqual(reply["error"], error)
            self.assertEqual(reply["actions"], [{"method": "tap", "observation_id": "s1", "error": "stale_observation"}])
            self.assertTrue(call("close")["ok"])
            proc.stdin.close()
            self.assertEqual(proc.wait(timeout=5), 0)
        self.assertEqual([r["method"] for r in self.server.calls], ["open", "observe", "tap", "close"])
        self.assertEqual(self.server.calls[2]["args"], {"observation_id": "s1", "x": 1, "y": 2, "space": "image"})

    def test_run_shows_ambiguous_app_candidates(self):
        candidates = [{"label": "地图", "package": "test.maps", "activity": "test.maps.Entry"},
                      {"label": "地图", "package": "test.travel", "activity": "test.travel.Entry"}]
        self.server.open_app_reply = {"ok": False, "error": {"code": "ambiguous_app", "message": "Choose an app",
                                                            "candidates": candidates}}
        with tempfile.TemporaryDirectory() as tmp:
            task = Path(tmp, "open_app.py")
            task.write_text('phone.open_app("地图")', encoding="utf-8")
            result = subprocess.run([sys.executable, str(MODULE), "run", str(task)],
                                    env={**self.env, "PYTHONIOENCODING": "utf-8"}, capture_output=True,
                                    encoding="utf-8", timeout=10)
        self.assertEqual(result.returncode, 1)
        self.assertIn("ambiguous_app", result.stderr)
        self.assertEqual(json.loads(result.stderr.splitlines()[-1]), {"candidates": candidates})
        self.assertEqual([r["method"] for r in self.server.calls], ["open", "open_app", "close"])

    def test_recents_uses_selected_screen_without_sending_a_shell_command(self):
        with tempfile.TemporaryDirectory() as tmp:
            task = Path(tmp) / "recent.py"
            task.write_text('assert phone.recents()["dispatch"] == "sent"\n', encoding="utf-8")
            subprocess.run([sys.executable, str(MODULE), "run", str(task)], env=self.env, check=True, timeout=10)
        self.assertEqual([c["method"] for c in self.server.calls], ["open", "recents", "close"])
        self.assertEqual(self.server.calls[0]["args"], {})
        self.assertEqual(self.server.calls[1]["args"], {})

    def test_waits_only_for_open_never_replays_an_action(self):
        self.server.starting = 2
        with tempfile.TemporaryDirectory() as tmp:
            task = Path(tmp) / "pending.py"
            task.write_text('phone.tap("starting-test", x=1, y=2)\n', encoding="utf-8")
            completed = subprocess.run([sys.executable, str(MODULE), "run", str(task)], env=self.env,
                                       capture_output=True, timeout=10)
        self.assertEqual(completed.returncode, 1)
        self.assertIn(b"starting", completed.stderr)
        self.assertEqual([c["method"] for c in self.server.calls], ["open", "open", "open", "tap", "close"])

    def test_drag_uses_one_observation_and_one_dispatch(self):
        cases = [
            ("main", "phone.observe()", "", {"space": "display", "hold_ms": 650, "duration_ms": 350}),
            ("virtual", "phone.observe(image=True, crop=(20,30,220,150), max_width=100).id",
             ', space="image", hold_ms=780, duration_ms=240', {"space": "image", "hold_ms": 780, "duration_ms": 240}),
        ]
        for screen, observation, options, timing in cases:
            with self.subTest(screen=screen), subprocess.Popen(
                [sys.executable, str(MODULE), "stdio"], env=self.env, text=True,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE
            ) as proc:
                def call(method, **params):
                    proc.stdin.write(json.dumps({"id": 1, "method": method, **params}) + "\n")
                    proc.stdin.flush()
                    return json.loads(proc.stdout.readline())
                start = len(self.server.calls)
                self.assertTrue(call("open", screen=screen)["ok"])
                reply = call("eval", code=f"phone.drag({observation}, 4, 6, 30, 45{options})")
                self.assertTrue(reply["ok"], reply)
                self.assertEqual(reply["actions"], [{"method": "drag", "observation_id": "s1",
                                                     "dispatch": "sent", "verification": "unknown"}])
                requests = self.server.calls[start:]
                self.assertEqual([r["method"] for r in requests], ["open", "observe", "drag"])
                self.assertEqual(requests[0]["args"], {"screen": screen})
                self.assertEqual(requests[2]["args"], {"observation_id": "s1", "x": 4, "y": 6, "x2": 30, "y2": 45, **timing})
                for invalid in ("hold_ms=0", "duration_ms=3001", "hold_ms=2.5"):
                    denied = call("eval", code=f'phone.drag("s1", 4, 6, 30, 45, {invalid})')
                    self.assertFalse(denied["ok"])
                    self.assertEqual(denied["actions"], [])
                rejected = call("eval", code='phone.drag("expired", 4, 6, 30, 45)')
                self.assertEqual(rejected["actions"], [{"method": "drag", "observation_id": "expired",
                                                        "error": "stale_observation"}])
                self.assertEqual([r["method"] for r in self.server.calls[start:]], ["open", "observe", "drag", "drag"])
                self.assertTrue(call("close")["ok"])
                proc.stdin.close()
                self.assertEqual(proc.wait(timeout=5), 0)

    def test_sample_colors_reads_the_selected_observation(self):
        for screen in ("main", "virtual"):
            start = len(self.server.calls)
            code = '''
from mobile_use import MobileError
shot = phone.observe(tree=False, image=True, crop=(20,20,36,28), max_width=8)
assert phone.sample_colors(shot, [(7,3),(0,0),(7,3)], radius=2) == [(7,3,17),(0,0,17),(7,3,17)]
assert phone.sample_colors(shot.id, [(0,0)]) == [(0,0,17)]
for points,radius in (([],0), ([(1,1)]*65,0), ([(1,True)],0), ([(1.5,2)],0), ([(1,-1)],0),
                      ([(8,0)],0), ([(0,4)],0), ([(1,1)],9), ([(1,1)],True)):
    try: phone.sample_colors(shot,points,radius)
    except ValueError: pass
    else: raise AssertionError("Invalid sampling parameters accepted")
try: phone.sample_colors("expired",[(0,0)])
except MobileError as e: assert e.code == "stale_observation"
else: raise AssertionError("Stale observation accepted")
try: phone.sample_colors(phone.observe(),[(0,0)])
except MobileError as e: assert e.code == "image_unavailable"
else: raise AssertionError("Tree-only observation accepted")
'''
            with self.subTest(screen=screen):
                proc = subprocess.run([sys.executable, str(MODULE), "run", "--screen", screen, "-"], env=self.env,
                                      input=code, text=True, capture_output=True, timeout=8)
                self.assertEqual(proc.returncode, 0, proc.stderr)
                calls = self.server.calls[start:]
                self.assertEqual([c["method"] for c in calls],
                                 ["open", "observe", "sample_colors", "sample_colors", "sample_colors", "observe", "close"])
                self.assertEqual(calls[2]["args"], {"observation_id":"s1","points":[[7,3],[0,0],[7,3]],"radius":2})
                self.assertEqual(calls[3]["args"]["radius"], 0)

    def test_optional_gesture_feedback(self):
        for screen in ("main", "virtual"):
            with self.subTest(screen=screen), subprocess.Popen(
                [sys.executable, str(MODULE), "stdio"], env=self.env, text=True,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE
            ) as proc:
                def call(method, **params):
                    proc.stdin.write(json.dumps({"id": 1, "method": method, **params}) + "\n")
                    proc.stdin.flush()
                    return json.loads(proc.stdout.readline())
                try:
                    call("open", screen=screen)
                    for method, coords in (("tap", "x=1,y=2"), ("long_press", "x=1,y=2"), ("swipe", "1,2,3,4"), ("drag", "1,2,3,4")):
                        start = len(self.server.calls)
                        reply = call("eval", code=f"(r := phone.{method}(phone.observe(), {coords}, feedback={{'crop':(1,2,81,62), 'max_width':40}}))")
                        self.assertTrue(reply["ok"], reply)
                        self.assertEqual([r["method"] for r in self.server.calls[start:]], ["observe", method])
                        self.assertEqual(self.server.calls[-1]["args"]["feedback"],
                                         {"crop": {"left": 1, "top": 2, "right": 81, "bottom": 62}, "max_width": 40})
                        self.assertEqual(reply["result"]["dispatch"], "sent")
                        self.assertEqual(reply["result"]["verification"], "unknown")
                        self.assertEqual(reply["result"]["feedback"]["feedback_delay_ms"], 35)
                        self.assertEqual(reply["result"]["feedback"]["image"]["attachment"], 0)
                        self.assertEqual(base64.b64decode(reply["attachments"][0]["data"]), b"feedback frame")
                        self.assertNotIn(reply["attachments"][0]["data"], json.dumps(reply["result"]))
                        self.assertNotIn(reply["attachments"][0]["data"], call("eval", code="print(r, r['feedback'])")["text"])
                        sampled = call("eval", code="phone.sample_colors(r['feedback'], [(1,2)])")
                        self.assertEqual(sampled["result"], [[1,2,17]])
                        self.assertEqual(sampled["actions"], [])
                        followup = call("eval", code="phone.tap(r['feedback'], x=5, y=8, space='image')")
                        self.assertTrue(followup["ok"], followup)
                        self.assertEqual(self.server.calls[-1]["args"]["observation_id"], "s2")
                        self.assertNotIn("feedback", self.server.calls[-1]["args"])
                    self.server.feedback_error = "screenshot_rate_limited"
                    failed = call("eval", code="phone.drag(phone.observe(), 1,2,3,4, feedback=True)")
                    self.assertTrue(failed["ok"], failed)
                    self.assertEqual(failed["result"]["feedback_error"]["code"], "screenshot_rate_limited")
                    self.assertEqual(failed["actions"][0]["dispatch"], "sent")
                    self.assertEqual(len(failed["actions"]), 1)
                    self.assertFalse(failed.get("attachments"))
                    self.server.feedback_error = None
                    start = len(self.server.calls)
                    for bad in ("{'crop':(3,1,3,4)}", "{'max_width':-2}", "{'movies':5}"):
                        invalid = call("eval", code=f"phone.tap('s1', x=1,y=2, feedback={bad})")
                        self.assertFalse(invalid["ok"], invalid)
                    self.assertEqual(len(self.server.calls), start)
                finally:
                    proc.stdin.close()
                    proc.wait(timeout=5)

    def test_stdio_state_attachments_and_interrupt(self):
        with subprocess.Popen([sys.executable, str(MODULE), "stdio"], env=self.env, text=True,
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE) as process:
            def send(ident, method, **args):
                process.stdin.write(json.dumps({"id": ident, "method": method, **args}) + "\n")
                process.stdin.flush()
            def receive():
                return json.loads(process.stdout.readline())
            send(1, "open")
            self.assertTrue(receive()["ok"])
            send(2, "eval", code="def total(a, b): return a + b")
            self.assertTrue(receive()["ok"])
            send(3, "eval", code="total(3, 5)")
            self.assertEqual(receive()["result"], 8)
            send(4, "eval", code="phone.observe(image=True)")
            reply = receive()
            self.assertEqual(reply["result"]["image"]["attachment"], 0)
            self.assertEqual(base64.b64decode(reply["attachments"][0]["data"]), b"image bytes")
            send(5, "eval", code="while True:\n    pass")
            send(6, "interrupt")
            first, second = receive(), receive()
            indexed = {r["id"]: r for r in [first, second]}
            self.assertTrue(indexed[6]["ok"])
            self.assertEqual(indexed[5]["error"]["code"], "interrupted")
            send(7, "close")
            self.assertTrue(receive()["ok"])
            process.stdin.close()
            self.assertEqual(process.wait(timeout=10), 0)

    def test_visual_decision_during_run(self):
        with tempfile.TemporaryDirectory() as tmp, subprocess.Popen(
            [sys.executable, str(MODULE), "stdio"], env=self.env, cwd=tmp, text=True,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE
        ) as proc:
            def send(i, method, **args):
                proc.stdin.write(json.dumps(dict(id=i, method=method, **args)) + "\n")
                proc.stdin.flush()
            def read():
                return json.loads(proc.stdout.readline())
            path = Path(tmp) / "quiz.py"
            path.write_text(
                'shot=phone.observe(tree=False,image=True,crop=(10,20,210,120),max_width=100)\n'
                'shot.save_metadata("shot.json")\n'
                'answer=phone.needs_decision(shot,"select the next icon")\n'
                'phone.tap(shot, x=answer["x"], y=answer["y"], space="image")\n'
                'phone.input(phone.observe(),"n1","private input")\n',encoding="utf-8")
            send(1, "run", path=str(path))
            ev = read()
            self.assertEqual((ev["id"], ev["event"], ev["observation_id"]), (1, "needs_decision", "s1"))
            self.assertEqual(len(ev["attachments"]), 1)
            self.assertNotIn("data", json.loads((Path(tmp)/"shot.json").read_text())["image"])
            args = dict(observation_id=ev["observation_id"], decision_id=ev["decision_id"], decision={"x": 3,"y": 4})
            send(2, "resume", **{**args,"observation_id":"wrong"})
            self.assertEqual(read()["error"]["code"], "stale_decision")
            send(3, "resume", **args)
            replies = [read(), read()]
            done = next(r for r in replies if r["id"] == 1)
            self.assertTrue(done["ok"])
            self.assertEqual([(r["method"],r["dispatch"]) for r in done["actions"]], [("tap","sent"),("input","sent")])
            self.assertNotIn("private input", json.dumps(done))
            send(4, "resume", **args)
            self.assertEqual(read()["error"]["code"], "stale_decision")
            recorded = self.server.calls
            self.assertEqual(recorded[1]["args"]["crop"], dict(left=10,top=20,right=210,bottom=120))
            self.assertEqual([r["args"]["space"] for r in recorded if r["method"]=="tap"], ["image"])
            actions = [r["method"] for r in recorded]
            self.assertEqual(actions[actions.index("tap")-1], "check")

            send(5, "eval", code='phone.needs_decision(phone.observe(image=True),"wait",timeout=.25)')
            ev = read()
            self.assertEqual(ev["event"], "needs_decision")
            expired = read()
            self.assertEqual(expired["error"]["code"], "stale_decision")
            send(6, "resume", observation_id=ev["observation_id"],decision_id=ev["decision_id"],decision=1)
            self.assertEqual(read()["error"]["code"], "stale_decision")
            send(7, "eval", code='phone.needs_decision(phone.observe(image=True),"wait")')
            ev = read()
            send(8, "interrupt")
            first,second=read(),read()
            result=next(r for r in [first,second] if r["id"]==7)
            self.assertEqual(result["error"]["code"],"interrupted")
            send(9,"resume",observation_id=ev["observation_id"],decision_id=ev["decision_id"],decision=1)
            self.assertEqual(read()["error"]["code"],"stale_decision")
            proc.stdin.close()
            self.assertEqual(proc.wait(timeout=5),0)

    def test_decision_observes_native_stop_and_validates_resume(self):
        with subprocess.Popen([sys.executable, str(MODULE), "stdio"], env=self.env, text=True,
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE) as proc:
            def send(request):
                proc.stdin.write(json.dumps(request)+"\n")
                proc.stdin.flush()
            def read():
                return json.loads(proc.stdout.readline())
            ask = 'phone.needs_decision(phone.observe(image=True), "which button?")'
            send({"id":1,"method":"eval","code":ask})
            ev=read()
            self.server.check_error = "stale_observation"
            send({"id":2,"method":"resume","decision_id":ev["decision_id"],"observation_id":ev["observation_id"],"decision":"A"})
            responses = [read(), read()]
            self.assertEqual({r["id"] for r in responses}, {1,2})
            self.assertTrue(all(r["error"]["code"]=="stale_observation" for r in responses))
            self.server.check_error = None
            send({"id":3,"method":"eval","code":ask})
            self.assertEqual(read()["event"], "needs_decision")
            self.server.check_error = "session_closed"
            self.assertEqual(read()["error"]["code"],"session_closed")
            proc.stdin.close()
            self.assertEqual(proc.wait(timeout=5),0)


if __name__ == "__main__":
    unittest.main()
