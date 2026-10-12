package io.rootexplorer.pro.feature.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two decisions a selection sweep makes.
 *
 * Both are pure, so they are tested here rather than by dragging a finger across a phone. Getting
 * them wrong is felt immediately: a sweep threshold that is too eager turns every scroll into a
 * selection, and a range that only works in one direction makes the gesture feel broken.
 */
class SwipeSelectionTest {

    private val threshold = 24f

    @Test
    fun `a leftward drag reaching the threshold is a selection sweep`() {
        // Reaching the threshold counts, so the rule reads as "at least this far left".
        assertEquals(SwipeGesture.SELECT, classifySwipe(dx = -threshold, dy = 0f, threshold = threshold))
        assertEquals(SwipeGesture.SELECT, classifySwipe(dx = -30f, dy = 0f, threshold = threshold))
        assertEquals(SwipeGesture.SELECT, classifySwipe(dx = -120f, dy = 6f, threshold = threshold))
    }

    @Test
    fun `a small movement is not a sweep, so a tap stays a tap`() {
        assertEquals(SwipeGesture.IGNORE, classifySwipe(dx = -10f, dy = 0f, threshold = threshold))
        assertEquals(SwipeGesture.IGNORE, classifySwipe(dx = -23f, dy = 0f, threshold = threshold))
    }

    @Test
    fun `a mostly vertical drag is left to the list so it can still scroll`() {
        assertEquals(SwipeGesture.IGNORE, classifySwipe(dx = -40f, dy = -60f, threshold = threshold))
        assertEquals(SwipeGesture.IGNORE, classifySwipe(dx = -40f, dy = 60f, threshold = threshold))
    }

    @Test
    fun `a rightward drag is ignored`() {
        assertEquals(SwipeGesture.IGNORE, classifySwipe(dx = 40f, dy = 0f, threshold = threshold))
        assertEquals(SwipeGesture.IGNORE, classifySwipe(dx = 120f, dy = 4f, threshold = threshold))
    }

    @Test
    fun `a sweep that drifts diagonally still counts while it is mostly horizontal`() {
        assertEquals(SwipeGesture.SELECT, classifySwipe(dx = -50f, dy = 20f, threshold = threshold))
    }

    @Test
    fun `the range covers both rows whichever direction the sweep went`() {
        assertEquals(0..3, selectionRange(anchorIndex = 0, currentIndex = 3))
        assertEquals(0..3, selectionRange(anchorIndex = 3, currentIndex = 0))
    }

    @Test
    fun `a sweep that never leaves its row selects exactly that row`() {
        assertEquals(2..2, selectionRange(anchorIndex = 2, currentIndex = 2))
    }

    @Test
    fun `a sweep back past its anchor collapses to the rows actually under it`() {
        assertEquals(2..5, selectionRange(anchorIndex = 5, currentIndex = 2))
        assertEquals(4..5, selectionRange(anchorIndex = 5, currentIndex = 4))
    }
}
