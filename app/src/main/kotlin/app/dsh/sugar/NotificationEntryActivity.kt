package app.dsh.sugar

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/** Route launches while preserving the active diagnostic or file picker above the workspace. */
class NotificationEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = AgentNotifications.target(intent)
        val prefs = getSharedPreferences(STORE, MODE_PRIVATE)
        val workspace = getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
            it.taskInfo?.baseActivity?.className == WorkspaceActivity::class.java.name
        }
        val launcher = intent.action == Intent.ACTION_MAIN ||
            (intent.action == null && intent.component?.className == "$packageName.MainActivity")
        if (RecoveryController.working || launcher && workspace != null) {
            // A launcher tap restores the whole task, including a diagnostic viewer or a file picker.
            if (session != null) prefs.edit().putString(SESSION, session).apply()
            workspace?.moveToFront()
        } else {
            if (session != null) prefs.edit().remove(SESSION).apply()
            val open = Intent(this, WorkspaceActivity::class.java).setAction(intent.action).setData(intent.data)
            if (intent.hasCategory(Intent.CATEGORY_LAUNCHER)) open.addCategory(Intent.CATEGORY_LAUNCHER)
            startActivity(open)
        }
        finish()
    }

    internal companion object {
        private const val STORE = "agent-notification-events"
        private const val SESSION = "deferredSession"

        fun intent(context: Context): Intent = Intent(context, NotificationEntryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // A notification surviving an APK update still holds the old CLEAR_TOP PendingIntent.
        // Preserve the original conversation, ongoing pairing actions and notification identity.
        fun migratePostedNotices(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            for (entry in manager.activeNotifications) {
                val old = entry.notification.contentIntent ?: continue
                val session = AgentNotifications.postedSession(entry)
                val replacement = when {
                    session != null -> AgentNotifications.openSession(context, session)
                    AdbPairingNotice.matches(entry) -> AdbPairingNotice.openPairing(context)
                    else -> continue
                }
                if (replacement == old) continue
                val updated = entry.notification.clone().apply {
                    contentIntent = replacement
                    flags = flags or Notification.FLAG_ONLY_ALERT_ONCE
                }
                manager.notify(entry.tag, entry.id, updated)
                old.cancel()
            }
        }

        fun takeDeferred(context: Context): String? {
            val prefs = context.getSharedPreferences(STORE, MODE_PRIVATE)
            val session = prefs.getString(SESSION, null) ?: return null
            prefs.edit().remove(SESSION).apply()
            return session.takeIf(AgentNotifications::validId)
        }
    }
}
