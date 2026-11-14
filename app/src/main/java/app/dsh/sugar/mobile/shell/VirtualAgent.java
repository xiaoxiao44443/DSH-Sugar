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

/** One ADB-authenticated process multiplexes independent displays and one Android UiAutomation registration. */
public final class VirtualAgent {
    private final Context shell;
    private final Map<String, Slot> slots = new HashMap<>();
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
        VirtualFrames frames;
        VirtualOperations operations;

        Slot(String id) { this.id = id; }

        // Enqueue in wire order. Only this worker touches the observations or the Surface.
        void command(long request, String op, JSONObject args) {
            worker.execute(() -> {
                try {
                    JSONObject result;
                    try {
                        if (stopped) result = failed("session_closed",new IOException("The screen is closed."));
                        else if ("open".equals(op)) {
                            frames = new VirtualFrames(shell, () -> stop(this,0,true));
                            operations = new VirtualOperations(frames,shell,() -> stopped);
                            result = operations.ready();
                        } else if (operations == null) result = failed("session_closed",new IOException("The display did not start."));
                        else result = operations.execute(op,args);
                    } catch (Exception e) {
                        result = failed("open".equals(op) ? "virtual_display_unavailable" : "driver_error",e);
                        if (!"open".equals(op) && !"observe".equals(op) && !"preview".equals(op))
                            result.put("dispatch","unknown").put("verification","unknown");
                    }
                    reply(request,result);
                    if (("open".equals(op) && !result.optBoolean("ok")) ||
                        (result.optJSONObject("error") != null &&
                         "display_lost".equals(result.getJSONObject("error").optString("code")))) stop(this,0,true);
                } catch (Exception streamLost) { System.exit(1); }
            });
        }
    }
    private synchronized void stop(Slot slot, long request, boolean notify) {
        if (slots.get(slot.id) != slot) return;
        slots.remove(slot.id);
        slot.stopped = true;
        slot.worker.execute(() -> {
            try {
                if (slot.operations != null) slot.operations.close();
                if (slot.frames != null) slot.frames.close();
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
            slot = new Slot(id);
            slots.put(id,slot);
            slot.command(request,op,message.getJSONObject("args"));
        } else if (slot == null || "open".equals(op)) {
            reply(request,failed("session_closed",new IOException("Invalid display task.")));
        } else if ("close".equals(op)) stop(slot,request,false);
        else slot.command(request,op,message.getJSONObject("args"));
    }

    private void run() {
        try {
            write(new JSONObject().put("ok",true).put("protocol",2));
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
        if (android.os.Process.myUid() != 2000) throw new SecurityException("The Android shell UID is required.");
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        Context shell = system.createPackageContext("com.android.shell",0);
        new Thread(new VirtualAgent(shell)::run,"sugar-virtual-commands").start();
        Looper.loop();
    }
}
