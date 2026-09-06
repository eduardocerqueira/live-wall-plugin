package io.jenkins.plugins.livewall;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Colour schemes for the wall.
 *
 * <p>The actual colours live in {@code live-wall.css} as custom properties under a
 * {@code [data-palette="..."]} selector, so a Jenkins administrator can override any of them from
 * the "Theme" / custom CSS of their instance without touching this plugin. Every built-in palette
 * is picked for legibility on a large screen viewed from several metres away: fully saturated fills
 * rather than pastel tints, and ink chosen for contrast against the fill rather than a fixed white.
 */
public enum Palette implements WallOption {

    /** Saturated fills on near-black. The default, and the one to beat on a real office TV. */
    VIVID("vivid", "Vivid (default) — saturated fills on near-black"),
    /** Glowing fills on pure black, for dim rooms. */
    NEON("neon", "Neon — glowing fills on pure black, for dim rooms"),
    /** Maximum contrast, thick edges. Best for a screen across a large open-plan floor. */
    CONTRAST("contrast", "High contrast — maximum separation, for very large rooms"),
    /** Light background, for screens facing a window. */
    DAYLIGHT("daylight", "Daylight — light background, for sunlit rooms"),
    /** Okabe-Ito derived, safe for the common forms of colour blindness. */
    COLORSAFE("colorsafe", "Colour-blind safe — blue/orange instead of green/red"),
    /** Deep, low-glare tones for a screen that stays on overnight. */
    MIDNIGHT("midnight", "Midnight — low glare, for screens left on overnight"),
    /** Colours supplied by the user in the view configuration. */
    CUSTOM("custom", "Custom — pick your own colours below");

    private final String id;
    private final String displayName;

    Palette(String id, String displayName) {
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
