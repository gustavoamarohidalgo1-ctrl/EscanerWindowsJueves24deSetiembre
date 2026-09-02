# Deudores, ventas a crédito y abonos

Este documento describe la cuenta por cobrar implementada en FacturaStock. Una deuda nace
exclusivamente al confirmar una **venta a crédito**: no se crea un saldo suelto sin productos ni se
edita después una venta publicada. De ese modo, el nombre de la persona, los productos entregados,
el descuento de existencias y el importe original quedan unidos al mismo hecho histórico.

## Registrar lo que una persona debe

1. Desde **Inicio**, abrir **«Deudores»** y tocar **«Registrar deuda»**; ese acceso abre **Vender**
   con **«A crédito»** ya seleccionado. También se puede entrar directamente por **«Vender»**.
2. En **«Tipo de venta»**, mantener o elegir **«A crédito»** y escribir el nombre de la persona. El nombre se normaliza,
   debe contener entre 2 y 120 caracteres y no admite caracteres de control.
3. Agregar los productos de cualquiera de estas dos formas:
   - **manual:** elegir **«Buscar»**, escribir el nombre y elegir el producto y almacén exactos;
   - **lector:** elegir **«Escáner físico»** y leer con un dispositivo USB/Bluetooth que
     Android reconozca como teclado físico HID (*keyboard wedge*).
4. Revisar cantidad, precio unitario, almacén y total. El nombre puede escribirse antes o después de
   agregar los productos, pero es obligatorio para confirmar una venta a crédito.
5. Revisar y confirmar. La venta, sus líneas, la salida de inventario y la deuda se publican juntas;
   si alguna validación falla no queda una deuda o un descuento de stock parcial.

El lector soportado **no es la cámara del teléfono**. Si no hay lector físico, la búsqueda manual
continúa disponible. Las reglas completas del lector están en
[`SALES_AND_BARCODE_SCANNER.md`](SALES_AND_BARCODE_SCANNER.md).

## Consultar y cobrar

**Deudores** muestra por defecto las cuentas abiertas y permite buscar por nombre o filtrar por
**Pendientes**, **Pagadas** o **Todas**. Cada tarjeta indica nombre, saldo actual, importe original,
cantidad de productos y última actualización. Si las cuentas abiertas usan monedas distintas, la
app no inventa ni convierte un total general.

El detalle conserva:

- nombre de la persona y estado `OPEN`/`PAID`;
- importe original y saldo pendiente;
- productos, cantidades, precios e importes de la venta;
- historial inmutable de abonos.

Para cobrar se toca **«Registrar pago»**, se ingresa un importe mayor que cero y no superior al
saldo, y se elige **Efectivo**, **Yape**, **Plin**, **Transferencia** u **Otro**. La nota y
la referencia son opcionales. Un abono parcial reduce el saldo; uno por el saldo exacto cierra la
cuenta como pagada. Un doble toque, un reintento o un ACK perdido no deben cobrar dos veces.

La deuda, la venta y los abonos son historia: no se sobrescriben ni se eliminan desde esta pantalla.
Esta versión tampoco ofrece anular una venta a crédito ni corregir un abono ya publicado; una futura
reversión deberá registrar un hecho compensatorio, no editar el pasado.

## Dos teléfonos y conexión

El comportamiento depende del flavor y del negocio:

| Caso | Venta a crédito | Abono | Otro teléfono |
| --- | --- | --- | --- |
| `local` o negocio nunca enlazado | Se confirma en Room y puede funcionar sin internet | Se registra en Room | No se comparte automáticamente |
| `cloud`, mismo negocio enlazado y respaldo activo | Requiere internet y autorización de Functions antes del commit local | Requiere internet y control de versión remoto | El pull incremental materializa venta, deuda, abonos y saldo autoritativo |

Para compartir entre dispositivos, ambos deben usar la variante `cloud`, iniciar sesión con cuentas
miembro del **mismo negocio**, mantener ese negocio enlazado y activar **«Respaldar registros en la
nube»**. Quien registra la venta o el abono necesita rol `OWNER`, `ADMIN` u `OPERATOR`; `READER`
puede consultar, pero no cobrar. Si falta conexión, sesión verificada, enlace coherente o respaldo,
la app conserva el carrito y bloquea la mutación en vez de crear una verdad distinta en un solo
teléfono.

El backend usa una transacción y una versión de deuda: dos dispositivos no pueden aplicar al mismo
tiempo dos cobros sobre el mismo saldo. Si otro teléfono ganó la carrera, la app muestra que la
cuenta cambió y debe sincronizarse/revisarse antes de reintentar.

## Datos y privacidad

El nombre del deudor, la venta, los productos y los abonos se guardan en Room. En un negocio cloud
enlazado también se guardan en Firestore y son legibles solo por miembros autorizados del negocio;
las reglas niegan escrituras directas del cliente y los cambios pasan por Functions. El backend no
guarda el UID de quien hizo la venta o el cobro dentro de la deuda o el abono.

La lectura cruda del lector vive solo en memoria. Al asociar un código a un producto, ese código sí
pasa a formar parte del catálogo. Las notas y referencias de pago deben evitar información sensible
innecesaria. La cuenta por cobrar no forma parte todavía del JSON `ACCOUNTING_LEDGER` v4 y el feed
cloud no es una restauración integral de toda la instalación.

## Puesta en servicio

El repositorio contiene el cliente Android, migración Room, reglas Firestore, callables y pruebas.
Eso no significa que exista un proyecto productivo desplegado: para sincronizar teléfonos hay que
configurar el flavor `cloud`, desplegar Functions y reglas, y verificar el proyecto Firebase real.
El flavor `local` sigue siendo gratuito y completamente local, pero por definición no sincroniza
automáticamente dos dispositivos. Los requisitos y advertencias de costo del backend están en
[`CLOUD_BACKUP_FIREBASE.md`](CLOUD_BACKUP_FIREBASE.md).
