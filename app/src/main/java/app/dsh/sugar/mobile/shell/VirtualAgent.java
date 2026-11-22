package app.dsh.sugar.mobile.shell;

import android.content.Context;
import android.os.Looper;
import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One authenticated process owns its display leases and one Android UiAutomation registration. */
public final class VirtualAgent {
    private final Context shell;
    private final Map<String, Slot> slots = new HashMap<>();
    private Slot mainUserOwner;
    private final DataOutputStream output = new DataOutputStream(System.out);

    private VirtualAgent(Context shell) { this.shell = shell; }

    private void write(JSONObject message) throws IOException {
        byte[] data = message.toString().getBytes(StandardCharsets.UTF_8);
        synchronized (output) {
            output.writeInt(data.length);
            output.write(data);
            output.flush();
        }
    }
    static JSONObject failed(String code, Throwable error) throws Exception {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return new JSONObject().put("ok",false).put("error",new JSONObject().put("code",code)
                .put("message",message == null ? cause.getClass().getSimpleName() : message));
    }
    private void reply(long request, JSONObject result) throws Exception {
        write(new JSONObject().put("request",request).put("result",result));
    }
    private final class Slot {
        final String id;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        volatile boolean stopped;
        volatile boolean userBlocked;
        volatile long generation;
        volatile String controlState = "agent";
        boolean controlPending;
        boolean closing;
        boolean closePending;
        VirtualFrames frames;
        VirtualOperations operations;
        boolean mainDisplay;

        Slot(String id) { this.id = id; }

        // Enqueue in wire order. Only this worker touches the observations or the Surface.
        void command(long request, String op, JSONObject args) {
            final long admittedGeneration = generation;
            final boolean control = isControl(op);
            final boolean foregroundQuery = "foreground_status".equals(op);
            worker.execute(() -> {
                try {
                    JSONObject result;
                    try {
                        if (stopped) result = failed("session_closed",new IOException("The screen is closed."));
                        else if ("open".equals(op)) {
                            mainDisplay = args.optBoolean("main", false);
                            frames = new VirtualFrames(shell, () -> stop(this,0,true), mainDisplay);
                            operations = new VirtualOperations(frames,shell,() -> stopped,() -> mainAvailable(this));
                            result = operations.ready();
                        } else if (operations == null) result = failed("session_closed",new IOException("The display did not start."));
                        else if ("close".equals(op)) result=operations.closeTasks();
                        else if (foregroundQuery) result=canReadForeground(this)?operations.foregroundStatus(args,()->canReadForeground(this)):foregroundUnavailable(this);
                        else if (control) result = operations.control(op);
                        else if (userBlocked || admittedGeneration != generation)
                            result = failed("user_control_active",new IOException("The user has control of this display. Wait for its return."))
                                    .put("control_state",controlState);
                        else result = operations.execute(op,args);
                    } catch (Exception e) {
                        result = failed("open".equals(op) ? "virtual_display_unavailable" : "driver_error",e);
                        if (!"open".equals(op) && !"observe".equals(op) && !"preview".equals(op))
                            result.put("dispatch","unknown").put("verification","unknown");
                    }
                    if ("close".equals(op)) {
                        finishClose(this,request,result);
                        return;
                    }
                    if(foregroundQuery) {
                        if(!canReadForeground(this)) result=foregroundUnavailable(this);
                        if(!result.has("task_foreground")) result.put("task_foreground",JSONObject.NULL);
                        result.put("control_state",controlState).put("dispatch","not_sent");
                    } else if (control || result.has("control_state")) {
                        if (!result.has("control_state")) result.put("control_state",stopped ? controlState : "unknown");
                        finishControl(this,op,result);
                    }
                    reply(request,result);
                    if (("open".equals(op) && !result.optBoolean("ok")) ||
                        (result.optJSONObject("error") != null &&
                         "display_lost".equals(result.getJSONObject("error").optString("code")))) stop(this,0,true);
                } catch (Exception streamLost) { System.exit(1); }
            });
        }
    }
    private static boolean isControl(String op) {
        return "takeover".equals(op) || "restore".equals(op) || "control_status".equals(op) || "focus".equals(op) || "reopen".equals(op);
    }
    private synchronized boolean canReadForeground(Slot slot) {
        return !slot.stopped && !slot.closing && !slot.controlPending && !slot.mainDisplay && "user".equals(slot.controlState);
    }
    private synchronized JSONObject foregroundUnavailable(Slot slot) throws Exception {
        return failed(slot.closing?"close_incomplete":slot.controlPending?"control_busy":"not_user_controlled",
                new IOException("Foreground status is available only while the user controls this task."))
                .put("control_state",slot.controlState).put("task_foreground",JSONObject.NULL)
                .put("dispatch","not_sent").put("verification","unknown");
    }
    private synchronized boolean mainAvailable(Slot slot) {
        if(mainUserOwner!=null && mainUserOwner!=slot) return false;
        for(Slot other:slots.values()) if(other!=slot && other.mainDisplay && !other.stopped) return false;
        return true;
    }
    private synchronized void finishControl(Slot slot, String op, JSONObject result) throws Exception {
        if(slot.closing) { slot.userBlocked=true; result.put("control_state","unknown"); return; }
        if ("control_status".equals(op) && slot.controlPending) {
            result.put("control_state",slot.controlState);
            return;
        }
        slot.controlState=result.optString("control_state","unknown");
        slot.userBlocked=!"agent".equals(slot.controlState);
        if(slot.userBlocked && mainUserOwner==null) mainUserOwner=slot;
        if (!"control_status".equals(op)) slot.controlPending=false;
        if (!slot.userBlocked && mainUserOwner==slot) mainUserOwner=null;
    }
    private synchronized void releaseMainReservation(Slot slot) {
        if (mainUserOwner==slot) mainUserOwner=null;
    }
    private synchronized void requestClose(Slot slot,long request) throws Exception {
        if(slot.closePending) {
            reply(request,failed("close_busy",new IOException("This screen is already closing."))
                    .put("closed",false).put("control_state","unknown"));
            return;
        }
        slot.closing=true; slot.closePending=true; slot.userBlocked=true; slot.generation++;
        slot.command(request,"close",new JSONObject());
    }
    private void finishClose(Slot slot,long request,JSONObject result) throws Exception {
        if(result.optBoolean("ok") && result.optBoolean("closed")) {
            // All task returns/removals have been verified before releasing the display.
            synchronized(this) { slots.remove(slot.id); slot.stopped=true; }
            if(slot.operations!=null) slot.operations.close();
            if(slot.frames!=null) slot.frames.close();
            releaseMainReservation(slot);
            slot.worker.shutdown();
        } else {
            synchronized(this) {
                slot.closePending=false; slot.controlState="unknown"; slot.userBlocked=true;
                if(mainUserOwner==null) mainUserOwner=slot;
            }
            result.put("ok",false).put("closed",false).put("control_state","unknown");
        }
        reply(request,result);
    }
    private synchronized void stop(Slot slot, long request, boolean notify) {
        if (slots.get(slot.id) != slot) return;
        slots.remove(slot.id);
        slot.stopped = true;
        slot.worker.execute(() -> {
            try {
                if (slot.operations != null) slot.operations.close();
                if (slot.frames != null) slot.frames.close();
                releaseMainReservation(slot);
                if (request != 0) reply(request,new JSONObject().put("ok",true));
                else if (notify) write(new JSONObject().put("event","removed").put("task",slot.id));
            } catch (Exception lost) { System.exit(1); }
        });
        slot.worker.shutdown();
    }
    private synchronized void dispatch(JSONObject message) throws Exception {
        long request = message.getLong("request");
        String id = message.getString("task");
        String op = message.getString("op");
        Slot slot = slots.get(id);
        if ("open".equals(op) && slot == null) {
            if (message.getJSONObject("args").optBoolean("main", false)) {
                if (android.os.Process.myUid() != 0) {
                    reply(request,failed("permission_denied",new IOException("Root is required for this main display driver.")));
                    return;
                }
                if (mainUserOwner!=null) {
                    reply(request,failed("screen_busy",new IOException("The user is controlling a task on the main display.")));
                    return;
                }
                for (Slot existing : slots.values()) {
                    if (existing.mainDisplay && !existing.stopped) {
                        reply(request,failed("screen_busy",new IOException("The main display is already in use.")));
                        return;
                    }
                }
            }
            slot = new Slot(id);
            slot.mainDisplay = message.getJSONObject("args").optBoolean("main", false);
            slots.put(id,slot);
            slot.command(request,op,message.getJSONObject("args"));
        } else if (slot==null && "close".equals(op)) {
            reply(request,new JSONObject().put("ok",true).put("closed",true).put("dispatch","not_sent").put("verification","verified"));
        } else if (slot == null || "open".equals(op)) {
            JSONObject result=failed("session_closed",new IOException("Invalid display task."));
            if (isControl(op)) result.put("control_state","unknown");
            if ("foreground_status".equals(op)) result.put("task_foreground",JSONObject.NULL).put("dispatch","not_sent");
            reply(request,result);
        } else if ("close".equals(op)) requestClose(slot,request);
        else if(slot.closing && !"control_status".equals(op)) {
            reply(request,failed("close_incomplete",new IOException("This screen is closing. Retry close before continuing."))
                    .put("closed",false).put("control_state","unknown"));
        }
        else if("foreground_status".equals(op)) {
            if(!canReadForeground(slot)) reply(request,foregroundUnavailable(slot));
            else slot.command(request,op,message.getJSONObject("args"));
        }
        else if ("takeover".equals(op) || "restore".equals(op) || "focus".equals(op) || "reopen".equals(op)) {
            if(("focus".equals(op) || "reopen".equals(op)) && !"user".equals(slot.controlState)) {
                reply(request,failed("not_user_controlled",new IOException("Only the original user-controlled task can be shown again."))
                        .put("control_state",slot.controlState));
                return;
            }
            if (slot.mainDisplay || slot.controlPending) {
                reply(request,failed(slot.mainDisplay ? "unsupported":"control_busy",new IOException(
                        slot.mainDisplay ? "Only virtual displays support handoff.":"A display handoff is already in progress."))
                        .put("control_state",slot.controlState));
                return;
            }
            boolean occupied=mainUserOwner!=null && mainUserOwner!=slot;
            for (Slot existing:slots.values()) if (existing!=slot && existing.mainDisplay && !existing.stopped) occupied=true;
            if (occupied) {
                reply(request,failed("screen_busy",new IOException("The main display is already in use."))
                        .put("control_state",slot.controlState));
                return;
            }
            mainUserOwner=slot;
            slot.userBlocked=true;
            slot.generation++;
            slot.controlState="takeover".equals(op)?"taking_over": "restore".equals(op)?"restoring":slot.controlState;
            slot.controlPending=true;
            slot.command(request,op,message.getJSONObject("args"));
        }
        else slot.command(request,op,message.getJSONObject("args"));
    }

    private void run() {
        try {
            write(new JSONObject().put("ok",true).put("protocol",2).put("runtime_uid",android.os.Process.myUid()));
            DataInputStream input = new DataInputStream(System.in);
            while (true) {
                int length = input.readInt();
                if (length < 2 || length > 65536) throw new IOException("Invalid virtual display request.");
                byte[] data = new byte[length];
                input.readFully(data);
                dispatch(new JSONObject(new String(data,StandardCharsets.UTF_8)));
            }
        } catch (Exception endOfStream) {
            // On ADB disconnect the shell process dies and Android releases every owned display.
        } finally { System.exit(0); }
    }
    public static void main(String[] args) throws Exception {
        int uid = android.os.Process.myUid();
        if (uid != 2000 && uid != 0) throw new SecurityException("An authorized Android shell or root process is required.");
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        Context shell = system.createPackageContext("com.android.shell",0);
        new Thread(new VirtualAgent(shell)::run,"sugar-virtual-commands").start();
        Looper.loop();
    }
}
