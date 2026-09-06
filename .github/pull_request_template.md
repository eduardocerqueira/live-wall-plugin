## What this changes

<!-- One or two sentences. What is different for someone standing in front of the wall? -->

## Why

<!-- Link the issue if there is one: Fixes #123 -->

## The bar (see docs/quality-bar.md)

- [ ] `mvn clean verify` passes locally, and every test passes in CI
- [ ] The CVE scan reports **zero** known vulnerabilities
- [ ] New behaviour is covered by a test
- [ ] No inline JavaScript in Jelly, and no `innerHTML` anywhere near a job name
- [ ] `SHAPE_INSET` in `wall.js` still matches `--lw-text-inset` in `wall.css` (only if you touched shapes)
- [ ] Docs updated if a setting was added, removed or renamed

## Checked on a real screen

<!-- Which browser, which screen size, roughly how many jobs. Layout problems are almost always
     about the job count. Screenshots are very welcome. -->
