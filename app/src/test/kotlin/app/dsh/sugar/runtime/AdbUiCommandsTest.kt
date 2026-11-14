package app.dsh.sugar.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbUiCommandsTest {
    @Test fun identifiesLaunchInputAndCaptureAcrossBothShellForms() {
        for (command in listOf(
            "monkey -p com.example.calculator 1", "am start -n com.example/.Main",
            "cmd activity start-activity --display 3 -n com.example/.Main",
            "pm list packages | head; input tap 1 2", "screencap -p > /data/local/tmp/a.png",
            "/system/bin/cmd input -d 4 keyevent BACK", "sh -c 'echo ready && /system/bin/input text hello'",
            "exec 'screencap' '-p'", "A=1 nohup screenrecord file.mp4", "uiautomator dump /dev/tty"
        )) assertEquals(command, AdbUiCommand.SCREEN, AdbUiCommands.inspect(command).action)
    }

    @Test fun packageForceStopTakesPrecedenceEvenWhenMixedWithLaunchOrInput() {
        for (command in listOf("am force-stop com.example; monkey -p com.example 1",
            "input keyevent BACK && /system/bin/cmd activity force-stop com.example",
            "sh -c 'am force-stop app.example'")) {
            assertEquals(command, AdbUiCommand.FORCE_STOP, AdbUiCommands.inspect(command).action)
        }
    }

    @Test fun cliReadsSignedRoleOnlyForScreenCommands() {
        val diagnostics = AdbUiCommands.inspect("dumpsys window | grep -E 'mCurrentFocus'")
        assertEquals(null, diagnostics.agentAction { error("Ordinary tickets have no manual claim") })

        val input = AdbUiCommands.inspect("input keyevent KEYCODE_APP_SWITCH; dumpsys window")
        assertTrue(runCatching { input.agentAction { error("Missing signed role") } }.isFailure)
        assertEquals(AdbUiCommand.SCREEN, input.agentAction { false })

        val capture = AdbUiCommands.inspect("screencap -p /sdcard/screen.png")
        assertTrue(runCatching { capture.agentAction { false } }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(null, capture.agentAction { true })
    }

    @Test fun packageDiagnosticsAndQuotedOutputRemainUsable() {
        for (command in listOf("pm list packages", "dumpsys activity activities | head",
            "cmd package resolve-activity --brief com.example", "getprop ro.build.version.release",
            "printf '%s' 'monkey -p app 1; am force-stop app'", "wm size", "input --help",
            "sh -c 'pm list packages | grep calculator'", "am startservice -n com.example/.Service")) {
            assertNull(command, AdbUiCommands.inspect(command).action)
        }
    }

    @Test fun catchesDeviceSharedCaptureFilesAndImplicitDumpDestinations() {
        for (command in listOf(
            "uiautomator dump", "uiautomator dump --compressed 2>/dev/null; cat /sdcard/window_dump.xml",
            "true && /system/bin/uiautomator dump --windows", "if uiautomator dump; then echo ok; fi",
            "uiautomator dump '/storage/emulated/0/Download/ui state.xml'",
            "/system/bin/screencap -p /sdcard/capture.png", "exec 'screencap' '-p' '/storage/self/primary/x'",
            "screencap -p>/sdcard/one.png", "screencap -p 1>>'/storage/emulated/0/a b'",
            ">/sdcard/b screencap -p", "screencap -p | tee /mnt/sdcard/current.png",
            "sh -c 'screencap -p' >/storage/emulated/0/shot.png",
            "screencap -p | cat >> /sdcard/shot", "uiautomator dump \"\$EXTERNAL_STORAGE/ui.xml\"",
            "sh -c 'echo ready && uiautomator dump --compressed >/dev/null'",
            "am force-stop pkg; screencap -p /sdcard/file"
        )) {
            val capture = AdbUiCommands.inspect(command)
            assertEquals(command, true, capture.writesSharedCapture)
            assertTrue(command, capture.action != null)
        }
    }

    @Test fun allowsWorkspaceStreamsPrivateStagingAndUnrelatedDeviceWrites() {
        for (command in listOf(
            "screencap -p", "screencap -p >/data/local/tmp/preview.png", "screencap -p /sdcard-other/file",
            "uiautomator dump /data/local/tmp/ui.xml >/dev/null", "uiautomator dump /dev/tty",
            "sh -c 'uiautomator dump --compressed \"\$ui_file\" > /dev/null; cat \"\$ui_file\"'",
            "cp /data/local/tmp/file /sdcard/file", "pm list packages > /sdcard/packages",
            "printf '%s' 'screencap -p > /sdcard/b'", "screencap -p; echo success >/sdcard/log",
            "cat /sdcard/old.xml | tee /sdcard/another.xml", "uiautomator dump /data/local/tmp/previous.xml; ls /sdcard",
            "uiautomator dump /data/local/tmp/ui.xml 2>/dev/null && echo dump-complete >/sdcard/report",
            "screencap -p 2>&1 | cat > /data/local/tmp/file"
        )) {
            assertEquals(command, false, AdbUiCommands.inspect(command).writesSharedCapture)
        }
    }
}
