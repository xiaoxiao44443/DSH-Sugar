package app.dsh.sugar

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.app.KeyguardManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

internal class AgentNotificationPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("agent-notifications", Context.MODE_PRIVATE)

    fun read(): JSONObject = JSONObject()
        .put("completion", prefs.getString("completion", "background"))
        .put("permission", prefs.getBoolean("permission", true))
        .put("question", prefs.getBoolean("question", true))
        .put("muteWhilePetVisible", prefs.getBoolean("muteWhilePetVisible", true))

    fun update(value: JSONObject) {
        val edit = prefs.edit()
        val completion = value.opt("completion")
        if (completion is String && completion in setOf("background", "always", "off"))
            edit.putString("completion", completion)
        for (key in listOf("permission", "question", "muteWhilePetVisible"))
            (value.opt(key) as? Boolean)?.let { edit.putBoolean(key, it) }
        edit.apply()
    }
}

/** The foreground runtime and its WebView share a process, but only visible activities count here. */
internal object AgentNotifications {
    private val visibleActivities = AtomicInteger()
    @Volatile private var petAttached = false
    fun petVisibilityChanged(attached: Boolean) { petAttached = attached }

    // The preference alone is insufficient: a hidden pet cannot replace a notification.
    private fun petVisible(context: Context): Boolean = petAttached && Settings.canDrawOverlays(context) &&
        context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    fun activityStarted() { visibleActivities.incrementAndGet() }
    fun activityStopped() { visibleActivities.decrementAndGet() }

    private const val OPEN_SESSION = "app.dsh.sugar.OPEN_AGENT_NOTIFICATION"
    private const val NOTICE = 2

    private enum class Kind(val key: String, val channelName: Int, val heading: Int, val importance: Int) {
        COMPLETION("completion", R.string.agent_turn_channel, R.string.agent_turn_title, NotificationManager.IMPORTANCE_HIGH),
        PERMISSION("permission", R.string.agent_approval_channel, R.string.agent_approval_title, NotificationManager.IMPORTANCE_HIGH),
        QUESTION("question", R.string.agent_question_channel, R.string.agent_question_title, NotificationManager.IMPORTANCE_HIGH)
    }

    fun refreshChannelNames(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        for (kind in Kind.entries) {
            val channel = manager.getNotificationChannel("agent_" + kind.key) ?: continue
            channel.name = context.sugarString(kind.channelName)
            manager.createNotificationChannel(channel)
        }
    }

    fun validId(id: String) = id.isNotBlank() && id.length <= 512 && id.none { it.isISOControl() }

    fun sessionIntent(context: Context, session: String): Intent {
        require(validId(session))
        return NotificationEntryActivity.intent(context).setAction(OPEN_SESSION)
            .setData(Uri.Builder().scheme("dsh-sugar").authority("session").appendPath(session).build())
    }

    fun postedSession(entry: StatusBarNotification): String? {
        if (entry.id != NOTICE || entry.tag == null) return null
        val tag = try { JSONArray(entry.tag) } catch (_: org.json.JSONException) { return null }
        if (tag.length() != 3 || Kind.entries.none { it.key == tag.optString(0) && entry.notification.channelId == "agent_" + it.key }) return null
        return (tag.opt(1) as? String)?.takeIf(::validId)
    }

    fun openSession(context: Context, session: String): PendingIntent =
        PendingIntent.getActivity(context, 0, sessionIntent(context, session), PendingIntent.FLAG_IMMUTABLE)

    fun target(intent: Intent?): String? {
        if (intent?.action != OPEN_SESSION) return null
        val uri = intent.data ?: return null
        if (uri.scheme != "dsh-sugar" || uri.authority != "session") return null
        return uri.pathSegments.singleOrNull()?.takeIf(::validId)
    }

    // A fresh DSH Host cannot accept responses to the previous Host's pending requests.
    fun clearPendingFromPreviousHost(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        for (entry in manager.activeNotifications) {
            if (entry.id == NOTICE && entry.notification.channelId in setOf("agent_permission", "agent_question"))
                manager.cancel(entry.tag, entry.id)
        }
    }

    @Synchronized
    fun receive(context: Context, event: JSONObject, dismiss: Boolean) {
        val kind = Kind.entries.first { it.key == event.getString("kind") }
        val session = event.getString("sessionId")
        val id = event.getString("eventId")
        require(validId(session) && validId(id))
        val tag = JSONArray(listOf(kind.key, session, id)).toString()
        val manager = context.getSystemService(NotificationManager::class.java)
        // A resolved request can arrive before a retried publication. Keep a bounded receipt
        // even after Android auto-cancels a tapped notification or recreates this process.
        val history = context.getSharedPreferences("agent-notification-events", Context.MODE_PRIVATE)
        val recent = JSONArray(history.getString("recent", "[]"))
        val seen = (0 until recent.length()).any { recent.optString(it) == tag }
        if (dismiss) manager.cancel(tag, NOTICE)
        if (seen) return
        if (!dismiss) {
            val settings = AgentNotificationPreferences(context).read()
            val policy = AgentNotificationPolicy(settings.getString("completion"), settings.getBoolean("permission"),
                settings.getBoolean("question"), settings.getBoolean("muteWhilePetVisible"))
            val enabled = policy.allows(kind.key, visibleActivities.get() > 0,
                policy.muteWhilePetVisible && petVisible(context))
            if (enabled && manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                val channel = "agent_" + kind.key
                manager.createNotificationChannel(NotificationChannel(channel, context.sugarString(kind.channelName), kind.importance))
                val open = openSession(context, session)
                val conversation = (event.opt("title") as? String)?.lineSequence()
                    ?.joinToString(" ") { it.trim() }?.trim()?.takeIf { it.isNotEmpty() }?.take(120)
                    ?: context.sugarString(R.string.agent_untitled, session.removePrefix("session-").take(8))
                val heading = context.sugarString(kind.heading)
                val body = AgentNotificationBody.normalize(event.opt("body") as? String) ?: heading
                val notification = Notification.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_runtime_notification)
                    .setContentTitle(conversation)
                    .setContentText(body)
                    .setStyle(Notification.BigTextStyle().bigText(body))
                    .setSubText(heading)
                    .setContentIntent(open)
                    .setCategory(if (kind == Kind.COMPLETION) Notification.CATEGORY_STATUS else Notification.CATEGORY_MESSAGE)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .build()
                manager.notify(tag, NOTICE, notification)
            }
        }
        if (recent.length() >= 256) recent.remove(0)
        recent.put(tag)
        history.edit().putString("recent", recent.toString()).apply()
    }
}
