package app.dsh.sugar.workspace

import app.dsh.sugar.R
import app.dsh.sugar.sugarString

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import app.dsh.sugar.runtime.SugarRuntime
import java.util.UUID
import kotlin.concurrent.thread

/** Reveal a workspace file without exporting a directory or sharing a filesystem path. */
internal class ProjectFolderReveal(private val activity: Activity) {
    private var resolving = false

    fun showInternal() = open(WorkspaceDocumentsProvider.internalRoot(activity))

    fun show(id: String?, path: String?, current: () -> Boolean) {
        if (resolving || !current()) return
        resolving = true
        thread(name = "sugar-reveal-directory") {
            val folder = runCatching {
                require(id != null && UUID.fromString(id).toString() == id &&
                    path != null && path.length <= 4095) { activity.sugarString(R.string.ws_invalid_path) }
                val parent = if (path.isEmpty()) {
                    // A project may have been registered just now, before any tool requested its workspace.
                    checkNotNull(SugarRuntime.activeProjects).workspace(id)
                    emptyList()
                } else path.split('/').onEach(::checkedName).dropLast(1)
                // Resolves the live project ID, rechecks the disk root and opens every ancestor without symlinks.
                val root = checkNotNull(SugarRuntime.activeProjects?.registeredRoot(id))
                val local = LocalFolderWorkspaces.treeFor(activity, id, root)
                if (local != null) LocalDocumentDirectory(activity, local).directoryUri(parent)
                else WorkspaceDocumentsProvider.uriForDirectory(activity, id, parent)
            }
            activity.runOnUiThread {
                resolving = false
                if (!current()) return@runOnUiThread
                folder.onSuccess(::open).onFailure { toast(activity.sugarString(R.string.ws_folder_not_found)) }
            }
        }
    }

    private fun picker(folder: Uri) = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder)

    private fun open(folder: Uri) {
        // Prefer the device's SAF file browser when it also implements directory VIEW.
        val systemFiles = activity.packageManager.resolveActivity(picker(folder),
            PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (systemFiles != null && systemFiles != "android" && launch(Intent(view).setPackage(systemFiles))) return
        if (launch(view)) return
        if (launch(picker(folder), fallback = true)) {
            toast(activity.sugarString(R.string.ws_picker_opened))
            return
        }
        toast(activity.sugarString(R.string.ws_no_file_manager))
    }

    @Suppress("DEPRECATION")
    private fun launch(intent: Intent, fallback: Boolean = false): Boolean = try {
        if (fallback) activity.startActivityForResult(intent, BROWSE_PICKER) else activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    companion object {
        const val BROWSE_PICKER = 0x2964
    }
}
