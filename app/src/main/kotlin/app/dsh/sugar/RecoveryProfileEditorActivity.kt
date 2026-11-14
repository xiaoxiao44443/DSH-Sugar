package app.dsh.sugar

import android.app.Activity
import android.os.Bundle
import app.dsh.sugar.runtime.RecoveryProfileDocument
import app.dsh.sugar.runtime.RecoverySession

/** The viewer runs in :recovery. Editing stays with the main process that owns the temporary guest. */
class RecoveryProfileEditorActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val project = intent.getStringExtra(PROJECT)
        if (project == null || project != RecoverySession.profileProject) { finish(); return }
        val store = RecoveryProfileDocument(applicationContext,project)
        SettingsDocumentEditor(this,store).showRecovery(::finish)
    }

    internal companion object { const val PROJECT = "recoveryProfileProject" }
}
