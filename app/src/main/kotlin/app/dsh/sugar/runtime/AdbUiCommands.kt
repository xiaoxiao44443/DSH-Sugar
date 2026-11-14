package app.dsh.sugar.runtime

/** Recognize the usual ADB UI commands so the agent can be routed to Sugar's selected screen. */
internal enum class AdbUiCommand { SCREEN, FORCE_STOP }

internal object AdbUiCommands {
    // Match shell operators before words, so 2>/dev/null does not turn '2' into a dump filename.
    private val redirect = Regex("""(?:[0-9]*|&)(?:>>|>\||>&|>)|[0-9]*(?:<<<|<<-?|<>|<&|<)""")
    private val tokens = Regex(redirect.pattern + """|(?:'[^']*'|"(?:\\.|[^"\\])*"|\\.|[^\s;&|()`'"\\<>])+|[;&|()\n`]+""")
    private val quoted = Regex("""'([^']*)'|"((?:\\.|[^"\\])*)"|\\(.)""")
    private val prefixes = setOf("!", "{", "if", "then", "while", "until", "do", "elif", "else", "exec", "command", "env", "nohup")
    private val assignment = Regex("[a-zA-Z_][a-zA-Z_0-9]*=.*", RegexOption.DOT_MATCHES_ALL)

    class Inspection {
        var action: AdbUiCommand? = null
            internal set
        var writesSharedCapture: Boolean = false
            internal set

        // Ordinary shell commands have a signed workspace ticket without manual/conversation claims.
        fun agentAction(isManual: () -> Boolean): AdbUiCommand? {
            val ui = action ?: return null
            if (isManual()) return null
            requireWorkspaceCapture()
            return ui
        }

        fun requireWorkspaceCapture() {
            require(!writesSharedCapture) {
                "请把截图或界面 XML 保存在工作区。使用 adb exec-out screencap -p > screen.png 或 adb ui-dump。"
            }
        }
    }

    fun inspect(command: String): Inspection = Inspection().also { scan(command, 0, it) }

    /** Inspect each simple command and its output redirections. This is not a shell interpreter. */
    private fun scan(command: String, depth: Int, result: Inspection): Boolean {
        val words = ArrayList<String>()
        var pendingRedirect: String? = null
        var sharedOutput = false
        var pipedCapture = false
        var containsCapture = false
        fun consume(pipe: Boolean) {
            val i = words.indexOfFirst { it !in prefixes && !assignment.matches(it) }
            val program = words.getOrNull(i)?.substringAfterLast('/')
            val args = if (i < 0) emptyList() else words.subList(i + 1, words.size)
            val direct = inspectProgram(program, args, depth, result)
            containsCapture = containsCapture || direct
            if ((direct || pipedCapture) && sharedOutput ||
                pipedCapture && program == "tee" && args.any(::sharedPath)) result.writesSharedCapture = true
            pipedCapture = pipe && (direct || pipedCapture)
            words.clear()
            pendingRedirect = null
            sharedOutput = false
        }
        for (match in tokens.findAll(command)) {
            val token = match.value
            if (redirect.matches(token)) pendingRedirect = token
            else if (token.first() in ";&|()\n`") consume(token == "|" || token == "|&")
            else {
                val word = quoted.replace(token) { it.groups[1]?.value ?: it.groups[2]?.value ?: it.groupValues[3] }
                if (pendingRedirect == null) words.add(word)
                else {
                    if ('>' in pendingRedirect!! && sharedPath(word)) sharedOutput = true
                    pendingRedirect = null
                }
            }
        }
        consume(false)
        return containsCapture
    }

    private fun inspectProgram(program: String?, args: List<String>, depth: Int, result: Inspection): Boolean {
        val first = args.getOrNull(0) ?: ""
        val second = args.getOrNull(1) ?: ""
        if (first == "--help" || first == "-h" || first == "help") return false
        if ((program == "sh" || program == "mksh" || program == "bash") && depth < 3) {
            val script = args.indexOfFirst { it.startsWith('-') && 'c' in it }
            if (script >= 0) return args.getOrNull(script + 1)?.let { scan(it, depth + 1, result) } ?: false
        }
        val activity = when (program) {
            "am" -> first
            "cmd" -> if (first == "activity") second else ""
            else -> ""
        }
        val action = when {
            activity == "force-stop" -> AdbUiCommand.FORCE_STOP
            activity == "start" || activity == "start-activity" -> AdbUiCommand.SCREEN
            program in setOf("monkey", "screencap", "screenrecord", "sendevent") -> AdbUiCommand.SCREEN
            program == "input" && first.isNotEmpty() -> AdbUiCommand.SCREEN
            program == "cmd" && first == "input" && second.isNotEmpty() -> AdbUiCommand.SCREEN
            program == "uiautomator" && first == "dump" -> AdbUiCommand.SCREEN
            else -> null
        }
        if (action != null && result.action != AdbUiCommand.FORCE_STOP) result.action = action
        val dump = program == "uiautomator" && first == "dump"
        val capture = dump || program == "screencap"
        if (dump) {
            val file = args.drop(1).lastOrNull { !it.startsWith('-') }
            if (file == null || sharedPath(file)) result.writesSharedCapture = true
        } else if (program == "screencap" && args.any(::sharedPath)) {
            result.writesSharedCapture = true
        }
        return capture
    }

    private val sharedRoots = listOf("/sdcard", "/mnt/sdcard", "/storage/emulated/0", "/storage/self/primary")
    private val externalStorage = Regex("""^\$(?:EXTERNAL_STORAGE|\{EXTERNAL_STORAGE\})(?=/|$)""")
    private fun sharedPath(word: String): Boolean {
        val path = word.replaceFirst(externalStorage, "/sdcard")
        if (!path.startsWith('/')) return false
        val components = ArrayList<String>()
        for (part in path.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (components.isNotEmpty()) components.removeAt(components.lastIndex)
            else -> components.add(part)
        }
        val normalized = components.joinToString("/", prefix = "/")
        return sharedRoots.any { normalized == it || normalized.startsWith("$it/") }
    }
}
