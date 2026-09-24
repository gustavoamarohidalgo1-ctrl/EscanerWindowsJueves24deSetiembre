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
Desde **Reportes → Anular venta** se puede anular la venta a crédito completa.
El recibo `sale_voids` cancela el saldo exigible y conserva el saldo histórico y los abonos originales;
las consultas de cuentas por cobrar excluyen esa venta y se rechazan nuevos pagos. La confirmación
muestra los abonos ya cobrados para que se devuelvan al cliente por el medio de cobro correspondiente.
No se corrige individualmente un abono.

## Conexión y otros dispositivos

La venta a crédito y los abonos se confirman en Room y funcionan sin internet. No se comparten con
otro teléfono o tablet: la variante cloud, que sincronizaba ventas, deudas y abonos entre
dispositivos, se retiró el 24 de septiembre de 2026.

Cada abono usa control optimista de versión e idempotencia: un doble toque o un reintento no aplica
dos cobros sobre el mismo saldo, y si la cuenta cambió mientras se registraba el pago, la app pide
revisarla antes de reintentar.

## Datos y privacidad

El nombre del deudor, la venta, los productos y los abonos se guardan solo en Room, dentro del
dispositivo.

La lectura cruda del lector vive solo en memoria. Al asociar un código a un producto, ese código sí
pasa a formar parte del catálogo. Las notas y referencias de pago deben evitar información sensible
innecesaria. La cuenta por cobrar no forma parte todavía del JSON `ACCOUNTING_LEDGER` v4: la única
copia completa es el respaldo `adb run-as` descrito en [`RUNBOOK.md`](RUNBOOK.md).
