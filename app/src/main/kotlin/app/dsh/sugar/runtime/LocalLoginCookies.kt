package app.dsh.sugar.runtime

import java.security.MessageDigest
import java.util.Base64

internal object LocalLoginCookies {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val namePattern = Regex("""dsh-auth-[A-Za-z0-9_-]{43}""")
    private val signedSession = Regex("""v1\.([A-Za-z0-9_-]{1,512})\.[A-Za-z0-9_-]{43}""")
    // The pinned DSH launcher emits this fixed JSON in its v1 browser-session cookies.
    // Skip unrecognized payloads, including separate v2 connection grants.
    private val bodyPattern = Regex(
        """\{"version":1,"authority":"(127\.0\.0\.1(?::([1-9][0-9]{0,4}))?)","issuedAt":[0-9]{1,16},"expiresAt":[0-9]{1,16}\}"""
    )

    fun nameForPort(port: Int): String = nameFor(if (port == 80) "127.0.0.1" else "127.0.0.1:$port")

    private fun nameFor(authority: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(authority.toByteArray(Charsets.US_ASCII))
        return "dsh-auth-" + encoder.encodeToString(digest)
    }

    // Cookies are host-scoped, even though DSH binds each credential to one port.
    fun staleNames(header: String?, currentName: String): Set<String> {
        if (header.isNullOrEmpty()) return emptySet()
        val stale = mutableSetOf<String>()
        for (part in header.splitToSequence(';')) {
            val pair = part.trim()
            val name = pair.substringBefore('=')
            if (name == currentName || !namePattern.matches(name)) continue
            val body = signedSession.matchEntire(pair.substringAfter('=', ""))?.groupValues?.get(1) ?: continue
            val decoded = try { decoder.decode(body).toString(Charsets.UTF_8) }
                catch (_: IllegalArgumentException) { continue }
            val match = bodyPattern.matchEntire(decoded) ?: continue
            val port = match.groupValues[2]
            if (port.isNotEmpty() && port.toInt() !in 1..65535) continue
            if (name == nameFor(match.groupValues[1])) stale.add(name)
        }
        return stale
    }
}
