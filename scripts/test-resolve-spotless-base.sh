#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/facturastock-spotless-base.XXXXXX")"
trap 'rm -rf "$fixture"' EXIT

git -C "$fixture" init -q
git -C "$fixture" config user.name "Synthetic CI"
git -C "$fixture" config user.email "synthetic-ci@example.invalid"
printf 'base\n' > "$fixture/sample.txt"
git -C "$fixture" add sample.txt
git -C "$fixture" commit -q -m base
base_sha="$(git -C "$fixture" rev-parse HEAD)"
printf 'head\n' >> "$fixture/sample.txt"
git -C "$fixture" commit -q -am head
head_sha="$(git -C "$fixture" rev-parse HEAD)"
head_branch="$(git -C "$fixture" symbolic-ref --short HEAD)"
git -C "$fixture" checkout -q -b synthetic-diverged "$base_sha"
printf 'side\n' > "$fixture/side.txt"
git -C "$fixture" add side.txt
git -C "$fixture" commit -q -m side
side_sha="$(git -C "$fixture" rev-parse HEAD)"
git -C "$fixture" checkout -q "$head_branch"

assert_event() {
    local event_name="$1"
    local pull_base="$2"
    local push_before="$3"
    local output="$fixture/output-$event_name"
    (
        cd "$fixture"
        GITHUB_EVENT_NAME="$event_name" \
        GITHUB_SHA="$head_sha" \
        PULL_REQUEST_BASE_SHA="$pull_base" \
        PUSH_BEFORE_SHA="$push_before" \
        GITHUB_OUTPUT="$output" \
            bash "$root/scripts/resolve-spotless-base.sh" >/dev/null
    )
    grep -qx "base_sha=$base_sha" "$output"
}

assert_event pull_request "$base_sha" ""
assert_event push "" "$base_sha"
assert_event schedule "" ""
assert_event workflow_dispatch "" ""

(
    cd "$fixture"
    GITHUB_EVENT_NAME=pull_request \
    GITHUB_SHA="$head_sha" \
    PULL_REQUEST_BASE_SHA="$side_sha" \
    GITHUB_OUTPUT="$fixture/diverged-output" \
        bash "$root/scripts/resolve-spotless-base.sh" >/dev/null
)
grep -qx "base_sha=$base_sha" "$fixture/diverged-output"

if (
    cd "$fixture"
    GITHUB_EVENT_NAME=push \
    GITHUB_SHA="$head_sha" \
    PUSH_BEFORE_SHA=0000000000000000000000000000000000000000 \
    GITHUB_OUTPUT="$fixture/invalid-output" \
        bash "$root/scripts/resolve-spotless-base.sh" >/dev/null 2>&1
); then
    printf 'ERROR: se aceptó un SHA base cero\n' >&2
    exit 1
fi

if (
    cd "$fixture"
    GITHUB_EVENT_NAME=pull_request \
    GITHUB_SHA="$head_sha" \
    PULL_REQUEST_BASE_SHA="$head_sha" \
    GITHUB_OUTPUT="$fixture/current-output" \
        bash "$root/scripts/resolve-spotless-base.sh" >/dev/null 2>&1
); then
    printf 'ERROR: se aceptó HEAD como baseline\n' >&2
    exit 1
fi

printf 'Spotless base test: eventos, merge-base, SHA cero y baseline actual verificados.\n'
