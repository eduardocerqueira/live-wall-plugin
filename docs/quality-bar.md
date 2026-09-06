# The bar

Short version, and it is not negotiable for anything merged into `main`:

> **Every pull request must have all tests passing and a CVE scan reporting zero known
> vulnerabilities.**

Both are enforced automatically. A pull request that fails either one does not merge.

| Gate | Workflow | Runs on | Passing means |
| --- | --- | --- | --- |
| Tests | [`ci.yml`](../.github/workflows/ci.yml) | Every PR, every push to `main`, JDK 17 and 21 | `mvn clean verify` is green on both JDKs — unit tests, the Jenkins harness tests, Jelly validation, SpotBugs and formatting |
| Security | [`cve-scan.yml`](../.github/workflows/cve-scan.yml) | Every PR, every push to `main`, **and every Sunday at 06:00 UTC** | Trivy finds **zero** known vulnerabilities across the resolved dependency tree, at every severity from `UNKNOWN` to `CRITICAL` |

## Why the scan also runs on a schedule

The code stops changing; the vulnerability database does not. A dependency that was clean when a
release was cut can be a published CVE three weeks later, with nobody touching the repository. The
Sunday run catches that, and opens (or comments on) an issue labelled `security` so a red workflow
cannot quietly scroll past.

## Zero, and what to do when zero is not reachable

Zero is the target because the alternative — "no *high* severity vulnerabilities" — is a number
that only ever goes up, and nobody notices when it does.

Almost everything this plugin depends on comes from Jenkins core, which means a finding is usually
fixed by moving the baseline in `pom.xml` rather than by changing any code here. Do that first.

When there is genuinely no fixed version yet, add an entry to [`.trivyignore`](../.trivyignore).
An entry is only acceptable when all three of these are true, and it has to say so:

1. The vulnerability is not reachable from this plugin, or upstream has no fix available.
2. There is a link to the upstream issue tracking the fix.
3. There is an expiry date, so the exception gets revisited instead of forgotten.

```
CVE-2026-12345 exp:2026-12-01
# why: only reachable through the XML parser, which this plugin never calls
# upstream: https://github.com/example/lib/issues/999
```

An expired entry fails the scan again, which is the point.

## Running both gates before you push

```bash
mvn clean verify          # the test gate

# the security gate, with Trivy installed locally
mvn -DskipTests package
mvn dependency:copy-dependencies -DincludeScope=test -DoutputDirectory=target/scan
trivy fs --scanners vuln --severity UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL --exit-code 1 .
```
