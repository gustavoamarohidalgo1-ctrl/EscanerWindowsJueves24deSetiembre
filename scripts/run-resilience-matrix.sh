#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf '%s\n' \
        "Uso: $0 <jvm|android|firebase|all> [directorio-evidencia-nuevo]" \
        "" \
        "jvm      Puertos deterministas: red, 429/5xx, reloj, replay, duplicados y dos outboxes." \
        "android  Checkpoints Room/archivos, rollback, leases y ENOSPC inyectado (requiere AVD)." \
        "firebase Idempotencia y dos teléfonos lógicos contra Firebase Emulator Suite." \
        "all      Ejecuta las tres capas en ese orden."
}

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

mode="${1:-}"
case "$mode" in
    jvm|android|firebase|all) ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
esac

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd "$script_dir/.." && pwd -P)"
requested_evidence_dir="${2:-}"
if [[ -n "$requested_evidence_dir" ]]; then
    case "$requested_evidence_dir" in
        /|.|..) fail "elige un directorio de evidencia específico" ;;
    esac
    if [[ "$requested_evidence_dir" = /* ]]; then
        evidence_dir="$requested_evidence_dir"
    else
        evidence_dir="$repository_root/$requested_evidence_dir"
    fi
    [[ ! -e "$evidence_dir" ]] || fail "la evidencia ya existe: $evidence_dir"
    mkdir -p "$evidence_dir"
else
    evidence_dir="$(mktemp -d "${TMPDIR:-/tmp}/facturastock-resilience.XXXXXX")"
fi

printf 'gate\tstatus\n' > "$evidence_dir/gates.tsv"
overall_status=0

run_gate() {
    local gate_name="$1"
    shift
    local exit_code
    set +e
    "$@" 2>&1 | tee "$evidence_dir/$gate_name.log"
    exit_code="${PIPESTATUS[0]}"
    set -e
    if [[ "$exit_code" -eq 0 ]]; then
        printf '%s\tPASS\n' "$gate_name" >> "$evidence_dir/gates.tsv"
    else
        printf '%s\tFAIL\n' "$gate_name" >> "$evidence_dir/gates.tsv"
        overall_status=1
    fi
}

run_jvm_local() {
    cd "$repository_root"
    ./gradlew --offline --no-daemon --max-workers=1 \
        :app:testLocalDebugUnitTest \
        --tests com.facturastock.app.domain.usecase.OutboxReplayFaultInjectionTest \
        --tests com.facturastock.app.domain.usecase.ProcessPurchaseBackupOutboxUseCaseTest \
        --tests com.facturastock.app.data.local.codec.PreparedPurchaseCodecTest \
        --tests com.facturastock.app.data.local.codec.InvoiceOcrPageCodecTest
}

run_jvm_cloud() {
    cd "$repository_root"
    ./gradlew --offline --no-daemon --max-workers=1 \
        :app:testCloudDebugUnitTest \
        --tests com.facturastock.app.data.sync.FunctionsErrorMapperTest
}

run_android() {
    local adb_command="${ADB_BIN:-adb}"
    command -v "$adb_command" >/dev/null 2>&1 || fail "adb no está disponible"
    local selected_serial="${FACTURASTOCK_DEVICE_SERIAL:-${ANDROID_SERIAL:-}}"
    if [[ -z "$selected_serial" ]]; then
        local devices
        devices="$($adb_command devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
        local device_count
        device_count="$(printf '%s\n' "$devices" | awk 'NF { count += 1 } END { print count + 0 }')"
        [[ "$device_count" -eq 1 ]] ||
            fail "android requiere exactamente un dispositivo/AVD o ANDROID_SERIAL; encontrados: $device_count"
        selected_serial="$devices"
    fi
    "$adb_command" -s "$selected_serial" get-state 2>/dev/null | grep -qx device ||
        fail "el dispositivo $selected_serial no está conectado/autorizado"

    cd "$repository_root"
    ANDROID_SERIAL="$selected_serial" ./gradlew --offline --no-daemon --max-workers=1 \
        :app:connectedLocalDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class=com.facturastock.app.data.repository.OfflineRoomRestartRepositoryTest,com.facturastock.app.data.repository.CapturedPageAtomicRestartTest,com.facturastock.app.data.repository.InvoiceOcrSnapshotRepositoryTest,com.facturastock.app.data.repository.PreparedPurchaseRepositoryTest,com.facturastock.app.data.local.PurchasePostingDaoTest#validBatchCommitsCompletePostedGraphAndLinksPreparedDraft,com.facturastock.app.data.local.PurchasePostingDaoTest#draftCommitFailureRollsBackTheCompletePostingGraph,com.facturastock.app.data.local.PurchasePostingDaoTest#duplicateOutboxKeyRollsBackLatePostingAndKeepsPriorPendingOperation,com.facturastock.app.data.local.dao.OutboxOperationDaoTest,com.facturastock.app.data.local.StorageErrorTranslationTest,com.facturastock.app.data.sync.PurchaseBackupSyncWorkerTest#storageFullWhileReadingTheBackupGateRetriesBeforeTouchingTheQueue,com.facturastock.app.data.sync.PurchaseBackupSyncWorkerTest#storageFullWhilePinningTenantRetriesBeforeClaimOrNetwork,com.facturastock.app.data.files.RetainedImageCipherTest#tamperedCiphertextFailsAuthenticationAndReturnsNull,com.facturastock.app.data.files.RetainedImageCipherTest#malformedEnvelopeIsCorruptAndEncryptDoesNotOverwriteIt,com.facturastock.app.data.files.LocalRetainedImageStoreTest#displayReadDistinguishesAbsentFromCorruptSoOnlyAbsenceCanUseCloud
}

run_firebase() {
    [[ -x "$repository_root/functions/node_modules/.bin/firebase" ]] ||
        fail "faltan dependencias bloqueadas; ejecuta npm ci dentro de functions"
    cd "$repository_root/functions"
    npx firebase emulators:exec \
        --project demo-facturastock \
        --only auth,firestore,functions,storage \
        "node --test --test-concurrency=1 test/postPurchase.test.mjs test/syncPull.test.mjs"
}

case "$mode" in
    jvm)
        run_gate jvm-local run_jvm_local
        run_gate jvm-cloud run_jvm_cloud
        ;;
    android)
        run_gate android-checkpoints run_android
        ;;
    firebase)
        run_gate firebase-idempotency run_firebase
        ;;
    all)
        run_gate jvm-local run_jvm_local
        run_gate jvm-cloud run_jvm_cloud
        run_gate android-checkpoints run_android
        run_gate firebase-idempotency run_firebase
        ;;
esac

printf 'Evidencia: %s\n' "$evidence_dir"
exit "$overall_status"
