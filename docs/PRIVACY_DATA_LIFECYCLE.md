# Privacidad y ciclo de vida de datos

Este documento describe el comportamiento implementado de FacturaStock y sirve como fuente
técnica para la política pública. Está escrito **contra el código**: si la implementación cambia,
este documento cambia en el mismo commit. No equivale a una política ya publicada. La distribución
queda bloqueada hasta reemplazar los marcadores de identidad/contacto, alojar URLs HTTPS reales y
verificar que la política pública configurada para el release coincide con este texto.

## Inventario de datos

| Dato | Finalidad | Procesamiento | Retención | Cifrado |
| --- | --- | --- | --- | --- |
| Foto del comprobante | OCR local y evidencia de la compra | Original privado en `filesDir/draft_images/…` (`data/files/LocalDraftImageImporter.kt`). No sale por defecto. Solo en `cloud`, con respaldo comercial **y** documental activos y una compra publicada, se prepara una copia JPEG derivada y se transfiere por HTTPS; el texto OCR no acompaña al archivo | Política elegida por el usuario: tras OCR / tras confirmar / 30 / 90 días / conservar (defecto). Una intención de purga cloud, cuando aplica, queda durable antes de borrar la copia local | Local retenida: AES/GCM 256 con clave no exportable de AndroidKeyStore (`data/files/RetainedImageCipher.kt`). Tránsito remoto: HTTPS. Firebase Storage aplica cifrado administrado en reposo; no es cifrado de extremo a extremo |
| Foto usada para importar productos | Detectar filas de producto con OCR local | Borrador privado del dispositivo; no se publica como compra ni documento remoto | Se elimina junto con el snapshot OCR y el borrador después de guardar o reconocer como existentes los productos seguros. Ante fallo se conserva para reintentar | Sandbox privado de Android durante el procesamiento |
| Versiones de trabajo OCR (grises, ≤2048 px) | Mejorar la lectura ML Kit | Solo dispositivo: `draft_images/{draftId}/ocr/…` (`data/files/LocalInvoiceImagePreprocessor.kt`) | Se purgan al confirmar la compra y en cada mantenimiento | Sandbox privado; no son el artefacto documental remoto ni sobreviven como imagen retenida |
| Datos de la compra (proveedor, RUC, comprobante, montos, líneas) | Registro contable e inventario | Local: Room (`data/local/`). Nube (opcional, flavor `cloud` con sesión verificada): Firestore vía `postPurchase` | **Nunca se borran automáticamente**: la normativa contable puede exigir conservarlos; la retención solo toca la foto | Local: sandbox de la app. Nube: TLS en tránsito + cifrado en reposo de Firestore |
| Lectura cruda de un lector HID | Identificar un producto durante una venta o consultar su inventario | Solo memoria: el `KeyboardWedgeAssembler` ensambla eventos de teclado físico mientras hay un receptor explícito y la app está desbloqueada. No conserva la última lectura completa ni la envía a logs, Analytics, Crashlytics u outbox | Hasta completar, cancelar, pausar la app, cambiar de dispositivo o superar el timeout; una trama >128 se descarta completa | Memoria del proceso y sandbox de Android |
| Código asociado a un producto | Identificación posterior del producto | Al confirmar la asociación pasa a `products.barcode` en Room. En `cloud`, el respaldo opcional de catálogo puede incluirlo como parte del producto; reemplazar un código existente exige confirmación | Mientras exista el producto; no hay retención automática específica para códigos | Sandbox local. Si se respalda el catálogo: TLS + cifrado en reposo de Firestore |
| Precio de venta del producto | Proponer el precio en el carrito y estimar ganancia frente al costo de inventario | Room lo conserva en unidades menores y moneda. En `cloud`, el catálogo y las líneas de venta sincronizadas pueden incluir ese precio; no se envía un reporte de utilidad realizada | Mientras exista el producto; puede sustituirse mediante una edición explícita | Sandbox local; en cloud, TLS + cifrado administrado en reposo de Firestore |
| Venta y líneas de venta (producto/almacén/código instantáneo, cantidad, precio y totales) | Carrito, checkout, trazabilidad y stock compartido | Room siempre. En un negocio `cloud` enlazado, `postSale` recibe la venta completa, reserva inventario transaccionalmente y la publica en un feed legible por los miembros; la lectura HID cruda no se envía | El borrador local se conserva para reanudarlo; la venta publicada no tiene borrado automático ni anulación v1. La copia cloud vive con el negocio | Sandbox local; en cloud, TLS + cifrado administrado en reposo de Firestore |
| Cuenta por cobrar (nombre del deudor, venta/productos, saldo, vencimiento opcional) | Saber quién debe los productos de una venta a crédito y cuánto queda pendiente | Room crea la deuda únicamente junto a una venta `POSTED`. En un negocio `cloud` enlazado, Functions crea y versiona la misma cuenta dentro del checkout; los miembros pueden leerla, pero el cliente no escribe Firestore directamente | Historia comercial sin borrado automático. No se sobrescribe el nombre instantáneo ni el importe original; la copia cloud vive con el negocio | Sandbox local; en cloud, TLS + cifrado administrado en reposo de Firestore |
| Abonos de deuda (importe, método, nota/referencia opcionales y fecha) | Reducir el saldo y conservar el historial de cobro | Room append-only. En cloud, `recordDebtPayment` autoriza por rol, saldo y versión antes del commit local y publica el resultado en el feed del negocio. El documento no conserva el UID de quien pagó o registró | Permanentes junto a la cuenta por cobrar; un abono no se edita ni elimina desde la app | Sandbox local; en cloud, TLS + cifrado administrado en reposo de Firestore |
| Texto OCR crudo y métricas de confianza | Revisión asistida | Solo dispositivo (`docs/LOCAL_OCR.md`); la nube nunca lo recibe | Con el borrador/compra que lo originó | Sandbox |
| Producto detectado en una factura | Añadir un nombre seguro al catálogo sin registrar una compra | Room; solo nombre y unidad. No se copian cantidades, costos, precios, proveedor ni datos del comprobante. En `cloud`, la outbox de catálogo puede respaldarlo con el opt-in comercial activo | Mientras exista el producto; se evita duplicarlo por nombre normalizado | Sandbox local; para respaldo opcional, TLS y cifrado administrado de Firestore |
| Cuenta (email) y sesión | Activar la sincronización | Firebase Authentication, solo flavor `cloud` | Hasta que el usuario elimina la cuenta | Gestionado por Firebase Auth; los tokens jamás se registran (`verifyNoSensitiveLogging`) |
| Enlace local ↔ nube y cursores de sync | Saber a qué negocio nube respaldar y por dónde va el pull | Solo dispositivo: DataStore `account_settings` (`CloudAccountSettingsStore`) | Hasta cerrar sesión (`clear()` los olvida) | Sandbox |
| Configuración (impuestos, política de retención, respaldo y diagnósticos activos) | Operación de la app | Solo dispositivo: DataStore `app_settings` (`data/settings/`) | Hasta desinstalar o borrar datos | Sandbox |
| Bitácora de auditoría | Trazabilidad de confirmaciones, anulaciones, ajustes, conflictos y revisiones | Local: Room append-only; payload cerrado con UUID internos, enums, booleanos y conteos. No contiene motivos, importes, documento, texto ni hashes derivados del contenido | Permanente (es el registro de control) | Sandbox |
| Diagnóstico operacional opcional | Saber si captura, edición, ajuste, confirmación, anulación o sync terminan, fallan o requieren reintento | Solo flavor `cloud`, tras opt-in explícito: los atributos definidos por la app son nombre de acción, resultado, código de error cerrado y tramo de antigüedad; no incluyen UUID ni contenido fiscal. Analytics puede adjuntar metadatos técnicos automáticos de app, sesión y dispositivo. La app no envía contenido, razones, importes, rutas, `Throwable` ni propiedades de usuario. El SDK de Crashlytics está presente en `cloud`, pero su colección automática permanece siempre desactivada y la app no le envía excepciones | Desactivado por defecto. Al revocar se detiene Analytics y se borra su estado local; lo ya agregado en Firebase sigue la retención/borrado configurada por el operador del proyecto | TLS y controles de Firebase |

## Terceros

- **Google Firebase**: Authentication, Firestore, Functions, Storage y App Check solo participan
  en el flavor `cloud`. La imagen no sale por iniciar sesión: exige los dos opt-ins de respaldo y
  una compra publicada. Firebase Analytics usa un consentimiento separado, explícito y apagado
  por defecto. El SDK de Crashlytics está incluido solo en `cloud`, con colección automática
  forzada a `false`, sin canal de excepciones manuales y sin subida de mapping; al revocar
  diagnósticos se descartan reportes no enviados si Firebase ya estaba inicializado. El APK
  `local` no contiene SDK de red (lo garantiza `verifyOfflineFirstBoundaries`).
- **ML Kit (Google ML Kit on-device)**: solo se empaqueta Text Recognition para OCR local; ninguna
  imagen ni texto sale hacia servicios de Google. Barcode Scanning no es una dependencia de la
  app: los códigos de venta e inventario llegan exclusivamente desde un teclado físico HID; las
  búsquedas manuales no capturan códigos con la cámara.
- No hay publicidad. Analytics deniega siempre `AD_STORAGE`, `AD_USER_DATA` y
  `AD_PERSONALIZATION`; la recolección de Advertising ID, las señales de personalización y
  el reporte automático de pantallas están desactivados en el manifiesto, y se eliminan los
  permisos `AD_ID`/AdServices aportados por dependencias transitivas.

## Lo que ninguna otra app puede ver

Las imágenes viven en el almacenamiento interno privado (`context.filesDir`), sin
`FileProvider`, sin `MediaStore` y sin permisos compartidos en el manifiesto productivo: el
sandbox de Android las hace inaccesibles a otras aplicaciones. Tras el commit de confirmación, el
hook cifra inmediatamente las fotos que permanecerán retenidas (formato
`[4B "FSE1"][12B IV][cifrado+tag]`); la clave AES/GCM vive
en AndroidKeyStore y no es exportable. Hasta esa pasada siguen dentro del sandbox privado. La
lectura para mostrarlas descifra bytes en memoria (`RetainedImageStore.readDecrypted`), sin
archivo temporal en claro. Para un respaldo documental se genera una copia JPEG derivada en
memoria y un artefacto privado AES-GCM; el transporte autorizado la envía por HTTPS y Storage la
cifra de forma administrada en reposo. Esta protección remota no es E2E. Los metadatos
EXIF/XMP/geolocalización se eliminan al importar (`data/files/ImageMetadataScrubber.kt`).

## Políticas de retención de imágenes

Configurables en Ajustes → Privacidad y datos (`image_retention_policy` en
`data/settings/AppSettingsDataStore.kt`; modelo `ImageRetentionPolicy` en
`domain/model/PrivacyModels.kt`). Es una preferencia global de la instalación: el mantenimiento la
aplica a las imágenes terminales de todos los negocios locales, no solo al seleccionado:

| Política | Momento exacto del borrado |
| --- | --- |
| Eliminar tras OCR | Al publicarse un resultado OCR que cubre las páginas activas (hook en `RunInvoiceOcrUseCase` → `ApplyImageRetentionAfterOcrUseCase`). El mantenimiento reintenta solo borradores que demuestran ese snapshot; un ingreso manual sin snapshot no se trata como OCR publicado |
| Eliminar tras confirmar | Después del commit durable de la compra. El hook consulta solo las páginas de ese borrador, deja primero cualquier `SYNC_DOCUMENT_PURGE` necesario y luego borra el archivo local |
| 30 / 90 días | Es elegible exactamente cuando `postedAt + días <= ahora` (`ImageRetentionDecider`, con pruebas de borde). La ejecución ocurre en la pasada inmediata solicitada por la UI o en el mantenimiento periódico posterior al vencimiento |
| Conservar (defecto) | Nunca |

El borrado por fecha lo aplica el mantenimiento diario (`data/privacy/PrivacyMaintenanceWorker.kt`,
`PeriodicWorkRequest` de 24 h, con batería no baja y backoff exponencial) y también un trabajo
único inmediato al confirmar, al arrancar o al cambiar a una política potencialmente destructiva.
Cada ejecución inmediata acota una cadena defectuosa a tres intentos; el siguiente evento o la
pasada diaria vuelven a intentarlo sin un loop perpetuo. El mantenimiento barre temporales de
importación, caché, temporales
privados conocidos de OCR/cifrado, carpetas de borradores huérfanos y versiones OCR de compras
confirmadas, derivados documentales `.fse` sin upload Room abierto, y migra a cifrado las retenidas
históricas
aún en claro. Cada etapa y archivo devuelve un estado cerrado; un fallo parcial se muestra como
advertencia/error y las demás etapas continúan.

Las mutaciones de `draft_images/{draftId}` comparten exclusión entre importación, OCR, cifrado,
borrado y barridos. Una carpeta aparentemente huérfana debe tener al menos una hora y Room se
revalida dentro de ese lock inmediatamente antes de eliminarla; un borrador creado después del
snapshot sobrevive. El cifrado publica por temporal autenticado y movimiento atómico, procesa la
fuente en chunks de 16 KiB y permite paralelismo entre borradores sin duplicar plaintext y
ciphertext completos en memoria.

Antes de retirar una imagen local que pudo respaldarse, `RunPrivacyMaintenanceUseCase` exige
una intención fijada al tenant exacto o demuestra `NOT_REQUIRED`. Una operación legacy que pudo
salir sin tenant demostrable devuelve `LEGACY_DESTINATION_UNKNOWN`: no se inventa el destino, se
conserva/cifra la fuente y se solicita revisión manual. `DURABLE` significa que la solicitud quedó
guardada en la outbox; **no** significa que Storage ya confirmó el borrado. Antes de volver, Room
elimina y verifica también el JPEG derivado AES-GCM, sin esperar red. Si ese archivo local se
resiste, devuelve `DURABLE_ARTIFACT_RETRY_REQUIRED`, mantiene visible el trabajo y el
barrido/reinicio lo reintenta; la purga remota sigue despertándose. **Solo se borran archivos de
imagen: las filas de la base de datos —el registro contable— jamás las toca**. Anular una compra
no altera esta política (ver [`PURCHASE_VOID.md`](PURCHASE_VOID.md)).

## Obligación contable: imagen separada del registro

En Perú los libros y registros contables están sujetos a plazos de conservación. Por eso la
app **siempre conserva los datos** de la compra (proveedor, RUC, serie/número, montos, líneas,
movimientos y bitácora) y trata la foto como **evidencia separada y opcional** a lo largo del
tiempo: la política de retención elimina la imagen sin tocar el registro, y la pantalla de
detalle muestra el comprobante completo con el aviso de imagen no retenida cuando corresponde.
La opción "Conservar" está marcada en la app como la recomendada para respaldo contable.

## Controles del usuario en Ajustes

- **Abrir política de privacidad**: desde Ajustes → Privacidad y diagnóstico abre la URL HTTPS
  pública configurada como `FACTURASTOCK_PRIVACY_POLICY_URL` (o `privacy.policyUrl` local). El
  candidato `cloudRelease` falla antes de empaquetarse si falta la URL o si no es HTTPS, no tiene
  host o incluye credenciales.

- **Exportar libro contable**: genera `schemaVersion = 4`, `exportKind = ACCOUNTING_LEDGER`, con
  negocio, proveedores, productos, unidades, almacenes, alias, compras/líneas, saldos,
  movimientos, auditoría completa del negocio (incluidos eventos sin `purchaseId`) y metadatos de
  imágenes retenidas. Excluye expresamente bytes de fotos,
  rutas privadas, borradores/artefactos OCR, preferencias, cuenta/membresías, estado de transporte,
  caché, temporales, credenciales y tokens. El serializador es determinista; antes de abrir el
  destino SAF exige una instantánea estable y, si los datos cambian continuamente, falla cerrado
  para reintentar en lugar de escribir una mezcla. El esquema v4 no modela cabeceras ni líneas de
  venta; los saldos, movimientos genéricos y eventos de auditoría pueden reflejar su efecto, pero no
  permiten reconstruir la venta. El campo `excludedData` declara esta exclusión como
  `SALE_HEADERS_AND_LINES` y `DEBTS_AND_PAYMENTS`, por lo que el archivo no debe presentarse como
  una exportación integral de ventas o deudas.
- **Borrar imágenes de este celular** y **Limpiar caché y temporales**: ejecutan el mantenimiento
  de inmediato. El borrado manual incluye originales de borradores abiertos y fotos de compras
  terminales de todos los negocios locales; solo estas últimas pueden requerir una purga cloud.
  La pantalla reporta intentos, eliminadas, ya ausentes, fallidas y etapas incompletas. El resultado
  local se muestra separado de las intenciones cloud durables y de su ACK remoto.
- **Desactivar el respaldo**: `backup_enabled` (`UpdateBackupEnabledUseCase`) detiene subidas y
  descargas comerciales y solicita cancelar ese trabajo. En un negocio que conserva un binding
  cloud durable también bloquea nuevos checkouts de venta: permitir una salida solo local dividiría
  el inventario entre miembros. Las purgas explícitas ya pendientes conservan un canal mínimo hasta
  recibir ACK; apagar respaldo no borra datos remotos.
- **Respaldar documentos**: segundo opt-in, independiente y apagado por defecto. Solo tiene efecto
  con el respaldo comercial activo y una política que todavía conserve la imagen. Desactivarlo
  retira candidatos de subida abiertos y despierta las purgas necesarias; no borra registros.
- **Enviar diagnósticos operativos**: opt-in independiente (`diagnostics_enabled`,
  `UpdateDiagnosticsConsentUseCase`), apagado por defecto. Al desactivarlo se cierra Analytics
  antes de guardar la preferencia, se limpia su estado local y no se borra ni interrumpe la
  bitácora contable local. La app no fija `userId`, no envía UUID de dominio y mantiene
  `ANALYTICS_STORAGE=DENIED`, por lo que el SDK no entrega app-instance ID. Los eventos ya
  recibidos por Firebase no se pueden borrar selectivamente desde el dispositivo y siguen la
  retención/borrado del proyecto. Eliminar la cuenta de respaldo no debe interpretarse como
  borrado retroactivo de estadísticas operacionales ya agregadas.
- **Eliminar cuenta y datos en la nube** (Ajustes → Cuenta y respaldo → Eliminar mi cuenta;
  flavor `cloud`, sesión autenticada, incluso antes de verificar el correo): después de una
  confirmación explícita solicita la contraseña solo para reautenticar con Firebase; no la guarda
  ni la incluye en el callable. El cliente fuerza un token nuevo y el servidor exige
  `auth_time` de como máximo cinco minutos antes de cualquier lectura o escritura destructiva;
  refrescar un token viejo no renueva esa prueba de presencia. Luego llama al callable
  `deleteMyAccount` (`functions/accountDeletion.js`), que:
  1. Rechaza con `failed-precondition` si el usuario es OWNER de un negocio con más miembros
     (debe transferir la propiedad primero); en ese caso no crea marca ni lock y no borra nada.
     También rechaza antes de consultar los árboles o escribir con
     `ACCOUNT_DELETION_SCOPE_TOO_LARGE` si figura como OWNER de más de 480 negocios; soporte debe
     reducir/segmentar el alcance y tampoco
     hay borrado parcial.
  2. En una transacción que vuelve a contar miembros crea una marca pseudónima de supresión de
     cuenta y bloquea cada negocio del que es único miembro; todos los callables mutantes leen
     esas guardas dentro de su propia transacción. Si hay email verificado, fija también un lock
     temporal pseudónimo que impide reinsertarlo mediante una invitación concurrente. Luego borra
     recursivamente esos negocios
     (compras, líneas, movimientos, índices, membresías, invitaciones y metadatos de sync).
  3. Elimina sus membresías no-OWNER y, cuando el token acredita que el correo está verificado,
     todas las invitaciones dirigidas a él, independientemente de si estaban pendientes,
     aceptadas o rechazadas. Además, cualquier invitación histórica cuyo `acceptedBy` o
     `declinedBy` sea su UID se elimina completa para cubrir correos anteriores. Un correo sin
     verificar no autoriza a tocar invitaciones ajenas.
  4. En negocios que sobreviven, sustituye el UID por el valor cerrado `deleted-account` en
     `business.createdBy`, `invitation.invitedBy/cancelledBy`, `member.roleUpdatedBy`,
     `purchase.syncedBy/voidedBy` y `voidRecord.cloudActorUid`. Conserva las compras comerciales;
     el `actorId` del payload de anulación es un UUID interno del negocio, no un UID Firebase, por
     lo que su JSON canónico y sus hashes no se reescriben.
  5. Elimina el usuario de Firebase Authentication; el refresh token muere de inmediato y el
     idToken ya emitido no puede mutar ni consultar los callables de cuenta porque la marca de
     supresión permanece; las reglas directas dejan de autorizar al eliminar la membresía. El
     token deja de verificarse al expirar y no puede renovarse.
  6. Devuelve un resumen cerrado (`businessesDeleted`, `membershipsRemoved`) que la app usa para
     verificar el éxito, sin mostrar identificadores internos. Luego guarda una marca restaurable
     y cierra la sesión/enlace local; si esa limpieza falla, ofrece reintentarla sin repetir el
     borrado remoto. Las compras, ventas, deudas, abonos y borradores del dispositivo **no** se borran. Las ventas
     cloud permanecen con un negocio compartido que sobrevive, sin UID del autor; si era un negocio
     del único miembro, el árbol completo —incluidas ventas, deudas, abonos e inventario— se
     elimina. En un negocio compartido sobreviviente, las deudas y abonos permanecen como hechos
     comerciales sin UID del autor.

  Las escrituras de limpieza se fraccionan bajo el límite de lote de Firestore y Auth solo se
  elimina al final. Si una consulta o lote falla, la cuenta de Auth se conserva para que el usuario
  pueda reintentar; las eliminaciones y sustituciones ya aplicadas son idempotentes. La marca de
  supresión se conserva indefinidamente por seguridad en `accountDeletionTombstones/{hash}`: su
  ruta es SHA-256 namespaced de un UID aleatorio, no contiene UID/email en claro y no es legible
  por clientes. Durante un reintento guarda IDs internos de negocios pendientes. Tras una segunda
  purga Storage posterior a la gracia de late-write, vacía la lista pendiente pero conserva esos
  UUID aleatorios como `retiredBusinessIds`. `createBusiness` no permite reutilizarlos: así un JWT
  antiguo no recrea datos y una outbox tardía de la generación eliminada no puede purgar una
  generación futura con la misma identidad.
  El checkpoint local es conservador ante respuestas ambiguas y no reenvía el callable. Una muerte
  de proceso en la ventana exacta entre persistirlo e iniciar la llamada puede cerrar la sesión sin
  haber solicitado todavía el borrado remoto; un protocolo journalado por servidor queda como
  límite conocido de esta versión.
  El lock de email es distinto: se elimina best-effort después de completar Firestore y Auth y
  deja de bloquear al cumplir 24 h. Si ese delete final falla, `inviteMember` reconoce la
  caducidad y lo retira dentro de la misma transacción que crea una invitación nueva. Además,
  `firestore.indexes.json` configura `expiresAt` como TTL para que Firestore purgue el documento
  físico de forma asíncrona; esa purga no ocurre necesariamente en el instante del vencimiento.

  Las invitaciones tienen vigencia lógica de 7 días y `invitations.expiresAt` también está
  configurado como TTL físico asíncrono. Para reducir abuso, `inviteMember` crea contadores
  fixed-window por actor (30/h), negocio (100/día) y destinatario (10/día). Sus IDs son hashes
  SHA-256 namespaced; los documentos no guardan UID, email ni businessId, no son legibles por
  clientes y `invitationRateLimits.expiresAt` programa su purga tras dos ventanas como máximo
  (2 h para actor, 48 h para negocio/destinatario). Un retry idéntico durante 60 s no aumenta
  esos contadores ni prolonga la invitación.

  La decodificación documental tiene contadores equivalentes por actor (60/h) y negocio
  (500/día). Sus documentos `documentUploadRateLimits` solo guardan scope, ventana y conteo bajo
  IDs SHA-256 namespaced, nunca UID o businessId en claro, y expiran tras dos ventanas. Un lease
  de `documentSyncOperations` hace que una clave idempotente consuma una sola unidad; los leases
  abandonados y marcadores `VALIDATION_FAILED` expiran a las 48 h. Los ACK completos eliminan ese
  `expiresAt` y permanecen para conservar idempotencia.

  El cierre de cuenta o la revocación de Analytics no ordenan automáticamente eliminar el
  Firebase Installation ID ni tokens/metadatos de App Check o Play Integrity. Su plazo y control
  aplicable deben confirmarse externamente para el proyecto productivo antes de publicar la
  política; este repositorio no inventa ni demuestra ese plazo.

## Pruebas

Toda la evidencia automatizada usa datos ficticios (RUCs y comprobantes de prueba). Detalles
en [`CLOUD_BACKUP_FIREBASE.md`](CLOUD_BACKUP_FIREBASE.md) (suite de emuladores, incluidos los
casos de `deleteMyAccount`) y [`BACKUP_SYNC.md`](BACKUP_SYNC.md).
