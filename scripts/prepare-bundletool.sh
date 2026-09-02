#!/usr/bin/env bash
set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

# Debe actualizarse como una unidad: versión, URL oficial y SHA-256 del artefacto standalone.
bundletool_version="1.18.3"
bundletool_sha256="a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
bundletool_url="https://github.com/google/bundletool/releases/download/${bundletool_version}/bundletool-all-${bundletool_version}.jar"

destination="${1:-}"
[[ -n "$destination" && $# -eq 1 ]] ||
    fail "indica una ruta absoluta para bundletool-all-${bundletool_version}.jar"
[[ "$destination" == /* ]] || fail "la ruta de bundletool debe ser absoluta"
[[ ! -L "$destination" ]] || fail "la ruta de bundletool no puede ser un enlace simbólico"

destination_parent="$(dirname "$destination")"
[[ -d "$destination_parent" ]] || fail "el directorio de destino no existe"
destination_parent="$(cd "$destination_parent" && pwd -P)"
destination="$destination_parent/$(basename "$destination")"

command -v java >/dev/null 2>&1 || fail "Java no está disponible"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

verify_bundletool() {
    local candidate="$1"
    local actual_sha version_output
    [[ -f "$candidate" ]] || fail "bundletool no es un archivo regular"
    actual_sha="$(sha256_of "$candidate")"
    [[ "$actual_sha" == "$bundletool_sha256" ]] ||
        fail "SHA-256 de bundletool no coincide con la versión fijada"
    version_output="$(java -jar "$candidate" version 2>/dev/null)" ||
        fail "bundletool no es un JAR standalone ejecutable"
    [[ "$version_output" == "$bundletool_version" ]] ||
        fail "bundletool devolvió una versión inesperada"
}

if [[ -e "$destination" ]]; then
    verify_bundletool "$destination"
else
    command -v curl >/dev/null 2>&1 || fail "curl no está disponible"
    download_path="$(mktemp "$destination_parent/.bundletool-download.XXXXXX")"
    trap 'rm -f "$download_path"' EXIT
    curl --fail --location --silent --show-error \
        --proto '=https' --tlsv1.2 --retry 3 --retry-connrefused \
        --output "$download_path" "$bundletool_url"
    verify_bundletool "$download_path"
    chmod 0644 "$download_path"
    mv "$download_path" "$destination"
    trap - EXIT
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf 'bundletool_path=%s\n' "$destination" >> "$GITHUB_OUTPUT"
fi
printf 'bundletool %s verificado por SHA-256.\n' "$bundletool_version"
