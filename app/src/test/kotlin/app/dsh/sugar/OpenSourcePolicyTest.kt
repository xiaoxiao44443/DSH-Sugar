package app.dsh.sugar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourcePolicyTest {
    private val origin = "http://127.0.0.1:32123"
    private val notices = origin + OpenSourcePolicy.PATH

    @Test fun onlyExactOwnedGetSubresourceReadsOfflineNotices() {
        assertTrue(OpenSourcePolicy.matchesRequest(notices, origin, "GET", false))
        assertFalse(OpenSourcePolicy.matchesRequest(notices, origin, "POST", false))
        assertFalse(OpenSourcePolicy.matchesRequest(notices, origin, "GET", true))
        assertFalse(OpenSourcePolicy.matchesRequest(notices, null, "GET", false))
        for (url in listOf(notices + "?file=other", notices + "#fragment",
            "$origin/__sugar__/../open-source-notices.json",
            "$origin/__sugar__/%6fpen-source-notices.json",
            "http://127.0.0.1:32124" + OpenSourcePolicy.PATH,
            "$origin.evil.example" + OpenSourcePolicy.PATH,
            "https://example.org" + OpenSourcePolicy.PATH)) {
            assertFalse(url, OpenSourcePolicy.matchesRequest(url, origin, "GET", false))
        }
        assertFalse(OpenSourcePolicy.matchesRequest("https://example.org" + OpenSourcePolicy.PATH,
            "https://example.org", "GET", false))
    }

    @Test fun componentLinksAreResolvedOnlyByPackagedId() {
        val target = "https://github.com/MuntashirAkon/spake2-java/tree/2.2.1"
        val links = mapOf("spake2" to target)
        assertEquals(target, OpenSourcePolicy.componentSource(links, "spake2"))
        for (id in listOf("", "missing", target, "../spake2", "spake2?url=https://evil.example", "spake2 "))
            assertNull(OpenSourcePolicy.componentSource(links, id))
    }

    @Test fun nonHttpsOrAmbiguousPackagedLinksAreNotOpened() {
        for (url in listOf("javascript:alert(1)", "intent://example", "file:///data/private",
            "http://example.org", "https://good.example@evil.example/source", "https://example.org:8080/source",
            "https://example.org\\@evil.example/source", "https:///missing-host")) {
            assertNull(url, OpenSourcePolicy.componentSource(mapOf("sample" to url), "sample"))
        }
    }

    @Test fun correspondingSourceReleaseUsesInstalledVersion() {
        val base = "https://github.com/xiaoxiao44443/DSH-Sugar/releases/tag/v"
        assertEquals(base + "0.2.3", OpenSourcePolicy.releaseUrl("0.2.3"))
        assertEquals(base + "0.3.0-rc.1", OpenSourcePolicy.releaseUrl("0.3.0-rc.1"))
        for (version in listOf("", "latest", "v0.2.3", "0.2.3/../../other", "0.2.3?url=x", "0.2.3#x", " 0.2.3"))
            assertNull(OpenSourcePolicy.releaseUrl(version))
    }
}
