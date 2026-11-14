package app.dsh.sugar.mobile.shell;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;

/** Query only windows which Android itself has placed on the specific virtual display. */
@android.annotation.TargetApi(34)
final class VirtualWindows implements AutoCloseable {
    final UiAutomation ui;
    // Android registers one UiAutomation per user. Each display still owns its own node snapshot.
    private static HandlerThread events;
    private static UiAutomation shared;
    private static int users;
    private final int displayId;
    private final HashMap<String,JSONObject> lastNodes = new HashMap<>();
    VirtualWindows(Context context, int displayId) throws Exception {
        this.displayId=displayId;
        synchronized (VirtualWindows.class) {
            if (shared == null) shared = connect(context);
            ui=shared;
            users++;
        }
    }
    private static UiAutomation connect(Context context) throws Exception {
        events = new HandlerThread("sugar-virtual-events");
        events.start();
        UiAutomation created = null;
        try {
            Class<?> binder = Class.forName("android.app.IUiAutomationConnection");
            Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            try {
                Constructor<?> ctor = UiAutomation.class.getDeclaredConstructor(Looper.class,binder);
                created = (UiAutomation)ctor.newInstance(events.getLooper(),connection);
            } catch (NoSuchMethodException e) {
                Constructor<?> ctor = UiAutomation.class.getDeclaredConstructor(Context.class,binder);
                created = (UiAutomation)ctor.newInstance(context,connection);
            }
            UiAutomation.class.getMethod("connect",int.class).invoke(created,UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = created.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            created.setServiceInfo(info);
            return created;
        } catch (Exception e) {
            if (created != null) disconnect(created);
            events.quitSafely();
            throw e;
        }
    }
    private static void disconnect(UiAutomation automation) {
        try { UiAutomation.class.getMethod("disconnect").invoke(automation); } catch(Exception ignored) { }
    }
    @Override public void close() {
        synchronized (VirtualWindows.class) {
            if (--users == 0) {
                disconnect(shared);
                shared = null;
                events.quitSafely();
                events = null;
            }
        }
    }
    AccessibilityWindowInfo window() {
        List<AccessibilityWindowInfo> windows = ui.getWindowsOnAllDisplays().get(displayId);
        if (windows == null) return null;
        AccessibilityWindowInfo best=null;
        for (AccessibilityWindowInfo w : windows) {
            if (w.getDisplayId() != displayId || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            if (w.isFocused()) return w;
            if (best == null || w.getLayer() > best.getLayer()) best=w;
        }
        return best;
    }
    static String text(CharSequence value) {
        if (value == null) return "";
        return value.subSequence(0,Math.min(value.length(),700)).toString();
    }
    private static JSONObject describe(AccessibilityNodeInfo n,String id,String parent) throws Exception {
        Rect r=new Rect(); n.getBoundsInScreen(r);
        return new JSONObject().put("id",id).put("parent_id",parent == null ? JSONObject.NULL : parent)
                .put("text",n.isPassword() ? "" : text(n.getText())).put("description",n.isPassword() ? "" : text(n.getContentDescription()))
                .put("class_name",text(n.getClassName())).put("resource_id",text(n.getViewIdResourceName()))
                .put("bounds",new JSONArray(new int[]{r.left,r.top,r.right,r.bottom}))
                .put("enabled",n.isEnabled()).put("visible",n.isVisibleToUser()).put("clickable",n.isClickable())
                .put("editable",n.isEditable()).put("long_clickable",n.isLongClickable()).put("password",n.isPassword())
                .put("selected",n.isSelected()).put("checked",n.isChecked());
    }
    private void visit(AccessibilityNodeInfo n,String id,String parent,JSONArray nodes,int depth) throws Exception {
        JSONObject item=describe(n,id,parent); nodes.put(item); lastNodes.put(id,item);
        if (depth >= 30) return;
        for (int i=0;i<n.getChildCount() && nodes.length()<500;i++) {
            AccessibilityNodeInfo child = n.getChild(i);
            if(child!=null) visit(child,id+"."+i,id,nodes,depth+1);
        }
    }
    JSONObject tree(AccessibilityNodeInfo root) throws Exception {
        lastNodes.clear();
        JSONArray nodes=new JSONArray();
        if (root!=null) visit(root,"0",null,nodes,0);
        return new JSONObject().put("nodes",nodes).put("truncated",nodes.length()>=500);
    }
    AccessibilityNodeInfo resolve(AccessibilityNodeInfo root,String id) throws Exception {
        JSONObject old=lastNodes.get(id);
        if(old==null || root==null) return null;
        String[] path=id.split("\\.");
        AccessibilityNodeInfo node=root;
        for(int i=1;i<path.length && node!=null;i++) {
            int index=Integer.parseInt(path[i]);
            node=index<node.getChildCount()?node.getChild(index):null;
        }
        if(node==null || !node.refresh() || !node.isVisibleToUser() || !node.isEnabled()) return null;
        JSONObject now=describe(node,id,null);
        for (String field : new String[]{"text","class_name","resource_id","description","bounds"}) {
            if (!old.get(field).toString().equals(now.get(field).toString())) return null;
        }
        return node;
    }
    boolean input(AccessibilityNodeInfo node,String value) {
        Bundle data=new Bundle();
        data.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        return node.isEditable() && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,data);
    }
}
