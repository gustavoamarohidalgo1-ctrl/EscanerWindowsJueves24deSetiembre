# Auditoría Fase F — Offline, nube, privacidad y seguridad (prompts 36–43)

Fecha: 22 de agosto de 2026 (`America/Lima`). Esta evidencia corresponde a revisión de código,
pruebas JVM, Firebase Emulator Suite, instrumentación completa en un AVD Pixel 10a API 37, Lint,
Kover, R8 y análisis estático. Los datos de prueba son ficticios.

Esta auditoría **no** demuestra un despliegue Firebase productivo, enforcement de App Check en
Firebase Console, una política de privacidad pública y revisada, una firma de distribución, una
pista de Google Play, un teléfono físico ni un piloto con personas. Los APK release construidos
para comprobar R8 son unsigned y no son candidatos para publicar.

## Matriz final estricta

| # | Objetivo y criterios | Resultado | Evidencia del contrato final |
| --- | --- | --- | --- |
| 36 — Consolidar Room como fuente única offline | Todo salvo el respaldo funciona en modo avión; un reinicio no pierde datos; los errores no borran borradores; repositorios probados sin red | **CUMPLE EN CÓDIGO Y AUTOMATIZACIÓN** | La UI observa Room mediante repositorios y `Flow`; OCR, revisión, catálogos, compras e inventario no leen Firebase. El flavor `local` no incluye SDK Firebase ni permiso `INTERNET`. Los estados editables, snapshots, archivos privados y libro contable sobreviven a recreación/reinicio. Los commits críticos son transaccionales y los fallos de almacenamiento conservan el último estado durable en vez de borrar el borrador. La suite completa en dispositivo incluye repositorios Room, rutas de migración `1→20`, reinicio y recorrido demo offline. |
| 37 — Implementar outbox y WorkManager | Offline crea outbox; reconectar drena automáticamente; reinicio conserva; se prueban éxito, 5xx, conflicto y repetición | **CUMPLE EN CÓDIGO Y AUTOMATIZACIÓN** | Publicar/anular y sus operaciones de respaldo se escriben en la misma transacción Room. WorkManager usa trabajo único, red conectada, backoff durable, claim CAS con token/lease y follow-up según próximo intento. El worker vuelve a leer sesión, binding y preferencias entre operaciones; las purgas de privacidad usan un canal separado que puede ejecutarse con el respaldo comercial apagado. Un ACK solo completa con la idempotency key exacta; 5xx reintenta, conflictos quedan visibles y un replay no duplica. Startup aísla recuperación, bootstrap y cada enqueue para que un fallo no omita los pasos restantes. |
| 38 — Configurar Firebase como respaldo opcional | Flavor y emuladores de desarrollo; sin Firebase el flujo local funciona; callable repetida devuelve el mismo resultado; ninguna imagen sale sin consentimiento | **CUMPLE EN CÓDIGO Y EMULADORES. GATE EXTERNO** | `local` permanece sin red/Firebase; `cloud` incorpora Auth, Firestore, Functions, Storage, App Check, Analytics y Crashlytics mediante dependencias por flavor. Debug usa Emulator Suite y provider App Check de depuración; release usa Play Integrity. Sin configuración cloud el transporte queda `configured=false` y no finge un respaldo. Callables y outbox son idempotentes. Las imágenes requieren los dos opt-ins apagados por defecto; se envía por HTTPS un JPEG derivado y Storage aplica cifrado administrado en reposo, no E2E. Falta configurar y desplegar un proyecto real. |
| 39 — Autenticación, negocios y roles | El negocio A no accede al B; OPERATOR no cambia roles; tokens no aparecen en logs; se prueban acceso, revocación y cambio de negocio | **CUMPLE EN CÓDIGO Y EMULADORES. GATE EXTERNO** | Firebase Authentication, negocio activo y membresía están separados del perfil local. OWNER/ADMIN/OPERATOR/READER se derivan de la membresía server-side; el cliente no afirma su rol. Las invitaciones, protección del último OWNER, revocación, cambio de negocio y tenant pinning de outbox fallan cerrado. Los logs y errores usan códigos cerrados y no conservan tokens. La eliminación de cuenta exige reautenticación cliente y `auth_time` reciente en Functions. Falta validación operacional con cuentas y proyecto productivos. |
| 40 — Crear y probar reglas Firestore/Storage | Deny cross-tenant; el cliente no escribe stock; un archivo ajeno no se descarga; reglas y despliegue versionados | **CUMPLE EN REGLAS Y EMULADORES. GATE EXTERNO** | Firestore es default-deny: lectura exige auth/membresía y compras, movimientos, auditoría, índices y membresías solo se escriben por Functions/Admin SDK. Storage limita lectura al miembro verificado del tenant y niega todo create/update/delete del SDK cliente; upload y purga pasan por callable con validación de rol, compra, tenant, JPEG, tamaño y SHA-256. Reglas, índices, TTL y comandos de rollout están versionados. No se ejecutó `firebase deploy` ni se activó App Check enforcement en consola. |
| 41 — Sincronizar, reconciliar y mostrar conflictos | Reenvío crea un único remoto; conflicto no sobrescribe; comparación local/nube entendible; dos dispositivos no duplican documento | **CUMPLE EN CÓDIGO Y EMULADORES. GATE EXTERNO** | Push-then-pull usa cursor monotónico consumido y una caché Room; el pull nunca reescribe el libro local. La identidad documental es estructurada y conserva ceros. Un reenvío exacto converge al mismo receipt; dos dispositivos con el mismo documento reciben conflicto en vez de overwrite. La UI muestra estados comerciales y documentales, comparación local/remota, ambigüedades de catálogo, conflicto y resolución auditada; la reconciliación es diagnóstica y no corrige saldos. El payload vigente es v3/documento v2 y reproduce v2/documento v1 sin backfill. Falta un ensayo real multi-dispositivo contra backend desplegado. |
| 42 — Implementar privacidad y ciclo de vida | Inventario de datos y política coincidente; retención exacta; imágenes privadas/cifradas; limpieza; exportación y borrados con resultado real; backup y eliminación de cuenta desde Android | **CUMPLE EN CÓDIGO Y AUTOMATIZACIÓN. POLÍTICA PÚBLICA PENDIENTE** | OCR siempre local. Originales y derivados viven en almacenamiento privado; retenidos usan AES-GCM con clave no exportable de AndroidKeyStore. Se implementaron tras OCR, tras confirmar, 30/90 días y conservar, con confirmación destructiva, pasada inmediata/periódica, purga durable antes del borrado y estados completo/parcial/fallido. El export `ACCOUNTING_LEDGER` schema 3 incluye libro contable y auditoría, sin bytes/rutas/tokens/borradores/OCR. La UI separa borrar local, purge cloud, opt-ins y eliminación de cuenta verificable. La plantilla pública conserva marcadores y no fue alojada ni revisada externamente. |
| 43 — Endurecer Android y secretos | Mínimo privilegio, componentes/URIs/PendingIntent/backups/cleartext/screenshots seguros; Keystore y tokens; logs sin PII; R8; biometría opcional recuperable | **CUMPLE EN CÓDIGO Y AUTOMATIZACIÓN. NO ES RELEASE** | Manifiestos niegan backup y cleartext, limitan componentes/exported y usan URIs temporales/SAF; pantallas sensibles aplican `FLAG_SECURE`. No se persisten contraseñas y Firebase mantiene los tokens. Logging/Analytics usan allowlists sin contenido fiscal; Crashlytics está solo en `cloud`, con colección automática, excepciones manuales y subida de mapping desactivadas. R8/resource shrinking pasan en ambos flavors. El bloqueo opcional admite biometría fuerte o credencial del dispositivo y solo se lanza con Activity reanudada. Falta firma/configuración productiva y prueba desde Play. |

## Cambios clave consolidados

1. **Room v20 y tenant fijado.** La outbox, cursores, caché remota, enlaces de catálogo y estado
   documental permanecen durables. Cada operación remota queda fijada al negocio local y al tenant
   cloud que existían al crearla; un login o cambio de negocio posterior no puede apropiársela.
   Operaciones legacy ya intentadas sin destino demostrable quedan
   `LEGACY_DESTINATION_UNKNOWN`, no se reasignan ni habilitan borrado local.
2. **Outbox y scheduler honestos.** Los estados `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`,
   `CONFLICT` y `RESOLVED` se derivan de Room. Claim, lease, ACK exacto y dependencias
   alta→anulación evitan duplicación. El trabajo comercial y el canal mínimo de purga tienen
   constraints y nombres únicos separados; apagar respaldo cancela trabajo regular sin impedir
   solicitudes explícitas de eliminación.
3. **Catálogo y pull multi-dispositivo.** Las mutaciones de proveedor/producto/unidad/almacén usan
   envelopes tipados, versión y tenant; el aplicador local detecta conflicto o ambigüedad sin
   adivinar equivalencias. El pull avanza el cursor solo después de persistir la página y la UI
   presenta operaciones documentales y conflictos sin rutas internas ni mensajes del backend.
4. **Wire compatible y duplicados server-side.** Las compras nuevas usan
   `payloadVersion=3 → document.version=2`, con impuesto/evidencia/procedencia. El replay
   `payloadVersion=2 → document.version=1` conserva wire y hash históricos y no se reescribe a
   ciegas. Una excepción de duplicado entrega target/borrador/evento/motivo; Functions deriva el
   rol, revalida negocio/identidad/estado, conserva el índice principal y minimiza lo persistido.
5. **Functions y reglas fail-closed.** Membresía y rol se vuelven a comprobar dentro del backend;
   límites de tamaño/escrituras, sumas `BigInt`, idempotencia, conflictos, secuencia de pull y
   borrado de cuenta están acotados. Firestore y Storage niegan cross-tenant; Android no escribe
   stock ni objetos de Storage directamente.
6. **Documento con doble consentimiento.** Solo ambos opt-ins permiten preparar un JPEG derivado.
   El preparador limita el tamaño fuente y los píxeles decodificados; un OOM se convierte en error
   permanente cerrado. Upload/purge usan callable/Admin. Una vista remota es temporal en memoria,
   revalida sesión, binding, opt-ins y `isBackedUp` antes de I/O y antes de entregar bytes; no se
   presenta como restauración local.
7. **Retención y cifrado recuperables.** La purga se hace durable antes de borrar una fuente que
   pudo salir. El `.fse` derivado se elimina y verifica sin esperar red; si falla queda señal de
   retry y el barrido de reinicio elimina huérfanos. El envelope AES-GCM valida magic, versión,
   nonce, longitud y tag; un archivo corrupto se reporta y no se sobrescribe silenciosamente.
8. **Privacidad accionable.** Ajustes muestra resultados reales de exportación SAF, borrado local,
   mantenimiento, retención, respaldo, diagnóstico y eliminación de cuenta. Los fallos parciales
   no se presentan como éxito; si un provider SAF no permite confirmar la limpieza de un destino
   parcial, se muestra una advertencia. El export contable es consistente y excluye información
   operativa/temporal no prometida.
9. **Release endurecido, no publicado.** Los flavors release ejecutan R8 y resource shrinking; los
   gates revisan manifiestos, cleartext, PII, dependencias Firebase por flavor y configuración.
   Crashlytics permanece sin canal de envío y App Check sigue deliberadamente en monitor hasta
   disponer de métricas reales.

## Hallazgos encontrados y corregidos durante la auditoría

La revisión no se limitó a confirmar el estado inicial. Encontró y corrigió, entre otras, estas
brechas:

- La outbox podía observar el negocio cloud de una sesión posterior. Se añadió tenant pinning a
  esquema y transporte, además de bloqueo explícito para operaciones legacy ambiguas.
- Desactivar respaldo podía dejar una pasada ya iniciada enviando más operaciones o cancelar el
  mismo canal necesario para purgar. El worker relee la preferencia entre operaciones y separa
  trabajo comercial de privacidad.
- Un fallo de WorkManager durante el arranque podía impedir recuperaciones posteriores. Cada paso
  de startup quedó aislado y best-effort, preservando `CancellationException`.
- Retención podía reportar éxito aunque fallaran archivos, cifrar una foto que la UI ya no podía
  leer, borrar sin despertar mantenimiento inmediato o ignorar borradores con OCR publicado. Se
  añadieron resultados parciales, lectura descifrada en memoria, consulta Room precisa y hooks
  seguros que registran purge antes de borrar.
- El export anterior no era un libro contable completo y exponía rutas internas. El schema 3 añade
  catálogos, alias, saldos, movimientos y auditoría de todo el negocio, incluso eventos sin compra,
  y elimina rutas/bytes/secretos.
- Un prefijo `FSE1` aislado se aceptaba como imagen cifrada y una fuente de dimensiones extremas
  podía reservar un bitmap desproporcionado. Se endureció el parser del envelope y el muestreo por
  píxeles/tamaño con fallo cerrado ante recursos insuficientes.
- El fallback documental comprobaba consentimiento demasiado pronto. Ahora lo revalida antes de
  consultar metadata, antes de descargar y antes de entregar; una revocación limpia el buffer.
- La eliminación de cuenta solo validaba autenticación. Android reautentica y Functions exige un
  `auth_time` reciente antes de leer o mutar datos.

La primera corrida completa de instrumentación detectó además cinco regresiones concretas; todas
se corrigieron antes del resultado final 424/424:

1. El E2E Hilt intentaba avanzar con `taxTreatment=UNKNOWN`. El guion ahora toma una decisión fiscal
   explícita; no se amplió el timeout ni se relajó el bloqueo.
2. `CatalogRepositoriesTest` suponía que UUID tipados con el mismo valor debían desaparecer también
   del `entityId` legítimo. La aserción distingue la identidad del envelope de UUID locales dentro
   del snapshot semántico.
3. `OfflineRoomRestartRepositoryTest` construía una anulación con metadata legacy incompleta. El
   fixture declara `entityType=PURCHASE`, `entityId=purchaseId` y `entityVersion=2`.
4. `withExactLinks` comparaba `null == null` y ejecutaba `requireNotNull` sin snapshot persistido,
   lo que podía romper un unlink exacto ante una publicación obsoleta. El guard exige que el
   snapshot exista y conserva la desvinculación con procedencia desconocida.
5. `InvoiceLineReviewScreenTest` trataba una línea con impuesto desconocido como revisada. El
   fixture resuelve `EXCLUDED` con IGV explícito y deja intacto el bloqueo productivo para
   decisiones desconocidas.

## Evidencia automatizada global final

Los siguientes resultados corresponden al árbol final posterior a todas las correcciones. A
diferencia de los focales históricos, las tres primeras filas son suites completas y sus conteos
no deben sumarse entre flavors.

| Grupo | Comando o alcance | Resultado final |
| --- | --- | --- |
| JVM local | `:app:testLocalDebugUnitTest` | **954/954**, 126 suites, 0 fallos, errores u omitidos |
| JVM cloud | `:app:testCloudDebugUnitTest` | **1027/1027**, 139 suites, 0 fallos, errores u omitidos |
| Android local completo | `./gradlew --no-daemon :app:connectedLocalDebugAndroidTest` | **424/424** en Pixel 10a AVD API 37, 0 fallos u omitidos; **BUILD SUCCESSFUL en 2 min 53 s** |
| Cobertura | `:app:koverVerify :app:koverXmlReportLocalDebug` | Verify verde; **10.431/11.723 líneas = 88,98 %**, sobre el piso de 80 % |
| Firebase Emulator Suite | Auth + Firestore + Functions + Storage, incluida la suite completa de Functions | **106/106**, 0 fallos |
| Android Lint | `:app:lintLocalDebug` y `:app:lintCloudDebug`, ejecutados por flavor | **0 errores y 61 warnings por flavor** |
| Release/R8 | `:app:assembleLocalRelease` y `:app:assembleCloudRelease`, ejecutados por flavor con `:app:verifyAndroidReleaseConfiguration` | Ambos flavors minificados y lintVital/configuración verdes; APK unsigned |
| Análisis estático | `./gradlew --no-daemon ciStaticAnalysis` | Verde: Spotless, logging de Functions y fronteras de dominio, UI, PII, seguridad móvil, OCR local, App Check, offline-first y Room |
| Workflow y secretos | `run-actionlint.sh`, `scan-repository-secrets.rb`, `test-prepare-ci-artifacts.rb`, `test-resolve-spotless-base.sh` | actionlint, escaneo de secretos, política de artefactos y resolución de baseline verdes |

Los reportes finales en disco confirman `424` tests de dispositivo sin fallos, `954` local JVM,
`1027` cloud JVM y los contadores Kover indicados. Los focales de
[`2026-08-22-fase-f-42-43.md`](2026-08-22-fase-f-42-43.md) explican casos de privacidad,
cifrado, SAF, límites de decode, tenant legacy y revalidación remota, pero no se suman a estos
totales globales.

## Gates externos y límites que permanecen abiertos

- **Firebase:** no hubo proyecto ni credenciales reales y no se ejecutó `firebase deploy`.
  Functions, reglas, índices y Storage se probaron con Emulator Suite. El rollout requerido es
  Functions/reglas compatibles primero y Android después.
- **App Check:** debug/release incorporan sus providers y los callables están instrumentados para
  monitorización, pero `enforceAppCheck` permanece desactivado. Faltan métricas reales y activar
  enforcement de Functions, Firestore y Storage en Firebase Console.
- **Política y Data Safety:** la plantilla conserva marcadores de responsable legal, contacto,
  plazos y URLs. Falta revisión legal/operacional, alojarla por HTTPS y trasladar exactamente sus
  declaraciones a Play Console.
- **Release:** los APK de esta auditoría son unsigned. Faltan keystore de carga autorizado,
  configuración Firebase productiva, URL de privacidad, versión aprobada, comprobación del
  certificado y distribución por una pista de Play.
- **Validación humana:** no se ejecutaron el guion completo con personas, un teléfono físico, dos
  dispositivos reales ni facturas reales. El fixture demo y el Emulator Suite no sustituyen esas
  actividades.
- **Dependencias y servicios externos:** los gates verdes describen el snapshot auditado; la
  operación debe revisar vulnerabilidades, retenciones reales de Firebase/Analytics/FID/App Check
  y alertas del proyecto antes de liberar.

## Conclusión de Fase F

Los criterios alcanzables en código de los prompts 36–43 quedan implementados y cuentan con cierre
global JVM, dispositivo, Emulator Suite, cobertura, Lint, R8 y análisis estático verde. Esto permite
declarar **Fase F completa en código y automatización**, no un backend productivo, una política
publicada, un release distribuible ni un piloto aprobado. Esos gates externos permanecen separados
y explícitos.
