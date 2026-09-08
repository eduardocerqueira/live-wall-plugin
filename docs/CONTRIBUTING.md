# Contributing

Thanks for looking. This is a small plugin with a narrow purpose — showing Jenkins job status on a
screen nobody is sitting in front of — and that purpose is the main thing to keep in mind when
proposing changes.

## The bar

> **Every pull request must have all tests passing, and zero known vulnerabilities in anything
> this plugin ships.**

Both are enforced by CI and neither is negotiable.

The security scan is deliberately two scans: a blocking one over what the `.hpi` actually contains,
where the bar is zero, and a loud but non-blocking one over Jenkins core and everything it brings
with it, which a plugin does not ship and cannot fix. [quality-bar.md](quality-bar.md) explains why,
and what to do about each.

## Reporting a bug

Use the [bug report template](https://github.com/eduardocerqueira/jenkins-live-wall/issues/new?template=bug_report.yml).
It asks for three things that look irrelevant and almost never are:

- **How many jobs are in the view.** Layout problems are nearly always about the job count.
- **The screen and browser.** A 1366×768 meeting-room panel and a 4K TV lay out completely
  differently, on purpose.
- **The palette, shape, animation and sizing.** Shapes that cut into the tile behave differently
  from rectangles.

A photograph of the actual screen is often more useful than a browser screenshot, because the
problem is usually about how it reads from a distance.

## Suggesting a feature

Use the [feature request template](https://github.com/eduardocerqueira/jenkins-live-wall/issues/new?template=feature_request.yml),
and describe the situation in front of the screen rather than the feature. "I cannot tell which of
our four deploy jobs is the broken one from the kitchen" leads somewhere; "add tooltips" does not.

## Submitting a pull request

1. **Open an issue first** for anything beyond a fix or a new palette, so we can agree on the
   approach before you spend an evening on it.
2. Fork, and branch from `main`.
3. Make the change, with a test. See [building.md](building.md) for the development loop.
4. Run the gates locally:
   ```bash
   mvn clean verify
   mvn spotless:apply     # if formatting failed
   ```
5. **Look at it on a real screen.** Run [`./scripts/demo.sh --jobs 150`](demo.md) and check the
   thing you changed at a realistic job count, then say in the pull request what you checked it on.
   Screenshots are very welcome.
6. Open the pull request. The template has a short checklist; it is the same list as above.
7. CI runs the tests on JDK 21 and 25 and runs the CVE scan. Both must be green.
8. **Label the pull request.** This is not cosmetic: the label decides both which section of the
   changelog the change lands in *and whether a release happens at all*. See
   [releasing.md](releasing.md) for the table, and pick a releasing label if the change is one
   users should get.

## What this plugin is not

Some things are deliberately absent. Pull requests adding them will probably be turned down, so
please open an issue before building one:

- **Pagination.** Everything fits on one screen, or the wall scrolls continuously. Never a page you
  have to wait for. This is the single biggest reason the plugin exists.
- **Per-tile detail** beyond the name and an optional build number. Commit messages, durations,
  culprits and avatars are unreadable at the distance this is designed for, and they cost the space
  the job name needs.
- **Low-contrast themes.** Every built-in palette has to survive a cheap panel, bad lighting and a
  viewer several metres away.

New palettes and new tile shapes are very welcome, as is anything that makes the wall clearer,
faster, or more readable to more people.

## House rules for the code

- **Formatting** is handled by spotless via the Jenkins plugin parent pom. `mvn spotless:apply`.
- **No inline JavaScript in Jelly.** Pass state through `data-` attributes and read it in `wall.js`.
  This is a Jenkins hosting requirement as well as a good idea.
- **Never build tile markup with `innerHTML`.** Job names are user input; there is a `setLabel()`
  helper that does it safely with `textContent` and `document.createElement`.
- **Keep `SHAPE_INSET` in `wall.js` in step with `--lw-text-inset` in `wall.css`.** They describe
  the same thing — how much of a tile each silhouette eats — and labels overflow their shapes when
  they drift apart.
- **Do not add plugin dependencies without a reason.** The plugin currently depends on Jenkins core
  and nothing else, which is a large part of why it installs anywhere and why the CVE gate is
  achievable.
- **Keep `getTiles()` cheap.** It runs once per wall per refresh, across every job in the view. Any
  new field that costs a build load has to be behind the setting that needs it — see how
  `completedAt` is only computed for the "most recently built" ordering.

## Adding a palette

1. Add a constant to `Palette.java` with a kebab-case id and a one-line description of the room it
   is for.
2. Add a `.lw-root[data-palette="<id>"]` block to `wall.css` overriding the custom properties. Only
   override what differs from the defaults at the top of the file.
3. Check it with `./scripts/demo.sh` at a realistic job count, from further away than feels
   necessary.
4. Say in the pull request what lighting and screen you designed it for. That is the useful part.

You do not need to pick text colours — the browser computes those from the contrast ratio.

## Adding a shape

1. Add a constant to `TileShape.java`.
2. Add the `clip-path` (or radius) and a `--lw-text-inset` to `wall.css`.
3. Add the matching `SHAPE_INSET` and `SHAPE_ASPECT` entries in `wall.js`. If the shape only looks
   right when square, add it to `SQUARE_SHAPES` too.
4. Check that a long job name still fits inside it.

## Licence

By contributing you agree that your contribution is licensed under the
[Apache License 2.0](../LICENSE), the same as the rest of the project.
