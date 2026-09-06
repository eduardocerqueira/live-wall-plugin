# Trying it locally in one command

[`scripts/demo.sh`](../scripts/demo.sh) takes you from a clean checkout to a wall full of jobs
without you configuring anything.

```bash
./scripts/demo.sh
```

That is it. About two minutes later it prints a URL to open.

## What it actually does

1. Builds the plugin from source (`mvn -DskipTests clean package`).
2. Pulls the latest Jenkins LTS image (`jenkins/jenkins:lts-jdk21`).
3. Bakes the two into a throwaway image, so Jenkins comes up with Live Wall already installed and
   the setup wizard already out of the way.
4. Starts it on <http://localhost:8080>.
5. Creates sample jobs — **20 by default, up to 200** — with a realistic spread of names and
   statuses, and builds them.
6. Creates a **Live Wall** view containing all of them, and prints the links.

## Usage

```bash
./scripts/demo.sh                       # 20 jobs
./scripts/demo.sh --jobs 150            # 150 jobs, which is where layout gets interesting
./scripts/demo.sh --jobs 200            # the maximum
./scripts/demo.sh --port 9090           # if 8080 is taken
./scripts/demo.sh --no-build            # reuse the .hpi you already built
./scripts/demo.sh --image jenkins/jenkins:latest   # weekly instead of LTS
./scripts/demo.sh --stop                # remove the container and image
```

| Option | Default | |
| --- | --- | --- |
| `--jobs N` | `20` | Sample jobs to create, 1 to 200 |
| `--port PORT` | `8080` | Host port to publish Jenkins on |
| `--image IMAGE` | `jenkins/jenkins:lts-jdk21` | Base Jenkins image |
| `--name NAME` | `live-wall-demo` | Container name |
| `--no-build` | | Skip Maven and reuse `target/live-wall.hpi` |
| `--stop` | | Tear everything down and exit |
| `-h`, `--help` | | Options and examples |

Re-running the script replaces the previous container, so you always get a clean controller. To
iterate on the plugin, change the code and run it again — or use `mvn hpi:run` (see
[building.md](building.md)), which is a faster loop when you are not testing the install path.

## What you get to look at

The script prints these when it finishes:

| | |
| --- | --- |
| `http://localhost:8080/view/Live%20Wall/wall` | The kiosk page. No Jenkins chrome. This is what a TV should point at. |
| `http://localhost:8080/view/Live%20Wall/` | The same wall inside Jenkins, with the palette and shape preview bar above it. |
| `http://localhost:8080/` | Jenkins itself, if you want to poke at the jobs. |

Try a look without saving it by putting it in the URL:

```
http://localhost:8080/view/Live%20Wall/wall?palette=neon&shape=octagon
http://localhost:8080/view/Live%20Wall/wall?palette=colorsafe&shape=hexagon&animation=stripes
http://localhost:8080/view/Live%20Wall/wall?palette=daylight&shape=cross&sizing=scroll
```

The full list of parameters is in [configuration.md](configuration.md#url-parameters).

## The sample jobs

**Names** are half the point of the test. A wall is easy to lay out when everything is called
`build`; it is hard when half the jobs are called
`ci-decision-control-tower-sidecar-pipeline`. So the script generates both:

- short single-word names — `drools`, `horizon`, `quarkus`, `vault`
- long hyphenated ones of three to six words — `infra-sync-registry-mirror-nightly`

**Statuses** are spread to look like a healthy but real controller, roughly:

| Status | Share | How the script produces it |
| --- | --- | --- |
| Success | ~55% | `exit 0` |
| Failure | ~15% | `exit 1` |
| Unstable | ~10% | An exit code registered as the shell step's unstable return |
| Aborted | ~7% | Starts a long build, then cancels it |
| Never built | ~7% | Created and left alone |
| Disabled | ~6% | Created, then disabled |

On top of that, **a few jobs rebuild on a timer** (every three minutes, one in ten of the total,
between two and eight of them). They sleep for a random 20–70 seconds and then randomly pass, fail
or go unstable. That is what gives you the in-progress animation and a wall whose colours change
while you watch it, rather than a static screenshot.

> **Progress bars need a finished build to estimate from.** The first time a job runs, the wall
> shows indeterminate stripes, because Jenkins has nothing to estimate the duration from yet. From
> the second build onwards the bar fills properly. The timer jobs get there on their own after a
> few minutes.

Everything is a plain freestyle job. The base Jenkins image ships with no plugins at all, and core
on its own can produce every status the wall knows about, so the script installs nothing else and
starts fast.

## Security

The demo controller runs **with no security whatsoever** — no setup wizard, no login, anonymous
users have full permissions. That is deliberate: it is what lets the script create two hundred jobs
without you first generating an API token, and it makes the whole thing disposable.

It also means:

- **Do not publish the port.** Keep it on `localhost`. Do not run this on a shared host, and do not
  put it behind a tunnel.
- **Do not point it at anything real.** It is a scratch pad.
- Delete it when you are done: `./scripts/demo.sh --stop`.

## Requirements

- **Docker or Podman**, running.
- **bash 4 or newer.** macOS still ships bash 3.2 as `/bin/bash`; `brew install bash` fixes it. The
  script checks and tells you.
- **Maven and a JDK**, unless you pass `--no-build`.
- **curl.**

## When something goes wrong

| | |
| --- | --- |
| `neither docker nor podman is on your PATH` | Install one, or start Docker Desktop. |
| `Jenkins did not come up` | The script prints the last 40 lines of the container log. Usually a port clash — try `--port 9090`. |
| `the live-wall plugin is not loaded` | Check `docker logs live-wall-demo` for the plugin failing to start; almost always means the `.hpi` was built against a newer Jenkins than the image. |
| `could not create the view automatically` | Harmless. Create it by hand: **New View → Live Wall → tick some jobs**. |
| Everything is grey | Builds are still running. Give it a minute. |

Files involved, if you want to change how the demo works:

| | |
| --- | --- |
| [`scripts/demo.sh`](../scripts/demo.sh) | The script itself |
| [`scripts/demo.Dockerfile`](../scripts/demo.Dockerfile) | Jenkins image plus the built plugin |
| [`scripts/demo-init/`](../scripts/demo-init/) | Groovy hooks run on the demo controller's first start |
