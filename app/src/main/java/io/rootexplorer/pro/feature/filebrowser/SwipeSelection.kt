package io.rootexplorer.pro.feature.filebrowser

import kotlin.math.abs

/** What the file list should do with a drag that has moved from its start point. */
enum class SwipeGesture {
    /** A leftward sweep: the list takes the gesture over and marks the rows it passes. */
    SELECT,

    /** Anything else — a vertical scroll, or a rightward drag — belongs to the list. */
    IGNORE,
}

/**
 * Decide whether a drag that has moved by ([dx], [dy]) from where it started is a selection sweep.
 *
 * The sweep is deliberately *leftward*: the user pushes rows to the left to mark them, which is
 * the direction a "swipe away to act on it" gesture reads as. Two things are left alone:
 *
 * * a mostly vertical drag, which is the list scrolling — taking it over would make the list
 *   impossible to scroll with a slightly slanted finger;
 * * a rightward drag, which is reserved so it can mean something else later (and so a twitch to
 *   the right does not silently select a row).
 *
 * [threshold] is how far left the finger must travel before the gesture counts at all, so a tap
 * with a few pixels of jitter does not turn into a swipe. Reaching the threshold exactly counts:
 * the rule reads as "at least this far left".
 *
 * Pure, so the decision is testable without a device.
 */
fun classifySwipe(dx: Float, dy: Float, threshold: Float): SwipeGesture = when {
    dx > -threshold -> SwipeGesture.IGNORE
    -dx <= abs(dy) -> SwipeGesture.IGNORE
    else -> SwipeGesture.SELECT
}

/**
 * The inclusive row indices between the row the sweep started on and the row it has reached.
 *
 * The sweep works in both directions once it has started, so dragging up and then back down is
 * the same gesture as dragging down: the caller replaces the swept range on every update and the
 * selection follows the finger either way.
 */
fun selectionRange(anchorIndex: Int, currentIndex: Int): IntRange =
    if (anchorIndex <= currentIndex) anchorIndex..currentIndex else currentIndex..anchorIndex
