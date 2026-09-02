#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf '%s\n' \
        "Uso:" \
        "  $0 start <directorio-evidencia>" \
        "  $0 capture <directorio-evidencia> <checkpoint>" \
        "  $0 finish <directorio-evidencia>"
}

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

command_name="${1:-}"
evidence_dir="${2:-}"
if [[ -z "$command_name" || -z "$evidence_dir" ]]; then
    usage
    exit 2
fi
case "$evidence_dir" in
    /|.|..)
        fail "elige un directorio de evidencia específico, no '$evidence_dir'"
        ;;
esac

adb_command="${ADB_BIN:-adb}"
command -v "$adb_command" >/dev/null 2>&1 || fail "adb no está disponible"

requested_serial="${FACTURASTOCK_DEVICE_SERIAL:-}"
if [[ -n "$requested_serial" ]]; then
    device_serial="$requested_serial"
else
    physical_devices="$($adb_command devices -l | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1 }')"
    device_count="$(printf '%s\n' "$physical_devices" | awk 'NF { count += 1 } END { print count + 0 }')"
    if [[ "$device_count" -ne 1 ]]; then
        fail "se requiere exactamente un teléfono físico ADB; encontrados: $device_count"
    fi
    device_serial="$physical_devices"
fi

[[ "$device_serial" != emulator-* ]] || fail "los emuladores no son evidencia de teléfono físico"
$adb_command -s "$device_serial" get-state 2>/dev/null | grep -qx device ||
    fail "el teléfono seleccionado no está autorizado o conectado"

features="$($adb_command -s "$device_serial" shell pm list features | tr -d '\r')"
printf '%s\n' "$features" | grep -Eq 'feature:android\.hardware\.camera($|\.)' ||
    fail "el dispositivo no declara hardware de cámara"

app_package="com.facturastock.app"
package_dump="$($adb_command -s "$device_serial" shell dumpsys package "$app_package" 2>/dev/null | tr -d '\r')"
printf '%s\n' "$package_dump" | grep -q "Package \[$app_package\]" ||
    fail "FacturaStock ($app_package) no está instalada en el teléfono"
version_name="$(printf '%s\n' "$package_dump" | sed -n 's/^[[:space:]]*versionName=//p' | head -n 1)"
version_code="$(printf '%s\n' "$package_dump" | sed -n 's/^[[:space:]]*versionCode=\([^[:space:]]*\).*/\1/p' | head -n 1)"

case "$command_name" in
    start)
        mkdir -p "$evidence_dir"
        [[ ! -e "$evidence_dir/device.txt" ]] ||
            fail "la sesión ya existe; usa otro directorio para no sobrescribir evidencia"
        {
            printf 'manufacturer\t%s\n' "$($adb_command -s "$device_serial" shell getprop ro.product.manufacturer | tr -d '\r')"
            printf 'model\t%s\n' "$($adb_command -s "$device_serial" shell getprop ro.product.model | tr -d '\r')"
            printf 'android\t%s\n' "$($adb_command -s "$device_serial" shell getprop ro.build.version.release | tr -d '\r')"
            printf 'sdk\t%s\n' "$($adb_command -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
            printf 'camera\tpresent\n'
            printf 'package\t%s\n' "$app_package"
            printf 'versionName\t%s\n' "$version_name"
            printf 'versionCode\t%s\n' "$version_code"
        } > "$evidence_dir/device.txt"
        {
            printf 'checkpoint\tresult\tnotes\tverified_by\tverified_at\n'
            printf '01-onboarding\tPENDING\t\t\t\n'
            printf '02-camera-real\tPENDING\t\t\t\n'
            printf '03-rotation\tPENDING\t\t\t\n'
            printf '04-process-death\tPENDING\t\t\t\n'
            printf '05-ocr-quality\tPENDING\t\t\t\n'
            printf '06-demo-38-lines\tPENDING\t\t\t\n'
            printf '07-double-tap\tPENDING\t\t\t\n'
            printf '08-history-inventory\tPENDING\t\t\t\n'
            printf '09-airplane-mode\tPENDING\t\t\t\n'
            printf '10-reconnect-conflict-expired\tPENDING\t\t\t\n'
        } > "$evidence_dir/checklist.tsv"
        printf 'Sesión creada en %s. No se guardó el número de serie.\n' "$evidence_dir"
        ;;
    capture)
        checkpoint="${3:-}"
        [[ "$checkpoint" =~ ^[0-9][0-9]-[a-z0-9-]+$ ]] ||
            fail "checkpoint inválido; usa por ejemplo 02-camera-preview"
        [[ -f "$evidence_dir/device.txt" ]] || fail "ejecuta start antes de capture"
        foreground="$($adb_command -s "$device_serial" shell dumpsys window windows 2>/dev/null | tr -d '\r' | grep -E 'mCurrentFocus|mFocusedApp' | tail -n 1 || true)"
        printf '%s\n' "$foreground" | grep -q 'com.facturastock.app' ||
            fail "FacturaStock debe estar en primer plano para evitar capturar otros datos"
        target="$evidence_dir/$checkpoint.png"
        [[ ! -e "$target" ]] || fail "la captura $target ya existe"
        $adb_command -s "$device_serial" exec-out screencap -p > "$target"
        printf 'Captura guardada: %s\n' "$target"
        ;;
    finish)
        [[ -f "$evidence_dir/device.txt" && -f "$evidence_dir/checklist.tsv" ]] ||
            fail "la sesión de evidencia está incompleta"
        awk -F '\t' '
            NR == 1 { next }
            NF < 5 || ($2 != "PASS" && $2 != "FAIL") || $4 == "" || $5 == "" { invalid = 1 }
            END { exit(NR == 11 && !invalid ? 0 : 1) }
        ' "$evidence_dir/checklist.tsv" ||
            fail "completa exactamente los diez resultados (PASS/FAIL), responsable y fecha"
        find "$evidence_dir" -maxdepth 1 -type f -name '*.png' -print -quit | grep -q . ||
            fail "no hay capturas PNG para firmar"
        if command -v sha256sum >/dev/null 2>&1; then
            (cd "$evidence_dir" && sha256sum device.txt checklist.tsv ./*.png > sha256.txt)
        else
            (cd "$evidence_dir" && shasum -a 256 device.txt checklist.tsv ./*.png > sha256.txt)
        fi
        printf 'Manifiesto SHA-256 guardado en %s/sha256.txt\n' "$evidence_dir"
        ;;
    *)
        usage
        exit 2
        ;;
esac
