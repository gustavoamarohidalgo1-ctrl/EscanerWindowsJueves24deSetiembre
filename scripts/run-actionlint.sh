#!/usr/bin/env bash
set -euo pipefail

actionlint_version="1.7.12"
platform=""
archive_sha256=""

case "$(uname -s):$(uname -m)" in
    Linux:x86_64)
        platform="linux_amd64"
        archive_sha256="8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8"
        ;;
    Linux:aarch64 | Linux:arm64)
        platform="linux_arm64"
        archive_sha256="325e971b6ba9bfa504672e29be93c24981eeb1c07576d730e9f7c8805afff0c6"
        ;;
    Darwin:x86_64)
        platform="darwin_amd64"
        archive_sha256="5b44c3bc2255115c9b69e30efc0fecdf498fdb63c5d58e17084fd5f16324c644"
        ;;
    Darwin:arm64)
        platform="darwin_arm64"
        archive_sha256="aba9ced2dee8d27fecca3dc7feb1a7f9a52caefa1eb46f3271ea66b6e0e6953f"
        ;;
    *)
        printf 'ERROR: plataforma no soportada por actionlint: %s/%s\n' "$(uname -s)" "$(uname -m)" >&2
        exit 2
        ;;
esac

archive_name="actionlint_${actionlint_version}_${platform}.tar.gz"
lint_tmp="$(mktemp -d "${TMPDIR:-/tmp}/facturastock-actionlint.XXXXXX")"
trap 'rm -rf "$lint_tmp"' EXIT

curl --fail --location --silent --show-error \
    --proto '=https' \
    --tlsv1.2 \
    "https://github.com/rhysd/actionlint/releases/download/v${actionlint_version}/${archive_name}" \
    --output "$lint_tmp/$archive_name"
printf '%s  %s\n' "$archive_sha256" "$lint_tmp/$archive_name" | shasum -a 256 --check
tar -xzf "$lint_tmp/$archive_name" -C "$lint_tmp" actionlint

workflow_files=()
while IFS= read -r workflow_file; do
    workflow_files+=("$workflow_file")
done < <(find .github/workflows -type f \( -name '*.yml' -o -name '*.yaml' \) -print | sort)

(( ${#workflow_files[@]} > 0 )) || {
    printf 'ERROR: no hay workflows para validar.\n' >&2
    exit 1
}
"$lint_tmp/actionlint" -color "${workflow_files[@]}"
