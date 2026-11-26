package com.octelys.plugins.tempo.core

/**
 * Remembers which target, such as a project, received the last activity, so the session of the
 * one left behind can be closed. Otherwise returning to it within the idle threshold would extend
 * its session over the time spent elsewhere.
 *
 * Not thread-safe; callers serialize access.
 */
class FocusTracker<T : Any> {
    var current: T? = null
        private set

    /** Records activity on [target]; returns the target that lost focus, if it changed. */
    fun moveTo(target: T): T? {
        val previous = current
        current = target
        return previous?.takeIf { it != target }
    }
}
