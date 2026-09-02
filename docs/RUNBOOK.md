# Runbook técnico — FacturaStock 1.0

Guía de operación para quien mantiene, publica y soporta la app. No es un manual de usuario: la
persona que usa el teléfono tiene el suyo en [`MANUAL_USUARIO.md`](MANUAL_USUARIO.md).

Todo procedimiento distingue dos modos: en `local` o sin binding, Room es autónomo; en un negocio
`cloud` enlazado, Functions es la autoridad transaccional del inventario y Room conserva una
materialización local. El pull de inventario aplica saldos y ventas remotas; el pull documental de
compras continúa siendo una réplica para diagnóstico. Las ventas a crédito y sus abonos usan la
misma autoridad y el mismo feed contiguo para materializar la cuenta por cobrar.

| Documento | Para qué |
| --- | --- |
| [`../README.md`](../README.md) | Setup, compilación, pruebas, migraciones, AAB |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Capas, límites y tipos financieros |
| [`BACKUP_SYNC.md`](BACKUP_SYNC.md) | Contrato de outbox, push y pull |
| [`CLOUD_BACKUP_FIREBASE.md`](CLOUD_BACKUP_FIREBASE.md) | Proyecto Firebase, reglas y Functions |
| [`SALES_AND_BARCODE_SCANNER.md`](SALES_AND_BARCODE_SCANNER.md) | Ventas locales/compartidas, consulta de Inventario, lectores HID, asociación y límites |
| [`DEBTORS_AND_CREDIT_SALES.md`](DEBTORS_AND_CREDIT_SALES.md) | Ventas a crédito, deudores, abonos, concurrencia y soporte |
| [`PRIVACY_DATA_LIFECYCLE.md`](PRIVACY_DATA_LIFECYCLE.md) | Retención y ciclo de vida de datos |
| [`ACEPTACION_V1.md`](ACEPTACION_V1.md) | Matriz de aceptación de la versión 1.0 |
| [`PILOTO_CERRADO.md`](PILOTO_CERRADO.md) | Protocolo del piloto con documentos anonimizados |

## 1. Piezas y responsabilidades

| Pieza | Dónde vive | Responsable |
| --- | --- | --- |
| App Android (`:app`) | Este repositorio | Desarrollo |
| Macrobenchmark (`:benchmark`) | Este repositorio | Desarrollo |
| Esquemas Room `1.json`–`27.json` | `app/schemas/` (versionados, append-only) | Desarrollo |
| Reglas Firestore/Storage e índices, Functions | `firebase.json`, `firestore.rules`, `storage.rules`, `firestore.indexes.json` y `functions/` | Desarrollo |
| Secretos de firma y de Firebase | Entorno protegido `android-production` de GitHub | Quien publica |
| Keystore de carga | **Fuera del repositorio**, custodia offline | Quien publica |
| Ficha y binarios en Play | Google Play Console | Quien publica |

Reglas no negociables, verificadas por tareas Gradle y scripts:

- El keystore release **jamás** entra al repositorio; la CI lo materializa temporalmente y lo borra
  con un paso `if: always()`.
- No existe ningún `google-services.json` en el repositorio; la configuración `cloud` se inyecta por
  variables de entorno.
- `local.properties` no se versiona.
- Ningún fuente de producción registra rutas, RUC ni contenido de documentos
  (`verifyNoSensitiveLogging`).
- Los marcadores del Emulator Suite (project ID, claves API sintéticas, application id de ejemplo,
  `10.0.2.2` y callable de siembra) no pueden llegar a `cloudRelease`.
- La clave de API de cliente de Firebase viaja dentro del APK **por diseño** y no se trata como
  secreto: su protección son las restricciones de Google Cloud, las reglas de Firestore/Storage y
  App Check.

## 2. Turno normal: qué vigila la integración continua

`.github/workflows/ci.yml` corre en cada *pull request*, en cada *push* a `main`, a demanda
(`workflow_dispatch`) y de lunes a viernes a las 07:17 UTC (02:17 en `America/Lima`).

| Job | Qué demuestra |
| --- | --- |
| Format, static analysis, lint, unit, and Room policy | Spotless, los diez verificadores Gradle, Lint, pruebas unitarias y política de esquema |
| Secret and dependency review | `scan-repository-secrets.rb` y revisión de dependencias |
| Submit Gradle dependency graph | Grafo de dependencias (no corre en *pull request*) |
| Firebase Emulator rules and Functions | Reglas y Functions contra el Emulator Suite |
| Room migration and persistence instrumentation | Migraciones reales en dispositivo virtual |
| Compose, cloud runtime, navigation, and 38-line E2E | Recorrido completo, incluido el escenario demo de 38 líneas |
| SDK 26/36 smoke | Regresión durable en `minSdk` y arranque Hilt de `MainActivity` en `targetSdk`, sin duplicar las suites API 35 |
| Macrobenchmark and StrictMode regression | Rendimiento; cámara, pipeline y lista rechazan IO/Main, fugas y p95 fuera de los quince presupuestos versionados. Inicio frío solo mide arranque |
| Debug, release APK, and unsigned AAB validation | Empaquetado de las variantes y AAB sin firmar |
| Signed cloud release and provenance | AAB firmado, APK universal derivado, checksums y atestación |

Si un job falla, la regla es leer su evidencia antes de tocar código: cada job publica artefactos y
`prepare-ci-artifacts.rb` los normaliza. `summarize-junit.rb`, `summarize-macrobenchmark.rb` y
`summarize-security-results.rb` producen los resúmenes legibles.

Comprobación local equivalente al primer job:

```bash
./gradlew --no-daemon ciStaticAnalysis
```

## 3. Publicar una versión

`signed-release` **solo** corre a demanda en `main`, cuando el despacho incluye explícitamente
`release_version_code` y `release_version_name`, y **solo** después de que los nueve jobs anteriores
pasen. Usa el entorno protegido `android-production`.

Secuencia que ejecuta, en este orden:

1. Exige que el `versionCode` supere `FACTURASTOCK_MAX_DISTRIBUTED_VERSION_CODE` y que el esquema
   Room no sea menor que `FACTURASTOCK_MAX_DISTRIBUTED_ROOM_SCHEMA`.
2. Valida el ancla pública de la clave de carga (`FACTURASTOCK_UPLOAD_CERT_SHA256`, huella SHA-256 de
   64 hexadecimales).
3. Prepara `bundletool` 1.18.3 fijado por versión (`scripts/prepare-bundletool.sh`).
4. Valida los secretos protegidos y materializa el keystore temporal
   (`scripts/prepare-release-signing.sh`).
5. Construye `:app:packageCloudProductionRelease`; esa tarea vuelve a exigir Lint y unitarias de
   `cloudRelease`, y después deriva el APK universal **desde el AAB**
   (`scripts/build-aab-validation-apk.sh`) — nunca se firma un APK aparte.
6. Borra el keystore temporal (`if: always()`).
7. Verifica firmas y genera `release-checksums.sha256` (`scripts/verify-release-artifacts.sh`).
8. Instala ese APK derivado en un AVD, verifica identidad/certificado/bytes, abre, fuerza cierre,
   reabre y rechaza crash o ANR (`scripts/run-release-apk-runtime-smoke.sh`).
9. Atesta la procedencia del AAB y del APK derivado.

Los ocho secretos `FACTURASTOCK_*` viven exclusivamente en ese entorno y se exponen únicamente a los
dos pasos que preparan la firma o construyen. Se citan por nombre; nunca se imprimen.

**Versión.** `versionCode` y `versionName` provienen de `FACTURASTOCK_VERSION_CODE` y
`FACTURASTOCK_VERSION_NAME`; sin ellas el build local usa `1` y `1.0.0`. El despacho de release las
recibe desde `release_version_code` y `release_version_name`. El environment protegido aporta los
máximos distribuidos que bloquean regresiones, pero la CI no consulta Play Console: antes de
iniciarlo hay que confirmar y actualizar esas dos anclas públicas contra la pista más adelantada.

## 4. Respaldo: qué respalda y qué no

Lo que sí hace:

- Al publicar o anular una compra, la **misma transacción** Room que escribe el asiento escribe su
  operación en la outbox. No hay ventana en la que exista el asiento sin su intención de respaldo.
- `WorkManagerPurchaseBackupScheduler` mantiene cadenas inmediatas separadas para respaldo y purga
  con `APPEND_OR_REPLACE`, de modo que un wake llegado durante una pasada no se pierda. Los
  follow-ups usan nombre por deadline + `KEEP`; el mismo instante se deduplica, deadlines distintos
  se conservan y un request temprano crea un sucesor identificado por el ID del work en ejecución.
  Ambos requieren `NetworkType.CONNECTED`; solo respaldo espera batería y almacenamiento no bajos.
  El retroceso WorkManager parte de 30 s y todos los requests son durables ante reinicios.
- Solo un *ack* con la **clave de idempotencia exacta** marca `SYNCED`.
- Para un negocio cloud enlazado, `postSale` comprueba y descuenta el saldo remoto dentro de una
  transacción antes del commit Room. La secuencia de inventario replica después la venta completa,
  deuda opcional, movimientos y saldos a los demás dispositivos; stock insuficiente o red ausente
  no degradan a una venta solo local. `recordDebtPayment` aplica el mismo criterio remoto previo y
  replica el pago y el nuevo saldo sin modificar inventario.
- Sin transporte configurado (flavor `local`) o con el respaldo desactivado, no se programa
  WorkManager. La transacción sí crea la operación Room y la outbox queda honestamente en
  `PENDING_SYNC`.
- El respaldo estructurado y el respaldo de documentos cifrados son dos opt-ins independientes,
  ambos apagados por defecto. Apagar el primero persiste la preferencia antes de solicitar la
  cancelación de WorkManager; si ya existe binding, también bloquea nuevos checkouts de venta para
  no bifurcar el inventario.

Lo que **no** hace, y hay que decirlo en cada conversación de soporte:

- **No restaura todo el dispositivo.** El pull aplica catálogo, inventario, ventas, deudas y pagos
  cloud, pero no reconstruye todos los documentos de compra, fotos, borradores, preferencias ni
  estado de UI.
- **No restaura desde una exportación.** Ajustes permite crear un JSON mediante SAF y confirma
  conteos reales, pero no existe un importador. Exportar es una copia legible, no restauración.
- **No sube imágenes por defecto.** Solo el segundo opt-in documental autoriza transferir un
  JPEG derivado por HTTPS cuando el respaldo comercial también está activo. Storage aplica
  cifrado administrado en reposo; no es E2E. Texto OCR y motivos libres permanecen locales.
- `ReconcileRemoteLedgerUseCase` es **diagnóstico documental**: compara y reporta, con techo de 500
  páginas. No corrige compras; es distinto del aplicador transaccional de inventario compartido.
- `allowBackup="false"` en el manifiesto: Android tampoco hace copia automática.

Estados que puede mostrar una compra: `DRAFT`, `PENDING_SYNC`, `SYNCING`, `SYNCED`, `ERROR`,
`CONFLICT` y el conflicto resuelto conservando la nube. Los estados de la operación en cola son
`PENDING`, `PROCESSING`, `COMPLETED`, `FAILED` y `CONFLICT`.

## 5. Incidentes

Regla general de triage: **primero confirmar si el libro local está intacto**. Si lo está —y lo está
salvo daño del dispositivo— el incidente es de respaldo o de interfaz, no de contabilidad, y no
justifica ninguna acción destructiva.

### 5.1 La cola de respaldo no avanza

Síntoma: compras en `PENDING_SYNC` que no pasan a `SYNCED`.

1. Confirmar sesión: ¿hay correo verificado y membresía vigente? Sin sesión, el comportamiento
   correcto es quedarse en `PENDING_SYNC`.
2. Confirmar el interruptor de respaldo (por defecto apagado) y el flavor instalado: en `local` no hay
   INTERNET y el respaldo no aplica.
3. Confirmar conectividad y las restricciones del worker: batería baja o almacenamiento bajo retrasan
   el drenado por diseño.
4. Revisar en la app la sección de sincronización; si aparece error, anotar el mensaje literal.
5. Ningún paso implica borrar la cola. **Vaciar la outbox no es un procedimiento de soporte.**

### 5.2 Una compra queda en CONFLICT

Significa que la nube ya tiene ese documento, normalmente publicado desde otro dispositivo. La copia
local no se descarta nunca.

1. Comparar ambos registros con la vista de sincronización.
2. Si el registro de la nube es el bueno, la persona autorizada elige **«Conservar versión de la
   nube»**: la operación local no se reintenta más y la compra local permanece intacta.
3. Si el conflicto no se explica, escalar a desarrollo solo con `operationId`, un ID interno de
   incidente y el estado sanitizado. No se copia el número fiscal, la clave de idempotencia ni el
   payload, y no se fuerza un reintento a ciegas.

### 5.3 Sesión vencida

La app muestra el aviso de sesión vencida y detiene el drenado; el trabajo local sigue disponible.
El transporte reacciona a `UNAUTHENTICATED` refrescando el token y reintentando una vez; si vuelve a
fallar, reporta sesión vencida. Procedimiento: volver a iniciar sesión con el mismo correo. Cerrar
sesión **no** borra compras, borradores, la outbox ni su target cloud inmutable en Room: cancela el
drenado y elimina únicamente la sesión y su enlace reconstruible de DataStore.

### 5.4 Deriva de esquema Room

Síntoma: la CI falla con esquema no confirmado, o `verifyRoomSchemaPolicy` /
`verify-room-schema-history.sh` rechazan el cambio.

```bash
./gradlew --no-daemon :app:kspLocalDebugKotlin :app:kspCloudDebugKotlin
git status --porcelain --untracked-files=all -- app/schemas
```

Si aparecen cambios, el esquema exportado no estaba confirmado: hay que versionarlo junto al código
que lo produce. La historia de `app/schemas` es **append-only** desde `git merge-base`: un `N.json`
existente no se edita jamás; una corrección exige una versión nueva con su `Migration`. Está
prohibido `fallbackToDestructiveMigration` en cualquiera de sus formas.

```bash
./gradlew --no-daemon :app:verifyRoomSchemaPolicy
bash scripts/verify-room-schema-history.sh "$(git rev-parse HEAD^)"
```

### 5.5 Artefacto o firma inválidos

Si `verify-release-artifacts.sh` falla, **no se sube nada a Play**. Causas típicas: huella de la clave
de carga distinta de `FACTURASTOCK_UPLOAD_CERT_SHA256`, `versionCode`/`versionName` distintos de los
declarados, o APK que no proviene del AAB. Se corrige la causa y se vuelve a ejecutar el job; el
keystore temporal se borra en cada intento.

### 5.6 Sospecha de secreto expuesto

1. Ejecutar el escáner del repositorio:

   ```bash
   ruby scripts/scan-repository-secrets.rb
   ```

2. Si lo expuesto es un secreto de firma o un secreto `FACTURASTOCK_*`: rotarlo en el entorno
   protegido `android-production`. La clave de **carga** comprometida se maneja con el procedimiento
   de reemplazo de clave de carga de Play Console; la clave de firma de la app la custodia Play.
3. Si lo expuesto es la clave de API de cliente de Firebase: **no es un secreto** y no se rota por
   pánico; se revisan las restricciones de Google Cloud, las reglas de Firestore/Storage y App Check.
4. Registrar el incidente con fecha, alcance y acción tomada. Nunca copiar el valor del secreto al
   registro.

### 5.7 Teléfono perdido, robado, reinstalado o con datos borrados

Este es el incidente más grave del producto. Room desaparece con la desinstalación y
`allowBackup="false"` impide la copia automática de Android. Al volver a enlazar se pueden
materializar catálogo, inventario, ventas, deudas y pagos recibidos por la nube, pero **no existe
restauración integral**. El JSON exportado sirve para custodia/lectura, pero la versión 1.0 no lo
importa.

- Con la cuenta se recupera el estado compartido soportado; compras completas, borradores, ajustes
  locales e imágenes no confirmadas siguen requiriendo el teléfono anterior o un resguardo externo.
- Las fotos sin respaldo documental confirmado se pierden con el dispositivo.
- Acción preventiva: exportar el JSON a un destino controlado y avisar a soporte **antes** de
  desinstalar o cambiar de equipo. No prometer que ese JSON se importa en la app.

### 5.8 El lector físico no abre el producto en Inventario

El receptor solo está habilitado en **Inventario → Existencias → Escáner físico**. No recibe
códigos en **Buscar**, en **Ganancias por producto**, dentro de la trazabilidad ni mientras hay una
operación bloqueante. Cambiar de modo, de pantalla o pausar la app descarta cualquier prefijo
incompleto.

1. Confirmar que Android reconoce el lector USB/Bluetooth como teclado físico HID y que envía
   **Enter**, **Enter de teclado numérico** o **Tab** al terminar. FacturaStock no pide permisos USB
   o Bluetooth ni administra el emparejamiento.
2. Probar un código ya asociado al producto dentro del negocio activo. La comparación es exacta:
   conserva ceros iniciales y mayúsculas/minúsculas.
3. Si aparece «no está asociado», no intentar asociarlo desde Inventario: esa pantalla es de solo
   lectura. Revisar el catálogo del negocio; una asociación nueva sigue siendo una decisión
   explícita del flujo de Ventas.
4. Si el código conocido abre `inventory/{productId}`, verificar allí la trazabilidad. El lookup no
   cambia stock, movimientos, precio ni el producto.
5. No habilitar CAMERA ni agregar ML Kit Barcode Scanning como mitigación: este recorrido usa
   exclusivamente el perfil HID. Para escalar, registrar modelo, Android, adaptador OTG,
   distribución y terminador, sin copiar el código comercial crudo a logs o tickets.

## 6. Rollback

| Qué revertir | Cómo | Advertencia |
| --- | --- | --- |
| Versión en Play | Detener el despliegue por etapas. Si ya llegó a dispositivos, reconstruir el último código conocido como bueno, con la misma identidad/firma y un `versionCode` **nuevo y mayor** | El AAB anterior no se vuelve a subir sin cambios: Play exige un código de versión creciente y no desinstala la versión defectuosa |
| Código | Backportear el comportamiento conocido como bueno sobre el código compatible con el esquema más reciente y dejar que la CI reconstruya | No se recompila sin más un commit antiguo: el nuevo `versionCode` es mayor y conserva entidades/migraciones ya distribuidas |
| Esquema Room | **No hay rollback de esquema.** Una base migrada no vuelve a una versión anterior | Cualquier corrección es una versión nueva hacia adelante con su `Migration` |
| Reglas Firestore/Storage e índices | Volver a desplegar las definiciones anteriores de `firestore.rules`, `storage.rules` y `firestore.indexes.json` mediante `firebase.json` | Verificar antes con el Emulator Suite |
| Cloud Functions | Volver a desplegar una revisión compatible con los contratos ya emitidos | El pull de inventario sí escribe la materialización Room; no desplegar una revisión que omita versiones/feed que clientes activos todavía consumen |

Regla: **un rollback nunca toca datos de usuario**. Si la única forma de «arreglar» algo fuera borrar
o reescribir asientos, se detiene y se escala.

La secuencia y los gates ejecutables están en
[`rollout-and-hotfix-policy.md`](play/rollout-and-hotfix-policy.md): interna → 1% → 5% → 20% →
50% → 100%, siempre con el mismo AAB y sin regresar Room.

## 7. Problemas conocidos de la versión 1.0

Se listan sin adornos: son limitaciones reales verificadas en el código, no riesgos hipotéticos.

| # | Problema | Impacto | Mitigación mientras exista |
| --- | --- | --- | --- |
| 1 | **No hay restauración integral.** El pull reconstruye catálogo, inventario, ventas, deudas y pagos cloud, pero el JSON no tiene importador y quedan datos solo locales | El teléfono nuevo no reproduce todo el libro, imágenes, ajustes ni borradores | Exportar antes de cambiar de equipo, custodiar el archivo y verificar ambos dispositivos antes de retirar el anterior |
| 2 | **La CI no consulta Play Console** | Las anclas máximas del environment pueden quedar obsoletas | Compararlas con la pista más adelantada y actualizarlas **antes** de iniciar `signed-release` (sección 3) |
| 3 | **El umbral de cobertura Kover (80 %) solo aplica a `com.facturastock.app.domain.*`** | `feature`, `data/local` y `data/repository` no tienen piso de cobertura | Mantener las pruebas UI/instrumentadas y revisar sus conteos; ampliar Kover si se acuerda un nuevo ámbito |
| 4 | **No hay análisis CodeQL/SARIF en la CI** | Algunas clases de vulnerabilidad de código no tienen un analizador dedicado | Se mantienen análisis Kotlin/Android, Dependency Review, npm audit y escaneo de secretos |
| 5 | **`devEnsureMembership` se exporta en Functions**, aunque su compuerta solo permite Emulator Suite | Superficie de función innecesaria en el bundle productivo | La compuerta impide su uso fuera del emulador y tiene prueba; retirarla del export en una revisión posterior |
| 6 | **Los presupuestos físicos de Prompt 47 no están aceptados.** Tras optimizar y remedir, el parser del AVD ya cumple, pero la captura virtual y los frames de la lista de 100 líneas aún exceden sus límites | Riesgo de captura lenta y scroll poco fluido en gama media | Continuar la lista y ejecutar 30 muestras más cámara real, TalkBack y fuente 200 % en Pixel 6a físico; no convertir el AVD en aprobación |
| 7 | **`npm audit` reporta 5 avisos moderados transitivos: 2 están en producción y 3 solo en tooling** | No hay avisos altos/críticos; los 2 de runtime proceden de `gaxios@6.7.1` → `uuid@9` | Mantener el gate alto/crítico y actualizar cuando exista una resolución compatible; no forzar otro major de `gaxios` sin soporte de sus consumidores |
| 8 | **No existe candidato productivo ni instalación por pista interna** | No se han validado firma real, Firebase productivo, política pública ni procesamiento de Play | Completar los ocho valores release, identidad/URLs legales, verificar artefactos y registrar la instalación interna antes de publicar |
| 9 | **El lector de Ventas e Inventario admite solo el perfil de teclado físico HID y no está certificado con un modelo real concreto** | Un lector serial/SPP, una distribución de teclado o un sufijo distintos pueden no entregar el código esperado | Configurar USB/Bluetooth como *keyboard wedge* con Enter/Tab y ejecutar el checklist de Ventas y de `Inventario → Existencias` con el modelo y adaptador OTG que usará el negocio |
| 10 | **Ventas compartidas no tienen anulación ni exportación JSON completa** | Una venta errónea no tiene reversión guiada y el JSON v4 no reconstruye ventas, deudas ni abonos | Revisar antes de confirmar y planificar una reversión auditada antes de uso productivo crítico; Firebase no equivale a backup integral del teléfono |

## 8. Registro de incidente

Se anota en el canal de soporte del proyecto, con este contenido mínimo y **sin datos personales,
RUC, números de documento reales ni valores de secretos**:

- Fecha y hora con zona (`America/Lima`).
- Versión instalada (`versionName` / `versionCode`) y flavor (`local` o `cloud`).
- Síntoma literal que vio la persona, incluido el texto del aviso.
- Qué se verificó y qué no se pudo verificar.
- Estado del libro local: intacto o comprometido.
- Acción tomada y si quedó pendiente algo.

Si el incidente terminó sin explicación, se registra así. Un incidente cerrado sin causa es
información útil; un incidente cerrado con una causa inventada no lo es.
