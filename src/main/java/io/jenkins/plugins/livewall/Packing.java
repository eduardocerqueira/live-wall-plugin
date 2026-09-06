package io.jenkins.plugins.livewall;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * How tiles are fitted together.
 *
 * <p>A plain grid gives every tile its own rectangular cell, which is right for rectangles and
 * squares and wrong for everything else: a wall of hexagons laid out on a grid is a scattering of
 * hexagons, not a honeycomb. Interlocking offsets alternate columns or rows and lets each shape
 * overhang its cell by exactly the amount its geometry needs, so the shapes tessellate into one
 * continuous surface.
 *
 * <p>Shapes that already tile edge to edge at zero gap — rectangles, squares, octagons, crosses —
 * are unaffected either way. Circles cannot tile at all, and are left alone.
 */
public enum Packing implements WallOption {

    /** Offset and overlap shapes so they tessellate. Hexagons become a honeycomb. */
    INTERLOCK("interlock", "Interlocked (default) — shapes tessellate into one continuous wall"),
    /** One tile per cell, no offsets. */
    GRID("grid", "Grid — every tile squarely in its own cell");

    private final String id;
    private final String displayName;

    Packing(String id, String displayName) {
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
