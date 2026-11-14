package app.dsh.sugar.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LauncherAppNamesTest {
    private val game = LauncherApp("崩坏：星穹铁道", "sample.game", "sample.game.Main")
    private val guide = LauncherApp("星穹铁道攻略", "sample.guide", "sample.guide.Guide")

    @Test fun launcherNameUsesUnicodeWidthCaseAndSpacing() {
        assertEquals(listOf(game), LauncherAppNames.matches("崩坏: 星穹铁道", sequenceOf(game, guide)))
        val notes = LauncherApp("Ｍｙ Notes", "sample.notes", "sample.notes.Editor")
        assertEquals(listOf(notes), LauncherAppNames.matches("myNOTES", sequenceOf(notes)))
    }

    @Test fun uniqueShortNameAndExactNameHaveDifferentPriority() {
        assertEquals(listOf(game), LauncherAppNames.matches("星穹铁道", sequenceOf(game)))
        assertEquals(listOf(game, guide), LauncherAppNames.matches("星穹铁道", sequenceOf(game, guide)))
        val exact = game.copy(label = "星穹铁道")
        assertEquals(listOf(exact), LauncherAppNames.matches("星穹铁道", sequenceOf(guide, exact)))
    }

    @Test fun ambiguousLauncherEntriesAreDistinctAndConsistentlyOrdered() {
        val other = game.copy(pkg = "other.game", activity = "other.game.Main")
        val alias = game.copy(activity = "sample.game.OtherEntry")
        assertEquals(listOf(other, game, alias), LauncherAppNames.matches(game.label, sequenceOf(alias, other, game, game)))
    }

    @Test fun noEnumerationFromEmptyOneCharacterOrUnknownQuery() {
        val neverRead = sequence<LauncherApp> { error("Should not query installed entries") }
        assertEquals("invalid_argument", assertThrows(ScreenProblem::class.java) { LauncherAppNames.matches("  ", neverRead) }.code)
        assertEquals("invalid_argument", assertThrows(ScreenProblem::class.java) { LauncherAppNames.matches("a".repeat(129), neverRead) }.code)
        assertEquals(emptyList<LauncherApp>(), LauncherAppNames.matches("星", sequenceOf(game)))
        assertEquals(emptyList<LauncherApp>(), LauncherAppNames.matches("not installed", sequenceOf(game)))
        assertEquals(listOf(guide.copy(label = "星")), LauncherAppNames.matches("星", sequenceOf(guide.copy(label = "星"), game)))
    }
}
