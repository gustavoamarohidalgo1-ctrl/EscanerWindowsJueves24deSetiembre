#!/usr/bin/env bash
set -euo pipefail

schema_dir="app/schemas/com.facturastock.app.data.local.FacturaStockDatabase"
requested_base="${1:-}"

if [[ -n "$requested_base" && ! "$requested_base" =~ ^0+$ ]] &&
    git cat-file -e "${requested_base}^{commit}" 2>/dev/null; then
    base_commit="$requested_base"
elif git rev-parse --verify HEAD^ >/dev/null 2>&1; then
    base_commit="$(git rev-parse HEAD^)"
else
    echo "Room schema history: initial commit, no previous tree to compare."
    exit 0
fi

# For pull requests the event gives the base branch tip. Comparing from the merge base avoids
# treating unrelated changes made later on the base branch as changes from this branch. For a
# normal push this is the previous commit itself.
comparison_base="$(git merge-base "$base_commit" HEAD 2>/dev/null || true)"
if [[ -z "$comparison_base" ]]; then
    comparison_base="$base_commit"
fi

base_max=0
while IFS= read -r path; do
    [[ -z "$path" ]] && continue
    filename="${path##*/}"
    version="${filename%.json}"
    if [[ "$filename" == "$version.json" && "$version" =~ ^[0-9]+$ ]] &&
        (( version > base_max )); then
        base_max="$version"
    fi
done < <(git ls-tree -r --name-only "$comparison_base" -- "$schema_dir")

violations=0
added_versions=()
while IFS=$'\t' read -r status first_path second_path; do
    [[ -z "$status" ]] && continue
    if [[ "${status:0:1}" != "A" ]]; then
        echo "ERROR: Room schema history is append-only; $status $first_path${second_path:+ -> $second_path}"
        violations=1
        continue
    fi

    filename="${first_path##*/}"
    version="${filename%.json}"
    if [[ "$filename" != "$version.json" || ! "$version" =~ ^[0-9]+$ ]]; then
        echo "ERROR: new Room schemas must be numeric <version>.json files: $first_path"
        violations=1
        continue
    fi
    added_versions+=("$version")
done < <(git diff --name-status --find-renames "$comparison_base" HEAD -- "$schema_dir")

if (( ${#added_versions[@]} > 0 )); then
    expected_version=$((base_max + 1))
    if (( ${#added_versions[@]} != 1 )) || [[ "${added_versions[0]}" -ne "$expected_version" ]]; then
        echo "ERROR: schema history may add only the next version, ${expected_version}.json; added=${added_versions[*]}"
        violations=1
    fi
fi

if (( violations != 0 )); then
    exit 1
fi

echo "Room schema history is append-only from ${comparison_base}: base=$base_max, added=${added_versions[*]:-none}."
