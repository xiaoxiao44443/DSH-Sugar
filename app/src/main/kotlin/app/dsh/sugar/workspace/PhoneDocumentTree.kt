package app.dsh.sugar.workspace

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.AtomicFile
import android.webkit.MimeTypeMap
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import org.json.JSONObject

internal class PhoneDocumentTree(
    private val resolver: ContentResolver, private val tree: Uri, private val journal: AtomicFile,
    private val watchDirectory: ((Uri) -> Unit)? = null
) : FolderTree {
    private data class Entry(val id: String, val name: String, val mime: String, val flags: Int) {
        val directory get() = mime == Document.MIME_TYPE_DIR
    }

    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS)
    private val directories = mutableMapOf("" to rootId)
    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    private fun entry(c: Cursor) = Entry(c.getString(0), checkedName(c.getString(1)), c.getString(2), c.getInt(3))

    private fun entries(address: Uri, directoryId: String? = null): List<Entry> =
        resolver.query(address, columns, null, null, null)?.use { cursor ->
            if (directoryId != null && watchDirectory != null) {
                // ExternalStorageProvider may notify its canonical (non-tree) children URI.
                // Other providers expose their own notification target through the cursor.
                val advertised = cursor.notificationUris.orEmpty() + listOfNotNull(cursor.notificationUri)
                val canonical = DocumentsContract.buildChildDocumentsUri(tree.authority!!,directoryId)
                (advertised + canonical).distinct().filter {
                    it.scheme == "content" && it.authority == tree.authority
                }.forEach(watchDirectory)
            }
            val found = mutableListOf<Entry>()
            while (cursor.moveToNext()) found.add(entry(cursor))
            check(!cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING) &&
                cursor.extras.getString(DocumentsContract.EXTRA_ERROR) == null) { "手机文件夹尚未完整载入，请重试。" }
            found
        } ?: throw IOException("无法读取已授权的手机文件夹。")

    private fun children(id: String): Map<String, Entry> {
        val result = linkedMapOf<String,Entry>()
        for (entry in entries(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),id)) {
            require(result.put(entry.name, entry) == null) { "手机目录包含同名文件：${entry.name}" }
        }
        return result
    }

    private fun parent(path: String) = directories.getValue(path.substringBeforeLast('/', ""))
    private fun leaf(path: String) = checkedName(path.substringAfterLast('/'))
    private fun child(path: String) = children(parent(path))[leaf(path)]
    private fun stream(entry: Entry): InputStream = resolver.openInputStream(uri(entry.id))
        ?: throw IOException("无法读取手机文件。")
    private fun stamp(entry: Entry?): String? = when {
        entry == null -> null
        entry.directory -> DIRECTORY
        entry.flags and Document.FLAG_VIRTUAL_DOCUMENT != 0 -> UNSUPPORTED
        else -> stream(entry).use { transferDigest(it) }
    }

    override fun scan(): Map<String, String> {
        recoverPending()
        val result = mutableMapOf<String, String>()
        val seen = mutableSetOf(rootId)
        fun visit(parent: String, prefix: String) {
            for (entry in children(parent).values) {
                require(seen.add(entry.id)) { "手机目录包含重复的文档标识，无法安全同步。" }
                val path = prefix + entry.name
                result[path] = checkNotNull(stamp(entry))
                if (entry.directory) {
                    directories[path] = entry.id
                    visit(entry.id, "$path/")
                }
            }
        }
        visit(rootId, "")
        return result
    }

    override fun read(path: String) = stream(child(path) ?: throw FileNotFoundException(path))

    private fun create(parent: String, name: String, mime: String): Entry {
        val created = DocumentsContract.createDocument(resolver, uri(parent), mime, name)
            ?: throw IOException("手机文件提供方无法创建文件。")
        val entry = entries(created).single()
        if (entry.name != name) {
            // Only ever delete the object just allocated for this request.
            DocumentsContract.deleteDocument(resolver, created)
            throw IOException("文件提供方无法原样保存名称：$name")
        }
        return entry
    }

    private fun rename(entry: Entry, newName: String) {
        check(entry.flags and Document.FLAG_SUPPORTS_RENAME != 0) { "该手机文件夹不支持安全地替换或重命名文件。" }
        val changed = DocumentsContract.renameDocument(resolver, uri(entry.id), newName)
            ?: throw IOException("手机文件重命名失败。")
        check(entries(changed).single().name == newName) { "手机文件提供方更改了目标文件名。" }
    }

    private fun remove(entry: Entry) {
        check(entry.flags and Document.FLAG_SUPPORTS_DELETE != 0 &&
            DocumentsContract.deleteDocument(resolver, uri(entry.id))) { "手机文件提供方拒绝删除文件。" }
    }

    override fun mkdir(path: String) {
        check(child(path) == null) { "手机文件在同步期间已被创建。" }
        directories[path] = create(parent(path), leaf(path), Document.MIME_TYPE_DIR).id
    }

    override fun remove(path: String, expected: String) {
        val parentId = parent(path)
        val entry = checkNotNull(child(path))
        check(stamp(entry) == expected) { "手机文件在同步期间已更改。" }
        if (entry.directory) check(children(entry.id).isEmpty()) { "手机目录已出现新文件，已保留。" }
        remove(entry)
        check(children(parentId)[leaf(path)] == null) { "手机文件提供方未完成删除。" }
        directories.remove(path)
    }

    private fun putJournal(value: JSONObject) {
        val out = journal.startWrite()
        try { out.write(value.toString().toByteArray()); journal.finishWrite(out) }
        catch (e: Throwable) { journal.failWrite(out); throw e }
    }

    // A SAF provider has no atomic replace-existing operation. Stage a verified sibling,
    // retain the original until publication, and persist the two names across app death.
    override fun write(path: String, source: InputStream, digest: String, expected: String?) {
        val parentId = parent(path)
        val name = leaf(path)
        val original = child(path)
        check(stamp(original) == expected) { "手机文件在同步期间已更改。" }
        val suffix = name.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,16}")) }
        val mime = original?.mime ?: suffix?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
            ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        val tmp = "sugar-sync-$id-new" + (suffix?.let { ".$it" } ?: "")
        val backup = "sugar-sync-$id-old" + (suffix?.let { ".$it" } ?: "")
        val tx = JSONObject().put("parent", parentId).put("name", name).put("temp",tmp)
            .put("backup",backup).put("new",digest).put("old",expected ?: "")
        putJournal(tx)
        try {
            val stage = create(parentId, tmp, mime)
            tx.put("stageId",stage.id)
            putJournal(tx)
            val written = resolver.openOutputStream(uri(stage.id), "wt")?.use { transferDigest(source, it) }
            check(written == digest && stamp(stage) == digest) { "文件在传输期间已更改，已保留原件。" }
            check(stamp(child(path)) == expected) { "手机文件在同步期间已更改。" }
            if (original != null) rename(original, backup)
            check(child(path) == null) { "手机目录的目标文件已被占用。" }
            rename(stage, name)
            check(stamp(child(path)) == digest) { "手机文件提供方未完成写入。" }
        } finally {
            recoverPending()
        }
    }

    fun recoverPending() {
        val tx = try { journal.openRead().bufferedReader().use { JSONObject(it.readText()) } }
            catch (_: FileNotFoundException) { return }
        val parentId = tx.getString("parent")
        val files = children(parentId)
        val name = tx.getString("name")
        val backup = files[tx.getString("backup")]
        if (backup != null) {
            check(stamp(backup) == tx.getString("old")) { "同步备份已被外部更改，请保留并检查手机文件：${backup.name}" }
            when (stamp(files[name])) {
                null -> rename(backup, name)
                tx.getString("new") -> remove(backup)
                else -> throw IOException("手机目标和同步备份均有更改，请先保存并处理：$name、${backup.name}")
            }
        }
        files[tx.getString("temp")]?.let {
            check(!it.directory && it.id == tx.optString("stageId")) {
                "临时文档的身份已更改，请先检查：${it.name}"
            }
            remove(it)
        }
        journal.delete()
    }
}
