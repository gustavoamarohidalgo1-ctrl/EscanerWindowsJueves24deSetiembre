# Evidencia — Blindaje contra errores

Fecha de cierre: **30 de agosto de 2026** (`America/Lima`). Esta ronda implementa una capa
importante de confiabilidad, gates y recuperación. No afirma que la app sea infalible ni que toda la
Opción 2 esté terminada: la restauración activa, la validación del flujo completo sobre el APK
firmado exacto y las operaciones reales de Play siguen pendientes y bloqueadas de forma explícita.

## Resultado verificable

| Control | Resultado final |
| --- | --- |
| Unitarias `localDebug` | **1.356/1.356**, 0 fallos, errores u omitidas |
| Unitarias `cloudDebug` | **1.474/1.474**, 0 fallos, errores u omitidas |
| Android ↔ Firebase Emulator, API 35 | **1/1**: registro, verificación, compra Room, outbox, Function, Firestore, pull, conflicto, anulación y sesión expirada |
| Functions + reglas Auth/Firestore/Storage | **147/147**, 0 fallos u omitidas; ejecución serial contra Emulator Suite |
| Preflight y primitivas de snapshot en API 35 | **7/7**, 0 fallos |
| Matriz Android de checkpoints/fallos | **45/45**, 0 fallos |
| Matriz JVM de resiliencia | **50/50**, 2/2 gates `PASS` |
| Gate final Gradle | **BUILD SUCCESSFUL**, 110 tareas: formato, unidades, compilación Android local/cloud, esquema Room y análisis estático |
| Contratos de release/rollout/artefactos, Macrobenchmark y Actionlint | **PASS** |

La primera ejecución de la matriz Android encontró un fixture imposible: intentaba asociar dos
tenants cloud al mismo negocio local y el trigger inmutable lo rechazó correctamente. Se modelaron
dos negocios reales en la prueba, se corrigió además la cronología terminal del fixture y la
repetición completa terminó 45/45.

## E2E Android ↔ Firebase real

[`CloudPurchaseSagaE2ETest.kt`](../../app/src/androidTestCloud/java/com/facturastock/app/data/sync/CloudPurchaseSagaE2ETest.kt)
usa el grafo Hilt cloud de la app, Room real y los SDK Firebase contra Auth, Firestore, Functions y
Storage Emulator. No sustituye el cliente por un fake. Recorre:

1. alta email/contraseña y verificación controlada solo dentro del emulador;
2. creación/vinculación del negocio y publicación contable local;
3. claim de outbox, callable `postPurchase`, ACK y documento Firestore;
4. pull incremental y cache local;
5. colisión sembrada por un segundo cliente lógico;
6. anulación compensatoria, outbox y pull final;
7. eliminación remota del usuario y transición cerrada a `SessionExpired`/`Expired`.

Los controles [`devVerifyCurrentUser` y `devExpireCurrentUser`](../../functions/index.js) devuelven
`not-found` fuera de `FUNCTIONS_EMULATOR=true`, exigen un usuario autenticado y rechazan campos no
declarados. Sus contratos están en
[`devControls.test.mjs`](../../functions/test/devControls.test.mjs).

El E2E descubrió que Auth Emulator puede representar un refresh token invalidado mediante una
`FirebaseException` genérica. [`AccountErrorMapper.kt`](../../app/src/cloud/java/com/facturastock/app/data/account/AccountErrorMapper.kt)
lo reconoce solo para operaciones sobre el usuario ya autenticado, con cadena de causas acotada y
sin propagar mensajes del SDK. Sign-in conserva su mapeo anti-enumeración. En
[`FirebaseAccountRepository.kt`](../../app/src/cloud/java/com/facturastock/app/data/account/FirebaseAccountRepository.kt),
`recoverSession`, refresh y reenvío publican la sesión expirada sin poder invalidar una identidad
nueva que haya reemplazado a la anterior.

El job `firebase-emulator` de [CI](../../.github/workflows/ci.yml) ahora fuerza
`--test-concurrency=1`; las suites que limpian y comparten emuladores ya no pueden interferirse en
paralelo. [`test-release-contracts.rb`](../../scripts/test-release-contracts.rb) fija esa propiedad.

## Reinicio, replay y fault injection

[`run-resilience-matrix.sh`](../../scripts/run-resilience-matrix.sh) separa tres capas reproducibles:

- JVM: red, 429/5xx, reloj atrasado, lease, ACK perdido, replay idempotente, duplicados, payload
  corrupto, dos outboxes y traducción de fallos;
- Android: cierre/reapertura Room, captura publicada, OCR, preparación, transacción contable,
  claim/ACK, CAS, rollback, Keystore y ENOSPC inyectado;
- Firebase: idempotencia y dos clientes lógicos sobre el backend emulado.

El contrato y sus límites están en
[`PROCESS_DEATH_AND_FAULT_INJECTION.md`](../PROCESS_DEATH_AND_FAULT_INJECTION.md). Estas son caídas
deterministas en fronteras durables; no son `SIGKILL` físicos dentro de CameraX/Firebase, disco
físicamente lleno, radio real desconectada ni dos teléfonos/AVD independientes.

Las pruebas generativas/deterministas cubren dinero, parser, ledger y payloads Functions. Outbox
tiene cobertura profunda de escenarios, carreras y replay, pero todavía no un property/fuzzer
aleatorio dedicado; no se presenta como cubierto.

## Observabilidad privada

[`ConsentAwareProductionObservability.kt`](../../app/src/main/java/com/facturastock/app/data/reporting/ConsentAwareProductionObservability.kt)
y [`FirebaseObservabilitySink.kt`](../../app/src/cloud/java/com/facturastock/app/data/reporting/FirebaseObservabilitySink.kt)
aplican opt-in y una allowlist cerrada: resultado, código de error y tramo de antigüedad. Se
instrumentan apertura Room, salida del proceso anterior en API 30+, OCR, sync, conflictos,
antigüedad de outbox y 429/5xx sin contenido fiscal, importes, rutas, `Throwable` ni UUID estables.

Crashlytics permanece deliberadamente desactivado y no recibe excepciones manuales. Crash/ANR se
resume al siguiente arranque como categoría cerrada; no se sube stacktrace. La descripción canónica
fue corregida en [`PRIVACY_DATA_LIFECYCLE.md`](../PRIVACY_DATA_LIFECYCLE.md).

## Snapshot completo: base segura, activación bloqueada

El contrato `FULL_DEVICE_SNAPSHOT` exige una imagen Room completa y digests para todas las tablas
v24/v25, incluidas `sales` y `sale_lines`. El productor ZIP es determinista; el validador limita
rutas, entradas y expansión, verifica tamaño/SHA-256 y nunca toca la base activa. El preflight
Android aislado verifica fingerprint, ausencia de sidecars, `user_version`, identity hash Room,
`integrity_check`, FKs y conteos antes y después de abrir la candidata.

El journal durable y las primitivas de archivos agregan lock, CAS, revisiones, `fsync`, renames
atómicos, quarantine y decisiones de rollback fail-closed. El detalle está en
[`FULL_DEVICE_SNAPSHOT_FOUNDATION.md`](../FULL_DEVICE_SNAPSHOT_FOUNDATION.md).

La restauración de la base viva **no está habilitada**:
`FullDeviceSnapshotRestoreCoordinator.requestActivation()` devuelve siempre `NOT_READY`. Faltan la
compuerta global para detener workers/coroutines/DAO y cerrar Room, migración aislada, digests
canónicos reales, invariantes contables completas, correspondencia filas↔archivos, SAF y cifrado
portable. El export/import JSON existente tampoco se convierte por esto en un backup de ventas.

## AAB, rollout y hotfix

El workflow [`signed-release`](../../.github/workflows/ci.yml) prepara un `cloudRelease` firmado,
deriva un APK universal del mismo AAB, verifica identidad/firma/checksums, lo instala, abre,
fuerza cierre, reabre y bloquea evidencia de crash/ANR. También exige `versionCode` monotónico y
esquema Room forward-only. No se materializó localmente porque las credenciales de firma y Firebase
de producción son secretos protegidos; el APK exacto solo tiene smoke de runtime, no el recorrido
cloud completo de compra.

[`rollout-and-hotfix-policy.md`](../play/rollout-and-hotfix-policy.md) y sus gates permiten solo
`internal → 1% → 5% → 20% → 50% → 100%`, conservando AAB, SHA-256 y `versionCode` entre
promociones. Ninguna publicación se ejecutó: cada etapa requiere acceso a Play, telemetría del
candidato y aprobación humana. Un hotfix debe avanzar desde el esquema Room máximo distribuido; aún
falta probarlo con artefactos archivados de una versión realmente publicada.

## Conclusión honesta

Quedó implementada y verde una primera capa grande del blindaje: E2E cloud real, replay/fallos,
observabilidad privada, contratos de release, esquema forward-only y fundamentos de snapshot
fail-closed. No existe software sin errores, y todavía no corresponde declarar la Opción 2
completa hasta cerrar restauración/importación activa, E2E sobre el APK firmado exacto, pruebas
físicas de muerte/fallos y el rollout real.
