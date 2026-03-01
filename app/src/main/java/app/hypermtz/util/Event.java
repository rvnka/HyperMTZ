package app.hypermtz.util;

import androidx.annotation.Nullable;

/**
 * Wraps a LiveData value that should only be consumed once.
 *
 * Standard LiveData replays the last value to new observers (e.g. after a
 * config change). For fire-and-forget events like Toast messages or navigation
 * actions we only want the event to fire once. Wrapping the value in Event and
 * calling {@link #getIfNotConsumed()} lets the observer decide to act or skip.
 */
public final class Event<T> {

    private final T content;
    private boolean consumed = false;

    public Event(T content) {
        this.content = content;
    }

    /**
     * Returns the content if it has not already been consumed, null otherwise.
     * Marks the event as consumed on first call.
     */
    @Nullable
    public T getIfNotConsumed() {
        if (consumed) return null;
        consumed = true;
        return content;
    }

    /** Returns the content without consuming it. */
    public T peek() {
        return content;
    }
}
