package app.dsh.sugar.runtime

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLoginCookiesTest {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    private fun name(authority: String) = "dsh-auth-" + encoder.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(authority.toByteArray(Charsets.US_ASCII)))

    private fun pair(authority: String, version: Int = 1): String {
        val body = """{"version":$version,"authority":"$authority","issuedAt":100,"expiresAt":200}"""
        return name(authority) + "=v$version." + encoder.encodeToString(body.toByteArray()) + "." + "A".repeat(43)
    }

    @Test fun removesOnlyPreviousSugarLogins() {
        val origin = "127.0.0.1:41703"
        val old = "127.0.0.1:41802"
        val current = LocalLoginCookies.nameForPort(41703)
        val parts = listOf(pair(old), pair(old), pair(origin), "app-pref=sugar", pair("localhost:41802"),
            pair("127.0.0.2:41802"), pair("127.0.0.1:41801", 2), pair("127.0.0.1:65536"),
            "dsh-auth-settings=1", pair(old).replace(name(old), name("127.0.0.1:1")),
            name("127.0.0.1:23") + "=v1.A." + "A".repeat(43))
        assertEquals(setOf(name(old)), LocalLoginCookies.staleNames(parts.joinToString("; "), current))
        assertEquals(emptySet<String>(), LocalLoginCookies.staleNames(null, current))
        assertEquals(emptySet<String>(), LocalLoginCookies.staleNames(pair(origin), current))
    }

    @Test fun httpDefaultPortUsesNormalizedAuthority() {
        assertEquals(name("127.0.0.1"), LocalLoginCookies.nameForPort(80))
        assertEquals(emptySet<String>(), LocalLoginCookies.staleNames(pair("127.0.0.1"), name("127.0.0.1")))
        assertEquals(setOf(name("127.0.0.1")), LocalLoginCookies.staleNames(pair("127.0.0.1"), name("127.0.0.1:81")))
    }
}
