package app.dsh.sugar

internal data class AgentNotificationPolicy(
    val completion: String = "background",
    val permission: Boolean = true,
    val question: Boolean = true,
    val muteWhilePetVisible: Boolean = true
) {
    fun allows(kind: String, appVisible: Boolean, petVisible: Boolean): Boolean {
        if (muteWhilePetVisible && petVisible) return false
        return when (kind) {
            "completion" -> completion == "always" || completion == "background" && !appVisible
            "permission" -> permission
            "question" -> question
            else -> false
        }
    }
}

/** Native validation also bounds content sent by older or external notification plugins. */
internal object AgentNotificationBody {
    private const val LIMIT = 2000
    fun normalize(value: String?): String? {
        val text = value?.replace("\r\n", "\n")?.replace('\r', '\n')
            ?.filter { it == '\n' || it == '\t' || !it.isISOControl() &&
                it !in '\u202a'..'\u202e' && it !in '\u2066'..'\u2069' && it != '\u200b' }
            ?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (text.length <= LIMIT) return text
        val end = (LIMIT - 1).let { if (text[it - 1].isHighSurrogate()) it - 1 else it }
        return text.take(end).trimEnd() + "…"
    }
}
