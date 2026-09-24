# Privacidad y ciclo de vida de datos

Este documento describe el comportamiento implementado de FacturaStock. Está escrito **contra el
código**: si la implementación cambia, este documento cambia en el mismo commit.

Desde el 24 de septiembre de 2026 la app tiene una sola variante, `local`, que elimina el permiso
`INTERNET`: **ningún dato sale del dispositivo**. La variante cloud y sus flujos de cuenta,
respaldo remoto de registros y documentos, diagnósticos operativos y eliminación de cuenta se
retiraron junto con Firebase.

## Inventario de datos

| Dato | Finalidad | Procesamiento | Retención | Cifrado |
| --- | --- | --- | --- | --- |
| Foto del comprobante | OCR local y evidencia de la compra | Original privado en `filesDir/draft_images/…` (`data/files/LocalDraftImageImporter.kt`). No sale del dispositivo | Política global de retención; por defecto se conserva (ver abajo) | Local retenida: AES/GCM 256 con clave no exportable de AndroidKeyStore (`data/files/RetainedImageCipher.kt`) |
| Foto usada para importar productos | Detectar filas de producto con OCR local | Borrador privado del dispositivo; no se publica como compra | Room elimina el borrador y los datos OCR tras guardar o reconocer los productos seguros; el archivo se intenta borrar de inmediato. Si el sistema de archivos no lo confirma, permanece privado como huérfano y el mantenimiento lo reintenta tras el intervalo de seguridad. Ante un fallo anterior al guardado se conserva para reintentar | Sandbox privado de Android durante el procesamiento |
| Versiones de trabajo OCR (grises, ≤2048 px) | Mejorar la lectura ML Kit | Solo dispositivo: `draft_images/{draftId}/ocr/…` (`data/files/LocalInvoiceImagePreprocessor.kt`) | Se purgan al confirmar la compra y en cada mantenimiento | Sandbox privado; no sobreviven como imagen retenida |
| Datos de la compra (proveedor, RUC, comprobante, montos, líneas) | Registro contable e inventario | Room (`data/local/`) | **Nunca se borran automáticamente**: la normativa contable puede exigir conservarlos; la retención solo toca la foto | Sandbox de la app |
| Lectura cruda de un lector HID | Identificar un producto durante una venta o consultar su inventario | Solo memoria: el `KeyboardWedgeAssembler` ensambla eventos de teclado físico mientras hay un receptor explícito y la app está desbloqueada. No conserva la última lectura completa ni la envía a logs u outbox | Hasta completar, cancelar, pausar la app, cambiar de dispositivo o superar el timeout; una trama >128 se descarta completa | Memoria del proceso y sandbox de Android |
| Código asociado a un producto | Identificación posterior del producto | Al confirmar la asociación pasa a `products.barcode` en Room; reemplazar un código existente exige confirmación | Mientras exista el producto; no hay retención automática específica para códigos | Sandbox local |
| Precio de venta del producto | Proponer el precio en el carrito y estimar ganancia frente al costo de inventario | Room lo conserva en unidades menores y moneda; no se genera un reporte de utilidad realizada | Mientras exista el producto; puede sustituirse mediante una edición explícita | Sandbox local |
| Venta y líneas de venta (producto/almacén/código instantáneo, cantidad, precio y totales) | Carrito, checkout y trazabilidad | Room; la lectura HID cruda no se guarda | El borrador local se conserva para reanudarlo; la venta publicada no tiene borrado automático y anularla desde Reportes conserva su historia | Sandbox local |
| Cuenta por cobrar (nombre del deudor, venta/productos, saldo, vencimiento opcional) | Saber quién debe los productos de una venta a crédito y cuánto queda pendiente | Room crea la deuda únicamente junto a una venta `POSTED` | Historia comercial sin borrado automático. No se sobrescribe el nombre instantáneo ni el importe original | Sandbox local |
| Abonos de deuda (importe, método, nota/referencia opcionales y fecha) | Reducir el saldo y conservar el historial de cobro | Room append-only | Permanentes junto a la cuenta por cobrar; un abono no se edita ni elimina desde la app | Sandbox local |
| Texto OCR crudo y métricas de confianza | Revisión asistida | Solo dispositivo (`docs/LOCAL_OCR.md`) | Con el borrador/compra que lo originó | Sandbox |
| Producto detectado en una factura | Añadir un nombre seguro al catálogo sin registrar una compra | Room; solo nombre y unidad. No se copian cantidades, costos, precios, proveedor ni datos del comprobante. Su operación de catálogo queda en la outbox local, sin destino remoto | Mientras exista el producto; se evita duplicarlo por nombre normalizado | Sandbox local |
| Configuración (impuestos, política de retención y preferencias heredadas) | Operación de la app | Solo dispositivo: DataStore `app_settings` (`data/settings/`). Las claves `backup_enabled`, `document_backup_enabled`, `diagnostics_enabled` y `biometric_lock_enabled` pueden seguir guardadas desde versiones anteriores, pero la interfaz ya no las modifica | Hasta desinstalar o borrar datos | Sandbox |
| Bitácora de auditoría | Trazabilidad de confirmaciones, anulaciones, ajustes, conflictos y revisiones | Local: Room append-only; payload cerrado con UUID internos, enums, booleanos y conteos. No contiene motivos, importes, documento, texto ni hashes derivados del contenido | Permanente (es el registro de control) | Sandbox |

## Terceros

- **ML Kit (Google ML Kit on-device)**: solo se empaqueta Text Recognition para OCR local; ninguna
  imagen ni texto sale hacia servicios de Google. Barcode Scanning no es una dependencia de la
  app: los códigos de venta e inventario llegan exclusivamente desde un teclado físico HID; las
  búsquedas manuales no capturan códigos con la cámara.
- No hay publicidad, analítica, reporte de fallos ni SDK de red. El APK no declara `INTERNET` y
  `verifyOfflineFirstBoundaries` falla la compilación si se declara cualquier dependencia
  `com.google.firebase`.

## Lo que ninguna otra app puede ver

Las imágenes viven en el almacenamiento interno privado (`context.filesDir`), sin
`FileProvider`, sin `MediaStore` y sin permisos compartidos en el manifiesto productivo: el
sandbox de Android las hace inaccesibles a otras aplicaciones. Tras el commit de confirmación, el
hook cifra inmediatamente las fotos que permanecerán retenidas (formato
`[4B "FSE1"][12B IV][cifrado+tag]`); la clave AES/GCM vive
en AndroidKeyStore y no es exportable. Hasta esa pasada siguen dentro del sandbox privado. La
lectura para mostrarlas descifra bytes en memoria (`RetainedImageStore.readDecrypted`), sin
archivo temporal en claro. Los metadatos EXIF/XMP/geolocalización se eliminan al importar
(`data/files/ImageMetadataScrubber.kt`).

## Políticas de retención de imágenes

La política se guarda en `image_retention_policy` (`data/settings/AppSettingsDataStore.kt`; modelo
`ImageRetentionPolicy` en `domain/model/PrivacyModels.kt`) y su valor por defecto es **Conservar**.
Desde septiembre de 2026 Ajustes ya no permite cambiarla: una instalación nueva conserva siempre las
imágenes, y una instalación que eligió otra política en una versión anterior la sigue aplicando,
porque el mantenimiento la lee en cada ejecución. Es una preferencia global de la instalación: el
mantenimiento la aplica a las imágenes terminales de todos los negocios locales, no solo al
seleccionado:

| Política | Momento exacto del borrado |
| --- | --- |
| Eliminar tras OCR | Al publicarse un resultado OCR que cubre las páginas activas (hook en `RunInvoiceOcrUseCase` → `ApplyImageRetentionAfterOcrUseCase`). El mantenimiento reintenta solo borradores que demuestran ese snapshot; un ingreso manual sin snapshot no se trata como OCR publicado |
| Eliminar tras confirmar | Después del commit durable de la compra. El hook consulta solo las páginas de ese borrador y luego borra el archivo local |
| 30 / 90 días | Es elegible exactamente cuando `postedAt + días <= ahora` (`ImageRetentionDecider`, con pruebas de borde). La ejecución ocurre en una pasada inmediata (al confirmar o al arrancar) o en el mantenimiento periódico posterior al vencimiento |
| Conservar (defecto) | Nunca |

El mantenimiento de privacidad sigue activo en la variante `local`: un trabajo diario
(`data/privacy/PrivacyMaintenanceWorker.kt`, `PeriodicWorkRequest` de 24 h, con batería no baja y
backoff exponencial, sin requisito de red) y un trabajo único inmediato al confirmar una compra y al
arrancar. Cada ejecución inmediata acota una cadena defectuosa a tres intentos; el siguiente evento
o la pasada diaria vuelven a intentarlo sin un loop perpetuo. El mantenimiento barre temporales de
importación, caché, temporales privados conocidos de OCR/cifrado, carpetas de borradores huérfanos
y versiones OCR de compras confirmadas, derivados documentales `.fse` sin upload Room abierto, y
migra a cifrado las retenidas históricas aún en claro. Cada etapa y archivo devuelve un estado
cerrado; un fallo parcial queda como advertencia/error y las demás etapas continúan.

Las mutaciones de `draft_images/{draftId}` comparten exclusión entre importación, OCR, cifrado,
borrado y barridos. Una carpeta aparentemente huérfana debe tener al menos una hora y Room se
revalida dentro de ese lock inmediatamente antes de eliminarla; un borrador creado después del
snapshot sobrevive. El cifrado publica por temporal autenticado y movimiento atómico, procesa la
fuente en chunks de 16 KiB y permite paralelismo entre borradores sin duplicar plaintext y
ciphertext completos en memoria.

`RunPrivacyMaintenanceUseCase` conserva la lógica de la etapa cloud que, antes de retirar una
imagen que pudo respaldarse, exigía dejar primero una intención de purga remota en la outbox. En la
variante `local` no existe transporte que haya podido enviar una imagen. **Solo se borran archivos
de imagen: las filas de la base de datos —el registro contable— jamás las toca**. Anular una compra
no altera esta política (ver [`PURCHASE_VOID.md`](PURCHASE_VOID.md)).

## Obligación contable: imagen separada del registro

En Perú los libros y registros contables están sujetos a plazos de conservación. Por eso la
app **siempre conserva los datos** de la compra (proveedor, RUC, serie/número, montos, líneas,
movimientos y bitácora) y trata la foto como **evidencia separada y opcional** a lo largo del
tiempo: una política de retención distinta de "Conservar" elimina la imagen sin tocar el registro,
y la pantalla de detalle muestra el comprobante completo con el aviso de imagen no retenida cuando
corresponde. "Conservar" es la política por defecto y la única que puede tener una instalación
nueva.

## Controles del usuario en Ajustes

Ajustes conserva un solo control de datos, en la sección **Datos**:

- **Exportar libro contable**: genera `schemaVersion = 4`, `exportKind = ACCOUNTING_LEDGER`, con
  negocio, proveedores, productos, unidades, almacenes, alias, compras/líneas, saldos,
  movimientos, auditoría completa del negocio (incluidos eventos sin `purchaseId`) y metadatos de
  imágenes retenidas. Excluye expresamente bytes de fotos,
  rutas privadas, borradores/artefactos OCR, preferencias, estado de transporte,
  caché, temporales, credenciales y tokens. El serializador es determinista; antes de abrir el
  destino SAF exige una instantánea estable y, si los datos cambian continuamente, falla cerrado
  para reintentar en lugar de escribir una mezcla. El esquema v4 no modela cabeceras ni líneas de
  venta; los saldos, movimientos genéricos y eventos de auditoría pueden reflejar su efecto, pero no
  permiten reconstruir la venta. El campo `excludedData` declara esta exclusión como
  `SALE_HEADERS_AND_LINES` y `DEBTS_AND_PAYMENTS`, por lo que el archivo no debe presentarse como
  una exportación integral de ventas o deudas.

Ya no existen en la interfaz: abrir una política de privacidad, elegir la retención de imágenes,
borrar imágenes, limpiar caché y temporales (el mantenimiento automático sigue corriendo),
interruptores de respaldo comercial y documental, consentimiento de diagnósticos, bloqueo
biométrico y eliminación de cuenta. Las preferencias de respaldo y diagnóstico que hayan quedado
en DataStore no tienen transporte ni telemetría que las use; una preferencia de bloqueo biométrico
activada en una versión anterior sigue aplicándose en `MainActivity`.

La única copia completa de los datos es el respaldo `adb run-as` que se hace desde la Mac de
desarrollo antes de cada actualización ([`RUNBOOK.md`](RUNBOOK.md)). Esa copia contiene datos
reales del negocio y se custodia fuera del repositorio.

## Pruebas

Toda la evidencia automatizada usa datos ficticios (RUCs y comprobantes de prueba). El protocolo
de la outbox, hoy sin transporte remoto, está en [`BACKUP_SYNC.md`](BACKUP_SYNC.md).
