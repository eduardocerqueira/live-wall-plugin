package io.jenkins.plugins.livewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.View;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.Page;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LiveWallViewTest {

    private LiveWallView createView(JenkinsRule r, String name) throws Exception {
        LiveWallView view = new LiveWallView(name, r.jenkins);
        r.jenkins.addView(view);
        return view;
    }

    @Test
    void defaultsAreTheOnesTheDocumentationPromises(JenkinsRule r) throws Exception {
        LiveWallView view = createView(r, "wall");

        assertSame(Palette.VIVID, view.getPalette());
        assertSame(TileShape.ROUNDED, view.getShape());
        assertSame(TileAnimation.PROGRESS, view.getAnimation());
        assertSame(Sizing.FIT, view.getSizing(), "fit, because pagination is the thing this replaces");
        assertSame(SortBy.NAME, view.getSortBy());
        assertSame(StatusScope.ALL, view.getStatusScope());
        assertEquals(LiveWallView.DEFAULT_REFRESH_SECONDS, view.getRefreshSeconds());
        assertTrue(view.isShowHeader());
        assertTrue(view.isShowFolderPath());
        assertFalse(view.isShowBuildNumber(), "per-tile detail stays off unless asked for");
    }

    @Test
    void tilesCarryTheLastOutcomeOfEveryJobInTheView(JenkinsRule r) throws Exception {
        FreeStyleProject good = r.createFreeStyleProject("good");
        FreeStyleProject bad = r.createFreeStyleProject("bad");
        bad.getBuildersList().add(new org.jvnet.hudson.test.FailureBuilder());
        r.buildAndAssertSuccess(good);
        r.assertBuildStatus(Result.FAILURE, bad.scheduleBuild2(0));

        LiveWallView view = createView(r, "wall");
        view.add(good);
        view.add(bad);

        List<Tile> tiles = view.getTiles();
        assertEquals(2, tiles.size());
        assertEquals(JobStatus.FAILURE, statusOf(tiles, "bad"));
        assertEquals(JobStatus.SUCCESS, statusOf(tiles, "good"));
    }

    @Test
    void jobsThatHaveNeverBuiltAreNotBuiltRatherThanFailing(JenkinsRule r) throws Exception {
        r.createFreeStyleProject("fresh");
        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        List<Tile> tiles = view.getTiles();
        assertEquals(1, tiles.size());
        assertEquals(JobStatus.NOT_BUILT, tiles.get(0).status());
        assertEquals(0, tiles.get(0).buildNumber());
    }

    @Test
    void sortByStatusFloatsProblemsToTheTopLeft(JenkinsRule r) throws Exception {
        FreeStyleProject passing = r.createFreeStyleProject("aaa-passing");
        FreeStyleProject failing = r.createFreeStyleProject("zzz-failing");
        failing.getBuildersList().add(new org.jvnet.hudson.test.FailureBuilder());
        r.buildAndAssertSuccess(passing);
        r.assertBuildStatus(Result.FAILURE, failing.scheduleBuild2(0));

        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        view.setSortBy("name");
        assertEquals(List.of("aaa-passing", "zzz-failing"), labels(view));

        view.setSortBy("status");
        assertEquals(List.of("zzz-failing", "aaa-passing"), labels(view));
    }

    @Test
    void problemsScopeLeavesAnEmptyWallWhenEverythingIsGreen(JenkinsRule r) throws Exception {
        r.buildAndAssertSuccess(r.createFreeStyleProject("fine"));

        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        assertEquals(1, view.getTiles().size());
        assertEquals(Messages.LiveWallView_NoJobs(), view.getEmptyMessage());

        view.setStatusScope("problems");

        assertTrue(view.getTiles().isEmpty(), "an empty problems wall is the good outcome");
        assertEquals(
                Messages.LiveWallView_NothingWrong(),
                view.getEmptyMessage(),
                "so it should read as good news rather than as a broken view");
    }

    @Test
    void disabledJobsCanBeHidden(JenkinsRule r) throws Exception {
        FreeStyleProject project = r.createFreeStyleProject("off");
        project.disable();

        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        assertEquals(JobStatus.DISABLED, view.getTiles().get(0).status());
        view.setHideDisabled(true);
        assertTrue(view.getTiles().isEmpty());
    }

    @Test
    void foldersDoNotBecomeTilesButTheirJobsDo(JenkinsRule r) throws Exception {
        MockFolder folder = r.createFolder("team");
        folder.createProject(FreeStyleProject.class, "main");

        LiveWallView view = createView(r, "wall");
        view.setRecurse(true);
        view.setIncludeRegex(".*");

        List<Tile> tiles = view.getTiles();
        assertEquals(1, tiles.size(), "the folder itself has no status to show");
        assertEquals("team/main", tiles.get(0).label(), "folders qualify an otherwise ambiguous name");

        view.setShowFolderPath(false);
        assertEquals("main", view.getTiles().get(0).label());
    }

    @Test
    void nameRewritingStripsTheNoiseAndNeverBlanksATile(JenkinsRule r) throws Exception {
        r.createFreeStyleProject("ci-decision-control-pipeline");

        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");
        view.setNameReplaceRegex("^ci-|-pipeline$");

        assertEquals("decision-control", view.getTiles().get(0).label());

        // A pattern that would erase the whole name leaves the job identifiable instead.
        view.setNameReplaceRegex(".*");
        assertEquals("ci-decision-control-pipeline", view.getTiles().get(0).label());
    }

    @Test
    void anInvalidRewritePatternIsIgnoredRatherThanBreakingTheWall(JenkinsRule r) throws Exception {
        r.createFreeStyleProject("job");
        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        view.setNameReplaceRegex("([unclosed");

        assertEquals("job", view.getTiles().get(0).label());
    }

    @Test
    void colourFieldsRejectAnythingThatIsNotAColour(JenkinsRule r) throws Exception {
        LiveWallView view = createView(r, "wall");

        view.setCustomFailure("#ff0055");
        assertEquals("#ff0055", view.getCustomFailure());

        view.setCustomFailure("tomato");
        assertEquals("tomato", view.getCustomFailure());

        // Anything that could escape the attribute and become markup is dropped outright.
        view.setCustomFailure("red\" onload=\"alert(1)");
        assertNull(view.getCustomFailure());

        view.setCustomFailure("url(https://example.invalid/x.png)");
        assertNull(view.getCustomFailure());
    }

    @Test
    void refreshIntervalIsClampedToSomethingAControllerCanSurvive(JenkinsRule r) throws Exception {
        LiveWallView view = createView(r, "wall");

        view.setRefreshSeconds(1);
        assertEquals(LiveWallView.MIN_REFRESH_SECONDS, view.getRefreshSeconds());

        view.setRefreshSeconds(999999);
        assertEquals(LiveWallView.MAX_REFRESH_SECONDS, view.getRefreshSeconds());

        view.setRefreshSeconds(0);
        assertEquals(LiveWallView.DEFAULT_REFRESH_SECONDS, view.getRefreshSeconds());
    }

    @Test
    void unknownOptionIdsFallBackInsteadOfThrowing(JenkinsRule r) throws Exception {
        LiveWallView view = createView(r, "wall");

        view.setPalette("no-such-palette");
        assertSame(Palette.VIVID, view.getPalette());

        // Enum constant names are accepted too, so a hand-edited config.xml still loads.
        view.setPalette("NEON");
        assertSame(Palette.NEON, view.getPalette());
        view.setShape("octagon");
        assertSame(TileShape.OCTAGON, view.getShape());
    }

    @Test
    void aMinimalConfigXmlLoadsWithEveryDefaultFilledIn(JenkinsRule r) throws Exception {
        // XStream does not run field initialisers, so everything a hand-written or scripted
        // config.xml leaves out has to be supplied by readResolve(). scripts/demo.sh creates its
        // view exactly like this.
        r.buildAndAssertSuccess(r.createFreeStyleProject("seeded"));
        String xml = "<io.jenkins.plugins.livewall.LiveWallView>"
                + "<name>Live Wall</name>"
                + "<includeRegex>.*</includeRegex>"
                + "</io.jenkins.plugins.livewall.LiveWallView>";

        View created = View.createViewFromXML("Live Wall", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        r.jenkins.addView(created);

        LiveWallView view = (LiveWallView) r.jenkins.getView("Live Wall");
        assertNotNull(view);
        assertSame(Palette.VIVID, view.getPalette());
        assertSame(TileShape.ROUNDED, view.getShape());
        assertSame(TileAnimation.PROGRESS, view.getAnimation());
        assertSame(Sizing.FIT, view.getSizing());
        assertSame(SortBy.NAME, view.getSortBy());
        assertSame(StatusScope.ALL, view.getStatusScope());
        assertEquals(LiveWallView.DEFAULT_REFRESH_SECONDS, view.getRefreshSeconds());
        assertEquals(LiveWallView.DEFAULT_MIN_TILE_HEIGHT, view.getMinTileHeight());
        assertEquals(List.of("seeded"), labels(view), "and the include regex still works");
    }

    @Test
    void wallDataServesTheTilesAsJson(JenkinsRule r) throws Exception {
        r.buildAndAssertSuccess(r.createFreeStyleProject("shipped"));
        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        JenkinsRule.WebClient client = r.createWebClient();
        Page page = client.goTo("view/wall/wallData", "application/json");
        JSONObject payload = JSONObject.fromObject(page.getWebResponse().getContentAsString());

        assertTrue(payload.getLong("generatedAt") > 0, "the browser corrects its clock from this");
        JSONArray tiles = payload.getJSONArray("tiles");
        assertEquals(1, tiles.size());
        JSONObject tile = tiles.getJSONObject(0);
        assertEquals("shipped", tile.getString("label"));
        assertEquals("shipped", tile.getString("name"));
        assertEquals("success", tile.getString("status"));
        assertEquals("job/shipped/", tile.getString("url"));
        assertFalse(tile.has("building"), "flags are omitted when false to keep the payload small");
    }

    @Test
    void configurationSurvivesARoundTripThroughTheForm(JenkinsRule r) throws Exception {
        LiveWallView view = createView(r, "wall");
        view.setPalette("colorsafe");
        view.setShape("octagon");
        view.setAnimation("stripes");
        view.setSizing("scroll");
        view.setSortBy("status");
        view.setStatusScope("problems");
        view.setRefreshSeconds(12);
        view.setMinTileHeight(140);
        view.setShowBuildNumber(true);
        view.setShowHeader(false);
        view.setShowFolderPath(false);
        view.setHideDisabled(true);
        view.setBurnInProtection(true);
        view.setNameReplaceRegex("^ci-");
        view.setCustomFailure("#ff0055");

        r.submit(r.createWebClient().getPage(view, "configure").getFormByName("viewConfig"));

        LiveWallView reloaded = (LiveWallView) r.jenkins.getView("wall");
        assertNotNull(reloaded);
        assertSame(Palette.COLORSAFE, reloaded.getPalette());
        assertSame(TileShape.OCTAGON, reloaded.getShape());
        assertSame(TileAnimation.STRIPES, reloaded.getAnimation());
        assertSame(Sizing.SCROLL, reloaded.getSizing());
        assertSame(SortBy.STATUS, reloaded.getSortBy());
        assertSame(StatusScope.PROBLEMS, reloaded.getStatusScope());
        assertEquals(12, reloaded.getRefreshSeconds());
        assertEquals(140, reloaded.getMinTileHeight());
        assertTrue(reloaded.isShowBuildNumber());
        assertFalse(reloaded.isShowHeader());
        assertFalse(reloaded.isShowFolderPath());
        assertTrue(reloaded.isHideDisabled());
        assertTrue(reloaded.isBurnInProtection());
        assertEquals("^ci-", reloaded.getNameReplaceRegex());
        assertEquals("#ff0055", reloaded.getCustomFailure());
    }

    @Test
    void bothPagesRenderAndTheKioskPageCarriesNoJenkinsChrome(JenkinsRule r) throws Exception {
        r.createFreeStyleProject("some-job");
        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        // Server-side rendering is what is under test here; the wall's own scripting is not.
        JenkinsRule.WebClient client = r.createWebClient();
        client.setJavaScriptEnabled(false);

        String embedded = client.goTo("view/wall/").getWebResponse().getContentAsString();
        assertTrue(embedded.contains("lw-root--embedded"), "the wall renders inside the view page");
        assertTrue(embedded.contains("data-palette=\"vivid\""));

        String kiosk = client.goTo("view/wall/wall").getWebResponse().getContentAsString();
        assertTrue(kiosk.contains("lw-root--kiosk"));
        assertFalse(kiosk.contains("breadcrumbBar"), "nothing but the wall on the television page");
    }

    private static JobStatus statusOf(List<Tile> tiles, String label) {
        return tiles.stream()
                .filter(tile -> tile.label().equals(label))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private static List<String> labels(LiveWallView view) {
        return view.getTiles().stream().map(Tile::label).collect(Collectors.toList());
    }
}
