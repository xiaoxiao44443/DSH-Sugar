package app.dsh.sugar.mobile.shell;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Moves one identified Android task while its original virtual display remains owned by the slot. */
final class VirtualTaskControl {
    private static final Object TASK_MUTATIONS=new Object();
    private static final Map<Integer,VirtualTaskControl> OWNERS=new HashMap<>();
    private final Context context;
    private final int originalDisplay;
    private final BooleanSupplier stopped;
    private final BooleanSupplier mainAvailable;
    private final Map<Integer,Lease> leases=new HashMap<>();
    private Transfer transfer;
    private String state="agent";
    private Integer currentDisplay;
    private boolean taskGone;

    private static final class Lease {
        final Transfer identity;
        final boolean borrowed;
        final int sourceDisplay;
        Lease(Transfer identity,boolean borrowed,int sourceDisplay) {
            this.identity=identity; this.borrowed=borrowed; this.sourceDisplay=sourceDisplay;
        }
    }

    private static final class Transfer {
        final int taskId, userId;
        final String baseComponent, packageName;
        int rootTaskId;
        Transfer(ActivityManager.RunningTaskInfo task, int root) throws Exception {
            taskId=task.taskId; userId=user(task); baseComponent=base(task);
            packageName=ComponentName.unflattenFromString(baseComponent).getPackageName(); rootTaskId=root;
        }
    }
    VirtualTaskControl(Context context, int originalDisplay, BooleanSupplier stopped) {
        this(context,originalDisplay,stopped,()->true);
    }
    VirtualTaskControl(Context context, int originalDisplay, BooleanSupplier stopped,BooleanSupplier mainAvailable) {
        this.context=context; this.originalDisplay=originalDisplay; this.stopped=stopped;
        this.mainAvailable=mainAvailable;
    }
    private static int display(ActivityManager.RunningTaskInfo task) throws Exception {
        return task.getClass().getField("displayId").getInt(task);
    }
    private static int user(ActivityManager.RunningTaskInfo task) throws Exception {
        return task.getClass().getField("userId").getInt(task);
    }
    private static String base(ActivityManager.RunningTaskInfo task) throws Exception {
        ComponentName component=task.baseIntent == null ? null : task.baseIntent.getComponent();
        if (component == null) component=task.baseActivity;
        if (component == null) throw new IllegalStateException("The task identity is unavailable.");
        return component.flattenToString();
    }
    @SuppressWarnings("deprecation")
    private List<ActivityManager.RunningTaskInfo> tasks() {
        List<ActivityManager.RunningTaskInfo> result=context.getSystemService(ActivityManager.class).getRunningTasks(1000);
        if (result==null) throw new IllegalStateException("The task list could not be read.");
        return result;
    }
    private ActivityManager.RunningTaskInfo selected() throws Exception {
        for (ActivityManager.RunningTaskInfo task:tasks())
            if (display(task)==originalDisplay && task.isRunning && task.topActivity!=null) return task;
        throw new IllegalStateException("The virtual display has no running app task.");
    }
    private ActivityManager.RunningTaskInfo tracked() throws Exception {
        taskGone=false;
        if (transfer==null) return null;
        ActivityManager.RunningTaskInfo found=find(transfer);
        taskGone=found==null;
        return found;
    }
    private ActivityManager.RunningTaskInfo find(Transfer identity) throws Exception {
        for (ActivityManager.RunningTaskInfo task:tasks()) if (task.taskId==identity.taskId) {
            if (user(task)!=identity.userId || !base(task).equals(identity.baseComponent))
                throw new IllegalStateException("The saved task identity no longer matches.");
            return task;
        }
        // getRunningTasks is bounded. Confirm absence in the complete root-task membership
        // before reporting a closed task; query failures must remain unknown.
        for (Object root:roots()) {
            if (root.getClass().getField("taskId").getInt(root)==identity.taskId)
                throw new IllegalStateException("The saved task still exists but its identity cannot be read.");
            int[] children=(int[])root.getClass().getField("childTaskIds").get(root);
            if (children==null) throw new IllegalStateException("The root task membership cannot be verified.");
            for (int child:children) if (child==identity.taskId)
                throw new IllegalStateException("The saved task still exists but its identity cannot be read.");
        }
        return null;
    }
    private static Class<?> api() throws ClassNotFoundException { return Class.forName("android.app.IActivityTaskManager"); }
    private static Object service() throws Exception {
        return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    }
    private static List<?> roots() throws Exception {
        Object result=api().getMethod("getAllRootTaskInfos").invoke(service());
        if (!(result instanceof List<?>)) throw new IllegalStateException("The root task list could not be read.");
        return (List<?>)result;
    }
    private int exclusiveRoot(ActivityManager.RunningTaskInfo task) throws Exception {
        for (Object root:roots()) {
            int rootId=root.getClass().getField("taskId").getInt(root);
            int rootDisplay=root.getClass().getField("displayId").getInt(root);
            int[] children=(int[])root.getClass().getField("childTaskIds").get(root);
            boolean contains=rootId==task.taskId;
            if (children!=null) for (int child:children) if (child==task.taskId) contains=true;
            if (!contains || rootDisplay!=display(task)) continue;
            if (children==null) throw new IllegalStateException("The root task membership cannot be verified.");
            for (int child:children) if (child!=task.taskId)
                throw new IllegalStateException("This task shares a root task with another app task.");
            return rootId;
        }
        throw new IllegalStateException("The app's root task cannot be verified.");
    }
    private void refresh() throws Exception {
        currentDisplay=null; taskGone=false;
        if (transfer==null) { state="agent"; currentDisplay=originalDisplay; return; }
        ActivityManager.RunningTaskInfo task=tracked();
        if (task==null) { state="unknown"; return; }
        currentDisplay=display(task);
        state=currentDisplay==originalDisplay ? "agent" : currentDisplay==Display.DEFAULT_DISPLAY ? "user" : "unknown";
    }
    private JSONObject snapshot(boolean ok) throws Exception {
        JSONObject result=new JSONObject().put("ok",ok).put("control_state",state)
                .put("original_display_id",originalDisplay);
        if (currentDisplay!=null) result.put("display_id",currentDisplay);
        if (transfer!=null) result.put("task_id",transfer.taskId).put("root_task_id",transfer.rootTaskId)
                .put("user_id",transfer.userId).put("package",transfer.packageName);
        Lease lease=transfer==null?null:leases.get(transfer.taskId);
        if(lease!=null) {
            result.put("task_origin",lease.borrowed?"borrowed":"created");
            if(lease.borrowed) result.put("source_display_id",lease.sourceDisplay);
        }
        if (taskGone) result.put("task_gone",true);
        return result;
    }
    private JSONObject failure(String code, String message, String dispatch) throws Exception {
        return snapshot(false).put("error",new JSONObject().put("code",code).put("message",message))
                .put("dispatch",dispatch).put("verification",taskGone || !state.equals("unknown")?"verified":"unknown");
    }
    private void refreshSafely() {
        try { refresh(); } catch (Exception failure) { state="unknown"; currentDisplay=null; taskGone=false; }
    }
    private JSONObject closedTask(String dispatch) throws Exception {
        return failure("task_gone","The original app task has closed and cannot be restored. You can close this virtual screen.",dispatch);
    }
    /** UI polling only. Never refresh control state, clear identity, or mutate a task. */
    JSONObject foregroundStatus() throws Exception {
        JSONObject result=new JSONObject().put("ok",true).put("control_state",state)
                .put("task_foreground",JSONObject.NULL).put("verification","unknown").put("dispatch","not_sent");
        Transfer identity=transfer;
        if(!state.equals("user") || identity==null)
            return result.put("reason","not_user_controlled");
        result.put("task_id",identity.taskId).put("user_id",identity.userId).put("package",identity.packageName);
        try {
            ActivityManager.RunningTaskInfo task=find(identity);
            if(task==null) return result.put("task_foreground",false).put("task_gone",true).put("verification","verified");
            int actualDisplay=display(task);
            result.put("display_id",actualDisplay);
            if(actualDisplay!=Display.DEFAULT_DISPLAY) return result.put("reason","unexpected_display");
            int root=exclusiveRoot(task);
            Object focused=api().getMethod("getFocusedRootTaskInfo").invoke(service());
            if(focused==null) return result.put("reason","focus_unavailable");
            int focusedDisplay=focused.getClass().getField("displayId").getInt(focused);
            int focusedId=focused.getClass().getField("taskId").getInt(focused);
            if(focusedDisplay!=Display.DEFAULT_DISPLAY) return result.put("reason","focus_not_main");
            // Do not publish a foreground value across a task migration or focus transition.
            ActivityManager.RunningTaskInfo confirmed=find(identity);
            if(confirmed==null || display(confirmed)!=Display.DEFAULT_DISPLAY || exclusiveRoot(confirmed)!=root)
                return result.put("reason","task_changed");
            Object confirmedFocus=api().getMethod("getFocusedRootTaskInfo").invoke(service());
            if(confirmedFocus==null || confirmedFocus.getClass().getField("displayId").getInt(confirmedFocus)!=focusedDisplay ||
                    confirmedFocus.getClass().getField("taskId").getInt(confirmedFocus)!=focusedId)
                return result.put("reason","focus_changed");
            return result.put("task_foreground",focusedId==root).put("verification","verified");
        } catch(Exception unavailable) { return result.put("reason","foreground_unavailable"); }
    }
    JSONObject execute(String op) throws Exception {
        synchronized (TASK_MUTATIONS) {
            boolean focusing=op.equals("focus") || op.equals("reopen");
            try {
                JSONObject result=executeAction(op);
                if(focusing && state.equals("agent")) {
                    state="unknown";
                    result.put("ok",false).put("control_state","unknown")
                            .put("error",new JSONObject().put("code","focus_unverified")
                                    .put("message","The task moved while being shown. Explicitly restore it before automation resumes."))
                            .put("verification","unknown");
                }
                return result;
            }
            finally {
                // A verified return completes this handoff; provenance lives separately.
                // A focus request never grants automation ownership, including an
                // external move observed mid-dispatch or along an exception path.
                if(focusing && state.equals("agent")) state="unknown";
                else if (state.equals("agent")) { transfer=null; taskGone=false; currentDisplay=originalDisplay; }
            }
        }
    }
    private JSONObject executeAction(String op) throws Exception {
        if (originalDisplay<=Display.DEFAULT_DISPLAY)
            return failure("unsupported","Only a virtual display can be handed to the user.","not_sent");
        refreshSafely();
        if (op.equals("control_status")) return snapshot(true);
        boolean takeover=op.equals("takeover");
        boolean focus=op.equals("focus") || op.equals("reopen");
        if (!takeover && !op.equals("restore") && !focus) return failure("unsupported","Unknown display control action.","not_sent");
        // Task removal can briefly leave an inactive entry. Allow that read-only
        // transition to settle, and require consecutive complete absence checks.
        boolean previouslyGone=taskGone;
        for (int attempt=0; state.equals("unknown") && transfer!=null && attempt<5 && !stopped.getAsBoolean(); attempt++) {
            try { Thread.sleep(60); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            refreshSafely();
            if (taskGone && previouslyGone) break;
            previouslyGone=taskGone;
        }
        if (state.equals("unknown")) return taskGone ? closedTask("not_sent") :
                failure("task_unavailable","The saved task or its display cannot be verified.","not_sent");
        if (focus) return focusUserTask();
        if (takeover && state.equals("user") || !takeover && state.equals("agent"))
            return snapshot(true).put("dispatch","not_sent").put("verification","verified");
        try {
            if (takeover) {
                transfer=null;
                ActivityManager.RunningTaskInfo task=selected();
                transfer=new Transfer(task,exclusiveRoot(task));
                currentDisplay=originalDisplay;
            }
            Display original=context.getSystemService(DisplayManager.class).getDisplay(originalDisplay);
            if (original==null || !original.isValid()) return failure("display_lost","The original virtual display is no longer available.","not_sent");
            ActivityManager.RunningTaskInfo task=tracked();
            int expected=takeover?originalDisplay:Display.DEFAULT_DISPLAY;
            if (task==null || display(task)!=expected) {
                refreshSafely(); return taskGone ? closedTask("not_sent") :
                        failure("task_changed","The task moved before the handoff could start.","not_sent");
            }
            transfer.rootTaskId=exclusiveRoot(task);
            if (stopped.getAsBoolean()) return failure("session_closed","The screen has closed.","not_sent");
        } catch (Exception beforeDispatch) {
            refreshSafely();
            if (taskGone) return closedTask("not_sent");
            return failure("handoff_unavailable",beforeDispatch.getMessage()==null?"The task cannot be moved safely.":beforeDispatch.getMessage(),"not_sent");
        }
        int target=takeover?Display.DEFAULT_DISPLAY:originalDisplay;
        Throwable error=null;
        try { api().getMethod("moveRootTaskToDisplay",int.class,int.class).invoke(service(),transfer.rootTaskId,target); }
        catch (Exception failure) { error=failure instanceof InvocationTargetException ? failure.getCause() : failure; }
        long until=SystemClock.uptimeMillis()+1800;
        do {
            refreshSafely();
            if (currentDisplay!=null && currentDisplay==target)
                return snapshot(true).put("dispatch","sent").put("verification","verified");
            if (error!=null || stopped.getAsBoolean()) break;
            try { Thread.sleep(40); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        } while (SystemClock.uptimeMillis()<until);
        if (taskGone) return closedTask(error instanceof SecurityException ? "not_sent":"unknown");
        return failure(error instanceof SecurityException ? "permission_denied":"handoff_failed",
                error instanceof SecurityException ? "Android did not authorize moving this task between displays.":
                        "Android did not confirm the requested task display.",error instanceof SecurityException ? "not_sent":"unknown");
    }

    private JSONObject focusUserTask() throws Exception {
        if (!state.equals("user") || transfer==null) {
            // Showing the app is not consent to return automation ownership, even
            // if another actor has moved the task back to the virtual display.
            if(transfer!=null) state="unknown";
            return failure("not_user_controlled","Only a task currently handed to the user can be shown again.","not_sent");
        }
        try {
            ActivityManager.RunningTaskInfo task=tracked();
            if (task==null) return closedTask("not_sent");
            if (display(task)!=Display.DEFAULT_DISPLAY) return failure("task_changed","The saved task is no longer on the main display.","not_sent");
            transfer.rootTaskId=exclusiveRoot(task);
            if (stopped.getAsBoolean()) return failure("session_closed","The screen has closed.","not_sent");
            api().getMethod("setFocusedTask",int.class).invoke(service(),transfer.taskId);
            long until=SystemClock.uptimeMillis()+1800;
            do {
                refreshSafely();
                if (!state.equals("user")) break;
                Object focused=api().getMethod("getFocusedRootTaskInfo").invoke(service());
                if (focused!=null && focused.getClass().getField("taskId").getInt(focused)==transfer.rootTaskId)
                    return snapshot(true).put("dispatch","sent").put("verification","verified");
                Thread.sleep(40);
            } while (SystemClock.uptimeMillis()<until && !stopped.getAsBoolean());
            return taskGone ? closedTask("unknown") : failure("focus_unverified","Android did not confirm that the original task is in front.","sent");
        } catch (Exception failed) {
            refreshSafely();
            return taskGone ? closedTask("not_sent") : failure("focus_unavailable","Android could not bring the original task to the front.","unknown");
        }
    }
    private static JSONObject launchError(String code,String message) throws Exception {
        return new JSONObject().put("ok",false).put("error",new JSONObject().put("code",code).put("message",message))
                .put("dispatch","not_sent").put("verification","verified");
    }
    private static boolean belongs(ActivityManager.RunningTaskInfo task,String pkg) {
        ComponentName component=task.baseIntent==null?null:task.baseIntent.getComponent();
        if (component==null) component=task.baseActivity;
        return component!=null && component.getPackageName().equals(pkg);
    }
    private void remember(Lease lease) throws Exception {
        VirtualTaskControl owner=OWNERS.get(lease.identity.taskId);
        if (owner!=null && owner!=this) throw new IllegalStateException("The task belongs to another virtual screen.");
        leases.put(lease.identity.taskId,lease); OWNERS.put(lease.identity.taskId,this);
    }
    private JSONObject describe(Lease lease,ActivityManager.RunningTaskInfo task) throws Exception {
        JSONObject result=new JSONObject().put("task_id",lease.identity.taskId).put("user_id",lease.identity.userId)
                .put("package",lease.identity.packageName).put("display_id",display(task))
                .put("task_origin",lease.borrowed?"borrowed":"created");
        if (lease.borrowed) result.put("source_display_id",lease.sourceDisplay);
        return result;
    }
    private ActivityManager.RunningTaskInfo move(Transfer identity,int expected,int target) throws Exception {
        return move(identity,expected,target,SystemClock.uptimeMillis()+1800);
    }
    private ActivityManager.RunningTaskInfo move(Transfer identity,int expected,int target,long deadline) throws Exception {
        ActivityManager.RunningTaskInfo task=find(identity);
        if (task==null || display(task)!=expected) throw new IllegalStateException("The identified task changed before migration.");
        identity.rootTaskId=exclusiveRoot(task);
        if (stopped.getAsBoolean() || SystemClock.uptimeMillis()>=deadline) throw new IllegalStateException("The screen closed or task migration timed out.");
        api().getMethod("moveRootTaskToDisplay",int.class,int.class).invoke(service(),identity.rootTaskId,target);
        long until=Math.min(deadline,SystemClock.uptimeMillis()+1800);
        do {
            task=find(identity);
            if (task!=null && display(task)==target) return task;
            Thread.sleep(40);
        } while(SystemClock.uptimeMillis()<until && !stopped.getAsBoolean());
        throw new IllegalStateException("Android did not confirm the task's destination display.");
    }
    /** Null means no reusable task exists. Imports are recorded before sending the move. */
    JSONObject existingLaunch(ComponentName target,int targetUser,boolean borrowAllowed) throws Exception {
        synchronized(TASK_MUTATIONS) {
            List<ActivityManager.RunningTaskInfo> matches=new ArrayList<>();
            for (ActivityManager.RunningTaskInfo task:tasks())
                if (user(task)==targetUser && belongs(task,target.getPackageName())) matches.add(task);
            if (matches.isEmpty()) return null;
            for (ActivityManager.RunningTaskInfo task:matches)
                if (display(task)!=Display.DEFAULT_DISPLAY && display(task)!=originalDisplay)
                    return launchError("app_on_other_display","This app already has a task on another virtual display.");
            if (matches.size()!=1) return launchError("ambiguous_task","This app has multiple tasks. Select one before moving it.");
            ActivityManager.RunningTaskInfo task=matches.get(0);
            VirtualTaskControl owner=OWNERS.get(task.taskId);
            if (owner!=null && owner!=this) return launchError("app_busy","Another virtual screen still owns this app task.");
            if (display(task)==originalDisplay) {
                Lease known=leases.get(task.taskId);
                if (known==null) return launchError("task_origin_unknown","This task's source cannot be verified.");
                task=find(known.identity); exclusiveRoot(task);
                api().getMethod("setFocusedTask",int.class).invoke(service(),task.taskId);
                return describe(known,task).put("ok",true).put("dispatch","sent").put("verification","verified");
            }
            if (!borrowAllowed || !mainAvailable.getAsBoolean()) return launchError("screen_busy","The main display is in use or has not been reserved for this app transfer.");
            Lease lease=new Lease(new Transfer(task,exclusiveRoot(task)),true,Display.DEFAULT_DISPLAY);
            remember(lease);
            try {
                ActivityManager.RunningTaskInfo moved=move(lease.identity,Display.DEFAULT_DISPLAY,originalDisplay);
                return describe(lease,moved).put("ok",true).put("dispatch","sent").put("verification","verified");
            } catch(Exception failure) {
                try {
                    ActivityManager.RunningTaskInfo actual=find(lease.identity);
                    if (actual!=null && display(actual)==originalDisplay)
                        return describe(lease,actual).put("ok",true).put("dispatch","sent").put("verification","verified");
                    if (actual!=null && display(actual)==Display.DEFAULT_DISPLAY) {
                        leases.remove(lease.identity.taskId); OWNERS.remove(lease.identity.taskId);
                        return launchError("borrow_failed","Android did not move the existing task; it remains on the main display.").put("dispatch","unknown");
                    }
                } catch(Exception unverifiable) { }
                transfer=lease.identity; state="unknown"; currentDisplay=null;
                return failure("borrow_unverified","The existing task's location cannot be verified. Close this screen to retry its return.","unknown");
            }
        }
    }
    Set<Integer> beforeLaunch() throws Exception {
        synchronized(TASK_MUTATIONS) {
            Set<Integer> ids=new HashSet<>();
            for (ActivityManager.RunningTaskInfo task:tasks()) ids.add(task.taskId);
            for (Object root:roots()) {
                ids.add(root.getClass().getField("taskId").getInt(root));
                int[] children=(int[])root.getClass().getField("childTaskIds").get(root);
                if (children==null) throw new IllegalStateException("The existing task membership cannot be read.");
                for(int child:children) ids.add(child);
            }
            return ids;
        }
    }
    JSONObject recordLaunch(String pkg,int targetUser,Set<Integer> before) throws Exception {
        synchronized(TASK_MUTATIONS) {
            ActivityManager.RunningTaskInfo candidate=null;
            for (ActivityManager.RunningTaskInfo task:tasks()) if(display(task)==originalDisplay && user(task)==targetUser && belongs(task,pkg) && !before.contains(task.taskId)) {
                if(candidate!=null) throw new IllegalStateException("Android created multiple candidate tasks.");
                candidate=task;
            }
            if(candidate==null) return null;
            Lease lease=new Lease(new Transfer(candidate,exclusiveRoot(candidate)),false,originalDisplay);
            remember(lease);
            return describe(lease,candidate);
        }
    }
    JSONObject metadata() {
        synchronized(TASK_MUTATIONS) {
            try {
                ActivityManager.RunningTaskInfo task=selected();
                Lease lease=leases.get(task.taskId);
                if(lease==null) return null;
                ActivityManager.RunningTaskInfo actual=find(lease.identity);
                return actual!=null && display(actual)==originalDisplay ? describe(lease,actual) : null;
            } catch(Exception unavailable) { return null; }
        }
    }
    JSONObject closeTasks() throws Exception {
        synchronized(TASK_MUTATIONS) {
            int returned=0,removed=0,preserved=0;
            long deadline=SystemClock.uptimeMillis()+10000;
            // Return every borrowed task before ending any task created by this screen.
            for (boolean borrowing:new boolean[]{true,false}) for(Lease lease:new ArrayList<>(leases.values())) {
                if(lease.borrowed!=borrowing) continue;
                try {
                    if(SystemClock.uptimeMillis()>=deadline) throw new IllegalStateException("Close verification reached its time limit.");
                    ActivityManager.RunningTaskInfo task=find(lease.identity);
                    if(task==null) continue;
                    int actual=display(task);
                    if(actual==Display.DEFAULT_DISPLAY) { preserved++; continue; }
                    if(actual!=originalDisplay) throw new IllegalStateException("The task is on an unexpected display.");
                    if(lease.borrowed) {
                        if(!mainAvailable.getAsBoolean()) throw new IllegalStateException("The main display is in use by another session.");
                        move(lease.identity,originalDisplay,lease.sourceDisplay,deadline); returned++;
                    } else {
                        exclusiveRoot(task);
                        // Recheck identity and location immediately before removing this exact task.
                        task=find(lease.identity);
                        if(task==null) continue;
                        if(display(task)!=originalDisplay) throw new IllegalStateException("The task moved before it could be closed.");
                        api().getMethod("removeTask",int.class).invoke(service(),lease.identity.taskId);
                        long until=Math.min(deadline,SystemClock.uptimeMillis()+1800);
                        boolean gone=false;
                        do {
                            try { gone=find(lease.identity)==null; } catch(Exception removing) { }
                            if(gone) break;
                            Thread.sleep(40);
                        } while(SystemClock.uptimeMillis()<until);
                        if(!gone) throw new IllegalStateException("Android did not confirm that the created task closed.");
                        removed++;
                    }
                } catch(Exception failed) {
                    return new JSONObject().put("ok",false).put("closed",false).put("control_state","unknown")
                            .put("error",new JSONObject().put("code",lease.borrowed?"close_restore_failed":"close_cleanup_failed")
                                    .put("message",lease.borrowed?"The borrowed app task could not be returned. The virtual screen remains open; retry close.":
                                            "The created app task could not be safely closed. The virtual screen remains open; retry close."))
                            .put("task_id",lease.identity.taskId).put("returned_tasks",returned).put("removed_tasks",removed)
                            .put("dispatch","unknown").put("verification","unknown");
                }
            }
            // Unknown tasks are never guessed to be ours. A public display release
            // preserves them on the main display, including on a broken connection.
            for(ActivityManager.RunningTaskInfo task:tasks())
                if(display(task)==originalDisplay && !leases.containsKey(task.taskId)) preserved++;
            return new JSONObject().put("ok",true).put("closed",true).put("control_state",state)
                    .put("returned_tasks",returned).put("removed_tasks",removed).put("preserved_tasks",preserved);
        }
    }
    void releaseOwnership() {
        synchronized(TASK_MUTATIONS) {
            for(Integer id:leases.keySet()) if(OWNERS.get(id)==this) OWNERS.remove(id);
            leases.clear();
        }
    }
}
