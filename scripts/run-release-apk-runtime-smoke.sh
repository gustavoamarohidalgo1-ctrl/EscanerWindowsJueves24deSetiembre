#!/usr/bin/env bash
set -euo pipefail

evidence_dir=""
temporary_dir=""
failure_reason=""

fail() {
    failure_reason="$1"
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

write_evidence_manifest() {
    local manifest_path="$evidence_dir/evidence.sha256"
    (
        cd "$evidence_dir"
        for evidence_file in *; do
            [[ -f "$evidence_file" && "$evidence_file" != "evidence.sha256" ]] || continue
            printf '%s  %s\n' "$(sha256_of "$evidence_file")" "$evidence_file"
        done
    ) > "$manifest_path"
}

on_exit() {
    local exit_status=$?
    trap - EXIT
    set +e
    if [[ -n "$evidence_dir" && -d "$evidence_dir" ]]; then
        if (( exit_status == 0 )); then
            printf 'gate,status\nrelease_apk_runtime_smoke,PASS\n' > "$evidence_dir/result.csv"
        else
            printf 'gate,status\nrelease_apk_runtime_smoke,FAIL\n' > "$evidence_dir/result.csv"
            if [[ -n "$failure_reason" ]]; then
                printf '# Fallo del smoke release\n\n%s\n' "$failure_reason" > "$evidence_dir/failure.md"
            fi
        fi
        write_evidence_manifest
    fi
    if [[ -n "$temporary_dir" && -d "$temporary_dir" ]]; then
        find "$temporary_dir" -depth -delete
    fi
    exit "$exit_status"
}
trap on_exit EXIT

normalize_fingerprint() {
    tr -d '[:space:]:' | tr '[:lower:]' '[:upper:]'
}

resolve_android_tool() {
    local override_name="$1"
    local tool_name="$2"
    local override_path="${!override_name:-}"
    local sdk_root candidate

    if [[ -n "$override_path" ]]; then
        [[ -x "$override_path" ]] || fail "$override_name no apunta a un ejecutable"
        printf '%s\n' "$override_path"
        return
    fi
    for sdk_root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}"; do
        [[ -n "$sdk_root" ]] || continue
        candidate="${sdk_root%/}/build-tools/36.0.0/$tool_name"
        if [[ -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    if command -v "$tool_name" >/dev/null 2>&1; then
        command -v "$tool_name"
        return
    fi
    fail "no se encontró $tool_name; configura $override_name o Android Build Tools 36.0.0"
}

apk_path="${1:-}"
evidence_dir="${2:-}"
[[ $# -eq 2 ]] || fail "uso: run-release-apk-runtime-smoke.sh <APK-universal> <directorio-evidencia-nuevo>"
[[ -f "$apk_path" && ! -L "$apk_path" ]] || fail "no existe un APK universal regular: $apk_path"
[[ -n "$evidence_dir" && ! -e "$evidence_dir" && ! -L "$evidence_dir" ]] ||
    fail "el directorio de evidencia debe usar una ruta nueva"
[[ "$evidence_dir" != *$'\n'* ]] || fail "el directorio de evidencia contiene un salto de línea"
mkdir -p "$evidence_dir"
evidence_dir="$(cd "$evidence_dir" && pwd -P)"
apk_path="$(cd "$(dirname "$apk_path")" && pwd -P)/$(basename "$apk_path")"
temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/facturastock-release-smoke.XXXXXX")"

expected_package="com.facturastock.app"
expected_activity="com.facturastock.app.MainActivity"
expected_version_code="${FACTURASTOCK_VERSION_CODE:-}"
expected_version_name="${FACTURASTOCK_VERSION_NAME:-}"
expected_fingerprint_raw="${FACTURASTOCK_EXPECTED_UPLOAD_CERT_SHA256:-}"
require_emulator="${FACTURASTOCK_SMOKE_REQUIRE_EMULATOR:-true}"
settle_seconds="${FACTURASTOCK_SMOKE_SETTLE_SECONDS:-8}"

[[ "$expected_version_code" =~ ^[1-9][0-9]{0,9}$ && "$expected_version_code" -le 2100000000 ]] ||
    fail "FACTURASTOCK_VERSION_CODE debe ser un entero entre 1 y 2100000000"
[[ "$expected_version_name" =~ ^[0-9A-Za-z][0-9A-Za-z._+-]{0,99}$ ]] ||
    fail "FACTURASTOCK_VERSION_NAME tiene un formato inválido"
expected_fingerprint="$(printf '%s' "$expected_fingerprint_raw" | normalize_fingerprint)"
[[ "$expected_fingerprint" =~ ^[0-9A-F]{64}$ ]] ||
    fail "FACTURASTOCK_EXPECTED_UPLOAD_CERT_SHA256 debe contener 64 hexadecimales"
[[ "$require_emulator" == "true" || "$require_emulator" == "false" ]] ||
    fail "FACTURASTOCK_SMOKE_REQUIRE_EMULATOR solo admite true o false"
[[ "$settle_seconds" =~ ^[0-9]+$ && "$settle_seconds" -le 30 ]] ||
    fail "FACTURASTOCK_SMOKE_SETTLE_SECONDS debe estar entre 0 y 30"

adb_path="${ADB_BIN:-adb}"
command -v "$adb_path" >/dev/null 2>&1 || fail "adb no está disponible"
aapt_path="$(resolve_android_tool AAPT_BIN aapt)"
apksigner_path="$(resolve_android_tool APKSIGNER_BIN apksigner)"

if ! badging_output="$("$aapt_path" dump badging "$apk_path" 2>&1)"; then
    fail "aapt no pudo leer el APK universal"
fi
artifact_package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging_output" | head -n 1)"
artifact_version_code="$(sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p" <<< "$badging_output" | head -n 1)"
artifact_version_name="$(sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" <<< "$badging_output" | head -n 1)"
artifact_activity="$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" <<< "$badging_output" | head -n 1)"
[[ "$artifact_package" == "$expected_package" ]] || fail "applicationId inesperado en el APK"
[[ "$artifact_version_code" == "$expected_version_code" ]] || fail "versionCode inesperado en el APK"
[[ "$artifact_version_name" == "$expected_version_name" ]] || fail "versionName inesperado en el APK"
[[ "$artifact_activity" == "$expected_activity" ]] || fail "Activity launcher inesperada en el APK"

if ! artifact_signer_output="$("$apksigner_path" verify --Werr --verbose --print-certs "$apk_path" 2>&1)"; then
    fail "apksigner rechazó el APK universal"
fi
grep -q '^Signer #2 ' <<< "$artifact_signer_output" && fail "el APK contiene más de un firmante"
artifact_fingerprint="$(
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$artifact_signer_output" |
        head -n 1 |
        normalize_fingerprint
)"
[[ "$artifact_fingerprint" == "$expected_fingerprint" ]] ||
    fail "la huella del APK no coincide con la clave de carga esperada"
artifact_sha256="$(sha256_of "$apk_path")"

devices_output="$("$adb_path" devices 2>&1)" || fail "adb no pudo enumerar dispositivos"
requested_serial="${FACTURASTOCK_DEVICE_SERIAL:-}"
if [[ -n "$requested_serial" ]]; then
    [[ "$requested_serial" =~ ^[A-Za-z0-9._:-]+$ ]] || fail "FACTURASTOCK_DEVICE_SERIAL contiene caracteres inválidos"
    awk -v serial="$requested_serial" 'NR > 1 && $1 == serial && $2 == "device" { found = 1 } END { exit(found ? 0 : 1) }' \
        <<< "$devices_output" || fail "el dispositivo solicitado no está conectado y autorizado"
    device_serial="$requested_serial"
else
    connected_devices="$(awk 'NR > 1 && $2 == "device" { print $1 }' <<< "$devices_output")"
    device_count="$(awk 'NF { count += 1 } END { print count + 0 }' <<< "$connected_devices")"
    [[ "$device_count" == "1" ]] || fail "se requiere exactamente un dispositivo ADB; encontrados: $device_count"
    device_serial="$connected_devices"
fi
adb=("$adb_path" -s "$device_serial")
"${adb[@]}" get-state 2>/dev/null | tr -d '\r' | grep -qx device ||
    fail "el dispositivo ADB no está listo"

is_emulator="$("${adb[@]}" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r[:space:]')"
if [[ "$require_emulator" == "true" ]]; then
    [[ "$device_serial" == emulator-* && "$is_emulator" == "1" ]] ||
        fail "este smoke está restringido a un emulador efímero"
elif [[ "$is_emulator" != "1" && -z "$requested_serial" ]]; then
    fail "un dispositivo físico exige FACTURASTOCK_DEVICE_SERIAL explícito"
fi

{
    printf 'property,value\n'
    printf 'device_kind,%s\n' "$([[ "$is_emulator" == "1" ]] && printf emulator || printf physical)"
    printf 'sdk,%s\n' "$("${adb[@]}" shell getprop ro.build.version.sdk | tr -d '\r,')"
    printf 'model,%s\n' "$("${adb[@]}" shell getprop ro.product.model | tr -d '\r,' | tr '\n' ' ')"
} > "$evidence_dir/device.csv"
{
    printf 'property,value\n'
    printf 'package,%s\n' "$artifact_package"
    printf 'version_code,%s\n' "$artifact_version_code"
    printf 'version_name,%s\n' "$artifact_version_name"
    printf 'launchable_activity,%s\n' "$artifact_activity"
    printf 'upload_certificate_sha256,%s\n' "$artifact_fingerprint"
    printf 'apk_sha256,%s\n' "$artifact_sha256"
} > "$evidence_dir/artifact.csv"

"${adb[@]}" logcat -b all -c >/dev/null 2>&1 || fail "no se pudo limpiar logcat antes del smoke"
if ! install_output="$("${adb[@]}" install -r "$apk_path" 2>&1)"; then
    printf '# Instalación ADB\n\n```text\n%s\n```\n' "$install_output" > "$evidence_dir/install.md"
    fail "adb no pudo instalar el APK universal"
fi
printf '# Instalación ADB\n\n```text\n%s\n```\n' "$install_output" > "$evidence_dir/install.md"
grep -q '^Success' <<< "$install_output" || fail "adb no confirmó la instalación del APK"

package_dump="$("${adb[@]}" shell dumpsys package "$expected_package" 2>&1 | tr -d '\r')" ||
    fail "no se pudo inspeccionar el paquete instalado"
installed_version_code="$(sed -n 's/^[[:space:]]*versionCode=\([^[:space:]]*\).*/\1/p' <<< "$package_dump" | head -n 1)"
installed_version_name="$(sed -n 's/^[[:space:]]*versionName=//p' <<< "$package_dump" | head -n 1)"
[[ "$installed_version_code" == "$expected_version_code" ]] || fail "versionCode instalado inesperado"
[[ "$installed_version_name" == "$expected_version_name" ]] || fail "versionName instalado inesperado"

package_paths="$("${adb[@]}" shell pm path "$expected_package" 2>&1 | tr -d '\r')" ||
    fail "PackageManager no devolvió la ruta del APK instalado"
installed_base_path="$(sed -n 's/^package:\(.*\/base\.apk\)$/\1/p' <<< "$package_paths" | head -n 1)"
[[ -n "$installed_base_path" ]] || fail "no se encontró base.apk para el paquete instalado"
pulled_apk="$temporary_dir/installed-base.apk"
if ! "${adb[@]}" pull "$installed_base_path" "$pulled_apk" > "$temporary_dir/adb-pull.txt" 2>&1; then
    fail "no se pudo extraer base.apk para verificar la instalación"
fi
[[ -s "$pulled_apk" ]] || fail "base.apk extraído está vacío"
installed_sha256="$(sha256_of "$pulled_apk")"
[[ "$installed_sha256" == "$artifact_sha256" ]] ||
    fail "el base.apk instalado no coincide byte a byte con el APK universal"

if ! installed_signer_output="$("$apksigner_path" verify --Werr --verbose --print-certs "$pulled_apk" 2>&1)"; then
    fail "apksigner rechazó el base.apk instalado"
fi
installed_fingerprint="$(
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$installed_signer_output" |
        head -n 1 |
        normalize_fingerprint
)"
[[ "$installed_fingerprint" == "$expected_fingerprint" ]] ||
    fail "el certificado del paquete instalado no coincide con el ancla esperada"
{
    printf 'property,value\n'
    printf 'package,%s\n' "$expected_package"
    printf 'version_code,%s\n' "$installed_version_code"
    printf 'version_name,%s\n' "$installed_version_name"
    printf 'upload_certificate_sha256,%s\n' "$installed_fingerprint"
    printf 'installed_base_apk_sha256,%s\n' "$installed_sha256"
} > "$evidence_dir/installed.csv"

component="$expected_package/$expected_activity"
start_activity() {
    local phase="$1"
    local output_file="$evidence_dir/launch-$phase.md"
    local launch_output process_id activity_state resumed_lines short_activity
    if ! launch_output="$("${adb[@]}" shell am start -W -n "$component" 2>&1 | tr -d '\r')"; then
        printf '# Lanzamiento %s\n\n```text\n%s\n```\n' "$phase" "$launch_output" > "$output_file"
        fail "ActivityManager no pudo iniciar MainActivity ($phase)"
    fi
    printf '# Lanzamiento %s\n\n```text\n%s\n```\n' "$phase" "$launch_output" > "$output_file"
    grep -q '^Status: ok$' <<< "$launch_output" || fail "MainActivity no devolvió Status: ok ($phase)"
    grep -qi '^Error:' <<< "$launch_output" && fail "MainActivity devolvió un error ($phase)"
    sleep "$settle_seconds"
    process_id="$("${adb[@]}" shell pidof "$expected_package" 2>/dev/null | tr -d '\r[:space:]')"
    [[ "$process_id" =~ ^[0-9]+$ ]] || fail "el proceso no sobrevivió al arranque ($phase)"
    activity_state="$("${adb[@]}" shell dumpsys activity activities 2>&1 | tr -d '\r')" ||
        fail "no se pudo consultar la Activity reanudada ($phase)"
    resumed_lines="$(grep -E 'mResumedActivity|topResumedActivity|ResumedActivity' <<< "$activity_state" || true)"
    printf '# Activity reanudada: %s\n\n```text\n%s\n```\n' \
        "$phase" "$resumed_lines" > "$evidence_dir/resumed-$phase.md"
    grep -Fq "$expected_package" <<< "$resumed_lines" || fail "FacturaStock no quedó en primer plano ($phase)"
    short_activity=".${expected_activity#"$expected_package."}"
    if ! grep -Fq "$expected_activity" <<< "$resumed_lines" &&
        ! grep -Fq "$expected_package/$short_activity" <<< "$resumed_lines"; then
        fail "MainActivity no quedó reanudada ($phase)"
    fi
}

start_activity initial
"${adb[@]}" shell am force-stop "$expected_package" >/dev/null 2>&1 ||
    fail "ActivityManager no pudo detener el paquete"
stopped_process="$("${adb[@]}" shell pidof "$expected_package" 2>/dev/null | tr -d '\r[:space:]')"
[[ -z "$stopped_process" ]] || fail "el proceso sobrevivió a am force-stop"
start_activity reopen

"${adb[@]}" logcat -b events -d -v brief > "$evidence_dir/runtime-events.log" 2>&1 ||
    fail "no se pudo leer el buffer de eventos"
"${adb[@]}" logcat -b crash -d -v threadtime > "$evidence_dir/runtime-crash.log" 2>&1 ||
    fail "no se pudo leer el buffer de crashes"
"${adb[@]}" logcat -d -v threadtime AndroidRuntime:E ActivityManager:E ActivityTaskManager:E '*:S' \
    > "$evidence_dir/runtime-errors.log" 2>&1 || fail "no se pudo leer el log de runtime"
"${adb[@]}" shell dumpsys activity lastanr > "$evidence_dir/runtime-last-anr.log" 2>&1 ||
    fail "no se pudo consultar el último ANR"

package_pattern="${expected_package//./\\.}"
runtime_failure_count=0
grep -Eq "am_(crash|anr)[[:space:]]*(\\([^)]*\\))?:.*${package_pattern}([,:[:space:]]|$)" \
    "$evidence_dir/runtime-events.log" && (( runtime_failure_count += 1 ))
grep -Eq "Process: ${package_pattern}(:[^,[:space:]]+)?," "$evidence_dir/runtime-crash.log" &&
    (( runtime_failure_count += 1 ))
grep -Eq "Cmdline: ${package_pattern}(:[^[:space:]]+)?([[:space:]]|$)" "$evidence_dir/runtime-crash.log" &&
    (( runtime_failure_count += 1 ))
grep -Eq "Process: ${package_pattern}(:[^,[:space:]]+)?," "$evidence_dir/runtime-errors.log" &&
    (( runtime_failure_count += 1 ))
grep -Fq "ANR in $expected_package" "$evidence_dir/runtime-errors.log" && (( runtime_failure_count += 1 ))
grep -Fq "$expected_package" "$evidence_dir/runtime-last-anr.log" && (( runtime_failure_count += 1 ))
{
    printf 'gate,status,matching_records\n'
    if (( runtime_failure_count == 0 )); then
        printf 'crash_anr_scan,PASS,0\n'
    else
        printf 'crash_anr_scan,FAIL,%s\n' "$runtime_failure_count"
    fi
    printf 'initial_process_survived,PASS,1\n'
    printf 'force_stop_terminated_process,PASS,1\n'
    printf 'reopened_process_survived,PASS,1\n'
} > "$evidence_dir/runtime-health.csv"
if (( runtime_failure_count > 0 )); then
    fail "se detectó crash o ANR durante el arranque/reapertura"
fi

final_process="$("${adb[@]}" shell pidof "$expected_package" 2>/dev/null | tr -d '\r[:space:]')"
[[ "$final_process" =~ ^[0-9]+$ ]] || fail "el proceso no seguía vivo al cerrar el smoke"
printf 'APK universal instalado y reabierto sin crash ni ANR; evidencia cerrada.\n'
