package app.dsh.sugar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLoginTest {
    @Test fun asynchronousPopupCanOnlyOpenThePlatformAuthorizationPage() {
        val url = "https://platform.deepseek.com/dsh/authorize"
        assertTrue(BrowserLogin.isAuthorizationPage("$url?theme=dark"))
        for (other in listOf("http://platform.deepseek.com/dsh/authorize",
            "https://platform.deepseek.com.evil.test/dsh/authorize",
            "https://user@platform.deepseek.com/dsh/authorize",
            "https://platform.deepseek.com:444/dsh/authorize", "$url/../other",
            "$url#fragment", "javascript:alert(1)", "http://127.0.0.1:12345/?token=x")) {
            assertFalse(BrowserLogin.isAuthorizationPage(other))
        }
    }
}
