#!/usr/bin/env bash
set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
project_root="$(cd "$script_dir/.." && pwd -P)"

aab_path="${1:-}"
apk_path="${2:-}"
checksums_path="${3:-}"
[[ $# -eq 3 ]] || fail "uso: verify-release-artifacts.sh <AAB> <APK-universal> <checksums-nuevo>"
[[ -f "$aab_path" ]] || fail "no existe el AAB firmado: $aab_path"
[[ -f "$apk_path" ]] || fail "no existe el APK universal firmado: $apk_path"
[[ -n "$checksums_path" && ! -e "$checksums_path" && ! -L "$checksums_path" ]] ||
    fail "indica un archivo de checksums nuevo que no sea enlace simbólico"
[[ "$aab_path" != *-unsigned.* && "$apk_path" != *-unsigned.* ]] ||
    fail "un artefacto publicable conserva el sufijo unsigned"

expected_version_code="${FACTURASTOCK_VERSION_CODE:-}"
expected_version_name="${FACTURASTOCK_VERSION_NAME:-}"
expected_fingerprint_raw="${FACTURASTOCK_EXPECTED_UPLOAD_CERT_SHA256:-}"
[[ "$expected_version_code" =~ ^[1-9][0-9]{0,9}$ ]] ||
    fail "FACTURASTOCK_VERSION_CODE debe estar presente y ser decimal positivo"
[[ -n "$expected_version_name" ]] || fail "FACTURASTOCK_VERSION_NAME es obligatorio"
[[ -n "$expected_fingerprint_raw" ]] ||
    fail "FACTURASTOCK_EXPECTED_UPLOAD_CERT_SHA256 es obligatorio"

normalize_fingerprint() {
    tr -d '[:space:]:' | tr '[:lower:]' '[:upper:]'
}

expected_fingerprint="$(printf '%s' "$expected_fingerprint_raw" | normalize_fingerprint)"
[[ "$expected_fingerprint" =~ ^[0-9A-F]{64}$ ]] ||
    fail "la huella SHA-256 de carga debe contener exactamente 64 dígitos hexadecimales"

for command_name in keytool jarsigner openssl ruby unzip grep sed awk diff; do
    command -v "$command_name" >/dev/null 2>&1 || fail "$command_name no está disponible"
done

temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/facturastock-release-verification.XXXXXX")"
cleanup() {
    if [[ -n "${temporary_dir:-}" && -d "$temporary_dir" ]]; then
        find "$temporary_dir" -depth -delete
    fi
}
trap cleanup EXIT

# jarsigner y keytool pueden terminar con código cero ante un JAR sin firma. Exigimos un único
# certificado, lo anclamos a la huella de carga registrada y luego verificamos toda la firma JAR.
if ! certificate_output="$(keytool -printcert -rfc -jarfile "$aab_path" 2>&1)"; then
    fail "no se pudo leer el certificado del AAB"
fi
certificate_count="$(grep -c -- '-----BEGIN CERTIFICATE-----' <<< "$certificate_output" || true)"
[[ "$certificate_count" == "1" ]] || fail "el AAB debe contener un único certificado de carga"
grep -q -- '-----END CERTIFICATE-----' <<< "$certificate_output" ||
    fail "el certificado del AAB está incompleto"

certificate_path="$temporary_dir/upload-certificate.pem"
awk '
    /-----BEGIN CERTIFICATE-----/ { copying = 1 }
    copying { print }
    /-----END CERTIFICATE-----/ { exit }
' <<< "$certificate_output" > "$certificate_path"

openssl x509 -in "$certificate_path" -noout -checkend 0 >/dev/null 2>&1 ||
    fail "el certificado de firma está vencido"
certificate_text="$(openssl x509 -in "$certificate_path" -noout -text)"
grep -q -- 'Public Key Algorithm: rsaEncryption' <<< "$certificate_text" ||
    fail "la clave de carga debe usar RSA"
public_key_bits="$(sed -n 's/.*Public-Key: *(\([0-9][0-9]*\) bit).*/\1/p' <<< "$certificate_text" | head -n 1)"
[[ "$public_key_bits" =~ ^[0-9]+$ && "$public_key_bits" -ge 2048 ]] ||
    fail "la clave RSA de carga debe tener al menos 2048 bits"
certificate_expiry="$(openssl x509 -in "$certificate_path" -noout -enddate | sed 's/^notAfter=//')"
CERTIFICATE_EXPIRY="$certificate_expiry" ruby -rtime -e '
  abort "caducidad ilegible" unless Time.parse(ENV.fetch("CERTIFICATE_EXPIRY")).utc > Time.utc(2033, 10, 22)
' >/dev/null 2>&1 || fail "el certificado debe vencer después del 22 de octubre de 2033"

aab_fingerprint="$(
    openssl x509 -in "$certificate_path" -noout -fingerprint -sha256 |
        sed 's/^[^=]*=//' |
        normalize_fingerprint
)"
[[ "$aab_fingerprint" == "$expected_fingerprint" ]] ||
    fail "el AAB no está firmado con la huella de carga esperada"

verification_store="$temporary_dir/trusted-upload-certificate.p12"
verification_store_password="facturastock-validation"
keytool -importcert -noprompt \
    -storetype PKCS12 \
    -keystore "$verification_store" \
    -storepass "$verification_store_password" \
    -alias facturastock-upload \
    -file "$certificate_path" >/dev/null 2>&1 ||
    fail "no se pudo preparar la confianza temporal del certificado del AAB"
jarsigner -verify -strict \
    -keystore "$verification_store" \
    -storepass "$verification_store_password" \
    "$aab_path" >/dev/null 2>&1 ||
    fail "la firma del AAB no es válida, está incompleta o presenta advertencias"

read_local_sdk_dir() {
    local properties_path="$1"
    ruby -e '
      path = ARGV.fetch(0)
      line = File.foreach(path).find { |candidate| candidate.match?(/\Asdk\.dir\s*[:=]/) }
      exit 1 unless line
      value = line.sub(/\Asdk\.dir\s*[:=]\s*/, "").strip
      value = value.gsub(/\\(.)/) { Regexp.last_match(1) }
      puts value unless value.empty?
    ' "$properties_path"
}

sdk_candidates=("${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}")
if [[ -f "$project_root/local.properties" ]]; then
    local_sdk_dir="$(read_local_sdk_dir "$project_root/local.properties" 2>/dev/null || true)"
    sdk_candidates+=("$local_sdk_dir")
fi

android_sdk_root=""
for candidate in "${sdk_candidates[@]}"; do
    [[ -n "$candidate" && -d "$candidate/build-tools/36.0.0" ]] || continue
    android_sdk_root="$(cd "$candidate" && pwd -P)"
    break
done
[[ -n "$android_sdk_root" ]] ||
    fail "no se encontró Android SDK con Build Tools 36.0.0 en ANDROID_SDK_ROOT, ANDROID_HOME ni local.properties"

build_tools_dir="$android_sdk_root/build-tools/36.0.0"
apksigner_path="$build_tools_dir/apksigner"
zipalign_path="$build_tools_dir/zipalign"
aapt_path="$build_tools_dir/aapt"
[[ -x "$apksigner_path" && -x "$zipalign_path" && -x "$aapt_path" ]] ||
    fail "Build Tools 36.0.0 no contiene apksigner, zipalign y aapt ejecutables"

if ! apksigner_output="$("$apksigner_path" verify --Werr --verbose --print-certs "$apk_path" 2>&1)"; then
    fail "apksigner rechazó la firma del APK universal"
fi
grep -q '^Signer #2 ' <<< "$apksigner_output" &&
    fail "el APK universal contiene más de un firmante"
apk_fingerprint="$(
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$apksigner_output" |
        head -n 1 |
        normalize_fingerprint
)"
[[ "$apk_fingerprint" =~ ^[0-9A-F]{64}$ ]] ||
    fail "apksigner no devolvió una huella SHA-256 legible"
[[ "$apk_fingerprint" == "$aab_fingerprint" && "$apk_fingerprint" == "$expected_fingerprint" ]] ||
    fail "los firmantes del AAB, APK universal y huella esperada no coinciden"

if ! badging_output="$("$aapt_path" dump badging "$apk_path" 2>&1)"; then
    fail "aapt no pudo leer los metadatos del APK universal"
fi
package_name="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging_output" | head -n 1)"
artifact_version_code="$(sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p" <<< "$badging_output" | head -n 1)"
artifact_version_name="$(sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" <<< "$badging_output" | head -n 1)"
compile_sdk="$(sed -n "s/^package: .*compileSdkVersion='\([^']*\)'.*/\1/p" <<< "$badging_output" | head -n 1)"
minimum_sdk="$(sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p" <<< "$badging_output" | head -n 1)"
target_sdk="$(sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" <<< "$badging_output" | head -n 1)"
application_label="$(sed -n "s/^application: label='\([^']*\)'.*/\1/p" <<< "$badging_output" | head -n 1)"
application_icon="$(sed -n "s/^application: .*icon='\([^']*\)'.*/\1/p" <<< "$badging_output" | head -n 1)"
launchable_activity="$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" <<< "$badging_output" | head -n 1)"

[[ "$package_name" == "com.facturastock.app" ]] || fail "applicationId inesperado en el APK universal"
[[ "$artifact_version_code" == "$expected_version_code" ]] || fail "versionCode inesperado en el APK universal"
[[ "$artifact_version_name" == "$expected_version_name" ]] || fail "versionName inesperado en el APK universal"
[[ "$minimum_sdk" == "26" && "$target_sdk" == "36" && "$compile_sdk" == "36" ]] ||
    fail "minSdk, targetSdk o compileSdk inesperado en el APK universal"
[[ "$application_label" == "FacturaStock" && -n "$application_icon" ]] ||
    fail "label o icono principal inesperado en el APK universal"
[[ "$launchable_activity" == "com.facturastock.app.MainActivity" ]] ||
    fail "actividad launcher inesperada en el APK universal"
grep -q "uses-feature-not-required: name='android.hardware.camera'" <<< "$badging_output" ||
    fail "la cámara debe permanecer como hardware opcional"
grep -q '^application-debuggable' <<< "$badging_output" && fail "el APK universal es debuggable"
grep -q '^application-testOnly' <<< "$badging_output" && fail "el APK universal es testOnly"

if ! manifest_tree="$("$aapt_path" dump xmltree "$apk_path" AndroidManifest.xml 2>&1)"; then
    fail "aapt no pudo decodificar AndroidManifest.xml"
fi
allow_backup_line="$(grep -m 1 'A: android:allowBackup' <<< "$manifest_tree" || true)"
extract_native_line="$(grep -m 1 'A: android:extractNativeLibs' <<< "$manifest_tree" || true)"
[[ "$allow_backup_line" == *'(type 0x12)0x0'* ]] || fail "allowBackup debe ser false en el artefacto"
[[ "$extract_native_line" == *'(type 0x12)0x0'* ]] || fail "extractNativeLibs debe ser false en el artefacto"
grep -q 'A: android:icon' <<< "$manifest_tree" || fail "el manifest no contiene icono"
grep -q 'A: android:roundIcon' <<< "$manifest_tree" || fail "el manifest no contiene icono redondo"
grep -Eq 'A: android:(debuggable|testOnly|usesCleartextTraffic).*\(type 0x12\)0xffffffff' <<< "$manifest_tree" &&
    fail "el manifest habilita un flag inseguro de release"
printf '%s\n' "$manifest_tree" |
    ruby "$script_dir/verify-release-manifest-metadata.rb" >/dev/null

permissions_output="$("$aapt_path" dump permissions "$apk_path")"
actual_permissions="$temporary_dir/actual-permissions.txt"
expected_permissions="$temporary_dir/expected-permissions.txt"
sed -n \
    -e "s/^uses-permission: name='\([^']*\)'.*/\1/p" \
    -e "s/^uses-permission-sdk-23: name='\([^']*\)'.*/\1/p" \
    -e "s/^uses-permission-sdk-m: name='\([^']*\)'.*/\1/p" \
    <<< "$permissions_output" |
    LC_ALL=C sort -u > "$actual_permissions"
cat > "$expected_permissions" <<'EOF'
android.permission.ACCESS_NETWORK_STATE
android.permission.CAMERA
android.permission.FOREGROUND_SERVICE
android.permission.INTERNET
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.WAKE_LOCK
com.facturastock.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
com.google.android.c2dm.permission.RECEIVE
com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE
com.google.android.providers.gsf.permission.READ_GSERVICES
EOF
if ! diff -u "$expected_permissions" "$actual_permissions" >/dev/null; then
    diff -u "$expected_permissions" "$actual_permissions" >&2 || true
    fail "la lista de permisos del APK universal cambió; requiere revisión de privacidad"
fi

forbidden_entry_pattern='(^|/)(google-services\.json|local\.properties|service-account[^/]*\.json|\.env([^/]*)?|[^/]+\.(jks|keystore|p12|pfx|pem|key))$'
forbidden_content_pattern='demo-facturastock|demo-api-key|AIza00000000000000000000000000000000000|1:1000000000000:android:0000000000000000000000|10\.0\.2\.2|devEnsureMembership|"type"[[:space:]]*:[[:space:]]*"service_account"|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|(AKIA|ASIA)[A-Z0-9]{16}|gh[oprsu]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,}|xox[baprs]-[A-Za-z0-9-]{20,}'

scan_archive() {
    local archive_path="$1"
    local archive_label="$2"
    local entries entry normalized_entry bundle_manifests
    unzip -tqq "$archive_path" >/dev/null || fail "$archive_label no es un ZIP íntegro"
    entries="$(unzip -Z1 "$archive_path")" || fail "no se pudo enumerar $archive_label"
    [[ -n "$entries" ]] || fail "$archive_label está vacío"

    while IFS= read -r entry; do
        [[ -n "$entry" ]] || continue
        [[ "$entry" != /* && "$entry" != ../* && "$entry" != *'/../'* ]] ||
            fail "$archive_label contiene una ruta insegura"
        [[ "$entry" != */ ]] || continue
        normalized_entry="$(printf '%s' "$entry" | tr '[:upper:]' '[:lower:]')"
        grep -Eq -- "$forbidden_entry_pattern" <<< "$normalized_entry" &&
            fail "$archive_label contiene un archivo de configuración o credencial prohibido: $entry"

    done <<< "$entries"

    # Se descomprime el archivo completo una sola vez. El subshell desactiva pipefail para que
    # grep pueda terminar al encontrar un marcador sin convertir el SIGPIPE esperado de unzip en
    # un falso negativo. unzip -tqq ya verificó antes la integridad de todas las entradas.
    if (set +o pipefail; unzip -p "$archive_path" | LC_ALL=C grep -aEq -- "$forbidden_content_pattern"); then
        fail "$archive_label contiene configuración demo/emulador o material secreto"
    fi
    # Varias bibliotecas incluyen literalmente los delimitadores PEM para poder analizar claves;
    # bloquear solo "BEGIN PRIVATE KEY" daría un falso positivo. Esta regla exige además un
    # cuerpo Base64 contiguo de tamaño material, que sí representa una clave incrustada.
    if unzip -p "$archive_path" | ruby -e '
      bytes = STDIN.read.force_encoding(Encoding::BINARY)
      pem = /-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----\r?\n[A-Za-z0-9+\/=\r\n]{80,}-----END (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----/n
      exit(bytes.match?(pem) ? 0 : 1)
    '; then
        fail "$archive_label contiene una clave privada PEM completa"
    fi

    if [[ "$archive_label" == "AAB" ]]; then
        bundle_manifests="$(grep '/manifest/AndroidManifest.xml$' <<< "$entries" || true)"
        [[ "$bundle_manifests" == "base/manifest/AndroidManifest.xml" ]] ||
            fail "el AAB debe contener exactamente el manifest del módulo base"
    fi
}

scan_archive "$aab_path" "AAB"
scan_archive "$apk_path" "APK universal"

zipalign_log="$temporary_dir/zipalign.txt"
if ! "$zipalign_path" -c -P 16 -v 4 "$apk_path" > "$zipalign_log" 2>&1; then
    tail -n 30 "$zipalign_log" >&2
    fail "el APK universal no cumple la alineación ZIP de 16 KiB"
fi

apk_entries="$(unzip -Z1 "$apk_path")"
native_library_count="$(grep -Ec '^lib/[^/]+/[^/]+\.so$' <<< "$apk_entries" || true)"
native_64_entries="$(grep -E '^lib/(arm64-v8a|x86_64)/[^/]+\.so$' <<< "$apk_entries" || true)"
native_64_count="$(grep -c '\.so$' <<< "$native_64_entries" || true)"
if (( native_library_count > 0 && native_64_count == 0 )); then
    fail "el APK contiene código nativo pero ninguna ABI Android de 64 bits"
fi
if (( native_64_count > 0 )); then
    native_root="$temporary_dir/native"
    while IFS= read -r entry; do
        [[ -n "$entry" ]] || continue
        destination="$native_root/$entry"
        mkdir -p "$(dirname "$destination")"
        unzip -p "$apk_path" "$entry" > "$destination" ||
            fail "no se pudo extraer una biblioteca nativa para verificarla"
    done <<< "$native_64_entries"
    ruby "$script_dir/verify-elf-alignment.rb" "$native_root"
fi

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

{
    printf '%s  %s\n' "$(sha256_of "$aab_path")" "$(basename "$aab_path")"
    printf '%s  %s\n' "$(sha256_of "$apk_path")" "$(basename "$apk_path")"
} > "$checksums_path"
printf 'AAB y APK derivado: firma anclada, manifest, permisos, higiene y 16 KiB verificados.\n'
