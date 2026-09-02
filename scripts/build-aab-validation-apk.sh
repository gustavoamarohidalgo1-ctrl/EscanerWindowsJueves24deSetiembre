#!/usr/bin/env bash
set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
bundletool_path="${1:-}"
aab_path="${2:-}"
universal_apk_path="${3:-}"
[[ $# -eq 3 ]] ||
    fail "uso: build-aab-validation-apk.sh <bundletool-all.jar> <AAB> <APK-universal-nuevo>"
[[ -f "$bundletool_path" ]] || fail "no existe bundletool standalone"
[[ -f "$aab_path" ]] || fail "no existe el AAB firmado"
[[ -n "$universal_apk_path" && ! -e "$universal_apk_path" && ! -L "$universal_apk_path" ]] ||
    fail "el APK universal debe usar una ruta nueva que no sea enlace simbólico"

required_signing_environment=(
    FACTURASTOCK_SIGNING_STORE_FILE
    FACTURASTOCK_SIGNING_STORE_PASSWORD
    FACTURASTOCK_SIGNING_KEY_ALIAS
    FACTURASTOCK_SIGNING_KEY_PASSWORD
)
missing=()
for variable_name in "${required_signing_environment[@]}"; do
    [[ -n "${!variable_name:-}" ]] || missing+=("$variable_name")
done
(( ${#missing[@]} == 0 )) ||
    fail "faltan variables de firma para derivar el APK universal: ${missing[*]}"
[[ -f "$FACTURASTOCK_SIGNING_STORE_FILE" ]] || fail "el keystore externo no existe"

command -v java >/dev/null 2>&1 || fail "Java no está disponible"
command -v unzip >/dev/null 2>&1 || fail "unzip no está disponible"
GITHUB_OUTPUT="" bash "$script_dir/prepare-bundletool.sh" "$bundletool_path" >/dev/null

working_dir="$(mktemp -d "${TMPDIR:-/tmp}/facturastock-bundletool.XXXXXX")"
cleanup() {
    if [[ -n "${working_dir:-}" && -d "$working_dir" ]]; then
        find "$working_dir" -depth -delete
    fi
}
trap cleanup EXIT
umask 077

store_password_path="$working_dir/store-password.txt"
key_password_path="$working_dir/key-password.txt"
apks_path="$working_dir/release.apks"
extracted_apk_path="$working_dir/universal.apk"
printf '%s' "$FACTURASTOCK_SIGNING_STORE_PASSWORD" > "$store_password_path"
printf '%s' "$FACTURASTOCK_SIGNING_KEY_PASSWORD" > "$key_password_path"

java -jar "$bundletool_path" validate --bundle="$aab_path" >/dev/null
java -jar "$bundletool_path" build-apks \
    --bundle="$aab_path" \
    --output="$apks_path" \
    --mode=universal \
    --ks="$FACTURASTOCK_SIGNING_STORE_FILE" \
    --ks-key-alias="$FACTURASTOCK_SIGNING_KEY_ALIAS" \
    "--ks-pass=file:$store_password_path" \
    "--key-pass=file:$key_password_path" >/dev/null

apks_entries="$(unzip -Z1 "$apks_path")"
grep -Fxq 'universal.apk' <<< "$apks_entries" ||
    fail "bundletool no produjo universal.apk"
unzip -p "$apks_path" universal.apk > "$extracted_apk_path"
[[ -s "$extracted_apk_path" ]] || fail "el APK universal derivado está vacío"

mkdir -p "$(dirname "$universal_apk_path")"
mv "$extracted_apk_path" "$universal_apk_path"
chmod 0644 "$universal_apk_path"
printf 'AAB validado; APK universal derivado sin publicar.\n'
