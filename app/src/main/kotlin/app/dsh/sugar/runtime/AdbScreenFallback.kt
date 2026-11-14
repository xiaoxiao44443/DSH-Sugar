package app.dsh.sugar.runtime

/** Enforce task lifetime and bind virtual-display input without interpreting the user's prompt. */
internal object AdbScreenFallback {
    fun usesSelectedScreen(turn: Long, taskTurn: Long?, phase: String?, error: String?): Boolean {
        check(turn >= 0) { "无法确认 ADB 操作所属的对话轮次。请在当前对话的新工作区命令中操作手机。" }
        if (phase == null || phase == "completed") return false
        if (phase in setOf("stopped", "failed", "parked") && taskTurn != turn) return false
        check(phase != "stopped") { "本轮手机操作已停止，请结束这次操作。" }
        check(phase != "failed") { error?.takeIf { it.isNotBlank() } ?: "本轮手机操作启动失败，请报告错误。" }
        check(phase != "parked") { "本轮虚拟屏操作已结束，请在新一轮对话中继续。" }
        check(phase != "choosing") { "手机操作方式正在等待选择，请等待当前对话里的选屏结果。" }
        check(turn == taskTurn) { "本命令不属于当前手机操作轮次，请从当前对话重新执行。" }
        check(phase == "active") { "所选屏幕尚未就绪，请查看手机操作浮窗中的状态。" }
        return true
    }

    // Android's input accepts an optional input source before its display option.
    private val sources = setOf("keyboard","dpad","gamepad","touchscreen","mouse","stylus","trackball",
        "touchpad","touchnavigation","joystick","rotaryencoder","mouse_relative")
    // Android handles these keys as system controls; a display ID does not confine their side effects.
    private val deviceKeys = mapOf(
        "POWER" to 26, "SLEEP" to 223, "WAKEUP" to 224, "SOFT_SLEEP" to 276,
        "VOLUME_UP" to 24, "VOLUME_DOWN" to 25, "VOLUME_MUTE" to 164, "MUTE" to 91,
        "BRIGHTNESS_DOWN" to 220, "BRIGHTNESS_UP" to 221, "NOTIFICATION" to 83,
        "APP_SWITCH" to 187, "ASSIST" to 219, "VOICE_ASSIST" to 231, "SYSRQ" to 120,
        "CALL" to 5, "ENDCALL" to 6, "HEADSETHOOK" to 79, "ALL_APPS" to 284,
        "MEDIA_PLAY_PAUSE" to 85, "MEDIA_STOP" to 86, "MEDIA_NEXT" to 87, "MEDIA_PREVIOUS" to 88,
        "MEDIA_REWIND" to 89, "MEDIA_FAST_FORWARD" to 90, "MEDIA_PLAY" to 126, "MEDIA_PAUSE" to 127,
        "MEDIA_RECORD" to 130,
    )
    private fun deviceKey(value: String): Boolean =
        value.removePrefix("KEYCODE_") in deviceKeys || value.toIntOrNull() in deviceKeys.values

    private val bare = Regex("[\\p{L}\\p{N}\\p{M}_./%@+:,=-]+")
    private val literal = Regex("""'([^']*)'|"([^"\\$\u0060]*)"|([\p{L}\p{N}\p{M}_./%@+:,=-]+)""")

    /** Reject compound shell syntax before rebuilding a single command directed to the chosen display. */
    private fun literalWords(command: String): List<String>? {
        if (command.any { it.isISOControl() && it != '\t' }) return null
        val words = mutableListOf<String>()
        var index = 0
        while (index < command.length) {
            when (command[index]) {
                ' ', '\t' -> index++
                else -> {
                    val match = literal.matchAt(command, index) ?: return null
                    words.add(match.groups.drop(1).firstNotNullOf { it?.value })
                    index = match.range.last + 1
                    if (index < command.length && command[index] !in " \t") return null
                }
            }
        }
        return words
    }

    fun virtualInput(command: String, display: Int): String? {
        if (display <= 0) return null
        val words = literalWords(command) ?: return null
        var index = when (words.firstOrNull()?.removePrefix("/system/bin/")) {
            "input" -> 1
            "cmd" -> if (words.getOrNull(1) == "input") 2 else return null
            else -> return null
        }
        val prefix = mutableListOf("input")
        if (words.getOrNull(index) in sources) prefix.add(words[index++])
        if (words.getOrNull(index) == "-d") {
            if (words.getOrNull(index + 1)?.toIntOrNull() != display) return null
            index += 2
        }
        val args = words.drop(index)
        if (args.isEmpty() || !bare.matches(args.first()) || args.first().startsWith("-") || "-d" in args) return null
        if (args.first() in setOf("keyevent", "keycombination")) {
            val keys = if (args.first() == "keycombination" && args.getOrNull(1) == "-t") args.drop(3) else args.drop(1)
            if (keys.any(::deviceKey)) return null
        }
        prefix.addAll(listOf("-d", display.toString()))
        return (prefix + args).joinToString(" ") { arg ->
            if (bare.matches(arg)) arg else "'" + arg.replace("'", "'\\''") + "'"
        }
    }

    fun readsScreen(command: String): Boolean {
        val words = literalWords(command) ?: return false
        return when (words.firstOrNull()?.removePrefix("/system/bin/")) {
            "screencap", "screenrecord" -> true
            "uiautomator" -> words.getOrNull(1) == "dump"
            else -> false
        }
    }
}
