#!/usr/bin/env bash
# Compila localRelease (R8, no depurable, perfil de arranque) y lo firma con la clave debug local
# para actualizar en sitio una tablet que hoy ejecuta localDebug SIN perder datos: mismo paquete,
# misma firma y mismo versionCode. Nunca se distribuye: es el único artefacto instalable de la tienda.
#
# Uso: scripts/build-tablet-optimized-apk.sh
# Salida: app/build/outputs/tablet/app-local-release-debugsigned.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")}}"
BUILD_TOOLS="$SDK_DIR/build-tools/36.0.0"
KEYSTORE="${FACTURASTOCK_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
UNSIGNED="$ROOT/app/build/outputs/apk/local/release/app-local-release-unsigned.apk"
DEBUG_APK="$ROOT/app/build/outputs/apk/local/debug/app-local-debug.apk"
OUT_DIR="$ROOT/app/build/outputs/tablet"
ALIGNED="$OUT_DIR/app-local-release-aligned.apk"
SIGNED="$OUT_DIR/app-local-release-debugsigned.apk"

[[ -x "$BUILD_TOOLS/apksigner" && -x "$BUILD_TOOLS/zipalign" ]] || {
  echo "Faltan build-tools 36.0.0 en $BUILD_TOOLS" >&2
  exit 1
}
[[ -r "$KEYSTORE" ]] || {
  echo "No existe la clave debug $KEYSTORE; sin ella la tablet rechazaría la actualización" >&2
  exit 1
}

cd "$ROOT"
./gradlew --quiet :app:assembleLocalRelease :app:assembleLocalDebug

mkdir -p "$OUT_DIR"
rm -f "$ALIGNED" "$SIGNED"
# -P 16 conserva las bibliotecas nativas alineadas a páginas de 16 KB.
"$BUILD_TOOLS/zipalign" -P 16 -f 4 "$UNSIGNED" "$ALIGNED"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  --out "$SIGNED" "$ALIGNED"
rm -f "$ALIGNED" "$SIGNED.idsig"

cert_of() {
  "$BUILD_TOOLS/apksigner" verify --print-certs "$1" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
}
release_cert="$(cert_of "$SIGNED")"
debug_cert="$(cert_of "$DEBUG_APK")"
if [[ -z "$release_cert" || "$release_cert" != "$debug_cert" ]]; then
  echo "La firma no coincide con localDebug; instalarla exigiría desinstalar y borraría datos" >&2
  exit 1
fi

echo "APK optimizado: $SIGNED"
echo "Firma SHA-256 (igual a localDebug): $release_cert"
echo "Instalar conservando datos: adb -s <serial> install -r \"$SIGNED\""
# adb install deja la app sin compilar hasta el dexopt nocturno; compilarla completa redujo a la
# mitad la CPU del recorrido diario en el emulador (22,3 s sin compilar → 12,0 s con speed).
echo "Luego compilar por completo: adb -s <serial> shell cmd package compile -m speed -f com.facturastock.app"
