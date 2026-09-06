package io.jenkins.plugins.livewall;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * How a tile signals that its job is building right now.
 *
 * <p>All of these are suppressed when the viewer's browser reports
 * {@code prefers-reduced-motion: reduce}; the tile then falls back to a static in-progress edge.
 */
public enum TileAnimation implements WallOption {

    /** Fills the tile left-to-right in step with the estimated duration. Falls back to
     *  indeterminate stripes once a build overruns its estimate. */
    PROGRESS("progress", "Progress fill (default) — tracks the estimated duration"),
    /** A light beam sweeping across the tile. */
    SWEEP("sweep", "Sweep — a light beam crossing the tile"),
    /** Barber-pole diagonal stripes. */
    STRIPES("stripes", "Stripes — moving diagonal bars"),
    /** Slow brightness pulse. */
    PULSE("pulse", "Pulse — slow breathing glow"),
    /** No motion at all, just a marked edge. */
    NONE("none", "None — static in-progress edge");

    private final String id;
    private final String displayName;

    TileAnimation(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    @Override
    @NonNull
    public String getId() {
        return id;
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return displayName;
    }
}
