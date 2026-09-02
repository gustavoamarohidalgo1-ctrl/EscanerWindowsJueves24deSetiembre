#!/usr/bin/env bash
set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

destination="${1:-}"
[[ -n "$destination" ]] || fail "indica el archivo temporal de keystore"
[[ -n "${GITHUB_OUTPUT:-}" ]] || fail "este script solo se ejecuta dentro de GitHub Actions"
[[ ! -e "$destination" ]] || fail "el keystore temporal ya existe"

required=(
    FACTURASTOCK_SIGNING_KEYSTORE_BASE64
    FACTURASTOCK_SIGNING_STORE_PASSWORD
    FACTURASTOCK_SIGNING_KEY_ALIAS
    FACTURASTOCK_SIGNING_KEY_PASSWORD
    FACTURASTOCK_FIREBASE_PROJECT_ID
    FACTURASTOCK_FIREBASE_APPLICATION_ID
    FACTURASTOCK_FIREBASE_API_KEY
    FACTURASTOCK_FIREBASE_STORAGE_BUCKET
    FACTURASTOCK_PRIVACY_POLICY_URL
)
missing=()
for variable_name in "${required[@]}"; do
    [[ -n "${!variable_name:-}" ]] || missing+=("$variable_name")
done
(( ${#missing[@]} == 0 )) || fail "faltan valores del environment protegido: ${missing[*]}"

umask 077
mkdir -p "$(dirname "$destination")"
if base64 --help 2>&1 | grep -q -- '--decode'; then
    printf '%s' "$FACTURASTOCK_SIGNING_KEYSTORE_BASE64" | base64 --decode > "$destination"
else
    printf '%s' "$FACTURASTOCK_SIGNING_KEYSTORE_BASE64" | base64 -D > "$destination"
fi
[[ -s "$destination" ]] || fail "el keystore decodificado está vacío"

keytool -list \
    -keystore "$destination" \
    -storepass:env FACTURASTOCK_SIGNING_STORE_PASSWORD \
    -alias "$FACTURASTOCK_SIGNING_KEY_ALIAS" >/dev/null

printf 'keystore_path=%s\n' "$destination" >> "$GITHUB_OUTPUT"
printf 'Keystore temporal validado; no se imprimieron credenciales.\n'
