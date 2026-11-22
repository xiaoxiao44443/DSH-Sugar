"""Operate the Sugar phone screen selected for the current DSH conversation."""
import argparse
import base64
import code
import contextlib
import io
import json
import math
import os
from pathlib import Path
import runpy
import socket
import struct
import sys
import threading
import time
import traceback
import uuid


class MobileError(RuntimeError):
    def __init__(self, code, message, **details):
        super().__init__(message)
        self.code = code
        self.details = details

    def as_dict(self):
        return {"code": self.code, "message": str(self), **self.details}


def _exact(reader, count):
    data = reader.read(count)
    if len(data) != count:
        raise EOFError("Mobile connection closed before the response was complete.")
    return data


def _request(method, session, args):
    port = os.environ.get("SUGAR_ADB_PORT", "")
    token = os.environ.get("SUGAR_ADB_TOKEN")
    if not token or not port.isdigit() or not 0 < int(port) <= 65535:
        raise MobileError("workspace_required", "Run this command in a Sugar project Bash or terminal.")
    payload = json.dumps({"op": "mobileUse", "token": token, "method": method,
                          "sessionId": session, "args": args}, separators=(",", ":"), ensure_ascii=False).encode() + b"\n"
    if len(payload) > 65536:
        raise ValueError("Mobile request exceeds the 64 KiB header limit.")
    stdout, stderr = bytearray(), bytearray()
    try:
        with socket.create_connection(("127.0.0.1", int(port)), timeout=10) as conn:
            conn.settimeout(60)
            conn.sendall(payload)
            with conn.makefile("rb") as reader:
                while True:
                    kind, size = struct.unpack(">BI", _exact(reader, 5))
                    if size > 65536 or len(stdout) + len(stderr) + size > 64 * 1024 * 1024:
                        raise ValueError("Malformed mobile response size.")
                    part = _exact(reader, size)
                    if kind == 3 and size == 4:
                        exit_code, = struct.unpack(">i", part)
                        break
                    if kind not in (1, 2):
                        raise ValueError("Malformed mobile response frame.")
                    (stdout if kind == 1 else stderr).extend(part)
    except (OSError, EOFError) as exc:
        raise MobileError("transport_lost", str(exc), outcome="unknown") from exc
    if exit_code:
        raise MobileError("request_failed", stderr.decode(errors="replace").strip() or f"Mobile bridge exited {exit_code}")
    reply = json.loads(stdout)
    if not isinstance(reply, dict):
        raise ValueError("Mobile response must be an object.")
    if reply.get("ok") is False:
        failure = dict(reply.get("error") or {})
        raise MobileError(failure.pop("code", "request_failed"), failure.pop("message", "Mobile request failed"), **failure)
    return reply


class Observation(dict):
    def __repr__(self):
        if not (self.get("image") or {}).get("data"):
            return super().__repr__()
        value, _ = _structured(self)
        return repr(value)

    @property
    def id(self):
        return self["observation_id"]

    @property
    def package(self):
        """Foreground package reported in this observation, if available."""
        return self.get("package")

    @property
    def nodes(self):
        return (self.get("tree") or {}).get("nodes", [])

    def find(self, **fields):
        """Return all matching nodes; never silently select the first duplicate."""
        return [node for node in self.nodes if all(node.get(k) == v for k, v in fields.items())]

    def display_rect(self, left, top, right, bottom):
        """Map a rectangle in this image to display coordinates for observe(crop=...), wait or feedback.

        Right and bottom are exclusive. A subsequent observation captures a new frame;
        use a fresh overview if the display rotated or the target moved.
        """
        image = self.get("image")
        if not image:
            raise MobileError("image_unavailable", "This observation does not include a screenshot.")
        if not all(type(v) in (int, float) and math.isfinite(v) for v in (left, top, right, bottom)):
            raise ValueError("Supply four finite screenshot coordinates.")
        if not (0 <= left < right <= image["width"] and 0 <= top < bottom <= image["height"]):
            raise ValueError("The rectangle must be nonempty and inside this observation image.")
        # Preserve edge pixels when the thumbnail covers fractional display coordinates.
        return (math.floor(image["offset_x"] + left * image["scale_x"]),
                math.floor(image["offset_y"] + top * image["scale_y"]),
                math.ceil(image["offset_x"] + right * image["scale_x"]),
                math.ceil(image["offset_y"] + bottom * image["scale_y"]))

    def save_image(self, destination):
        image = self.get("image")
        if not image or not image.get("data"):
            raise MobileError("image_unavailable", "This observation does not include an image.")
        path = Path(destination)
        path.write_bytes(base64.b64decode(image["data"], validate=True))
        return path

    def metadata(self):
        """Copy observation geometry and provenance without image pixels or UI text."""
        result = {k: self[k] for k in ("observation_id", "display", "display_id", "width", "height", "rotation",
                                         "window_id", "window", "task_origin", "task_id", "user_id", "source_display_id",
                                         "timestamp_ms", "image_timestamp_ms", "feedback_delay_ms") if k in self}
        if self.get("image"):
            result["image"] = {k: v for k, v in self["image"].items() if k != "data"}
        return result

    def save_metadata(self, destination):
        path = Path(destination)
        path.write_text(json.dumps(self.metadata(), ensure_ascii=False), encoding="utf-8")
        return path


class ActionReceipt(dict):
    """A dispatched action and optionally its first post-action observation."""
    def __repr__(self):
        value, _ = _structured(self)
        return repr(value)


class HistoryFrame(dict):
    """A read-only moment or contact sheet from this turn. Has no input observation ID."""
    save_image = Observation.save_image
    __repr__ = Observation.__repr__


_evaluation = threading.local()
_action_methods = {"tap", "long_press", "swipe", "drag", "input", "launch", "open_app", "back", "home", "recents"}


class Phone:
    def __init__(self, screen=None):
        if screen not in (None, "main", "virtual"):
            raise ValueError("screen must be main or virtual")
        self.screen = screen
        self.session_id = None
        self.connection = None
        self._closed = False
        self._lock = threading.Lock()
        self._ending = threading.Event()
        self._current_observation = None

    def open(self):
        with self._lock:
            if self._ending.is_set():
                raise MobileError("session_closed", "Open a new Phone instance to start a new session.")
            if self.session_id is None:
                args = {"screen": self.screen} if self.screen else {}
                deadline = time.monotonic() + 20
                while True:
                    try:
                        self.connection = _request("open", None, args)
                        break
                    except MobileError as error:
                        remaining = deadline - time.monotonic()
                        if error.code != "starting" or remaining <= 0:
                            raise
                        if self._ending.wait(min(0.25, remaining)):
                            raise MobileError("session_closed", "Screen selection was interrupted.") from error
                self.session_id = self.connection["sessionId"]
            return self.connection

    def _call(self, method, args, *, feedback=False):
        if feedback is not False:
            options = {} if feedback is True else feedback
            if not isinstance(options, dict) or set(options) - {"crop", "max_width"}:
                raise ValueError("feedback must be True or a mapping with crop and/or max_width.")
            capture = {}
            if "max_width" in options:
                width = options["max_width"]
                if type(width) is not int or width < 1:
                    raise ValueError("max_width must be a positive integer.")
                capture["max_width"] = width
            if "crop" in options:
                rect = options["crop"]
                if not isinstance(rect, (tuple, list)) or len(rect) != 4 or not all(
                        type(v) in (int, float) and math.isfinite(v) for v in rect):
                    raise ValueError("crop requires four finite display coordinates.")
                left, top, right, bottom = rect
                if left >= right or top >= bottom:
                    raise ValueError("crop must be a nonempty rectangle.")
                capture["crop"] = dict(left=left, top=top, right=right, bottom=bottom)
            args = {**args, "feedback": capture}
        session = self.open()["sessionId"]
        if method not in _action_methods:
            return _request(method, session, args)
        self._current_observation = None
        entry = {"method": method}
        if "observation_id" in args:
            entry["observation_id"] = args["observation_id"]
        try:
            started = time.monotonic()
            reply = _request(method, session, args)
            if feedback is not False:
                reply = ActionReceipt(reply)
                if "feedback" in reply:
                    shot = Observation(reply["feedback"])
                    shot._captured = started
                    self._current_observation = shot.id
                    reply["feedback"] = shot
            for key in ("dispatch", "verification"):
                if key in reply:
                    entry[key] = reply[key]
            return reply
        except MobileError as error:
            entry["error"] = error.code
            if "outcome" in error.details:
                entry["verification"] = error.details["outcome"]
            raise
        finally:
            actions = getattr(_evaluation, "actions", None)
            if actions is not None:
                if len(actions) < 512:
                    actions.append(entry)
                else:
                    _evaluation.actions_omitted += 1

    def observe(self, *, tree=True, image=False, crop=None, max_width=None):
        args = {"tree": tree, "image": image}
        if crop is not None:
            left, top, right, bottom = crop
            args["crop"] = dict(left=left, top=top, right=right, bottom=bottom)
        if max_width is not None:
            args["max_width"] = max_width
        if not image and (crop is not None or max_width is not None):
            raise ValueError("Request image=True to crop or resize a screenshot.")
        captured = time.monotonic()
        self._current_observation = None
        obs = Observation(self._call("observe", args))
        obs._captured = captured
        self._current_observation = obs.id
        return obs

    def status(self, text):
        """Show the current meaningful step on the pet. An empty string clears it."""
        if not isinstance(text, str) or len(text.encode("utf-16-le")) > 480:
            raise ValueError("Supply a short status (up to 240 UTF-16 code units).")
        return self._call("companion_status", {"text": text})

    def watch(self, interval=2.0):
        """Keep recent compressed frames for this selected screen. None stops new sampling."""
        if interval is not None and not (type(interval) in (int, float) and 1 <= interval <= 60):
            raise ValueError("Sampling interval must be 1–60 seconds, or None.")
        return self._call("companion_watch", {"interval_ms": round(interval * 1000) if interval else 0})

    def _history(self, seconds, limit, **options):
        if type(seconds) not in (int, float) or not 0 <= seconds <= 60:
            raise ValueError("History covers at most the last 60 seconds.")
        if type(limit) is not int or not 1 <= limit <= 12:
            raise ValueError("Request between 1 and 12 frames.")
        return self._call("companion_history", {"since_ms": round(seconds * 1000), "limit": limit, **options})

    def history(self, seconds=60, limit=6, images=True, *, frame_id=None):
        """Return chronological frames; request an individual frame by ID for detail."""
        selection = {"frame_id": frame_id} if frame_id is not None else {}
        result = self._history(seconds, limit, include_images=images, **selection)
        if not result["frames"] and result.get("last_capture_error"):
            raise MobileError("history_unavailable", result["last_capture_error"])
        return [HistoryFrame(frame) for frame in result["frames"]]

    def history_sheet(self, seconds=60, limit=6):
        """One labelled image of the selected moments. An empty history has no image."""
        result = self._history(seconds, limit, sheet=True)
        if not result["frames"] and result.get("last_capture_error"):
            raise MobileError("history_unavailable", result["last_capture_error"])
        return HistoryFrame(result)

    def wait(self, *, timeout=None, until=None, interval=2.0, tree=True, image=False, crop=None, max_width=None,
             description=None):
        """Wake on a user steer, local predicate, or deadline. Publish an optional pet caption once at entry."""
        if timeout is not None and (type(timeout) not in (int, float) or not math.isfinite(timeout) or timeout < 0):
            raise ValueError("Timeout must be finite and nonnegative.")
        if type(interval) not in (int, float) or not math.isfinite(interval) or interval < 0.25:
            raise ValueError("Poll interval must be at least 0.25 seconds.")
        if until is not None and not callable(until):
            raise ValueError("until must be callable.")
        if description is not None:
            if not isinstance(description, str) or not description.strip():
                raise ValueError("Supply a nonempty wait description or omit it.")
            self.status(description)
        deadline = None if timeout is None else time.monotonic() + timeout
        next_check = time.monotonic()
        last = None
        while True:
            now = time.monotonic()
            targets = [now + 12]
            if until is not None:
                targets.append(next_check)
            if deadline is not None:
                targets.append(deadline)
            pause = max(0, min(targets) - now)
            if self._call("companion_wait", {"timeout_ms": math.ceil(pause * 1000)})["reason"] == "user":
                return {"reason": "user"}
            now = time.monotonic()
            if deadline is not None and now >= deadline:
                return {"reason": "timeout", **({"observation": last} if last is not None else {})}
            if until is not None and now >= next_check:
                last = self.observe(tree=tree, image=image, crop=crop, max_width=max_width)
                if until(last):
                    if self._call("companion_wait", {"timeout_ms": 0})["reason"] == "user":
                        return {"reason": "user"}
                    return {"reason": "condition", "observation": last}
                next_check = time.monotonic() + interval

    def needs_decision(self, observation, prompt, *, timeout=45):
        """Suspend this stdio run/eval while the host decides from one screenshot."""
        gate = getattr(_evaluation, "decisions", None)
        if gate is None:
            raise MobileError("decision_host_required", "Run this script using mobile_use stdio to request a host decision.")
        if not isinstance(observation, Observation) or not (observation.get("image") or {}).get("data"):
            raise MobileError("image_unavailable", "Request an image observation for a visual decision.")
        if not isinstance(prompt, str) or not prompt.strip():
            raise ValueError("A decision prompt is required.")
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("timeout must be positive and finite.")
        return gate.ask(self, observation, prompt, timeout)

    def sample_colors(self, observation, points, radius=0):
        """Read ordered (r, g, b) tuples from observation-image coordinates, averaging a clipped square."""
        if type(radius) is not int or not 0 <= radius <= 8:
            raise ValueError("radius must be an integer from 0 to 8.")
        if not isinstance(points, (list, tuple)) or not 1 <= len(points) <= 64:
            raise ValueError("Supply 1 to 64 image points.")
        image = None
        if isinstance(observation, Observation):
            image = observation.get("image")
            if not image:
                raise MobileError("image_unavailable", "This observation does not include a screenshot.")
            obs_id = observation.id
        else:
            obs_id = observation
        if not isinstance(obs_id, str) or not obs_id:
            raise ValueError("An observation or observation_id is required.")
        samples = []
        for point in points:
            if not isinstance(point, (tuple, list)) or len(point) != 2 or any(
                    type(v) is not int or not 0 <= v <= 2_147_483_647 for v in point):
                raise ValueError("Each point must contain two nonnegative integer image coordinates.")
            x, y = point
            if image is not None and (x >= image["width"] or y >= image["height"]):
                raise ValueError("A point lies outside the observation image.")
            samples.append([x, y])
        result = self._call("sample_colors", {"observation_id": obs_id, "points": samples, "radius": radius})
        return [tuple(rgb) for rgb in result["colors"]]

    def find_template(self, observation, png_path, *, tolerance=12, max_matches=8):
        """Locate a fixed-size PNG inside the observed image, without touching the screen."""
        if type(tolerance) is not int or not 0 <= tolerance <= 64:
            raise ValueError("tolerance must be an integer from 0 to 64.")
        if type(max_matches) is not int or not 1 <= max_matches <= 32:
            raise ValueError("max_matches must be an integer from 1 to 32.")
        with Path(png_path).open("rb") as stream:
            template = stream.read(32769)
        if len(template) > 32768 or not template.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError("Provide a PNG template of at most 32 KiB.")
        obs_id = observation.id if isinstance(observation, Observation) else observation
        if not isinstance(obs_id, str) or not obs_id:
            raise ValueError("An observation or observation_id is required.")
        return self._call("find_template", {
            "observation_id": obs_id, "tolerance": tolerance, "max_matches": max_matches,
            "template": base64.b64encode(template).decode("ascii")
        })

    @staticmethod
    def _point(observation, node, x, y, space):
        obs_id = observation.id if isinstance(observation, Observation) else observation
        if not isinstance(obs_id, str) or not obs_id:
            raise ValueError("An observation or observation_id is required.")
        if node is not None:
            if x is not None or y is not None:
                raise ValueError("Supply one node or a coordinate pair.")
            return {"observation_id": obs_id, "node_id": node["id"] if isinstance(node, dict) else node}
        if x is None or y is None or space not in ("image", "display"):
            raise ValueError("Supply a node or x,y with image/display space.")
        return {"observation_id": obs_id, "x": x, "y": y, "space": space}

    def tap(self, observation, node=None, *, x=None, y=None, space="display", feedback=False):
        return self._call("tap", self._point(observation, node, x, y, space), feedback=feedback)

    def long_press(self, observation, node=None, *, x=None, y=None, space="display", duration_ms=650, feedback=False):
        args = self._point(observation, node, x, y, space)
        return self._call("long_press", {**args, "duration_ms": duration_ms}, feedback=feedback)

    def swipe(self, observation, x, y, x2, y2, *, duration_ms=350, space="display", feedback=False):
        args = self._point(observation, None, x, y, space)
        return self._call("swipe", {**args, "x2": x2, "y2": y2, "duration_ms": duration_ms}, feedback=feedback)

    def drag(self, observation, x, y, x2, y2, *, hold_ms=650, duration_ms=350, space="display", feedback=False):
        """Hold, move in a straight line, and release in one gesture on the selected screen."""
        args = self._point(observation, None, x, y, space)
        if x2 is None or y2 is None:
            raise ValueError("Supply both destination coordinates.")
        if any(type(ms) is not int or not 1 <= ms <= 3000 for ms in (hold_ms, duration_ms)):
            raise ValueError("hold_ms and duration_ms each require an integer from 1 to 3000.")
        return self._call("drag", {**args, "x2": x2, "y2": y2, "hold_ms": hold_ms, "duration_ms": duration_ms}, feedback=feedback)

    def input(self, observation, node, text):
        args = self._point(observation, node, None, None, "display")
        return self._call("input", {**args, "text": text})

    def back(self):
        return self._call("back", {})

    def home(self):
        return self._call("home", {})

    def recents(self):
        """Open Android's recent apps on the selected main screen."""
        return self._call("recents", {})

    def launch(self, package, activity=None):
        return self._call("launch", {"package": package, **({"activity": activity} if activity else {})})

    def open_app(self, name):
        """Open an app by its launcher label on the selected screen."""
        if not isinstance(name, str) or not name.strip():
            raise MobileError("invalid_argument", "Supply a nonempty app name.")
        return self._call("open_app", {"name": name})

    def _finish(self, method):
        self._ending.set()
        with self._lock:
            session = self.session_id
            self._closed = True
            self.session_id = None
        return _request(method, session, {}) if session else {"ok": True}

    def close(self):
        return self._finish("close")

    def interrupt(self):
        return self._finish("interrupt")

    def __enter__(self):
        self.open()
        return self

    def __exit__(self, typ, value, tb):
        self.close()


phone = Phone()


def _namespace():
    return {"__name__": "__console__", "phone": phone, "Phone": Phone, "MobileError": MobileError}


def _run(path, argv, *, source=None):
    old_argv, old_path = sys.argv, sys.path[:]
    sys.argv = [path, *argv]
    script_dir = Path(path).resolve().parent if source is None else Path.cwd()
    sys.path.insert(0, str(script_dir))
    try:
        if source is None:
            runpy.run_path(path, init_globals={"phone": phone}, run_name="__main__")
        else:
            namespace = {"__name__": "__main__", "__file__": "<stdin>", "phone": phone}
            exec(compile(source, "<stdin>", "exec"), namespace)
    finally:
        sys.argv = old_argv
        sys.path[:] = old_path


def _eval(source, namespace):
    try:
        expression = compile(source, "<mobile_use>", "eval")
    except SyntaxError:
        exec(compile(source, "<mobile_use>", "exec"), namespace)
        return None
    return eval(expression, namespace)


def _structured(value):
    attachments = []
    def detach(item):
        if isinstance(item, (Observation, HistoryFrame)) and (item.get("image") or {}).get("data"):
            image = dict(item["image"])
            index = len(attachments)
            attachments.append({"mime_type": image["mime_type"], "data": image.pop("data")})
            item = {**item, "image": {**image, "attachment": index}}
        if isinstance(item, dict):
            return {key: detach(entry) for key, entry in item.items()}
        if isinstance(item, (list, tuple)):
            return [detach(entry) for entry in item]
        return item
    try:
        value = detach(value)
        json.dumps(value)
    except (TypeError, ValueError, RecursionError):
        value = repr(value)
    return value, attachments


class _Decisions:
    """One host decision can resume this Python worker exactly once."""
    def __init__(self, request_id, send):
        self.request_id = request_id
        self.send = send
        self.condition = threading.Condition()
        self.pending = None
        self.canceled = False

    def ask(self, device, observation, prompt, timeout):
        expires = min(observation._captured + 60, time.monotonic() + timeout)

        def fresh():
            return not device._ending.is_set() and device._current_observation == observation.id and time.monotonic() < expires

        entry = {"id": uuid.uuid4().hex, "observation": observation.id, "fresh": fresh}
        with self.condition:
            if self.canceled:
                raise MobileError("interrupted", "The running operation was interrupted.")
            if not fresh():
                raise MobileError("stale_observation", "Observe the current screen before requesting a decision.")
            self.pending = entry
        try:
            meta, attachments = _structured(observation)
            self.send({"id": self.request_id, "event": "needs_decision", "decision_id": entry["id"],
                       "observation_id": observation.id, "prompt": prompt,
                       "observation": meta, "attachments": attachments,
                       "timeout_ms": max(0, int((expires-time.monotonic())*1000))})
            while True:
                with self.condition:
                    if not self.canceled and "proposal" not in entry and fresh():
                        self.condition.wait(min(2, max(0, expires-time.monotonic())))
                    if self.canceled:
                        raise MobileError("interrupted", "The running operation was interrupted.")
                    if not fresh():
                        raise MobileError("stale_decision", "This decision expired; inspect the current question.")
                    proposed = entry.get("proposal")
                # Validate the existing attachment, without opening or extending the lease. This also
                # notices a card stop while the model is thinking. Keep input free to handle interrupt.
                _request("check", device.session_id, {"observation_id": observation.id})
                with self.condition:
                    if self.canceled:
                        raise MobileError("interrupted", "The running operation was interrupted.")
                    if not fresh():
                        raise MobileError("stale_decision", "This decision expired; inspect the current question.")
                    if proposed is not None:
                        self.pending = None
                        self.send({"id": proposed[0], "ok": True, "result": {"accepted": True}})
                        return proposed[1]
                    # A resume received during that periodic check must get its own check.
        except (Exception, KeyboardInterrupt) as error:
            with self.condition:
                self.pending = None
                if "proposal" in entry:
                    failure = error.as_dict() if isinstance(error, MobileError) else {
                        "code": "decision_unavailable", "message": "Could not validate the current screen."
                    }
                    self.send({"id": entry["proposal"][0], "ok": False, "error": failure})
            raise
        finally:
            with self.condition:
                if self.pending is entry:
                    self.pending = None

    def resume(self, request):
        with self.condition:
            p = self.pending
            if self.canceled or p is None or p["id"] != request.get("decision_id") or p["observation"] != request.get("observation_id"):
                raise MobileError("stale_decision", "No matching decision is waiting.")
            if "proposal" in p or not p["fresh"]():
                self.condition.notify_all()
                raise MobileError("stale_decision", "This decision was already answered or expired.")
            if "decision" not in request:
                raise ValueError("resume requires a decision value.")
            p["proposal"] = request.get("id"), request["decision"]
            self.condition.notify_all()

    def cancel(self):
        with self.condition:
            self.canceled = True
            self.condition.notify_all()


def _stdio(input_stream, output_stream):
    global phone
    namespace = _namespace()
    pending = None
    stopped = threading.Event()
    done = threading.Event()
    decisions = None
    output_lock = threading.Lock()

    def send(message):
        with output_lock:
            output_stream.write(json.dumps(message, ensure_ascii=False) + "\n")
            output_stream.flush()

    def execute(request):
        text = io.StringIO()
        _evaluation.decisions = decisions
        _evaluation.actions = []
        _evaluation.actions_omitted = 0
        try:
            def check_stop(frame, event, arg):
                if stopped.is_set() and frame.f_globals is not globals() and frame.f_globals.get("__name__") in ("__console__", "__main__"):
                    raise KeyboardInterrupt("Interrupted by host.")
                return check_stop
            with contextlib.redirect_stdout(text), contextlib.redirect_stderr(text):
                sys.settrace(check_stop)
                try:
                    if request["method"] == "run":
                        _run(request["path"], request.get("argv", []))
                        value = None
                    else:
                        value = _eval(request["code"], namespace)
                finally:
                    sys.settrace(None)
            value, attachments = _structured(value)
            reply = {"ok": True, "result": value, "attachments": attachments}
        except (Exception, KeyboardInterrupt, SystemExit) as error:
            failure = error.as_dict() if isinstance(error, MobileError) else {
                "code": "interrupted" if isinstance(error, KeyboardInterrupt) else "python_error",
                "message": "".join(traceback.format_exception_only(type(error), error)).strip()
            }
            reply = {"ok": False, "error": failure}
        done.set()
        send({"id": request.get("id"), "text": text.getvalue(), "actions": _evaluation.actions,
              "actions_omitted": _evaluation.actions_omitted, **reply})

    try:
        for line in input_stream:
            req_id = None
            try:
                request = json.loads(line)
                req_id = request.get("id")
                method = request["method"]
                if method == "interrupt":
                    stopped.set()
                    if decisions:
                        decisions.cancel()
                    result = phone.interrupt()
                elif method == "resume":
                    if decisions is None:
                        raise MobileError("stale_decision", "No decision is waiting.")
                    decisions.resume(request)
                    continue   # The evaluation worker acknowledges only after its native check.
                elif pending and not done.is_set():
                    raise MobileError("eval_busy", "Interrupt or wait for the active evaluation.")
                elif method == "open":
                    if phone._closed or request.get("screen") != phone.screen:
                        phone.close()
                        phone = Phone(request.get("screen"))
                        namespace = _namespace()
                    result = phone.open()
                elif method in ("eval", "run"):
                    stopped.clear()
                    done.clear()
                    decisions = _Decisions(req_id, send)
                    pending = threading.Thread(target=execute, args=(request,), daemon=True)
                    pending.start()
                    continue
                elif method == "close":
                    result = phone.close()
                    namespace = _namespace()
                else:
                    raise ValueError("Unknown method; expected open/eval/run/resume/interrupt/close.")
                send({"id": req_id, "ok": True, "result": result})
            except Exception as error:
                failure = error.as_dict() if isinstance(error, MobileError) else {"code": "invalid_request", "message": str(error)}
                send({"id": req_id, "ok": False, "error": failure})
    finally:
        if pending and pending.is_alive():
            stopped.set()
            if decisions:
                decisions.cancel()
            phone.interrupt()
        else:
            phone.close()


def main(argv=None):
    global phone
    parser = argparse.ArgumentParser(prog="mobile_use")
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("repl", "run", "stdio"):
        cmd = sub.add_parser(name)
        if name != "stdio":
            cmd.add_argument("--screen", choices=("main", "virtual"), default=None)
        if name == "run":
            cmd.add_argument("path", help="Python script path, or - to read the script from stdin")
            cmd.add_argument("args", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    if args.command == "stdio":
        protocol = sys.stdin
        with open(os.devnull, encoding="utf-8") as script_input:
            sys.stdin = script_input
            try:
                _stdio(protocol, sys.stdout)
            finally:
                sys.stdin = protocol
        return 0
    phone = Phone(args.screen)
    try:
        if args.command == "run":
            source = getattr(sys.stdin, "buffer", sys.stdin).read() if args.path == "-" else None
            _run(args.path, args.args, source=source)
        else:
            code.interact(banner="mobile_use · phone", local=_namespace(), exitmsg="")
    except KeyboardInterrupt:
        phone.interrupt()
        return 130
    except MobileError as error:
        print(f"{error.code}: {error}", file=sys.stderr)
        if error.code == "ambiguous_app" and error.details.get("candidates"):
            print(json.dumps({"candidates": error.details["candidates"]}, ensure_ascii=False), file=sys.stderr)
        return 1
    finally:
        phone.close()
    return 0


if __name__ == "__main__":
    sys.modules["mobile_use"] = sys.modules[__name__]
    raise SystemExit(main())
