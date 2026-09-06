package io.jenkins.plugins.livewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The filter people use instead of writing a regular expression, so its surprises are the ones that
 * matter most.
 */
class NameFilterTest {

    @Test
    void anEmptyFilterKeepsEverything() {
        NameFilter filter = NameFilter.of(null, null);
        assertTrue(filter.isEmpty());
        assertTrue(filter.accepts("anything"));
        assertTrue(NameFilter.of("  ", "\n , \n").accepts("anything"), "and so does a filter of only whitespace");
    }

    @Test
    void aPlainWordMeansContains() {
        NameFilter filter = NameFilter.of("drools", null);

        assertTrue(filter.accepts("drools"));
        assertTrue(filter.accepts("drools-nightly"));
        assertTrue(filter.accepts("ci-drools-pipeline"));
        assertTrue(filter.accepts("CI-DROOLS"), "matching ignores case");
        assertFalse(filter.accepts("kogito"));
    }

    @Test
    void wildcardsAnchorToTheWholeName() {
        assertTrue(NameFilter.of("ci-*", null).accepts("ci-decision-control"));
        assertFalse(NameFilter.of("ci-*", null).accepts("legacy-ci-decision"), "ci- has to be at the start");

        assertTrue(NameFilter.of("*-pipeline", null).accepts("decision-pipeline"));
        assertFalse(NameFilter.of("*-pipeline", null).accepts("decision-pipeline-old"));

        assertTrue(NameFilter.of("release-?", null).accepts("release-1"));
        assertFalse(NameFilter.of("release-?", null).accepts("release-10"), "? is exactly one character");
    }

    @Test
    void everythingElseIsTakenLiterally() {
        // The whole point of not using a regular expression: these are characters, not syntax.
        assertTrue(NameFilter.of("build.sh", null).accepts("run-build.sh-nightly"));
        assertFalse(NameFilter.of("build.sh", null).accepts("build-sh"), "the dot is a dot");

        assertTrue(NameFilter.of("app(v2)", null).accepts("deploy-app(v2)"));
        assertTrue(NameFilter.of("a+b", null).accepts("job-a+b"));
        assertFalse(NameFilter.of("a+b", null).accepts("job-aaab"));
    }

    @Test
    void patternsSeparateOnCommasAndNewlines() {
        NameFilter filter = NameFilter.of("drools\nci-*, kogito", null);

        assertTrue(filter.accepts("drools"));
        assertTrue(filter.accepts("ci-anything"));
        assertTrue(filter.accepts("kogito-runtime"));
        assertFalse(filter.accepts("quarkus"));
    }

    @Test
    void excludesWinOverIncludes() {
        NameFilter filter = NameFilter.of("ci-*", "*-sandbox, experimental");

        assertTrue(filter.accepts("ci-decision"));
        assertFalse(filter.accepts("ci-decision-sandbox"), "excluded even though it matches the include");
        assertFalse(filter.accepts("ci-experimental-thing"));
    }

    @Test
    void excludingWithoutIncludingKeepsTheRest() {
        NameFilter filter = NameFilter.of(null, "*-sandbox");

        assertTrue(filter.accepts("ci-decision"));
        assertFalse(filter.accepts("ci-decision-sandbox"));
    }

    @Test
    void anyOfTheOfferedNamesCanMatch() {
        // The view tries the full name, the display name and the tile label, so a pattern works
        // whichever of them the person had in mind.
        NameFilter filter = NameFilter.of("main", null);
        assertTrue(filter.accepts("team/repo/main", "main", "repo/main"));
        assertFalse(filter.accepts("team/repo/develop", "develop", "repo/develop"));
    }

    @Test
    void theFilterDescribesItselfInWords() {
        assertEquals("", NameFilter.describe(null));
        assertEquals("names containing drools", NameFilter.describe("Drools"));
        assertEquals("names matching ci-*", NameFilter.describe("ci-*"));
        assertEquals("names containing drools, or names matching ci-*", NameFilter.describe("drools, ci-*"));
    }
}
