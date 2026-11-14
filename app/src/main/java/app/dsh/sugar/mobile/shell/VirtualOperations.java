package app.dsh.sugar.mobile.shell;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

final class VirtualOperations implements AutoCloseable {
    private static final HashSet<String> launching = new HashSet<>();
    private final VirtualFrames frames;
    private final Context shell;
    private final int display;
    private final VirtualWindows windows;
    private final InputManager inputManager;
    private final Method inject;
    private final BooleanSupplier stopped;
    private String observation, previousScope;
    private long timestamp;
    VirtualOperations(VirtualFrames frames,Context shell,BooleanSupplier stopped) {
        this.frames=frames; this.shell=shell; display=frames.display.getDisplayId();
        this.stopped = stopped;
        inputManager = shell.getSystemService(InputManager.class);
        Method method = null;
        try {
            if (inputManager != null && android.os.Build.VERSION.SDK_INT >= 34)
                method = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            android.util.Log.w("SugarVirtual", "Display touch injection unavailable", unavailable);
        }
        inject = method;
        VirtualWindows w=null;
        try { w=new VirtualWindows(shell,display); } catch (Exception unavailable) {
            android.util.Log.w("SugarVirtual","Display window observation unavailable",unavailable);
        }
        windows=w;
    }
    public void close() { if(windows!=null) windows.close(); }
    private static JSONObject ok() throws Exception { return new JSONObject().put("ok",true); }
    private static JSONObject error(String code,String message) throws Exception {
        return new JSONObject().put("ok",false).put("error",new JSONObject().put("code",code).put("message",message));
    }
    JSONObject ready() throws Exception {
        return ok().put("display","virtual").put("display_id",display).put("driver","adb")
                .put("capabilities",new JSONObject().put("tree",windows!=null).put("image",true)
                        .put("node_action",windows!=null).put("gesture",true).put("drag",inject!=null).put("text",windows!=null)
                        .put("launch",true).put("open_app",true).put("home",false).put("recents",false));
    }
    private static int taskDisplay(ActivityManager.RunningTaskInfo t) throws Exception {
        return t.getClass().getField("displayId").getInt(t);
    }
    @SuppressWarnings("deprecation")
    private List<ActivityManager.RunningTaskInfo> tasks() {
        return shell.getSystemService(ActivityManager.class).getRunningTasks(80);
    }
    private String top() throws Exception {
        for(ActivityManager.RunningTaskInfo t:tasks())
            if(taskDisplay(t)==display && t.topActivity!=null) return t.topActivity.flattenToShortString();
        return "";
    }
    private static String packageOf(String component) {
        int at=component.indexOf('/');
        return at==-1 ? "" : component.substring(0,at);
    }
    private String scope(String component,AccessibilityWindowInfo window) {
        return display+"|"+component+"|"+(window==null ? -1 : window.getId())+"|"+frames.display.getRotation();
    }
    private static String cli(String... arguments) throws Exception {
        Process p = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        try {
            if(!p.waitFor(6,TimeUnit.SECONDS)) throw new IllegalStateException("Device command timed out; result unknown.");
            try(InputStream in=p.getInputStream()) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buf=new byte[512];
                for(int n; bytes.size()<16384 && (n=in.read(buf))!=-1;) bytes.write(buf,0,n);
                String output=bytes.toString("UTF-8").trim();
                if(p.exitValue()!=0 || output.startsWith("Error") || output.contains("Exception occurred"))
                    throw new IllegalStateException(output.isEmpty()?"Android rejected the display command.":output.substring(0,Math.min(320,output.length())));
                return output;
            }
        } finally { p.destroy(); }
    }
    private String number(JSONObject args,String key,int limit) throws Exception {
        double value=args.getDouble(key);
        if(!Double.isFinite(value)||value<0 || value>=limit) throw new IllegalArgumentException("Coordinate outside virtual display.");
        return Integer.toString((int)value);
    }
    private void input(String... args) throws Exception {
        List<String> cmd=new ArrayList<>(Arrays.asList("/system/bin/cmd","input","-d",Integer.toString(display)));
        cmd.addAll(Arrays.asList(args)); cli(cmd.toArray(new String[0]));
    }
    @android.annotation.TargetApi(34)
    private void sendTouch(MotionEvent.PointerProperties[] pointer, MotionEvent.PointerCoords[] coords,
                           long down, int action, float x, float y) throws Exception {
        coords[0].x = x;
        coords[0].y = y;
        coords[0].size = 1f;
        coords[0].pressure = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ? 0f : 1f;
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, pointer, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, display, 0, MotionEvent.CLASSIFICATION_NONE);
        if (event == null) throw new IllegalStateException("Could not create display touch event.");
        try {
            // WAIT_FOR_RESULT acknowledges routing, without waiting for the target app to draw a frame.
            if (!Boolean.TRUE.equals(inject.invoke(inputManager, event, 1)))
                throw new IllegalStateException("Android rejected virtual display touch input.");
        } finally {
            event.recycle();
        }
    }

    private void waitForDragTime(long deadline) throws InterruptedException {
        for (;;) {
            if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new InterruptedException("Virtual drag was canceled.");
            long left = deadline - SystemClock.uptimeMillis();
            if (left <= 0) return;
            Thread.sleep(Math.min(left, 16));
        }
    }

    private static int dragTime(JSONObject args, String name, int fallback) {
        if (!args.has(name)) return fallback;
        Object value = args.opt(name);
        double millis = value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
        return millis >= 1 && millis <= 3000 && millis == (int) millis ? (int) millis : -1;
    }

    private JSONObject drag(JSONObject args, Point size) throws Exception {
        if (inject == null) return error("unsupported", "Continuous drag is unavailable on this display.");
        if (args.has("node_id")) return error("invalid_argument", "Drag requires start and end coordinates.");
        int hold = dragTime(args, "hold_ms", 650);
        int duration = dragTime(args, "duration_ms", 350);
        if (hold < 0 || duration < 0) return error("invalid_argument", "Drag hold and duration must each be 1–3000 ms.");
        String space = args.optString("space", "display");
        if (!space.equals("display") && !space.equals("image")) return error("invalid_argument", "Unknown coordinate space.");
        float x = (float) args.optDouble("x"), y = (float) args.optDouble("y");
        float x2 = (float) args.optDouble("x2"), y2 = (float) args.optDouble("y2");
        if (!(x >= 0 && x < size.x && y >= 0 && y < size.y && x2 >= 0 && x2 < size.x && y2 >= 0 && y2 < size.y))
            return error("invalid_argument", "Drag endpoints must be inside the virtual display.");
        if (stopped.getAsBoolean()) return error("session_closed", "Virtual screen is closed.");

        MotionEvent.PointerProperties finger = new MotionEvent.PointerProperties();
        finger.id = 0;
        finger.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerProperties[] pointer = { finger };
        MotionEvent.PointerCoords[] coords = { new MotionEvent.PointerCoords() };
        long down = SystemClock.uptimeMillis();
        observation = null;
        try {
            sendTouch(pointer, coords, down, MotionEvent.ACTION_DOWN, x, y);
            waitForDragTime(down + hold);
            long moveStart = SystemClock.uptimeMillis();
            long end = moveStart + duration;
            long now;
            do {
                waitForDragTime(Math.min(end, SystemClock.uptimeMillis() + 16));
                now = SystemClock.uptimeMillis();
                float progress = Math.min(1f, (now - moveStart) / (float) duration);
                sendTouch(pointer, coords, down, MotionEvent.ACTION_MOVE, x + (x2 - x) * progress, y + (y2 - y) * progress);
            } while (now < end);
            waitForDragTime(end);
            sendTouch(pointer, coords, down, MotionEvent.ACTION_UP, x2, y2);
        } catch (Exception failure) {
            try {
                sendTouch(pointer, coords, down, MotionEvent.ACTION_CANCEL, coords[0].x, coords[0].y);
            } catch (Exception cancelFailure) {
                failure.addSuppressed(cancelFailure);
            }
            throw failure;
        }
        return sent();
    }
    private JSONObject sent() throws Exception {
        observation=null;
        return ok().put("display_id",display).put("dispatch","sent").put("verification","unknown");
    }
    private JSONObject launch(JSONObject args, boolean byName) throws Exception {
        String pkg=args.getString("package");
        if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))return error("invalid_argument","Invalid package.");
        String name=args.optString("activity","");
        if(name.isEmpty()) {
            Intent launch=shell.getPackageManager().getLaunchIntentForPackage(pkg);
            if(launch!=null && launch.getComponent()!=null) name=launch.getComponent().getClassName();
        }
        if(name.startsWith(".")) name=pkg+name;
        if(!name.matches("[a-zA-Z_$][a-zA-Z0-9_$.]*")) return error("activity_unavailable","No launchable activity for that package.");
        ComponentName target=new ComponentName(pkg,name);
        // Package resolution ran in Sugar's user profile. The shell helper may run under user 0.
        int targetUser=-1;
        if(byName) {
            android.os.UserHandle user=android.os.UserHandle.getUserHandleForUid(args.getInt("owner_uid"));
            targetUser=(int)android.os.UserHandle.class.getMethod("getIdentifier").invoke(user);
            if(targetUser<0) return error("app_unavailable","Could not determine the app's Android user.");
            if(pkg.equals(foregroundPackageForUser(targetUser)))
                return ok().put("display_id",display).put("dispatch","already_foreground").put("verification","verified");
        }
        // Avoid moving a task out of a display on which the user is already working.
        for(ActivityManager.RunningTaskInfo task:tasks()) if(taskDisplay(task)!=display &&
                task.topActivity!=null && pkg.equals(task.topActivity.getPackageName()) && task.isRunning &&
                task.topActivity.equals(target))
            return error("app_on_other_display","That activity already has a task on another display.");
        observation = null;
        // This launch belongs to the virtual display. Let Android discard its recent entry when its activity finishes.
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK |
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
        List<String> command = new ArrayList<>(Arrays.asList("/system/bin/cmd","activity","start-activity"));
        if(byName) command.addAll(Arrays.asList("--user",Integer.toString(targetUser),"-a",Intent.ACTION_MAIN,"-c",Intent.CATEGORY_LAUNCHER));
        command.addAll(Arrays.asList("--display",Integer.toString(display),"-f",Integer.toString(flags),"-n",target.flattenToString()));
        cli(command.toArray(new String[0]));
        JSONObject result=sent();
        long until=SystemClock.uptimeMillis()+2500;
        do {
            if(pkg.equals(byName ? foregroundPackageForUser(targetUser) : packageOf(top())))
                return result.put("verification","verified").put("package",pkg);
            SystemClock.sleep(75);
        } while(SystemClock.uptimeMillis()<until);
        return result;
    }
    private String foregroundPackageForUser(int user) throws Exception {
        for(ActivityManager.RunningTaskInfo task:tasks()) {
            if(taskDisplay(task)!=display || task.topActivity==null) continue;
            return task.getClass().getField("userId").getInt(task)==user ? task.topActivity.getPackageName() : "";
        }
        return "";
    }
    JSONObject execute(String op,JSONObject args) throws Exception {
        JSONObject receipt = executeOne(op, args);
        if (!args.has("feedback") || !"sent".equals(receipt.optString("dispatch"))) return receipt;
        long dispatchedAt = SystemClock.uptimeMillis();
        try {
            JSONObject query = new JSONObject().put("tree", false).put("image", true).put("feedback_after_uptime_ms", dispatchedAt);
            JSONObject shot = executeOne("observe", query);
            if (shot.optBoolean("ok")) receipt.put("feedback", shot);
            else receipt.put("feedback_error", shot.getJSONObject("error"));
        } catch (Exception e) {
            receipt.put("feedback_error", new JSONObject().put("code","feedback_unavailable")
                    .put("message","Could not capture a fresh virtual display frame after the action."));
        }
        return receipt;
    }

    private JSONObject executeOne(String op,JSONObject args) throws Exception {
        if(!frames.display.isValid() || frames.display.getDisplayId()!=display || display<=Display.DEFAULT_DISPLAY)
            return error("display_lost","Virtual display is gone.");
        Point size=new Point(); frames.display.getRealSize(size);
        if(size.x!=frames.width || size.y!=frames.height || frames.display.getRotation()!=Surface.ROTATION_0)
            return error("unsupported_geometry","The virtual display changed orientation or size; reopen the task.");
        if(op.equals("preview")) return ok().put("image",frames.capture()).put("width",size.x).put("height",size.y)
                .put("rotation",0).put("display","virtual").put("display_id",display);
        if(op.equals("check_foreground"))
            return top().isEmpty() ? error("no_window","The selected virtual display has no foreground app.") : ok();
        if(op.equals("launch") || op.equals("open_app")) {
            // Keep starts of the same package from racing its ownership check on two displays.
            String pkg = args.getString("package");
            synchronized (launching) {
                if (!launching.add(pkg)) return error("app_busy","This app is starting on another display.");
            }
            try { return launch(args,op.equals("open_app")); }
            finally { synchronized (launching) { launching.remove(pkg); } }
        }
        AccessibilityWindowInfo window=windows==null?null:windows.window();
        String component=top();
        String currentScope=scope(component,window);
        if(op.equals("observe")) {
            if(args.optBoolean("tree",true) && windows==null) return error("tree_unavailable","No virtual display tree; request image only.");
            JSONObject result=ok().put("width",size.x).put("height",size.y).put("rotation",0)
                    .put("display","virtual").put("display_id",display).put("package",packageOf(component))
                    .put("window_id",window==null?-1:window.getId()).put("timestamp_ms",System.currentTimeMillis());
            if(args.optBoolean("tree",true)) result.put("tree_timestamp_ms",System.currentTimeMillis())
                    .put("tree",windows.tree(window==null?null:window.getRoot()));
            else if(windows!=null) windows.tree(null);
            if(args.optBoolean("image",false)) {
                JSONObject image = frames.capture(args.optLong("feedback_after_uptime_ms", -1));
                result.put("image_timestamp_ms",image.getLong("timestamp_ms"));
                if (image.has("feedback_delay_ms")) result.put("feedback_delay_ms",image.remove("feedback_delay_ms"));
                result.put("image",image);
            }
            if(!currentScope.equals(scope(top(),windows==null?null:windows.window())))
                return error("stale_observation","Virtual display changed during observation.");
            observation=UUID.randomUUID().toString(); previousScope=currentScope; timestamp=SystemClock.elapsedRealtime();
            return result.put("observation_id",observation);
        }
        if(op.equals("home")) return error("unsupported","Virtual-display HOME is not available.");
        if(!op.equals("back") || args.has("observation_id")) {
            if(observation==null || !observation.equals(args.optString("observation_id")) || !currentScope.equals(previousScope) ||
                    SystemClock.elapsedRealtime()-timestamp>60000) return error("stale_observation","Observe the virtual display again.");
        }
        if(op.equals("back")) {
            if(component.isEmpty()) return error("no_window","The virtual display has no application to navigate.");
            observation=null;
            input("keyevent","4"); return sent();
        }
        if(!Arrays.asList("tap","long_press","input","swipe","drag").contains(op)) return error("unsupported","Unknown virtual action.");
        if(component.isEmpty()) return error("no_window","Launch an application on this virtual display first.");
        if(op.equals("drag")) return drag(args, size);
        if(args.has("node_id")) {
            AccessibilityNodeInfo node=windows==null || window==null ? null : windows.resolve(window.getRoot(),args.getString("node_id"));
            if(node==null) return error("stale_node","Node no longer matches this display.");
            observation=null;
            boolean done;
            if(op.equals("input")) done=windows.input(node,args.getString("text"));
            else if(op.equals("tap") || op.equals("long_press")) done=node.performAction(op.equals("tap") ?
                    AccessibilityNodeInfo.ACTION_CLICK : AccessibilityNodeInfo.ACTION_LONG_CLICK);
            else return error("invalid_argument","Swipe requires coordinates.");
            if(!done) return error("action_rejected","Node rejected the requested action.");
        } else {
            if(op.equals("input")) return error("invalid_argument","Text entry requires an editable node.");
            String x=number(args,"x",size.x), y=number(args,"y",size.y);
            observation=null;
            if(op.equals("tap")) input("tap",x,y);
            else {
                int duration=args.optInt("duration_ms",op.equals("long_press")?650:350);
                if(duration<1 || duration>3000) return error("invalid_argument","Gesture duration outside 1–3000 ms.");
                String x2=op.equals("swipe")?number(args,"x2",size.x):x, y2=op.equals("swipe")?number(args,"y2",size.y):y;
                input("swipe",x,y,x2,y2,Integer.toString(duration));
            }
        }
        return sent();
    }
}
