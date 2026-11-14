package app.dsh.sugar.mobile

import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

internal data class LauncherApp(val label: String, val pkg: String, val activity: String) {
    fun json() = JSONObject().put("label", label).put("package", pkg).put("activity", activity)
}

internal object LauncherAppNames {
    private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filterNot(Char::isWhitespace)

    fun matches(name: String, apps: Sequence<LauncherApp>): List<LauncherApp> {
        val query = normalized(name)
        if (name.length > 128 || query.isEmpty()) throw ScreenProblem("invalid_argument", "Supply an app name of 1–128 characters.")
        val partial = query.codePointCount(0, query.length) >= 2
        val exactMatches = mutableListOf<LauncherApp>()
        val partialMatches = mutableListOf<LauncherApp>()
        for (app in apps) {
            val label = normalized(app.label)
            if (query == label) exactMatches.add(app)
            else if (partial && label.contains(query)) partialMatches.add(app)
        }
        return exactMatches.ifEmpty { partialMatches }.distinctBy { it.pkg to it.activity }
            .sortedWith(compareBy({ it.label }, { it.pkg }, { it.activity }))
    }

    @Suppress("DEPRECATION")
    fun resolve(manager: PackageManager, name: String): JSONObject {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = sequence {
            for (entry in manager.queryIntentActivities(launcher, 0)) {
                val activity = entry.activityInfo ?: continue
                if (activity.exported) yield(LauncherApp(activity.loadLabel(manager).toString(), activity.packageName, activity.name))
            }
        }
        val found = matches(name, apps)
        if (found.isEmpty()) return screenError("app_not_found", "No launcher app matches this name in the current profile.")
        if (found.size > 1) {
            val error = screenError("ambiguous_app", "Multiple launcher entries match. Choose the intended app before launching.")
            error.getJSONObject("error").put("candidates", JSONArray().apply { found.take(8).forEach { put(it.json()) } })
            return error
        }
        return found.single().json().put("ok", true)
    }
}
