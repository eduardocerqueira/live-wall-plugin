package io.jenkins.plugins.livewall;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Tile ordering on the wall.
 *
 * <p>The orderings that key off status move tiles around as builds come and go, which is the point
 * of them — the thing you need to look at rises to the top left. {@link #NAME} is the one to pick
 * when you would rather learn where each job lives and have it stay there.
 */
public enum SortBy implements WallOption {

    /** Building now, then queued, then worst-first among the rest. */
    RUNNING("running", "Running first (default) — whatever is building rises to the top"),
    /** Broken first, then unstable, aborted, never built and finally successful. */
    STATUS("status", "Failed first — problems at the top left"),
    /** Passing first, working down to the failures. */
    SUCCESS("success", "Passing first — failures at the bottom"),
    /** Alphabetical. Tiles keep their position between refreshes. */
    NAME("name", "Name — stable positions, nothing ever moves"),
    /** Most recently built first. */
    RECENT("recent", "Most recently built first"),
    /** Whatever order the underlying view produces. */
    VIEW_ORDER("view-order", "View order — leave the order alone");

    private final String id;
    private final String displayName;

    SortBy(String id, String displayName) {
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
