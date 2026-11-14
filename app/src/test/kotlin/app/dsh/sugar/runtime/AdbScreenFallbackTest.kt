package app.dsh.sugar.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbScreenFallbackTest {
    @Test fun permitsConnectedAdbWithoutAScreenAndHonorsTaskLifetime() {
        fun selected(phase: String?, taskTurn: Long? = 8, commandTurn: Long = 8) =
            AdbScreenFallback.usesSelectedScreen(commandTurn, taskTurn, phase, null)
        assertFalse(selected(null, null))
        assertTrue(runCatching { selected(null, null, -1) }.isFailure)
        assertFalse(selected("completed"))
        assertFalse(selected("stopped", 7))
        assertFalse(selected("parked", 7))
        assertTrue(selected("active"))
        for (phase in listOf("stopped","failed","parked","choosing","starting","permission"))
            assertTrue(phase, runCatching { selected(phase) }.isFailure)
        assertTrue(runCatching { selected("active", 7) }.isFailure)
    }

    @Test fun bindsAnyInputOperationToTheVirtualDisplay() {
        val cases = mapOf(
            "input tap 160 240" to "input -d 17 tap 160 240",
            "input keyevent --longpress KEYCODE_ENTER" to "input -d 17 keyevent --longpress KEYCODE_ENTER",
            "input keyboard keyevent 131" to "input keyboard -d 17 keyevent 131",
            "/system/bin/cmd 'input' 'touchscreen' '-d' '17' 'swipe' '1' '2' '3' '4' '240'" to
                "input touchscreen -d 17 swipe 1 2 3 4 240",
            "input text 'hello world!'" to "input -d 17 text 'hello world!'",
            "input text 欢迎" to "input -d 17 text 欢迎",
            "input future-subcommand 1" to "input -d 17 future-subcommand 1",
        )
        for ((raw,bound) in cases) assertEquals(raw,bound,AdbScreenFallback.virtualInput(raw,17))
    }

    @Test fun rejectsUnboundActionsAndCompoundSyntax() {
        for (raw in listOf("input -d 0 tap 1 2", "input -d 17 -d 0 tap 1 2", "input -d bad keyevent 1",
                "input tap 1 2; id","input tap 1 2 && input back","input tap 1 2\ninput tap 3 4",
                "sh -c 'input tap 1 2'","input text \$HOME","input text a > /sdcard/x",
                "screencap -p","uiautomator dump","am start -n pkg/.Activity")) {
            assertNull(raw,AdbScreenFallback.virtualInput(raw,17))
        }
        assertNull(AdbScreenFallback.virtualInput("input tap 3 4",0))
    }

    @Test fun limitsSystemWideKeysOnlyOnVirtualDisplays() {
        for (raw in listOf("input keyevent 26", "input keyevent KEYCODE_POWER", "input keyevent --longpress SLEEP",
                "cmd input keyevent 66 KEYCODE_WAKEUP", "input keycombination 59 26", "input keyevent VOLUME_UP"))
            assertNull(raw, AdbScreenFallback.virtualInput(raw, 17))
        assertEquals("input -d 17 keycombination -t 26 59 29", AdbScreenFallback.virtualInput("input keycombination -t 26 59 29", 17))
        assertEquals("input -d 17 text POWER", AdbScreenFallback.virtualInput("input text POWER", 17))
    }

    @Test fun preservesObservationsForReadOnlyMainScreenCapture() {
        assertTrue(AdbScreenFallback.readsScreen("screencap '-p'"))
        assertTrue(AdbScreenFallback.readsScreen("/system/bin/uiautomator dump /dev/tty"))
        assertFalse(AdbScreenFallback.readsScreen("screencap -p; input tap 1 2"))
        assertFalse(AdbScreenFallback.readsScreen("input keyevent KEYCODE_APP_SWITCH; sleep 1; dumpsys window"))
    }
}
