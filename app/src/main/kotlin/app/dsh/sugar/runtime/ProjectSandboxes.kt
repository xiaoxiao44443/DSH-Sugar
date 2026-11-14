package app.dsh.sugar.runtime

import app.dsh.sugar.workspace.LocalFolderWorkspaces
import app.dsh.sugar.workspace.WorkspaceRemoval
import android.content.Context
import android.util.AtomicFile
import com.sucrune.probe.NativeProbe
import com.sucrune.probe.RuntimeClient
import com.sucrune.probe.RuntimeExceptionWithCode
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

internal data class ProjectSandbox(val workspaceId: String, val root: String, val envId: String, val workspace: File)
internal data class DocumentProjectInfo(val title: String, val path: String, val deletable: Boolean)

/** Manager-only project registry. Never take a host path or Sucrune environment ID from the guest. */
internal class ProjectSandboxes(
    context: Context,
    private val client: RuntimeClient,
    private val controlEnvId: String,
    private val registeredProject: (String, String) -> Boolean
) {
    private val app = context.applicationContext
    private val registry = AtomicFile(File(app.noBackupFilesDir, "sugar-projects-v1.json"))

    fun ensure(workspaceId: String, root: String): ProjectSandbox = synchronized(lock) {
        validateId(workspaceId)
        val path = normalizedGuestRoot(root, workspaceId, registeredProject)
        val bindings = readRegistry()
        val current = bindings.optJSONObject(workspaceId)
        if (current != null) {
            check(current.getString("root") == path) { "此工作区的根目录已更改，需要重新绑定。" }
            if (current.optString("state") == "detached") {
                check(matchesFolder(current)) { "此目录尚未重新关联。" }
                current.put("state", "restoring")
                save(bindings)
            }
            check(current.getString("state") in setOf("pending", "restoring", "ready")) { "项目正在删除或已删除。" }
            if (current.getString("state") == "ready") {
                val project = resolve(workspaceId, current)
                ProjectUserland.prepareHelpers(app, client, project.envId, project.workspace.toPath().parent.parent)
                return@synchronized project
            }
        } else {
            bindings.keys().forEach { key ->
                val other = bindings.getJSONObject(key)
                if (other.getString("state") != "deleted" &&
                    (other.getString("state") != "detached" || matchesFolder(other))) {
                    val previous = other.getString("root")
                    check(path != previous && !path.startsWith("$previous/") && !previous.startsWith("$path/")) {
                        "工作区与另一项目的根目录重叠。请选择独立目录。"
                    }
                }
            }
        }
        val control = guestRoot(controlEnvId)
        ProjectTreeCopy.openSource(control, path).close()
        // Write our intent first. The deterministic environment name recovers a crash immediately
        // after Sucrune publishes the environment, before we can record its assigned ID.
        val row = current ?: JSONObject().put("root", path).put("state", "pending").also {
            bindings.put(workspaceId, it)
            save(bindings)
        }
        if (!row.has("envId")) {
            val name = environmentName(workspaceId)
            val found = findByName(name)
            row.put("envId", (found ?: client.createEnvironment(name)).getString("id"))
            save(bindings)
        }
        val project = resolve(workspaceId, row)
        val fs = project.workspace.parentFile!!.toPath()
        val metadata = fs.parent
        if (row.optString("state") == "restoring") {
            retained(workspaceId).restore(fs)
            val receipt = metadata.resolve("sugar-workspace-imported")
            if (!Files.exists(receipt, NOFOLLOW_LINKS)) Files.createFile(receipt)
        }
        ProjectUserland.prepare(fs, metadata)
        ProjectUserland.prepareHelpers(app, client, project.envId, metadata)
        val receipt = metadata.resolve("sugar-workspace-imported")
        if (!Files.isRegularFile(receipt, NOFOLLOW_LINKS)) {
            // No worker can start through this registry until migration commits. An interrupted
            // copy can be repeated against the original, which is retained throughout.
            val stage = metadata.resolve("workspace-import.pending")
            GuestImage.removeTree(stage)
            Files.createDirectory(stage)
            try {
                if (!LocalFolderWorkspaces.isDirectRoot(path)) {
                    ProjectTreeCopy.copy(control, path, stage)
                }
                GuestImage.removeTree(project.workspace.toPath())
                Files.move(stage, project.workspace.toPath(), ATOMIC_MOVE)
                Files.createFile(receipt)
            } finally {
                GuestImage.removeTree(stage)
            }
        }
        row.put("state", "ready")
        save(bindings)
        project
    }

    fun lookup(workspaceId: String): ProjectSandbox? = synchronized(lock) {
        validateId(workspaceId)
        val row = readRegistry().optJSONObject(workspaceId) ?: return@synchronized null
        if (row.getString("state") == "ready") resolve(workspaceId, row) else null
    }

    /** Upgrade only a previously initialized, registered sandbox. */
    fun migrateIdleSharedTools(id: String, root: String) = synchronized(lock) {
        val row = readRegistry().optJSONObject(id) ?: return@synchronized
        if (row.optString("state") != "ready" || row.optString("root") != root) return@synchronized
        val project = resolve(id, row)
        SharedProjectTools.install(app, client, project.envId, project.workspace.toPath().parent.parent)
    }

    private fun retained(id: String): RetainedWorkspace {
        require(documentIdPattern.matches(id)) { "工作区 ID 无效。" }
        return RetainedWorkspace(app.filesDir.toPath().resolve("sucrune/sugar-retained").resolve(id))
    }

    private fun folderKey(path: String, control: Path = guestRoot(controlEnvId)): String =
        ProjectTreeCopy.openSource(control, path).use {
            val stat = android.system.Os.fstat(it.fileDescriptor)
            "${stat.st_dev}:${stat.st_ino}"
        }

    private fun matchesFolder(row: JSONObject, control: Path = guestRoot(controlEnvId)): Boolean = try {
        row.getString("folderKey") == folderKey(row.getString("root"), control)
    } catch (_: IllegalArgumentException) {
        // ProjectTreeCopy.directory rejects a former directory that was replaced by a plain file.
        false
    } catch (e: android.system.ErrnoException) {
        if (e.errno in setOf(android.system.OsConstants.ENOENT, android.system.OsConstants.ENOTDIR,
                android.system.OsConstants.ELOOP)) false else throw e
    }

    fun retainedId(path: String, reserve: Boolean = true): String? = synchronized(lock) {
        if (!isWorkspaceRoot(path)) return@synchronized null
        val bindings = readRegistry()
        if (reserve) {
            val control = guestRoot(controlEnvId)
            ProjectTreeCopy.openSource(control, path).close()
            for (entry in bindings.keys()) {
                val row = bindings.getJSONObject(entry)
                val other = row.getString("root")
                if (other != path && (other.startsWith("$path/") || path.startsWith("$other/")) &&
                    row.optString("state") == "detached" && matchesFolder(row,control))
                    error("目录与一个保留的工作区重叠。请选择独立目录。")
            }
        }
        val id = bindings.keys().asSequence().firstOrNull { id ->
            val row = bindings.getJSONObject(id)
            row.optString("state") == "detached" && row.optString("root") == path && matchesFolder(row)
        }
        if (reserve && id != null) {
            // The JS registry commits asynchronously after this lookup. DocumentsUI must not
            // delete the retained tree in that window, even if Android restarts the process.
            bindings.getJSONObject(id).put("registrationPending", true)
            save(bindings)
        }
        id
    }

    /** The directory picker sees the control environment's folder, which may only be a placeholder. */
    fun retainedFolder(path: String): JSONObject = synchronized(lock) {
        val id = retainedId(path, reserve = false) ?: return@synchronized JSONObject()
        val files = retained(id).files
        JSONObject().put("id",id).put("empty", Files.isDirectory(files,NOFOLLOW_LINKS) &&
            Files.newDirectoryStream(files).use { !it.iterator().hasNext() })
    }

    fun forgetEmptyFolder(id: String, path: String) = synchronized(lock) {
        require(documentIdPattern.matches(id))
        val bindings = readRegistry()
        val row = bindings.getJSONObject(id)
        check(row.getString("root") == path && row.getString("state") == "detached" &&
            !row.optBoolean("registrationPending") && documentIndexContents(app).optJSONObject("items")?.has(id) != true)
        val archive = retained(id)
        check(Files.newDirectoryStream(archive.files).use { !it.iterator().hasNext() })
        row.put("state","deleted")
        save(bindings)
        GuestImage.removeTree(archive.directory)
        notifyDocuments()
    }

    /** DSH removes a registration. User files survive in the provider while the sandbox is recycled. */
    fun delete(workspaceId: String, path: String, title: String) = synchronized(lock) {
        validateId(workspaceId)
        require(documentIdPattern.matches(workspaceId))
        val bindings = readRegistry()
        val direct = LocalFolderWorkspaces.isDirectRoot(path)
        val row = bindings.optJSONObject(workspaceId) ?: JSONObject().put("root", path).put("state","pending").also {
            // Older DSH versions could register system directories that Sugar never initialized.
            if (direct || !isWorkspaceRoot(path)) return@synchronized
            bindings.put(workspaceId,it)
        }
        check(row.getString("root") == path)
        val state = row.getString("state")
        if (state == "deleted" || state == "detached") return@synchronized
        // Re-add may have stopped after moving data back, before publishing the import receipt.
        // Finish that transfer before changing the journal to removal, so an empty picker directory
        // can never become the source instead of the user's restored tree.
        if (state == "restoring" && row.has("envId")) {
            val guest = resolve(workspaceId, row).workspace.toPath().parent
            retained(workspaceId).restore(guest)
            val receipt = guest.parent.resolve("sugar-workspace-imported")
            if (!Files.exists(receipt, NOFOLLOW_LINKS)) Files.createFile(receipt)
        }
        if (!direct && state !in setOf("retaining", "archived")) {
            row.put("folderKey",folderKey(path)).put("title",title.ifBlank { path.substringAfterLast('/') })
                .put("state","retaining")
            save(bindings)
        }
        val id = if (row.has("envId")) row.getString("envId") else findByName(environmentName(workspaceId))?.getString("id")
        if (!direct && row.optString("state") == "retaining") {
            val archive = retained(workspaceId)
            val guest = id?.let {
                check(it != controlEnvId && client.getEnvironment(it).getString("name") == environmentName(workspaceId))
                guestRoot(it)
            }
            if (guest != null && Files.isRegularFile(guest.parent.resolve("sugar-workspace-imported"), NOFOLLOW_LINKS)) {
                val runs = client.listExecutions(id)
                for (i in 0 until runs.length()) {
                    val run = runs.getJSONObject(i)
                    if (run.getString("state") == "running") {
                        client.cancel(run.getString("execution_id"))
                        client.waitFor(run.getString("execution_id"),30_000)
                    }
                }
                archive.retain(guest)
            } else if (!Files.exists(archive.files, NOFOLLOW_LINKS)) {
                Files.createDirectories(archive.directory)
                val stage = archive.directory.resolve("import.pending")
                GuestImage.removeTree(stage)
                Files.createDirectory(stage)
                ProjectTreeCopy.copy(guestRoot(controlEnvId), path, stage)
                Files.move(stage, archive.files, ATOMIC_MOVE)
            }
            row.put("state","archived")
            save(bindings)
        }
        if (id != null) {
            check(id != controlEnvId)
            try {
                check(client.getEnvironment(id).getString("name") == environmentName(workspaceId))
                client.deleteProject(id)
            } catch (error: RuntimeExceptionWithCode) {
                if (error.code != "not_found") throw error
            }
        }
        row.remove("envId")
        row.put("state", if (direct) "deleted" else "detached")
        save(bindings)
        notifyDocuments()
    }

    private fun notifyDocuments() {
        val authority = app.packageName + ".workspaces"
        app.contentResolver.notifyChange(android.provider.DocumentsContract.buildRootsUri(authority), null)
        app.contentResolver.notifyChange(android.provider.DocumentsContract.buildChildDocumentsUri(authority,"projects"), null)
    }

    private fun finishDocumentDeletion(bindings: JSONObject, id: String, row: JSONObject,
                                       didDelete: (String) -> Unit): Boolean {
        val archive = retained(id)
        val path = row.getString("root")
        if (Files.exists(archive.directory, NOFOLLOW_LINKS)) {
            val held = archive.directory.resolve(RetainedRootDeletion.CONTROL)
            val expected = row.getString("folderKey")
            fun identity(file: Path): String? = try {
                val stat = android.system.Os.lstat(file.toString())
                if (android.system.OsConstants.S_ISDIR(stat.st_mode)) "${stat.st_dev}:${stat.st_ino}" else null
            } catch (e: android.system.ErrnoException) {
                if (e.errno == android.system.OsConstants.ENOENT) null else throw e
            }
            if (identity(held) != expected) {
                val captured = try {
                    ProjectTreeCopy.openSource(guestRoot(controlEnvId),path.substringBeforeLast('/')).use { parent ->
                        val original = File("/proc/self/fd/${parent.fd}",path.substringAfterLast('/')).toPath()
                        RetainedRootDeletion.stageOriginal(original,held,expected,::identity)
                    }
                } catch (e: android.system.ErrnoException) {
                    if (e.errno !in setOf(android.system.OsConstants.ENOENT,android.system.OsConstants.ENOTDIR,
                            android.system.OsConstants.ELOOP) || Files.exists(held, NOFOLLOW_LINKS)) throw e
                    false
                }
                if (!captured) {
                    row.put("state","detached")
                    save(bindings)
                    return false
                }
            }
            // One rename removes both the real tree and the original control folder from view.
            // The app-private trash scavenger retries physical removal after a process death.
            WorkspaceRemoval.remove(app.cacheDir, archive.directory.toString(), "archive") { removed ->
                RetainedRootDeletion.relativeDocument(removed)?.let(didDelete)
            }
        }
        row.put("state","deleted")
        save(bindings)
        didDelete("")
        notifyDocuments()
        return true
    }

    private fun resolve(id: String, row: JSONObject): ProjectSandbox {
        val env = row.getString("envId")
        check(env != controlEnvId && client.getEnvironment(env).getString("name") == environmentName(id))
        val workspace = guestRoot(env).resolve("workspace")
        if (row.getString("state") == "ready") {
            check(Files.isDirectory(workspace, NOFOLLOW_LINKS)) { "项目工作区不是有效目录。" }
        }
        return ProjectSandbox(id, row.getString("root"), env, workspace.toFile())
    }

    private fun findByName(name: String): JSONObject? {
        val all = client.listEnvironments()
        val matches = (0 until all.length()).map(all::getJSONObject).filter { it.getString("name") == name }
        check(matches.size <= 1) { "项目环境身份不唯一，需要人工检查。" }
        return matches.singleOrNull()
    }

    private fun guestRoot(env: String): Path {
        val request = JSONObject().put("op", "env.paths").put("env_id", env)
        val response = JSONObject(NativeProbe.coreCall(File(app.filesDir, "sucrune").path, request.toString()))
        check(response.getBoolean("ok")) { "无法定位项目环境。" }
        return File(response.getJSONObject("result").getString("workspace")).toPath()
    }

    /** The host persists a removal intent before unregistering a project. Fail closed if it dies between snapshots. */
    private fun pendingDocumentRemovals(): Set<String> = try {
        val root = guestRoot(controlEnvId)
        SharedDshHome.prepareControl(root)
        ProjectTreeCopy.openSource(root, "${SharedDshHome.ROUTING}/sugar-project-removals").use { dir ->
            val names = File("/proc/self/fd/${dir.fd}").list() ?: error("无法核对项目移除状态。")
            names.asSequence().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.toSet()
        }
    } catch (e: android.system.ErrnoException) {
        if (e.errno == android.system.OsConstants.ENOENT) emptySet() else throw e
    }

    /** DSH owns registration and display names. Persist its snapshot for DocumentsUI while DSH is stopped. */
    fun publishDocumentRegistry(rows: org.json.JSONArray, first: Boolean = false, settled: String? = null) = synchronized(lock) {
        val file = documentIndex(app)
        val before = file.readIfPresent()
        val previousDates = before?.let { runCatching { JSONObject(it).optJSONObject("createdAt") }.getOrNull() }
        val items = JSONObject()
        val external = org.json.JSONArray()
        val createdAt = JSONObject()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val id = row.getString("id")
            if (!documentIdPattern.matches(id)) continue
            val stamp = row.optLong("createdAt").takeIf { it > 0L } ?: previousDates?.optLong(id) ?: 0L
            if (stamp > 0L) createdAt.put(id, stamp)
            val root = row.getString("root")
            if (externalRoot.matches(root)) { external.put(root); continue }
            if (!isWorkspaceRoot(root)) continue
            items.put(id, JSONObject().put("root", root)
                .put("title", row.optString("title").ifBlank { root.substringAfterLast('/') }))
        }
        // Phone workspaces have registration dates too, but are not roots of our DocumentsProvider.
        val value = JSONObject().put("control", controlEnvId).put("items", items)
            .put("external", external).put("createdAt", createdAt).toString()
        if (before != value) {
            val stream = file.startWrite()
            try {
                stream.write(value.toByteArray())
                file.finishWrite(stream)
            } catch (failure: Throwable) { file.failWrite(stream); throw failure }
            notifyDocuments()
        }
        val bindings = readRegistry()
        var changed = false
        for (id in bindings.keys()) {
            val row = bindings.getJSONObject(id)
            if (row.optBoolean("registrationPending") && (first || id == settled || items.has(id))) {
                row.remove("registrationPending")
                changed = true
            }
        }
        if (changed) save(bindings)
    }


    private fun readRegistry(): JSONObject = (try {
        registry.openRead().bufferedReader().use { JSONObject(it.readText()) }
    } catch (e: FileNotFoundException) {
        if (registry.baseFile.exists()) throw e
        JSONObject()
    }).also { bindings ->
        for (id in bindings.keys()) {
            val row = bindings.getJSONObject(id)
            if (row.optString("state") == "purging") {
                // A failed cleanup remains protected by its journal. Other projects are still usable.
                runCatching { finishDocumentDeletion(bindings,id,row) {} }
                    .onFailure { android.util.Log.w("SugarDocuments", "Retained directory cleanup deferred", it) }
            }
        }
    }

    private fun save(data: JSONObject) {
        val output = registry.startWrite()
        try {
            output.write(data.toString().toByteArray())
            registry.finishWrite(output)
        } catch (e: Throwable) {
            registry.failWrite(output)
            throw e
        }
    }

    companion object {
        private val lock = Any()
        private val documentIdPattern = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        private val externalRoot = Regex("/workspace/local-[0-9a-f]{64}(?:/.*)?")
        private fun documentIndex(context: Context) = AtomicFile(File(context.noBackupFilesDir, "sugar-document-projects-v1.json"))

        private fun AtomicFile.readIfPresent(): String? = try {
            openRead().bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            if (baseFile.exists()) throw e
            null
        }

        private fun documentIndexContents(context: Context): JSONObject =
            documentIndex(context).readIfPresent()?.let(::JSONObject) ?: JSONObject()

        /** Snapshot trusted host records; this path never creates or starts an environment. */
        fun storageRegistry(context: Context): JSONObject = synchronized(lock) {
            val index = documentIndexContents(context)
            val docs = index.optJSONObject("items") ?: JSONObject()
            val raw = AtomicFile(File(context.noBackupFilesDir, "sugar-projects-v1.json")).readIfPresent()
            val states = raw?.let(::JSONObject) ?: JSONObject()
            val folders = LocalFolderWorkspaces.registeredFolders(context)
            val ids = (docs.keys().asSequence() + states.keys().asSequence() + folders.keys.asSequence()).toSortedSet()
            val rows = JSONObject()
            for (id in ids) {
                if (!documentIdPattern.matches(id)) continue
                val state = states.optJSONObject(id)
                if (state?.optString("state") in setOf("deleted", "deleting", "purging", "retaining", "archived", "detached")) continue
                val doc = docs.optJSONObject(id)
                val folder = folders[id]
                val root = state?.getString("root") ?: doc?.getString("root") ?: folder?.first ?: continue
                val location = folder?.takeIf { it.first == root }?.second.orEmpty()
                val title = doc?.takeIf { it.getString("root") == root }?.optString("title").orEmpty().ifBlank {
                    location.substringAfterLast(':').substringAfterLast('/').ifBlank { root.substringAfterLast('/') }
                }
                rows.put(id, JSONObject().put("title", title).put("root", root).put("location", location)
                    .put("createdAt", index.optJSONObject("createdAt")?.opt(id))
                    .put("external", LocalFolderWorkspaces.isDirectRoot(root)).put("envId", state?.optString("envId") ?: "")
                    .put("ready", state?.optString("state") == "ready"))
            }
            JSONObject().put("control", index.optString("control")).put("items", rows)
        }

        private fun documentManager(context: Context, index: JSONObject): ProjectSandboxes {
            val client = RuntimeClient.get(context)
            val control = index.getString("control")
            check(client.getEnvironment(control).getString("name") == "DSH Sugar")
            val items = index.getJSONObject("items")
            return ProjectSandboxes(context, client, control) { id, root ->
                items.optJSONObject(id)?.optString("root") == root && items.keys().asSequence().none { otherId ->
                    val other = items.getJSONObject(otherId).getString("root")
                    id != otherId && isWorkspaceRoot(other) && (root == other || root.startsWith("$other/") || other.startsWith("$root/"))
                }
            }
        }

        private data class DocumentMounts(val owners: Map<String,String?>, val deletable: Set<String>)

        private fun documentMounts(manager: ProjectSandboxes, index: JSONObject, states: JSONObject,
                                   control: Path, removing: Set<String>): DocumentMounts {
            val items = index.getJSONObject("items")
            val projects = mutableMapOf<String,String?>()
            // A registered or pending project shadows its control directory, even if it is not ready.
            // The caller must not fall back through that mount while a deletion is in flight.
            for (id in items.keys()) {
                val path = items.getJSONObject(id).getString("root")
                if (!isWorkspaceRoot(path)) continue
                val row = states.optJSONObject(id)
                val available = id !in removing && !externalRoot.matches(path) && (row == null ||
                    row.getString("root") == path && row.optString("state") in setOf("pending","restoring","ready","detached"))
                projects[path] = if (available && path !in projects) id else null
            }
            val registeredPaths = projects.keys.toSet()
            for (id in states.keys()) {
                val row = states.getJSONObject(id)
                val path = row.getString("root")
                if (!isWorkspaceRoot(path) || path in registeredPaths || row.optString("state") == "deleted") continue
                if (row.optString("state") == "detached") {
                    // After same-name recreation the old tree still exists by UUID, but no longer owns this path.
                    if (manager.matchesFolder(row, control)) projects[path] = if (path in projects) null else id
                } else projects[path] = null
            }
            index.optJSONArray("external")?.let { mounts ->
                for (i in 0 until mounts.length()) projects[mounts.getString(i)] = null
            }
            // Existing phone-folder registrations from an older index are also reserved.
            for ((path, _) in LocalFolderWorkspaces.registeredFolders(manager.app).values) projects[path] = null
            val registrations = items.keys().asSequence().toSet()
            val deletable = projects.keys.filterTo(mutableSetOf()) { path ->
                val id = projects[path] ?: return@filterTo false
                val row = states.optJSONObject(id) ?: return@filterTo false
                row.optString("state") == "detached" && !row.optBoolean("registrationPending") &&
                    canRemoveRetainedRoot(id, path, registrations, removing, projects)
            }
            return DocumentMounts(projects, deletable)
        }

        /** Keep the namespace snapshot stable until the provider has pinned or opened its target. */
        fun <T> withInternalDocuments(context: Context, block: (File, Map<String, String?>, Set<String>) -> T): T = synchronized(lock) {
            val index = documentIndexContents(context)
            if (!index.has("items")) throw FileNotFoundException("工作区服务尚未就绪。")
            val manager = documentManager(context, index)
            val control = manager.guestRoot(manager.controlEnvId)
            val mounts = documentMounts(manager,index,manager.readRegistry(),control,manager.pendingDocumentRemovals())
            block(control.toFile(), mounts.owners, mounts.deletable)
        }

        /** Listing must not initialize a project just to render the root. */
        fun documentProjects(context: Context): Map<String, DocumentProjectInfo> = synchronized(lock) {
            val index = documentIndexContents(context)
            val items = index.optJSONObject("items") ?: return@synchronized emptyMap()
            val manager = documentManager(context, index)
            val removing = manager.pendingDocumentRemovals()
            val states = manager.readRegistry()
            val mounts = documentMounts(manager,index,states,manager.guestRoot(manager.controlEnvId),removing)
            buildMap {
                for (id in states.keys()) {
                    val row = states.getJSONObject(id)
                    val path = row.getString("root")
                    if (row.optString("state") == "detached" && id !in removing)
                        put(id, DocumentProjectInfo(row.getString("title"),path,
                            mounts.owners[path] == id && path in mounts.deletable))
                }
                for (id in items.keys()) {
                    val item = items.getJSONObject(id)
                    val root = item.getString("root")
                    if (id in removing || externalRoot.matches(root) || !isWorkspaceRoot(root)) continue
                    val row = states.optJSONObject(id)
                    if (row == null || (row.getString("root") == item.getString("root") &&
                        row.getString("state") in setOf("pending","restoring","ready","detached")))
                        put(id, DocumentProjectInfo(item.getString("title"),root,false))
                }
            }
        }

        fun canDeleteRetainedDocument(context: Context, id: String, path: String): Boolean =
            withInternalDocuments(context) { _, mounts, deletable -> mounts[path] == id && path in deletable }

        fun deleteRetainedDocument(context: Context, id: String, path: String, didDelete: (String) -> Unit = {}): Unit = synchronized(lock) {
            require(documentIdPattern.matches(id))
            val index = documentIndexContents(context)
            val manager = documentManager(context,index)
            val states = manager.readRegistry()
            val mounts = documentMounts(manager,index,states,manager.guestRoot(manager.controlEnvId),manager.pendingDocumentRemovals())
            check(mounts.owners[path] == id && path in mounts.deletable) { "目录已变化或仍属于工作区。" }
            val row = states.getJSONObject(id)
            check(Files.isDirectory(manager.retained(id).files, NOFOLLOW_LINKS)) { "保留工作区暂不可删除。" }
            row.put("state","purging")
            manager.save(states)
            check(manager.finishDocumentDeletion(states,id,row,didDelete)) { "原目录已变化，已保留文件。" }
        }

        /** Hold the same lock used by registration and project deletion while checking identity and accessing its tree. */
        fun <T> withDocumentProject(context: Context, id: String, action: (String, File) -> T): T = synchronized(lock) {
            require(documentIdPattern.matches(id))
            val index = documentIndexContents(context)
            val manager = documentManager(context, index)
            val row = manager.readRegistry().optJSONObject(id)
            if (row?.optString("state") == "detached") {
                return@synchronized action(row.getString("title"),manager.retained(id).files.toFile())
            }
            val item = index.optJSONObject("items")?.optJSONObject(id) ?: throw FileNotFoundException("工作区已移除。")
            val root = item.getString("root")
            require(isWorkspaceRoot(root) && !externalRoot.matches(root) && id !in manager.pendingDocumentRemovals())
            val project = manager.lookup(id) ?: manager.ensure(id, root)
            check(project.root == root)
            action(item.getString("title"), project.workspace)
        }

        private fun validateId(id: String) {
            require(id.isNotBlank() && id.length <= 512 && id.none(Char::isISOControl)) { "工作区 ID 无效。" }
        }
        private fun environmentName(id: String): String = "Sugar project " +
            MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun normalizedGuestRoot(input: String, id: String, registered: (String, String) -> Boolean): String {
            require(isWorkspaceRoot(input)) { "请选择 /workspace 或 /home 下独立、非隐藏的项目目录。" }
            require(registered(id,input)) { "只能打开 DSH 已登记的工作区。" }
            return input
        }
    }
}
