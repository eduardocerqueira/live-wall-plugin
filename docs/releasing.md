# Releasing

This plugin is released by the Jenkins project's continuous delivery process
([JEP-229](https://github.com/jenkinsci/jep/blob/master/jep/229/README.adoc)). There is no release
branch, no version bump commit, and nobody uploads anything by hand.

> **A release is a side effect of merging a labelled pull request.** That is the whole model. If you
> merge something users should get, label it, and it ships.

## How a release happens

[`cd.yaml`](../.github/workflows/cd.yaml) calls the Jenkins project's shared `maven-cd` workflow.
Three conditions must all hold before it publishes anything:

1. **The `Jenkins` check on the merge commit is green.** Note the name — this is the
   [`Jenkinsfile`](../Jenkinsfile) build on [ci.jenkins.io](https://ci.jenkins.io), *not* the GitHub
   Actions CI in [`ci.yml`](../.github/workflows/ci.yml). The gate
   ([`verify-ci-status-action`](https://github.com/jenkins-infra/verify-ci-status-action)) looks for
   a check run called exactly `Jenkins` and treats anything else as `wrong-check`.
2. **Release Drafter produces a draft** with the changes since the last release, grouped by pull
   request label. The configuration is not in this repository — it is inherited from
   [`jenkinsci/.github`](https://github.com/jenkinsci/.github/blob/master/.github/release-drafter.yml),
   which is why you will not find a `release-drafter.yml` here.
3. **At least one change is in an "interesting" category.** A release that contains only dependency
   bumps, documentation or chores is not worth pushing to every controller in the world, so it is
   skipped. This is decided by matching the draft against the emoji of each category heading.

When all three hold, the release job builds and deploys to Artifactory, tags the commit, and
publishes the draft renamed to the new version.

Because CD is enabled *exclusively* — the default, and
[recommended](https://github.com/jenkins-infra/helpdesk/issues/5280#issuecomment-5575090307) — no
maintainer has Artifactory deploy access. Merging is the only way to release, which is the point:
there is no credential to leak and no laptop that can publish a build nobody reviewed.

## Which labels release, and which do not

This decides whether your merge ships. Get it wrong in the safe direction and nothing happens; get
it wrong in the other direction and a docs typo goes out to every Jenkins in the world.

| Label | Changelog section | Releases? |
| --- | --- | --- |
| `breaking` | 💥 Breaking changes | **yes** |
| `removed` | 🚨 Removed | **yes** |
| `major-enhancement`, `major-rfe` | 🎉 Major features and improvements | **yes** |
| `major-bug` | 🐛 Major bug fixes | **yes** |
| `deprecated` | ⚠️ Deprecated | **yes** |
| `enhancement`, `feature`, `rfe` | 🚀 New features and improvements | **yes** |
| `bug`, `fix`, `bugfix`, `regression`, `regression-fix` | 🐛 Bug fixes | **yes** |
| `localization` | 🌐 Localization and translation | **yes** |
| `developer` | 👷 Changes for plugin developers | **yes** |
| `documentation` | 📝 Documentation updates | no |
| `dependencies` | 📦 Dependency updates | no |
| `chore`, `internal`, `maintenance` | 👻 Maintenance | no |
| `test`, `tests` | 🚦 Tests | no |
| *anything else, or no label at all* | ✍ Other changes | no |

Two consequences worth spelling out, because both have caught this repository already:

- **An unlabelled pull request releases nothing.** It lands under "Other changes", which is not an
  interesting category. Several early pull requests here went in unlabelled and are therefore in
  `main` but in no release.
- **A project-specific label is not enough on its own.** `accessibility` is a useful label for
  finding things in the issue tracker, but it means nothing to the shared configuration above. A
  change that improves accessibility and should ship needs `enhancement` (or `bug`) *as well*.

`dependencies` deliberately does not release: Dependabot applies it automatically, and a bump only
reaches users on the next real change, which is the intent.

## Cutting a release deliberately

Merging is the normal path. To release what is already on `main` — say the last few merges were all
unlabelled and you now want them out — run the **cd** workflow manually from the Actions tab. A
`workflow_dispatch` run skips the interesting-category check entirely, so it publishes whatever is
there, as long as the `Jenkins` check on that commit passed.

Tick **validate_only** to see what *would* be released, with the changelog it would carry, and no
deployment. Worth doing before the first real release.

## Version numbers

Versions are generated, not chosen, and they are **not semver**:

```
13.va_b_f2d971b_72c
│  └── the abbreviated commit hash, encoded
└───── the number of commits
```

This comes from `-Dchangelist.format=%d.v%s` in [`.mvn/maven.config`](../.mvn/maven.config), which
the Jenkins hosting process requires, and it is the standard scheme across the plugin ecosystem —
the `credentials` plugin ships versions like `1371.vfee6b_095f0a_3`. To see the exact version the
next release would carry:

```bash
mvn -Dset.changelist -Dexpression=project.version -q -DforceStdout help:evaluate
```

Ordinary builds are `999999-SNAPSHOT`; the real version is only substituted when `-Dset.changelist`
is passed, which is why you never see it during development.

Three things follow from this, and the first one surprises people:

- **There is nowhere to express intent.** A breaking change and a typo fix produce
  indistinguishable version numbers. The `breaking` label and the changelog are how a user finds
  out, so labelling honestly is the only signal there is.
- **Ordering still works.** Maven compares the leading number, so `13.v…` sorts above the earlier
  `1.0.0` tag and upgrades behave correctly. The numbers only ever climb.
- **`v1.0.0` was hand-cut and is the odd one out.** It predates CD in this repository. Every release
  after it is generated.

## Before any of this works

CD requires two secrets, `MAVEN_USERNAME` and `MAVEN_TOKEN`, which are provisioned by the Jenkins
project when the plugin's hosting request is approved — they are not something a maintainer creates.
It also requires the `Jenkins` check, which only exists once the repository is built by
ci.jenkins.io. Until both are in place the **cd** workflow will run and correctly decline to do
anything.

## A caution about the release build

The release build runs with `-Pquick-build`, which **skips the tests**. It does not re-verify what
it is about to publish; it trusts the `Jenkins` check from step 1.

That is the reason [the bar](quality-bar.md) is written the way it is. By the time CD runs, the
decision to ship has already been made — the tests either passed on the merge commit or they never
ran at all.
