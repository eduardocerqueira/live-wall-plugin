#!/usr/bin/env bash
#
# Live Wall — one command from a clean checkout to a wall full of jobs.
#
#   ./scripts/demo.sh              # 20 sample jobs
#   ./scripts/demo.sh --jobs 150   # 150 of them
#   ./scripts/demo.sh --stop       # tear it all down again
#
# It pulls the latest Jenkins LTS image, builds this plugin from source, bakes the two together
# into a throwaway image, starts it, and fills it with jobs whose names and statuses look like a
# real controller's — short names and long ones, mostly green with enough red to be worth looking
# at, plus a handful that keep rebuilding so the wall never sits still.
#
# The controller it starts has no security at all, because the alternative is making you paste an
# API token before you can look at anything. Keep it on localhost. See docs/demo.md.

set -euo pipefail

if [ "${BASH_VERSINFO[0]:-0}" -lt 4 ]; then
    echo "error: this script needs bash 4 or newer." >&2
    echo "       macOS still ships bash 3.2; 'brew install bash' then re-run, or run it as" >&2
    echo "       'bash ./scripts/demo.sh' with a newer bash on your PATH." >&2
    exit 1
fi

# ------------------------------------------------------------------ defaults

JOBS=20
PORT=8080
IMAGE="jenkins/jenkins:lts-jdk21"
CONTAINER="live-wall-demo"
TAG="live-wall-demo:latest"
BUILD_PLUGIN=1
STOP_ONLY=0

MAX_JOBS=200
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ------------------------------------------------------------------ pretty output

if [ -t 1 ]; then
    BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'
else
    BOLD=""; DIM=""; RED=""; GREEN=""; YELLOW=""; RESET=""
fi

step() { printf '\n%s==>%s %s%s%s\n' "$GREEN" "$RESET" "$BOLD" "$*" "$RESET"; }
info() { printf '    %s\n' "$*"; }
note() { printf '    %s%s%s\n' "$DIM" "$*" "$RESET"; }
warn() { printf '    %s! %s%s\n' "$YELLOW" "$*" "$RESET"; }
die()  { printf '\n%serror:%s %s\n' "$RED" "$RESET" "$*" >&2; exit 1; }

usage() {
    # The header comment at the top of this file, minus the shebang and the leading hashes.
    awk 'NR > 1 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    cat <<EOF

Options:
  --jobs N        Sample jobs to create. Default ${JOBS}, maximum ${MAX_JOBS}.
  --port PORT     Host port to publish Jenkins on. Default ${PORT}.
  --image IMAGE   Base Jenkins image. Default ${IMAGE}.
  --name NAME     Container name. Default ${CONTAINER}.
  --no-build      Reuse target/live-wall.hpi instead of running Maven.
  --stop          Stop and remove the demo container and image, then exit.
  -h, --help      This.
EOF
}

# ------------------------------------------------------------------ arguments

while [ $# -gt 0 ]; do
    case "$1" in
        --jobs)     JOBS="${2:-}"; shift 2 ;;
        --port)     PORT="${2:-}"; shift 2 ;;
        --image)    IMAGE="${2:-}"; shift 2 ;;
        --name)     CONTAINER="${2:-}"; shift 2 ;;
        --no-build) BUILD_PLUGIN=0; shift ;;
        --stop)     STOP_ONLY=1; shift ;;
        -h|--help)  usage; exit 0 ;;
        *)          die "unknown option: $1 (try --help)" ;;
    esac
done

case "$JOBS" in
    ''|*[!0-9]*) die "--jobs needs a number, got '$JOBS'" ;;
esac
[ "$JOBS" -ge 1 ] || die "--jobs must be at least 1"
[ "$JOBS" -le "$MAX_JOBS" ] || die "--jobs must be at most $MAX_JOBS (asked for $JOBS)"

# ------------------------------------------------------------------ container runtime

if command -v docker >/dev/null 2>&1; then
    RUNTIME=docker
elif command -v podman >/dev/null 2>&1; then
    RUNTIME=podman
else
    die "neither docker nor podman is on your PATH"
fi

$RUNTIME info >/dev/null 2>&1 || die "$RUNTIME is installed but not running"

BASE="http://localhost:${PORT}"

teardown() {
    step "Tearing down"
    $RUNTIME rm -f "$CONTAINER" >/dev/null 2>&1 && info "removed container $CONTAINER" || note "no container named $CONTAINER"
    $RUNTIME rmi -f "$TAG" >/dev/null 2>&1 && info "removed image $TAG" || note "no image $TAG"
    info "done"
}

if [ "$STOP_ONLY" = "1" ]; then
    teardown
    exit 0
fi

# ------------------------------------------------------------------ build the plugin

if [ "$BUILD_PLUGIN" = "1" ]; then
    step "Building the plugin"
    command -v mvn >/dev/null 2>&1 || die "maven is not on your PATH (or pass --no-build)"

    # Honour the repository's own settings file when the caller's global settings would get in the
    # way; see docs/building.md.
    MVN_ARGS=(-B -ntp -DskipTests clean package)
    if grep -q 'external:\*' "${HOME}/.m2/settings.xml" 2>/dev/null; then
        note "your ~/.m2/settings.xml mirrors external:*, using .mvn/settings.xml"
        MVN_ARGS=(-s "${REPO_ROOT}/.mvn/settings.xml" "${MVN_ARGS[@]}")
    fi
    (cd "$REPO_ROOT" && mvn "${MVN_ARGS[@]}")
fi

HPI="${REPO_ROOT}/target/live-wall.hpi"
[ -f "$HPI" ] || die "no plugin at target/live-wall.hpi — run without --no-build"
info "plugin: $HPI ($(du -h "$HPI" | cut -f1))"

# ------------------------------------------------------------------ start jenkins

step "Pulling $IMAGE"
$RUNTIME pull "$IMAGE"

step "Baking the plugin into a throwaway image"
CONTEXT="$(mktemp -d)"
trap 'rm -rf "$CONTEXT"' EXIT
cp "$HPI" "$CONTEXT/live-wall.hpi"
cp -R "${REPO_ROOT}/scripts/demo-init" "$CONTEXT/init.groovy.d"
cp "${REPO_ROOT}/scripts/demo.Dockerfile" "$CONTEXT/Dockerfile"
$RUNTIME build --build-arg "JENKINS_IMAGE=${IMAGE}" -t "$TAG" "$CONTEXT" >/dev/null
info "built $TAG"

step "Starting Jenkins on $BASE"
$RUNTIME rm -f "$CONTAINER" >/dev/null 2>&1 || true
$RUNTIME run -d --name "$CONTAINER" -p "${PORT}:8080" "$TAG" >/dev/null

printf '    waiting for Jenkins'
for _ in $(seq 1 180); do
    if curl -sf -o /dev/null "${BASE}/login"; then
        printf ' up\n'
        break
    fi
    printf '.'
    sleep 2
done
curl -sf -o /dev/null "${BASE}/login" || {
    printf '\n'
    $RUNTIME logs --tail 40 "$CONTAINER" >&2
    die "Jenkins did not come up on $BASE"
}

VERSION="$(curl -s "${BASE}/pluginManager/api/xml?depth=1&xpath=//plugin%5BshortName=%27live-wall%27%5D/version/text()" || true)"
[ -n "$VERSION" ] || die "the live-wall plugin is not loaded; try $RUNTIME logs $CONTAINER"
info "Live Wall $VERSION is installed"

# ------------------------------------------------------------------ jenkins api helpers

# CSRF is on even with security off, so every POST carries a crumb.
CRUMB="$(curl -s "${BASE}/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,%22:%22,//crumb)")"
[ -n "$CRUMB" ] || die "could not fetch a CSRF crumb from $BASE"

post() { # post <path> [curl args...]
    local path="$1"; shift
    curl -s -o /dev/null -w '%{http_code}' -X POST -H "$CRUMB" "${BASE}${path}" "$@"
}

create_job() { # create_job <name> <config.xml on stdin>
    local name="$1" code
    code="$(post "/createItem?name=${name}" -H 'Content-Type: application/xml' --data-binary @-)"
    [ "$code" = "200" ] || warn "creating $name returned HTTP $code"
}

# Freestyle jobs only, because the base Jenkins image ships no plugins and core alone can produce
# every status the wall knows about — including unstable, via the shell step's unstable exit code.
job_config() { # job_config <command> <unstable-exit-code|-> <timer-spec|->
    local command="$1" unstable="$2" timer="$3"
    printf '%s' "<?xml version='1.1' encoding='UTF-8'?><project><description>Sample job created by scripts/demo.sh</description><keepDependencies>false</keepDependencies><properties/><scm class='hudson.scm.NullSCM'/><disabled>false</disabled><blockBuildWhenDownstreamBuilding>false</blockBuildWhenDownstreamBuilding><blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding><triggers>"
    [ "$timer" = "-" ] || printf '<hudson.triggers.TimerTrigger><spec>%s</spec></hudson.triggers.TimerTrigger>' "$timer"
    printf '</triggers><concurrentBuild>false</concurrentBuild><builders><hudson.tasks.Shell><command>%s</command>' "$command"
    [ "$unstable" = "-" ] || printf '<unstableReturn>%s</unstableReturn>' "$unstable"
    printf '%s' "</hudson.tasks.Shell></builders><publishers/><buildWrappers/></project>"
}

wait_for_build_start() { # wait_for_build_start <name>
    local name="$1"
    for _ in $(seq 1 60); do
        if [ "$(curl -s "${BASE}/job/${name}/lastBuild/api/xml?xpath=/*/building" || true)" = "<building>true</building>" ]; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# ------------------------------------------------------------------ sample names

SHORT_WORDS=(drools horizon kogito quarkus nix vault atlas pulse forge grid nova relay edge mesh beacon anvil harbor prism quill ember)
LONG_WORDS=(ci cd infra platform decision control tower sidecar release scan sync mirror agent registry dashboard integration nightly canary staging pipeline builder deploy gateway service worker bundle snapshot upstream downstream)

declare -A USED_NAMES=()

random_name() {
    local name parts count i
    while :; do
        if [ $((RANDOM % 5)) -lt 2 ]; then
            # Short: the single-word jobs every Jenkins has.
            name="${SHORT_WORDS[$((RANDOM % ${#SHORT_WORDS[@]}))]}"
        else
            # Long: the ci-decision-control-tower-sidecar-pipeline kind, which is what makes a wall
            # hard to lay out and therefore what it needs to be tested against.
            count=$((3 + RANDOM % 4))
            parts=""
            for ((i = 0; i < count; i++)); do
                parts="${parts}${parts:+-}${LONG_WORDS[$((RANDOM % ${#LONG_WORDS[@]}))]}"
            done
            name="$parts"
        fi
        if [ -z "${USED_NAMES[$name]:-}" ]; then
            USED_NAMES[$name]=1
            printf '%s' "$name"
            return
        fi
        # Collided: fall through and try again, adding a suffix after a few attempts.
        name="${name}-$((RANDOM % 900 + 100))"
        if [ -z "${USED_NAMES[$name]:-}" ]; then
            USED_NAMES[$name]=1
            printf '%s' "$name"
            return
        fi
    done
}

# ------------------------------------------------------------------ seed the jobs

# Roughly what a healthy-but-real controller looks like: mostly green, some red, a bit of
# everything else. "live" jobs rebuild on a timer so the wall keeps moving while you look at it.
LIVE=$(( JOBS / 10 ))
if [ "$LIVE" -lt 2 ]; then LIVE=2; fi
if [ "$LIVE" -gt 8 ]; then LIVE=8; fi
if [ "$LIVE" -gt "$JOBS" ]; then LIVE=$JOBS; fi
STATIC=$(( JOBS - LIVE ))

LIVE_JOB_SCRIPT='#!/bin/bash
sleep $((20 + RANDOM % 50))
case $((RANDOM % 5)) in
  0) exit 1 ;;
  1) exit 3 ;;
  *) exit 0 ;;
esac'

step "Creating $JOBS sample jobs"
info "$STATIC with a fixed status, $LIVE rebuilding on a timer"

declare -a ABORT_JOBS=()
declare -a BUILD_JOBS=()
declare -a DISABLE_JOBS=()

created=0
for ((n = 0; n < STATIC; n++)); do
    name="$(random_name)"
    roll=$((RANDOM % 100))

    if   [ "$roll" -lt 55 ]; then status=success
    elif [ "$roll" -lt 70 ]; then status=failure
    elif [ "$roll" -lt 80 ]; then status=unstable
    elif [ "$roll" -lt 87 ]; then status=aborted
    elif [ "$roll" -lt 94 ]; then status=notbuilt
    else                          status=disabled
    fi

    case "$status" in
        success)  job_config "echo built ok; exit 0" - -            | create_job "$name"; BUILD_JOBS+=("$name") ;;
        failure)  job_config "echo something broke; exit 1" - -     | create_job "$name"; BUILD_JOBS+=("$name") ;;
        unstable) job_config "echo tests failed; exit 3" 3 -        | create_job "$name"; BUILD_JOBS+=("$name") ;;
        aborted)  job_config "sleep 300" - -                        | create_job "$name"; ABORT_JOBS+=("$name") ;;
        notbuilt) job_config "exit 0" - -                           | create_job "$name" ;;
        disabled) job_config "exit 0" - -                           | create_job "$name"; DISABLE_JOBS+=("$name") ;;
    esac

    created=$((created + 1))
    printf '\r    created %d/%d' "$created" "$JOBS"
done

for ((n = 0; n < LIVE; n++)); do
    name="$(random_name)"
    # A random walk between green, red and unstable, so the wall changes colour on its own.
    # The shebang matters: Jenkins runs a shell step with /bin/sh, where RANDOM is just an unset
    # variable that evaluates to zero, and every one of these jobs would take the same branch.
    job_config "$LIVE_JOB_SCRIPT" 3 "H/3 * * * *" | create_job "$name"
    BUILD_JOBS+=("$name")
    created=$((created + 1))
    printf '\r    created %d/%d' "$created" "$JOBS"
done
printf '\n'

step "Building them"
for name in "${BUILD_JOBS[@]}"; do
    post "/job/${name}/build" >/dev/null
done
info "queued ${#BUILD_JOBS[@]} builds"

for name in "${DISABLE_JOBS[@]}"; do
    post "/job/${name}/disable" >/dev/null
done
[ "${#DISABLE_JOBS[@]}" -eq 0 ] || info "disabled ${#DISABLE_JOBS[@]} jobs"

if [ "${#ABORT_JOBS[@]}" -gt 0 ]; then
    info "starting and then cancelling ${#ABORT_JOBS[@]} builds, for the aborted colour"
    for name in "${ABORT_JOBS[@]}"; do
        post "/job/${name}/build" >/dev/null
    done
    for name in "${ABORT_JOBS[@]}"; do
        if wait_for_build_start "$name"; then
            post "/job/${name}/lastBuild/stop" >/dev/null
        else
            warn "$name never started, leaving it unbuilt"
        fi
    done
fi

# ------------------------------------------------------------------ create the wall

step "Creating the Live Wall view"
# XStream fills in only what is here; LiveWallView.readResolve() supplies every default, which is
# what makes a two-line config.xml legal.
VIEW_XML="<io.jenkins.plugins.livewall.LiveWallView><name>Live Wall</name><includeRegex>.*</includeRegex></io.jenkins.plugins.livewall.LiveWallView>"
VIEW_CODE="$(printf '%s' "$VIEW_XML" | post "/createView?name=Live%20Wall" -H 'Content-Type: application/xml' --data-binary @-)"

if [ "$VIEW_CODE" = "200" ]; then
    info "created"
else
    warn "could not create the view automatically (HTTP $VIEW_CODE)"
    warn "make one by hand: New View -> Live Wall -> tick some jobs"
fi

# ------------------------------------------------------------------ done

cat <<EOF

${GREEN}${BOLD}Ready.${RESET}

  Wall (full screen, what a TV should point at)
      ${BOLD}${BASE}/view/Live%20Wall/wall${RESET}

  Wall inside Jenkins, with the palette and shape preview bar
      ${BASE}/view/Live%20Wall/

  Try a look without saving it
      ${BASE}/view/Live%20Wall/wall?palette=neon&shape=octagon
      ${BASE}/view/Live%20Wall/wall?palette=colorsafe&shape=hexagon&animation=stripes

  Jenkins itself
      ${BASE}/

${DIM}Builds are still finishing, so give it a minute before judging the colours.
The timer-driven jobs rebuild every few minutes, so the wall keeps moving.
Progress bars are indeterminate stripes until a job has one finished build to
estimate from, then they fill properly.

Stop and remove everything:  ./scripts/demo.sh --stop${RESET}
EOF
