package app.dsh.sugar.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainWindowGeometryTest {
    private val observed = MainWindow(
        id = 17, packageName = "test.calc", width = 1080, height = 2400, rotation = 0, generation = 10L,
        bounds = MainWindowBounds(21, 30, 421, 630),
        contentBounds = MainWindowBounds(21, 62, 421, 630),
        focused = true, layer = 9,
    )

    private fun assertChanged(next: MainWindow) {
        assertNotEquals(observed, next)
        assertNotEquals(observed.geometrySignature, next.geometrySignature)
    }

    @Test fun movingTheWindowInvalidatesTheSnapshotWithoutADisplayOrGenerationChange() {
        val moved = observed.copy(bounds = MainWindowBounds(41, 50, 441, 650))

        assertEquals(observed.id, moved.id)
        assertEquals(observed.width, moved.width)
        assertEquals(observed.height, moved.height)
        assertEquals(observed.rotation, moved.rotation)
        assertEquals(observed.generation, moved.generation)
        assertChanged(moved)
    }

    @Test fun resizingTheWindowInvalidatesTheSnapshotAtTheSameOrigin() {
        val resized = observed.copy(bounds = MainWindowBounds(21, 30, 521, 830))

        assertEquals(observed.width, resized.width)
        assertEquals(observed.height, resized.height)
        assertEquals(observed.generation, resized.generation)
        assertChanged(resized)
    }

    @Test fun changingOnlyTheContentBoundsInvalidatesTheSnapshot() {
        val insetChanged = observed.copy(contentBounds = MainWindowBounds(21, 78, 421, 610))

        assertEquals(observed.bounds, insetChanged.bounds)
        assertEquals(observed.generation, insetChanged.generation)
        assertChanged(insetChanged)
    }

    @Test fun focusLayerAndTheirAvailabilityArePartOfTheSnapshot() {
        for (changed in listOf(
            observed.copy(focused = false),
            observed.copy(focused = null),
            observed.copy(layer = 10),
            observed.copy(layer = null),
        )) assertChanged(changed)
    }

    @Test fun repeatedEquivalentValuesHaveTheSameSignature() {
        repeat(5) {
            val same = observed.copy(
                bounds = MainWindowBounds(21, 30, 421, 630),
                contentBounds = MainWindowBounds(21, 62, 421, 630),
            )
            assertEquals(observed, same)
            assertEquals(observed.geometrySignature, same.geometrySignature)
        }
    }

    @Test fun movingAwayAndReturningStillInvalidatesAnEarlierObservation() {
        val away = observed.copy(bounds = MainWindowBounds(41, 50, 441, 650), generation = 11L)
        val returned = away.copy(bounds = observed.bounds, generation = 12L)

        assertEquals(observed, returned.copy(generation = observed.generation))
        assertChanged(returned)
    }

    @Test fun unknownMetadataRemainsNullableAndDistinctFromKnownBounds() {
        val unknown = MainWindow(17, "test.calc", 1080, 2400, 0, 10L)

        assertNull(unknown.bounds)
        assertNull(unknown.contentBounds)
        assertNull(unknown.focused)
        assertNull(unknown.layer)
        assertEquals(unknown, unknown.copy())
        assertEquals(unknown.geometrySignature, unknown.copy().geometrySignature)
        val known = unknown.copy(bounds = MainWindowBounds(0, 0, 1080, 2400))
        assertNotEquals(unknown, known)
        assertNotEquals(unknown.geometrySignature, known.geometrySignature)
    }

    @Test fun identityDisplayMetricsAndRotationRemainPartOfTheSignature() {
        for (changed in listOf(
            observed.copy(id = 18),
            observed.copy(packageName = "test.other"),
            observed.copy(width = 1200),
            observed.copy(height = 2200),
            observed.copy(rotation = 1),
        )) assertChanged(changed)
    }
}
