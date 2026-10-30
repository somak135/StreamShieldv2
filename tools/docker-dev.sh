#!/usr/bin/env bash
# Runs the dev container (see Dockerfile and docs/LOCAL_TESTING.md) with rootless Docker: a
# private Docker daemon running as your user, so no sudo, no docker group and nothing started at
# boot. The daemon runs as a transient systemd user unit that exists only while it is running.
#
#   tools/docker-dev.sh build    build the streamshield-dev image (starts the daemon)
#   tools/docker-dev.sh shell    open a shell in the container; stops the daemon when you leave
#   tools/docker-dev.sh run CMD  run one command in the container, then stop the daemon
#   tools/docker-dev.sh stop     stop the container and the daemon
#   tools/docker-dev.sh purge    stop the daemon and delete everything it stored
#
# Everything lives in ~/.local/share/docker: the image and the named volumes streamshield-gradle
# (Gradle caches) and streamshield-claude (Claude Code login). The repository is bind-mounted.
# Needs the docker-ce-rootless-extras package and pasta or slirp4netns.
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE=streamshield-dev
NAME=streamshield
UNIT=streamshield-dockerd
DATA_ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/docker"
export DOCKER_HOST="unix://${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/docker.sock"

start_daemon() {
    systemctl --user is-active --quiet "$UNIT" && return
    systemd-run --user --quiet --unit="$UNIT" --collect -p Delegate=yes -p KillMode=mixed \
        -E PATH=/usr/bin:/usr/sbin /usr/bin/dockerd-rootless.sh
    for _ in $(seq 60); do
        docker info > /dev/null 2>&1 && return
        sleep 1
    done
    echo "Rootless Docker did not start; see: journalctl --user -u $UNIT" >&2
    exit 1
}

stop_daemon() {
    if systemctl --user is-active --quiet "$UNIT"; then
        systemctl --user stop "$UNIT"
        echo "Docker stopped."
    fi
}

run_container() {
    local tty=(-i)
    [ -t 0 ] && [ -t 1 ] && tty=(-it)
    local kvm=()
    [ -e /dev/kvm ] && kvm=(--device /dev/kvm)
    # Root in a rootless container is your own user on the host, so running as root keeps files
    # written to the mounted repository owned by you (the image's dev user maps to a sub-UID).
    docker run "${tty[@]}" --rm --name "$NAME" "${kvm[@]}" --network host \
        --user root -e HOME=/home/dev -e CLAUDE_CONFIG_DIR=/home/dev/.claude -e IS_SANDBOX=1 \
        -v "$PWD":/workspace \
        -v streamshield-gradle:/home/dev/.gradle \
        -v streamshield-claude:/home/dev/.claude \
        "$IMAGE" "$@"
}

case "${1:-}" in
    build)
        start_daemon
        docker build -t "$IMAGE" "${@:2}" .
        ;;
    shell)
        start_daemon
        status=0
        run_container || status=$?
        [ -z "$(docker ps -q)" ] && stop_daemon
        exit "$status"
        ;;
    run)
        start_daemon
        status=0
        run_container bash -lc "${*:2}" || status=$?
        [ -z "$(docker ps -q)" ] && stop_daemon
        exit "$status"
        ;;
    stop)
        stop_daemon
        ;;
    purge)
        stop_daemon
        # Files in the data root belong to sub-UIDs, so delete them from inside the user namespace.
        [ -d "$DATA_ROOT" ] && rootlesskit rm -rf "$DATA_ROOT"
        echo "Removed $DATA_ROOT."
        ;;
    *)
        sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
        exit 1
        ;;
esac
