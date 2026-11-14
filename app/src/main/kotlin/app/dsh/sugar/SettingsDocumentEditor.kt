package app.dsh.sugar

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.sugar.runtime.SettingsDocumentPatch
import app.dsh.sugar.runtime.RecoveryProfileDocument
import app.dsh.sugar.shared.WorkspaceEndpoint
import com.sucrune.probe.RuntimeClient
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.concurrent.thread

internal class SettingsDocumentEditor(private val activity: Activity, private val recovery: RecoveryProfileDocument? = null) {
    private var boundView: WebView? = null
    private var endpoint: WorkspaceEndpoint? = null
    private var action = ""
    private var showing = false
    private val external by lazy { ExternalProfileEdit(activity) }
    private var snapshotReview: (() -> Unit)? = null

    fun attach(view: WebView, origin: String, stockRecoveryButton: Boolean = false) {
        val current = WorkspaceEndpoint.parse(origin) ?: return
        if (!current.owns(view.url.orEmpty())) return
        boundView = view
        endpoint = current
        action = "dsh-sugar://settings/open/" + UUID.randomUUID()
        val script = """
            (() => {
              if (window.top !== window || location.origin !== ${JSONObject.quote(current.origin)}) return;
              window.__dshSugarOpenProfile = () => {
                if (navigator.userActivation && !navigator.userActivation.isActive) return;
                location.href = ${JSONObject.quote(action)};
              };
              if ($stockRecoveryButton) {
                if (window.__sugarRecoveryProfileClick) window.removeEventListener('click',window.__sugarRecoveryProfileClick,true);
                // The bundled DSH settings module has its original button. The installed
                // copy may already delegate to __dshSugarOpenProfile through the Android marker.
                const click = e => {
                  const b = e.target.closest?.('button');
                  if (!e.isTrusted || !b || b.disabled || !b.matches('.me01iq_action > button,button[data-sugar-open-settings]')) return;
                  e.preventDefault(); e.stopImmediatePropagation(); window.__dshSugarOpenProfile();
                };
                window.__sugarRecoveryProfileClick = click;
                window.addEventListener('click',click,true);
              }
            })()
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    fun handle(view: WebView, request: WebResourceRequest, openEditor: () -> Unit = ::chooseEditor): Boolean {
        if (request.url.scheme != "dsh-sugar") return false
        if (request.isForMainFrame && request.hasGesture() && view === boundView &&
            request.url.toString() == action && endpoint?.owns(view.url.orEmpty()) == true && !showing) {
            openEditor()
        }
        return true
    }

    fun onActivityResult(requestCode: Int): Boolean =
        external.takeResult(requestCode, ::review)

    fun restoreReview() = external.restoreReview(::review)

    fun retainReview() { snapshotReview?.invoke() }

    private fun review(draft: ExternalProfileEdit.Draft?) {
        if (draft != null && !showing && !activity.isFinishing && !activity.isDestroyed) show(draft)
    }

    private fun chooseEditor() {
        var selected = -1
        val choices = arrayOf(activity.sugarString(R.string.profile_builtin), activity.sugarString(R.string.profile_external))
        val dialog = SugarDialog.builder(activity, landscapeMaxWidthDp = 320).setTitle(activity.sugarString(R.string.profile_open_with))
            .setItems(choices) { _, index -> selected = index }
            .setNegativeButton(activity.sugarString(R.string.dialog_cancel), null).create()
        showing = true
        dialog.setOnDismissListener {
            showing = false
            if (!activity.isFinishing && !activity.isDestroyed) {
                when (selected) {
                    0 -> show()
                    1 -> openExternal()
                }
            }
        }
        dialog.show()
    }

    private data class Profile(val id: String, val revision: String, val raw: ByteArray)

    private data class Document(
        val text: String,
        val bom: Boolean,
        val crlf: Boolean
    ) {
        fun encode(draft: String): ByteArray {
            val body = if (crlf) draft.replace("\r\n", "\n").replace("\n", "\r\n") else draft
            return ((if (bom) "\uFEFF" else "") + body).toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): Document {
                require(bytes.size <= LIMIT)
                val decoded = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                val content = decoded.removePrefix("\uFEFF")
                val crlf = "\r\n" in content && '\n' !in content.replace("\r\n", "")
                val displayed = if (crlf) content.replace("\r\n", "\n") else content
                return Document(displayed, decoded.startsWith("\uFEFF"), crlf)
            }
        }
    }

    private val client get() = RuntimeClient.get(activity.applicationContext)

    private fun read(): Profile {
        recovery?.let {
            val raw = it.read()
            return Profile(it.project, raw?.let(SettingsDocumentPatch::sha256) ?: "missing", raw ?: byteArrayOf())
        }
        val projects = client.listEnvironments()
        val id = (0 until projects.length()).map { projects.getJSONObject(it) }
            .single { it.getString("name") == "DSH Sugar" }.getString("id")
        val raw = client.readFile(id, PROFILE)
        require(raw.size <= LIMIT)
        return Profile(id, SettingsDocumentPatch.sha256(raw), raw)
    }

    private fun save(profile: Profile, expectedRevision: String, bytes: ByteArray): Int {
        recovery?.let { return it.save(expectedRevision,bytes) }
        SettingsDocumentPatch.recoverEditorLock(client, profile.id)
        val helper = "/opt/sugar/android-save-profile.mjs"
        activity.assets.open("guest/save-profile.mjs").use { client.writeFile(profile.id, helper, it.readBytes()) }
        val id = client.executePath(profile.id, "/usr/bin/node", listOf(helper, expectedRevision),
            env = listOf("HOME=/home/sugar", "DSH_HOME=/home/sugar/.dsh", "LANG=C.UTF-8",
                "PATH=/usr/bin:/bin", "TMPDIR=/tmp", "SUCRUNE_MEMORY_POLICY=linux-jit",
                "LD_LIBRARY_PATH=/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu"),
            timeoutMs = 30_000, stdin = bytes)
        return client.waitFor(id, 35_000).optInt("exit_code", 1)
    }

    private fun <T> inBackground(dialog: AlertDialog, work: () -> T, done: (Result<T>) -> Unit) {
        thread(name = "DSH-settings-document") {
            val result = try { Result.success(work()) } catch (e: Exception) { Result.failure(e) }
            activity.runOnUiThread {
                if (!activity.isDestroyed && !activity.isFinishing && dialog.isShowing) done(result)
            }
        }
    }

    private fun openExternal() {
        val dialog = SugarDialog.builder(activity).setTitle(activity.sugarString(R.string.profile_external))
            .setMessage(activity.sugarString(R.string.profile_reading)).setPositiveButton(activity.sugarString(R.string.profile_retry), null)
            .setNegativeButton(activity.sugarString(R.string.dialog_cancel), null).create()
        showing = true
        dialog.setOnDismissListener { showing = false }
        dialog.show()
        val retry = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        fun launch() {
            dialog.setMessage(activity.sugarString(R.string.profile_reading))
            retry.visibility = View.GONE
            inBackground(dialog, { read().also { Document.decode(it.raw) } }) { result ->
                val profile = result.getOrNull()
                if (profile == null) {
                    dialog.setMessage(activity.sugarString(R.string.profile_unreadable))
                    retry.visibility = View.VISIBLE
                } else {
                    dialog.dismiss()
                    external.launch(profile.raw, profile.revision)
                }
            }
        }
        retry.setOnClickListener { launch() }
        launch()
    }

    private data class Loaded(
        val file: Profile, val document: Document, val conflict: Boolean, val alreadyApplied: Boolean
    )
    private class InvalidExternalDocument : Exception()

    fun showRecovery(onDismiss: () -> Unit) {
        check(recovery != null)
        show(onDismiss = onDismiss)
    }

    private fun show(returned: ExternalProfileEdit.Draft? = null, onDismiss: () -> Unit = {}) {
        val builder = SugarDialog.builder(activity)
        val context = builder.context
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
            typeface = Typeface.MONOSPACE
            textSize = 14f
            gravity = Gravity.TOP or Gravity.START
            minLines = 5
            maxLines = 16
            isEnabled = false
        }
        val status = TextView(context).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply { text = if (recovery == null) PROFILE else RecoveryProfileDocument.PATH; textSize = 12f })
            addView(input)
            addView(status)
        }
        val title = if (returned == null) activity.sugarString(R.string.profile_edit) else activity.sugarString(R.string.profile_review)
        val dialog = builder.setTitle(title).setView(content)
            .setNegativeButton(activity.sugarString(R.string.dialog_cancel), null).setNeutralButton(activity.sugarString(R.string.profile_reload), null)
            .setPositiveButton(activity.sugarString(R.string.profile_save), null).create()
        dialog.setOnDismissListener {
            showing = false
            snapshotReview = null
            onDismiss()
        }
        showing = true
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val reload = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        var opened: Loaded? = null
        var pending = returned
        var persistedBytes = returned?.bytes
        var discardOnReload = false
        fun discardReview() {
            pending?.let(external::discardReview)
            pending = null
            snapshotReview = null
        }
        cancel.setOnClickListener { discardReview(); dialog.dismiss() }
        dialog.setOnCancelListener { discardReview() }
        snapshotReview = {
            val current = opened
            val draft = pending
            if (current != null && draft != null) {
                val bytes = current.document.encode(input.text.toString())
                if (!bytes.contentEquals(persistedBytes) && external.updateReview(draft, bytes)) persistedBytes = bytes
            }
        }
        fun load() {
            ok.isEnabled = false
            input.isEnabled = false
            reload.visibility = View.GONE
            status.text = activity.sugarString(R.string.profile_loading)
            val draft = pending
            inBackground(dialog, {
                val file = read()
                val document = if (draft == null) Document.decode(file.raw) else try {
                    Document.decode(draft.bytes)
                } catch (_: IllegalArgumentException) {
                    throw InvalidExternalDocument()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    throw InvalidExternalDocument()
                }
                val conflict = draft != null && file.revision != draft.originalRevision
                val applied = draft != null && conflict && SettingsDocumentPatch.sha256(draft.bytes) == file.revision
                Loaded(file, document, conflict, applied)
            }) { result ->
                opened = result.getOrNull()
                val loaded = opened
                if (loaded?.alreadyApplied == true) {
                    discardReview()
                    dialog.dismiss()
                    Toast.makeText(activity, activity.sugarString(R.string.profile_unchanged), Toast.LENGTH_SHORT).show()
                } else if (loaded == null) {
                    if (result.exceptionOrNull() is InvalidExternalDocument) {
                        status.text = activity.sugarString(R.string.profile_invalid_external)
                        discardOnReload = true
                    } else {
                        status.text = activity.sugarString(R.string.profile_unavailable)
                    }
                    reload.visibility = View.VISIBLE
                } else {
                    input.setText(loaded.document.text)
                    input.setSelection(0)
                    input.isEnabled = true
                    ok.isEnabled = !loaded.conflict
                    discardOnReload = loaded.conflict
                    reload.visibility = if (loaded.conflict) View.VISIBLE else View.GONE
                    status.text = when {
                        loaded.conflict -> activity.sugarString(R.string.profile_conflict)
                        draft != null -> activity.sugarString(R.string.profile_check_external)
                        recovery != null -> activity.sugarString(R.string.diag_profile_edit_hint)
                        else -> ""
                    }
                }
            }
        }
        reload.setOnClickListener {
            if (discardOnReload) {
                discardReview()
                discardOnReload = false
                dialog.setTitle(activity.sugarString(R.string.profile_edit))
            }
            load()
        }
        ok.setOnClickListener {
            val base = opened ?: return@setOnClickListener
            if (base.conflict) return@setOnClickListener
            val expected = pending?.originalRevision ?: base.file.revision
            val draft = input.text.toString()
            val bytes = base.document.encode(draft)
            if (bytes.size > LIMIT) {
                status.text = activity.sugarString(R.string.profile_too_large)
                return@setOnClickListener
            }
            if (SettingsDocumentPatch.sha256(bytes) == expected) {
                discardReview()
                dialog.dismiss()
                return@setOnClickListener
            }
            ok.isEnabled = false
            cancel.isEnabled = false
            input.isEnabled = false
            dialog.setCancelable(false)
            status.text = activity.sugarString(R.string.profile_saving)
            inBackground(dialog, { save(base.file, expected, bytes) }) { result ->
                val code = result.getOrDefault(1)
                if (code == 0) {
                    discardReview()
                    dialog.dismiss()
                    Toast.makeText(activity, activity.sugarString(R.string.profile_saved), Toast.LENGTH_SHORT).show()
                } else {
                    status.text = when (code) {
                        2 -> activity.sugarString(R.string.profile_conflict)
                        3 -> activity.sugarString(R.string.profile_invalid_yaml)
                        4 -> activity.sugarString(R.string.profile_busy)
                        else -> activity.sugarString(R.string.profile_save_failed)
                    }
                    reload.visibility = if (code == 2) View.VISIBLE else View.GONE
                    discardOnReload = code == 2
                    ok.isEnabled = code != 2
                    cancel.isEnabled = true
                    input.isEnabled = true
                    dialog.setCancelable(true)
                }
            }
        }
        load()
    }

    companion object {
        // DSH's web shorthand sets --profile web. Settings.prepareDocument returns this profile patch.
        private const val PROFILE = "/home/sugar/.dsh/profiles/web/cordis.patch.yml"
        private const val LIMIT = 1_048_576
    }
}
