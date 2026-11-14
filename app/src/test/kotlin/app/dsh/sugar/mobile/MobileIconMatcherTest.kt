package app.dsh.sugar.mobile

import org.junit.Assert.*
import org.junit.Test

class MobileIconMatcherTest {
    private val black = 0xff000000.toInt()
    private val red = 0xffff0000.toInt()
    private val blue = 0xff0000ff.toInt()

    @Test fun translatedIconUsesMaskAndBlendedEdge() {
        val s = IntArray(35) { black }
        // Transparent corners overlap arbitrary content. Half-opacity red on blue is purple.
        val p = 2 * 7 + 3
        s[p]=blue; s[p+1]=red; s[p+7]=0xff80007f.toInt()
        val r = MobileIconMatcher.find(s,7,5,intArrayOf(0,red,0x80ff0000.toInt(),0),2,2,0,8)
        assertEquals("unique",r.status)
        assertEquals(listOf(MobileIconMatcher.Hit(3,2)),r.hits)
    }
    @Test fun limitedResultsStillReportTwoMatches() {
        val s = intArrayOf(blue,red,black,blue,red,black)
        val r = MobileIconMatcher.find(s,6,1,intArrayOf(blue,red),2,1,0,1)
        assertEquals("multiple",r.status)
        assertTrue(r.multiple)
        assertTrue(r.truncated)
        assertEquals(1,r.hits.size)
    }
    @Test fun toleranceAppliesPerChannel() {
        val s=intArrayOf(0xfffe0101.toInt())
        assertEquals("unique",MobileIconMatcher.find(s,1,1,intArrayOf(red),1,1,1,1).status)
        assertEquals("not_found",MobileIconMatcher.find(s,1,1,intArrayOf(red),1,1,0,1).status)
    }
    @Test fun transparentOrOversizedIconIsInvalid() {
        assertThrows(IllegalArgumentException::class.java) { MobileIconMatcher.find(intArrayOf(black),1,1,intArrayOf(0),1,1,0,8) }
        assertThrows(IllegalArgumentException::class.java) { MobileIconMatcher.find(intArrayOf(black),1,1,IntArray(193),193,1,0,8) }
    }
    @Test fun iconLargerThanObservationHasNoMatch() {
        assertEquals("not_found",MobileIconMatcher.find(intArrayOf(red),1,1,intArrayOf(red,blue),2,1,0,8).status)
    }
    @Test fun comparisonBudgetNeverReportsAnUnprovenAbsence() {
        val screen=IntArray(250*250) { red }
        val icon=IntArray(32*32) { red }.also { it[it.lastIndex]=0xfffe0000.toInt() }
        val r=MobileIconMatcher.find(screen,250,250,icon,32,32,0,8)
        assertEquals("incomplete",r.status)
        assertTrue(r.truncated)
        assertTrue(r.hits.isEmpty())
    }
}
