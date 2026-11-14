package app.dsh.sugar

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.content.pm.PackageManager
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams

/** Keeps each document-picker result with the WebView request that opened it. */
internal class UploadPicker {
    private data class Request(val code: Int, val multiple: Boolean, val callback: ValueCallback<Array<Uri>>)
    private var next = FIRST
    private var pending: Request? = null

    fun restore(state: Bundle?) {
        next = state?.getInt(NEXT, FIRST)?.takeIf { it in FIRST..LAST } ?: FIRST
    }

    fun save(state: Bundle) = state.putInt(NEXT, next)

    @Suppress("DEPRECATION")
    fun open(activity: Activity, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        cancel()
        val multiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        if (!multiple && params.mode != FileChooserParams.MODE_OPEN) {
            callback.onReceiveValue(null)
            return true
        }
        val types = params.acceptTypes.flatMap { it.split(',') }.mapNotNull {
            val value = it.trim().lowercase()
            when {
                value.startsWith('.') -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(value.drop(1))
                '/' in value -> value
                else -> null
            }
        }.distinct()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
            type = types.singleOrNull() ?: "*/*"
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        val code = next
        next = if (next == LAST) FIRST else next + 1
        pending = Request(code, multiple, callback)
        try {
            activity.startActivityForResult(intent, code)
        } catch (_: ActivityNotFoundException) {
            cancel()
        } catch (_: SecurityException) {
            cancel()
        }
        return true
    }

    fun result(activity: Activity, code: Int, result: Int, data: Intent?): Boolean {
        if (code !in FIRST..LAST) return false
        val current = pending?.takeIf { it.code == code } ?: return true
        pending = null
        val selected = linkedSetOf<Uri>()
        if (result == Activity.RESULT_OK && data != null) {
            fun accept(uri: Uri?) {
                if (uri?.scheme == "content" && !uri.authority.isNullOrEmpty() &&
                    activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    == PackageManager.PERMISSION_GRANTED) selected.add(uri)
            }
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) accept(clip.getItemAt(i).uri) }
            accept(data.data)
        }
        val files = if (current.multiple) selected.toList() else selected.take(1)
        current.callback.onReceiveValue(files.takeIf { it.isNotEmpty() }?.toTypedArray())
        return true
    }

    fun cancel() {
        val current = pending
        pending = null
        current?.callback?.onReceiveValue(null)
    }

    private companion object {
        const val NEXT = "nextUploadRequest"
        const val FIRST = 0x4200
        const val LAST = 0x7eff
    }
}
