#!/usr/bin/env bash
set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

event_name="${GITHUB_EVENT_NAME:-}"
current_sha="${GITHUB_SHA:-}"
pull_request_base_sha="${PULL_REQUEST_BASE_SHA:-}"
push_before_sha="${PUSH_BEFORE_SHA:-}"
output_file="${GITHUB_OUTPUT:-}"
zero_sha="0000000000000000000000000000000000000000"

[[ -n "$event_name" ]] || fail "falta GITHUB_EVENT_NAME"
[[ -n "$output_file" ]] || fail "falta GITHUB_OUTPUT"
[[ "$current_sha" =~ ^[0-9a-fA-F]{40}$ && "$current_sha" != "$zero_sha" ]] ||
    fail "GITHUB_SHA no es un commit SHA-1 válido"
git cat-file -e "${current_sha}^{commit}" 2>/dev/null || fail "GITHUB_SHA no existe en el checkout"

case "$event_name" in
    pull_request | pull_request_target)
        candidate_sha="$pull_request_base_sha"
        ;;
    push)
        candidate_sha="$push_before_sha"
        ;;
    schedule | workflow_dispatch)
        candidate_sha="$(git rev-parse --verify HEAD^ 2>/dev/null)" ||
            fail "el evento $event_name necesita al menos un commit padre"
        ;;
    *)
        fail "evento sin política SPOTLESS_BASE_SHA: $event_name"
        ;;
esac

[[ "$candidate_sha" =~ ^[0-9a-fA-F]{40}$ && "$candidate_sha" != "$zero_sha" ]] ||
    fail "el SHA base del evento no es válido o es cero"
git cat-file -e "${candidate_sha}^{commit}" 2>/dev/null ||
    fail "el SHA base no existe; checkout debe usar fetch-depth: 0"

base_sha="$(git merge-base "$candidate_sha" "$current_sha")"
[[ "$base_sha" =~ ^[0-9a-fA-F]{40}$ && "$base_sha" != "$zero_sha" ]] ||
    fail "git merge-base no devolvió un commit válido"
git cat-file -e "${base_sha}^{commit}" 2>/dev/null || fail "merge-base no es un commit"
[[ "$base_sha" != "$current_sha" ]] || fail "el baseline no puede ser el commit actual"

printf 'base_sha=%s\n' "$base_sha" >> "$output_file"
printf 'Baseline Spotless validado mediante merge-base: %.12s\n' "$base_sha"
