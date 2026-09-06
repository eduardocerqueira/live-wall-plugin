package io.jenkins.plugins.livewall;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * What the wall does when there are more jobs than comfortably fit on the screen.
 *
 * <p>Notably absent: pagination. Flipping between pages means the thing you need to see is off
 * screen half the time, which defeats the point of a radiator.
 */
public enum Sizing implements WallOption {

    /** Shrink every tile until all of them fit in one screenful. Nothing is ever hidden. */
    FIT("fit", "Fit everything on one screen (default)"),
    /** Keep tiles at a readable minimum height and scroll the wall slowly and continuously. */
    SCROLL("scroll", "Keep tiles readable and scroll continuously");

    private final String id;
    private final String displayName;

    Sizing(String id, String displayName) {
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
