# Muerte de proceso y fault injection

Esta matriz convierte el recorrido captura → OCR → preparación → transacción → claim → envío →
ACK en checkpoints verificables. El comando canónico es:

```bash
scripts/run-resilience-matrix.sh all build/resilience-evidence/manual-001
```

También se puede ejecutar una capa aislada con `jvm`, `android` o `firebase`. El directorio de
evidencia debe ser nuevo: el runner no sobrescribe resultados anteriores y siempre genera
`gates.tsv` más un log por gate.

## Contrato durable por checkpoint

| Ventana de caída | Autoridad al reiniciar | Resultado exigido | Prueba ejecutada |
| --- | --- | --- | --- |
| Antes de publicar captura | Ninguna fila nueva | La app no inventa una página; el usuario puede recapturar | Límite explícito, no se presenta como página recuperada |
| Después de publicar captura | Recibo de publicación + imagen + borrador en Room y archivo privado | Reabrir conserva exactamente página, intención e identidad | `CapturedPageAtomicRestartTest`, `OfflineRoomRestartRepositoryTest` |
| OCR en curso | `activeOcrRunId` + `OCR_PROCESSING` | El reinicio recupera por CAS el run interrumpido sin borrar imagen ni revisión | `OfflineRoomRestartRepositoryTest`, `InvoiceOcrSnapshotRepositoryTest` |
| OCR publicado / preparación | Snapshot OCR y `PreparedPurchase` versionados, con SHA-256 | Publicación exacta es idempotente; payload distinto u operación parcial falla sin sobrescribir | `InvoiceOcrSnapshotRepositoryTest`, `PreparedPurchaseRepositoryTest`, tests de codecs |
| Transacción contable | Transacción Room única | Se observa el grafo completo o ningún artefacto: nunca compra sin inventario/auditoría/outbox | tres escenarios focales de `PurchasePostingDaoTest` |
| Después de claim | Fila `PROCESSING`, `claimToken`, lease y contador | Lease vivo no se roba; vencido vuelve a `PENDING` sin perder identidad ni intentos | `OfflineRoomRestartRepositoryTest`, `OutboxOperationDaoTest` |
| Efecto remoto, antes del ACK | Clave idempotente durable | Se reenvía la misma clave; dos entregas producen un solo efecto remoto | `OutboxReplayFaultInjectionTest`, `postPurchase.test.mjs` |
| Después del ACK | CAS `PROCESSING` → `COMPLETED` condicionado por token y eco de clave | Solo el dueño y un ACK de la misma operación cierran la fila; un replay no repite efecto | `ProcessPurchaseBackupOutboxUseCaseTest`, `OutboxReplayFaultInjectionTest` |

El cierre/reapertura de Room elimina las instancias de base, repositorios y flows y vuelve a abrir el
mismo archivo SQLite. Es una prueba determinista del journal y de los checkpoints persistidos. No
es un `SIGKILL` real en mitad de una instrucción de CameraX o del SDK Firebase; esas ventanas se
resuelven mediante el contrato anterior (sin fila antes del commit, replay idempotente después del
commit), no afirmando que el buffer de cámara en memoria sobreviva.

## Matriz de fallos

| Fallo solicitado | Capa y mecanismo | Invariante comprobada |
| --- | --- | --- |
| Disco lleno / ENOSPC | Android: `SQLiteFullException`, causa envuelta e `IOException` ENOSPC inyectadas en puertos de storage y worker | Se traduce a `InsufficientSpace`, WorkManager reintenta y no toca claim ni red |
| Red cortada | JVM: conectividad compartida del transporte; Firebase: Emulator Suite para el contrato remoto | La fila vuelve a `PENDING`, conserva clave y completa tras reconexión |
| HTTP 429 | Cloud JVM: `RESOURCE_EXHAUSTED` del SDK → transitorio; puerto del drenador con resultado inyectado | Backoff durable, contador creciente y misma clave |
| HTTP 5xx / timeout | Cloud JVM: `INTERNAL`, `UNAVAILABLE`, `DEADLINE_EXCEEDED`; puerto del drenador | Nunca se marca éxito; reintenta y al límite queda `FAILED` manual |
| Reloj alterado | JVM: salto de −24 h y posterior avance más allá del lease | Un reloj atrasado no roba un claim vivo; el lease vencido se recupera conservando intentos |
| Archivo/payload corrupto | JVM codecs truncados/versiones desconocidas; Android AES-GCM alterado/malformado | Falla cerrado, no decodifica ni sobrescribe el original corrupto |
| Duplicado / ACK perdido | JVM remoto idempotente y Functions Emulator | Repetir la misma clave devuelve el mismo recibo y crea un solo efecto |
| Dos procesadores | JVM: dos procesadores sobre una outbox; Android: CAS Room | Solo un token gana claim y solo el dueño puede cerrar |
| Dos dispositivos | JVM: dos outboxes independientes contra un transporte compartido; Functions: dos teléfonos lógicos | Ambos clientes convergen y el backend materializa una sola identidad |

## Qué es real y qué es determinista

- `jvm` es fault injection determinista sobre los puertos productivos. No abre sockets ni llena el
  volumen físico; permite ordenar exactamente carreras como “commit remoto, ACK perdido”.
- `android` usa Room, SQLite, archivos privados, Android Keystore y WorkManager en un AVD o
  dispositivo. ENOSPC se inyecta como excepción real del API, pero no ocupa todo el disco del host.
- `firebase` usa Auth, Firestore, Functions y Storage Emulator en sus puertos de `firebase.json`.
  Los “dos teléfonos” son dos identidades/solicitudes lógicas; no son dos AVD físicos.
- Una prueba física de `SIGKILL` durante la captura CameraX requiere un hook de checkpoint de
  debug o coordinación manual con cámara real. No se automatiza con taps por coordenadas porque
  sería frágil y no probaría el journal de forma reproducible.

Para evidencia de cámara y muerte física, se mantiene el checklist de
[`ANDROID_E2E_DEVICE_CHECKLIST.md`](ANDROID_E2E_DEVICE_CHECKLIST.md); no debe sustituirse su
resultado manual por el PASS de esta matriz.
