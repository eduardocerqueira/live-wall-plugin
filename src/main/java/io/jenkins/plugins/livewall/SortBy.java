package io.jenkins.plugins.livewall;

import edu.umd.cs.findbugs.annotations.NonNull;

/** Tile ordering on the wall. */
public enum SortBy implements WallOption {

    /** Alphabetical. Tiles keep their position between refreshes, which is what most walls want. */
    NAME("name", "Name (default) — stable positions"),
    /** Broken first, then unstable, aborted, never built and finally successful. */
    STATUS("status", "Status — problems first, top left"),
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
