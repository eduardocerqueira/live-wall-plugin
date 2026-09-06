package io.jenkins.plugins.livewall;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Which jobs earn a tile.
 *
 * <p>On a busy controller a wall of five hundred green tiles says nothing. Narrowing the scope
 * turns the same view into an alert board without needing a second view.
 */
public enum StatusScope implements WallOption {

    /** Every job in the view. */
    ALL("all", "All jobs (default)"),
    /** Only failing, unstable or aborted jobs — an empty wall means everything is fine. */
    PROBLEMS("problems", "Only problems — an empty wall is good news"),
    /** Problems plus anything building right now. */
    PROBLEMS_AND_RUNNING("problems-and-running", "Problems and jobs building right now");

    private final String id;
    private final String displayName;

    StatusScope(String id, String displayName) {
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

    /**
     * Whether a job in this state earns a tile.
     *
     * @param status the job's last known outcome
     * @param active whether it is building or waiting in the queue right now
     */
    boolean accepts(@NonNull JobStatus status, boolean active) {
        return switch (this) {
            case ALL -> true;
            case PROBLEMS -> status.isProblem();
            case PROBLEMS_AND_RUNNING -> status.isProblem() || active;
        };
    }
}
