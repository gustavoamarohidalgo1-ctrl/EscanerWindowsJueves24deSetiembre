# Anular una venta desde Reportes y devolver el stock

Fecha: 2026-09-08. Este documento describe la implementación presente en el código.
La compilación, las pruebas unitarias, las pruebas en emulador y los controles
de calidad de esta revisión pasaron. La instalación y las pruebas en la tablet están pendientes de recuperar
su conexión Wi-Fi; no se trasladan resultados de revisiones anteriores.

## Guía de uso

1. Abrir **Reportes** y elegir **Día**, **Semana** o **Mes** para encontrar la venta.
2. Pulsar **Anular venta** en su registro. Este botón está separado de la acción que
   despliega los detalles de ganancia.
3. Revisar la fecha, el importe y cada producto, con su cantidad, unidad y almacén
   de devolución. Si la venta fue a crédito, revisar también el saldo que se
   cancelará y el dinero ya cobrado que corresponde devolver al cliente.
4. Pulsar **Cancelar** para cerrar la revisión sin cambiar la venta ni el stock,
   o **Anular venta** para confirmar el impacto mostrado.
5. Esperar la confirmación. La aplicación informa **Venta anulada** y la venta deja
   de participar en Reportes cuando llega la lectura actualizada de la base de datos.
   El aviso permanece visible hasta pulsar **Entendido**.

La operación anula la venta completa y devuelve todas sus líneas a los productos y
almacenes de origen. Este flujo no ofrece devoluciones parciales ni una acción para
deshacer la anulación.

## Alcance y autorización

La anulación está habilitada para un negocio **local, activo y actualmente
seleccionado**, con una venta confirmada perteneciente a ese negocio. Una venta en
borrador o perteneciente a otro negocio no puede confirmarse desde este flujo.

El repositorio exige un actor con rol `OWNER` o `MANAGER`, reutilizando el contrato
de autorización de anulaciones de compras. Un `OPERATOR` no puede anular. En la
variante estrictamente local, el proveedor existente representa al propietario
del negocio activo; esta función no añade una pantalla nueva de usuarios o roles.

Un negocio con vinculación cloud persistida devuelve `SharedBusinessUnsupported`.
La pantalla explica que la devolución de stock mediante anulación todavía no está
disponible para negocios compartidos. Tener un rol de propietario no elimina esta
restricción y no se envía una anulación remota como parte de este flujo.

## Stock, historial y costos

La confirmación se realiza en una transacción Room. Añade movimientos positivos
`SALE_VOID` por las cantidades originales, actualiza los saldos de los almacenes,
registra el evento `SALE_VOIDED` y crea un recibo inmutable en `sale_voids`.

Se conservan la venta y sus líneas, las salidas originales `SALE`, la deuda original
y todos sus cobros. La venta histórica conserva su estado `POSTED`; el recibo
separado determina que su efecto queda anulado. Las consultas de Reportes excluyen
las ventas con recibo, por lo que sus ingresos y ganancias dejan de sumar en los
períodos donde aparecían. No se borra la evidencia original de la operación.

El stock vuelve al mismo producto y almacén, incluso si sus fichas fueron
archivadas posteriormente. La devolución utiliza el costo histórico de la salida
y lo combina con el saldo actual mediante el cálculo de costo promedio existente.
No restaura por copia un saldo antiguo que pudiera omitir compras o movimientos
posteriores. Si faltan saldos o el historial no permite comprobar la operación, se
rechaza la anulación con un mensaje de historial incompleto.

El esquema Room pasa de 28 a 29 mediante una migración que añade `sale_voids` y sus
restricciones. El contrato de instantánea completa incluye la nueva tabla desde
el esquema 29, y la reconstrucción del inventario reconoce los movimientos
`SALE_VOID`. La ejecución y validación de estas rutas se registran al final de este
documento cuando existan resultados comprobados.

## Crédito y devolución del dinero

El diálogo distingue dos importes:

| Situación de una venta de S/ 100 | Saldo que se cancela | Dinero que corresponde devolver |
| --- | ---: | ---: |
| Venta al contado | S/ 0 | S/ 100 |
| Crédito sin abonos | S/ 100 | S/ 0 |
| Crédito con S/ 30 abonados | S/ 70 | S/ 30 |
| Crédito pagado completamente | S/ 0 | S/ 100 |

En una venta a crédito, `refundAmount` se calcula como importe original menos saldo
pendiente. El recibo conserva los importes revisados. La deuda histórica y sus
abonos permanecen intactos; las consultas de Deudas excluyen esa deuda y las
guardas de repositorio y base de datos impiden aplicar nuevos cobros a la venta
anulada. La cancelación del saldo se representa mediante el recibo, sin reescribir
el saldo histórico como si el cliente hubiera realizado otro pago.

**El dinero se devuelve manualmente al cliente.** La aplicación muestra el importe
que corresponde devolver, pero no realiza transferencias, reembolsos de tarjeta ni
movimientos bancarios. El importe guardado en el recibo no acredita que una
devolución externa de dinero se haya ejecutado. Una venta con descuento completo e
importe cero también puede devolver stock, sin dinero que reintegrar.

## Confirmación, cambios y reintentos

`VoidSaleUseCase.preview` obtiene un `SaleVoidPreview` que identifica negocio,
venta, fecha, importes, líneas y un sello del impacto. El repositorio vuelve a leer
y comparar el impacto dentro de la transacción de `confirm`, incluyendo stock,
cobros y autorización.

El ViewModel reserva inmediatamente la confirmación para impedir dos envíos en el
mismo frame. Durante la escritura se deshabilitan confirmar, cancelar y cambiar de
período. La fila no se retira anticipadamente. Los identificadores deterministas,
el recibo único por venta y las restricciones SQL impiden devolver el stock otra
vez al recibir la misma confirmación; `AlreadyVoided` reconoce una anulación
existente.

Si el impacto cambió, `Stale` vuelve a cargar una vista previa y muestra que hay que
revisar los importes y productos actualizados. Se exige otro clic explícito en
**Anular venta**; la nueva revisión nunca se confirma automáticamente. Un fallo de
lectura o una respuesta de escritura incierta ofrece **Volver a revisar**.

Cambiar de negocio o de período invalida una revisión pendiente. El repositorio
también comprueba el negocio y actor antes de escribir. La rotación conserva el
estado del ViewModel y el aviso de éxito; una instancia nueva no inicia por sí sola
una confirmación. El recibo persistido evita duplicar el efecto tras un reintento.

## Código y cobertura incorporada

La interfaz y coordinación están en `feature/reports/ReportsContract.kt`,
`ReportsViewModel.kt`, `ReportsScreen.kt` y `ReportsTestTags.kt`; los textos nuevos
están en `res/values/reports_void_strings.xml`. La operación de dominio se define
en `domain/repository/SaleVoidRepository.kt` y `domain/usecase/VoidSaleUseCase.kt`.
La escritura y sus invariantes están en `RoomSaleVoidRepository`, `SaleVoidEntity`,
`SaleVoidDao` y `SaleVoidPersistenceInvariants`.

Las pruebas incorporadas cubren revisión sin escritura, cancelación seguida de
confirmación, doble clic, cambios de negocio y período, impacto desactualizado,
rechazo de negocios compartidos, reintento tras respuesta incierta, importes de
crédito, accesibilidad y fuente al 200 %. También existen pruebas del repositorio
Room, restricciones SQL y migración para stock, historial, concurrencia, reversión
de transacciones y autorización. La existencia de estas pruebas no implica aquí
un resultado de ejecución.

## Validación e instalación

| Comprobación de esta revisión | Estado | Evidencia |
| --- | --- | --- |
| Compilación local y cloud | Correcta; APK local y APK QA compilados | `build-and-unit.log`, `build-android.log`, `qa-build.log` |
| Pruebas unitarias local y cloud | 1.643 local + 1.821 cloud; cero fallos | `verification.json` y XML de Gradle |
| Análisis estático y Android Lint | CI correcto; cero errores y 66 advertencias existentes en cada variante | `static-and-lint.log`, informes XML/HTML de Lint |
| Migración Room 28 → 29 e invariantes SQL | Correctas; también migraciones completas 1–28 → 29 | `emulator-instrumentation.log` |
| Pruebas de repositorio e interfaz en emulador | 90 pruebas, cero fallos, 96,745 segundos | `emulator-instrumentation.log` |
| Jornada de venta → Reportes → cancelar/anular con datos QA | Correcta en emulador; cancelación sin cambios y persistencia tras recreación | `ScannerSaleJourneyTest` en `emulator-instrumentation.log` |
| Pruebas con datos QA en tablet | Pendiente: tablet desconectada | Última dirección `192.168.18.27:5555` sin respuesta |
| Hash, firma y versión del APK de esta revisión | SHA-256 registrado, firma verificada, versión 1.0.4 (5) | `verification.json`, `apk-signature.txt` |
| Actualización de producción y conservación de datos | Pendiente de conexión; no se ha instalado esta revisión en la tablet | `verification.json` |

Las evidencias de esta revisión están en `build/reports/sale-void-reports-2026-09-08/`.

Las comprobaciones de anulación deben usar ventas de prueba en el entorno QA.
Esta documentación no autoriza ni declara anulaciones de ventas reales para
validar la función. La instalación anterior de mejoras del escáner no acredita que
esta revisión de Reportes esté instalada.
