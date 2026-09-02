# Ventas, inventario y lector externo de códigos

Este documento delimita el alcance funcional v1 de ventas y de la consulta de inventario mediante
códigos en FacturaStock. Describe el contrato del repositorio; no acredita por sí solo una
compilación, una instalación desde Google Play ni una publicación productiva.

## Alcance

El carrito de venta es **local y offline-first**. Para el negocio activo se conserva un único
`DRAFT` por moneda; cerrar la pantalla o perder la conexión no publica ni descuenta existencias. La
autoridad del checkout depende del negocio: uno sin enlace cloud confirma íntegramente en Room y
puede hacerlo sin internet; uno enlazado de forma durable a un negocio cloud exige respaldo activo,
sesión verificada y conexión para que Functions autorice primero la venta contra el saldo compartido.
El carrito permite:

- agregar un producto escribiendo su nombre y eligiendo entre coincidencias similares;
- recibir el código de un lector físico USB o Bluetooth configurado como teclado
  (*keyboard wedge*);
- elegir el almacén exacto cuando el producto tiene existencias en más de una ubicación;
- revisar cantidad y el precio unitario de venta sugerido antes de confirmar;
- elegir **«Contado»** o **«A crédito»** en **«Tipo de venta»**; el crédito exige el nombre de la persona y crea una cuenta por
  cobrar ligada a las líneas publicadas.

Solo se ofrecen productos activos con existencia positiva. Una línea siempre identifica el par
concreto producto–almacén: el flujo no presenta el stock agregado de varias ubicaciones como si fuera
una única existencia vendible. Si el saldo es positivo pero menor que una unidad, la cantidad inicial
usa exactamente ese saldo fraccionario en vez de intentar vender una unidad inexistente.

## Flujo manual

1. Desde **Inicio**, abrir **«Vender»**.
2. Elegir **«Buscar»** y escribir el nombre o parte del nombre del producto.
3. Elegir el producto y la ubicación de la que saldrá el stock.
4. Revisar la cantidad y el **precio unitario de venta** sugerido en el carrito.
5. Revisar el total y usar **«Revisar y confirmar venta»**.

La venta manual no interpreta el texto como SKU, código de barras ni código de proveedor. Incluso
si la consulta contiene solo números, se compara con el nombre del producto y nunca activa una
búsqueda encubierta por código. Para leer códigos se debe cambiar explícitamente a
**«Escáner físico»**.

## Consulta desde Inventario

En **Inventario → Existencias** hay dos modos explícitos: **«Buscar»** y **«Escáner físico»**.
**«Buscar»** conserva la consulta manual por producto, SKU o almacén. Solo al elegir
**«Escáner físico»** la pantalla recibe las ráfagas del lector HID.

Una lectura completa y válida hace una búsqueda exacta del código dentro del negocio activo. Si el
producto existe, la app abre su trazabilidad en la ruta existente `inventory/{productId}`. El valor
del código no viaja como argumento de navegación: la ruta recibe únicamente el `ProductId`
canónico resuelto localmente.

Esta consulta es de solo lectura. Inventario no crea productos, no asocia ni reemplaza códigos y no
modifica existencias, movimientos o precios. Un código desconocido solo muestra que no está
asociado a un producto de ese negocio y permite volver a escanear. Cambiar a **«Buscar»**, pasar a
**«Ganancias por producto»**, abrir la trazabilidad, pausar la app o iniciar una operación que
bloquee la pantalla desactiva el receptor y descarta cualquier lectura parcial.

El escáner de Inventario tampoco abre la cámara ni usa ML Kit Barcode Scanning. Comparte el mismo
contrato de teclado físico HID descrito abajo.

## Venta a crédito y Deudores

El tipo de venta se elige en **«Tipo de venta»** dentro del mismo carrito. **«Contado»** conserva el contrato v1 anterior y no
crea una deuda. **«A crédito»** exige un nombre normalizado de 2 a 120 caracteres; el lector y la
búsqueda manual siguen disponibles aunque el nombre todavía esté vacío, pero el checkout permanece
bloqueado hasta completarlo.

Entrar desde **Deudores → Registrar deuda** abre este mismo carrito con **«A crédito»**
preseleccionado; aun así, el tipo visible sigue siendo la fuente explícita del comportamiento.

La confirmación a crédito es atómica con la venta y el inventario. Room crea una deuda `OPEN`, con
saldo igual al total y una identidad determinística derivada de `saleId`; en cloud, `postSale` usa
el documento v2 y Functions crea la misma cuenta dentro de la transacción que reserva el stock. No
existe una ruta para crear una deuda sin una venta `POSTED` exacta.

El acceso **Deudores** lista cuentas abiertas/pagadas, muestra los productos originales y conserva
un libro append-only de abonos. Los cobros parciales o totales usan control optimista de versión e
idempotencia; un negocio enlazado exige autorización remota antes de escribir en Room. El contrato
funcional, de concurrencia y privacidad está en
[`DEBTORS_AND_CREDIT_SALES.md`](DEBTORS_AND_CREDIT_SALES.md).

## Lector USB o Bluetooth tipo teclado

FacturaStock no administra el emparejamiento. Android debe reconocer el dispositivo como un teclado
físico HID/*keyboard wedge*:

- **USB:** conectar el lector al teléfono o tableta mediante el adaptador USB/OTG apropiado.
- **Bluetooth:** emparejarlo desde los ajustes de Android y seleccionar, si el fabricante lo exige,
  el perfil HID/teclado.
- Configurar el lector para enviar al final **Enter**, **Enter de teclado numérico** o **Tab**. Sin
  terminador, la aplicación no trata la ráfaga como una lectura completa.

La entrada física se atiende únicamente cuando la app está desbloqueada y Ventas o
**Inventario → Existencias** han activado de forma explícita **«Escáner físico»**. Cambiar de
pantalla, pausar la app o desactivar ese modo descarta cualquier lectura parcial. Solo puede existir
un receptor activo y la última lectura completa no queda almacenada en el ensamblador.

El ensamblador separa dispositivos y reinicia una lectura si transcurre más de un segundo entre
teclas. Ignora repeticiones y duplicados del mismo evento. Si una ráfaga supera 128 caracteres,
descarta la trama completa hasta el terminador: nunca entrega un código truncado.

La compatibilidad descrita es la del protocolo de teclado físico. No implica certificación de todos
los modelos USB/Bluetooth, distribuciones de teclado o configuraciones de fabricante.

### Checklist para el modelo físico

Antes de usar un lector en producción, registrar sin datos comerciales reales:

1. marca/modelo del lector, teléfono o tableta, versión de Android y adaptador OTG si corresponde;
2. que Android lo muestre como teclado físico y que no requiera permisos dentro de FacturaStock;
3. una lectura conocida con letras, ceros iniciales y terminador Enter o Tab, comprobando el valor
   exacto que llega;
4. en Ventas, un código conocido, uno desconocido que se asocia de forma explícita y un producto
   presente en dos almacenes;
5. en **Inventario → Existencias**, que un código conocido abra la trazabilidad del producto exacto
   y que uno desconocido no cree ni modifique ningún producto;
6. dos lecturas seguidas del mismo producto en Ventas, verificando cantidad y un único descuento de
   stock al confirmar;
7. pausa, rotación y bloqueo de la app en mitad de una lectura, verificando que el fragmento se
   descarte;
8. navegación con teclado/TalkBack: Tab o Enter sin una lectura pendiente deben seguir llegando a
   la interfaz.

Si cualquiera falla, conservar los modos **«Buscar»** de Ventas e Inventario y no declarar
compatible ese modelo hasta corregir su perfil, sufijo o distribución de teclado.

## Contrato del código

El valor final entregado por el lector se valida con un contrato único:

- se recortan solo espacios ASCII (`U+0020`) de los bordes;
- el resultado debe tener entre **1 y 128** caracteres;
- solo admite ASCII imprimible (`U+0020..U+007E`);
- conserva ceros iniciales, mayúsculas/minúsculas y espacios interiores;
- rechaza controles, marcas bidireccionales y cualquier carácter Unicode no ASCII.

La comparación conserva mayúsculas y minúsculas; `ABC`, `abc`, `00123` y `123` son valores
distintos. FacturaStock no transforma un código para que “parezca” otro.

El contrato es deliberadamente agnóstico a la simbología. **No impone un checksum universal ni
certifica GTIN, EAN, UPC u otro estándar.** Una lectura aceptada significa que el texto cumple el
formato seguro de almacenamiento, no que GS1 o un fabricante hayan validado su asignación.

## Asociación explícita en Ventas

Si el código ya pertenece a un producto del negocio, la app propone ese producto. Cuando existe en
varios almacenes, exige elegir la ubicación exacta antes de incorporarlo al carrito.

Si no existe, no crea un producto ni adivina una coincidencia:

1. muestra que la asociación requiere revisión;
2. permite buscar un producto recibido con existencia positiva;
3. la persona elige explícitamente el producto y el almacén;
4. el código queda guardado en ese producto solo tras esa elección.

Un código es único dentro de cada negocio. Si el producto elegido ya tiene otro código, se muestra
el valor anterior y el nuevo y se exige una confirmación separada antes de reemplazarlo. Cancelar la
asociación no cambia el catálogo ni el carrito.

Elegir un producto que todavía no tiene código confirma la asociación solicitada; no se presenta
como un «reemplazo». Una vez que la app informa que el código quedó asociado, ese cambio de catálogo
es durable e independiente del carrito. Si el producto no puede agregarse después por un conflicto
de versión o de stock, la pantalla cierra el estado cancelable de asociación, conserva el código ya
guardado e informa el fallo del carrito. Así nunca ofrece «Cancelar asociación» después de haberla
persistido.

## Precio, carrito, ganancias estimadas y checkout

Al vincular o crear un producto durante el ingreso de una compra se solicita un **precio de venta**
mayor que cero y se guarda en el catálogo del negocio con su moneda. También puede actualizarse
después desde **«Ganancias por producto»**. **Nunca se deriva automáticamente del costo promedio de
inventario:** siempre es una decisión humana y la venta gratuita no forma parte del alcance v1.

Al agregar el producto a una venta, el carrito propone el precio guardado cuando su moneda coincide
con la del carrito. La persona puede cambiarlo para esa venta; esa edición no modifica el catálogo ni
el precio que se propondrá después. Si falta precio o la moneda no coincide, la línea queda pendiente
y el checkout continúa bloqueado hasta ingresar un precio válido.

**«Ganancias por producto»** muestra una proyección, no utilidad contable realizada. Para cada
producto suma el stock de sus almacenes y calcula el costo unitario promedio ponderado usando los
costos que dejaron las compras publicadas:

`ganancia unitaria = precio de venta - costo promedio ponderado`

`margen % = ganancia unitaria / precio de venta × 100`

`ganancia potencial = ganancia unitaria × stock actual`

Puede mostrar valores negativos. No mezcla ni convierte monedas: si los saldos tienen monedas
distintas, o el precio de venta usa otra moneda, muestra el motivo y no inventa una cifra. Tampoco
presenta una proyección cuando falta precio, no hay stock valorizado o los datos persistidos no son
comparables. Los importes se calculan con aritmética decimal exacta; el margen tiene redondeo
explícito solo para presentación.

Agregar o editar líneas solo modifica el carrito local. En un negocio no enlazado, el stock cambia al
confirmar el checkout en una única transacción Room que:

1. vuelve a comprobar negocio, productos, unidades y almacenes activos;
2. vuelve a calcular el contenido y los totales del carrito;
3. exige precio en todas las líneas y existencia suficiente por producto y almacén;
4. descuenta los saldos mediante control optimista de versión;
5. escribe movimientos `SALE` negativos y un evento de auditoría local;
6. marca la venta como `POSTED` con una clave idempotente ligada a la versión y al contenido.

En un negocio cloud enlazado, la preparación local aplica las mismas comprobaciones, pero la
confirmación no degrada a una venta offline. La app traduce las identidades locales de catálogo a
las remotas y envía a Functions la venta completa con una clave idempotente ligada a `saleId`,
versión y hash de contenido. El servidor vuelve a validar autenticación, membresía, catálogo,
totales y saldo; en una sola transacción autoritativa registra la venta y sus movimientos, actualiza
los saldos y publica el cambio incremental. Solo después del ACK la app confirma en Room la venta,
los movimientos `SALE`, la auditoría y los saldos recibidos. Sin conexión, sesión plena, enlace
coherente o respaldo activo, el checkout queda bloqueado y no crea una venta local invisible para
el otro teléfono.

El carrito no admite dos líneas para el mismo par producto–almacén; una lectura repetida no puede
crear una segunda línea silenciosa y la cantidad debe revisarse sobre la línea existente. Si el
carrito o el inventario cambió concurrentemente, la operación falla sin aplicar un checkout parcial
y debe recargarse. Repetir la misma confirmación o tocar dos veces no crea un segundo descuento. El
checkout **no permite stock negativo**.

La idempotencia también cubre un ACK perdido o la muerte del proceso entre el commit remoto y el
commit Room. Un reintento del mismo contenido obtiene el mismo hecho remoto; el pull puede completar
un `DRAFT` local normal solo cuando identidad, versión, creación, líneas, totales y hash todavía
coinciden con la venta aceptada. Si el carrito se modificó, falla cerrado en vez de atribuirle el
hecho remoto. En otro dispositivo, el pull aplica primero el catálogo y luego materializa en una
única transacción la venta completa, sus movimientos y auditoría, los saldos finales autoritativos y
el avance del cursor. Sus UUID locales de producto o almacén pueden diferir: la resolución usa el
enlace de catálogo y el nombre canónico no ambiguo del almacén.

Las cantidades y precios editados se guardan antes de salir. Si Room informa que el borrador fue
eliminado o publicado por otra operación mientras había una edición local, la app intenta persistirla
contra la versión capturada; si no puede, mantiene el valor visible y exige una confirmación explícita
antes de descartarlo.

## Offline, privacidad y respaldo

Los borradores, búsquedas, lecturas y ediciones se guardan en Room dentro del sandbox de la
aplicación y siguen funcionando sin internet. La confirmación también es offline para el flavor
`local` y para un negocio que nunca fue enlazado. En cambio, un negocio con binding cloud durable
usa autoridad remota síncrona: apagar el respaldo o perder la conexión no elimina el binding ni
habilita un checkout local alternativo.

Las ventas cloud no usan la outbox comercial diferida de compras. Functions conserva el hecho
idempotente y un feed incremental de inventario; cada dispositivo enlazado descarga por pull la
venta completa y los saldos finales. Este mecanismo comparte hechos `POSTED`, pero no convierte
Firebase en una restauración integral de la instalación: no recupera borradores, preferencias ni
todo el estado local, y las imágenes requieren su opt-in documental independiente.

La ráfaga cruda del lector solo se mantiene en memoria mientras se ensambla en Ventas o Inventario y
no se registra en logs, Analytics ni Crashlytics. Consultar desde Inventario no la persiste. Solo si
la persona completa una asociación explícita en Ventas el valor deja de ser efímero y pasa a ser el
campo `barcode` del producto. En el flavor `cloud`, ese dato de catálogo puede entrar en el respaldo
opcional de catálogos cuando se cumplen sus condiciones. Si el negocio está enlazado y el respaldo
activo, esa identidad de catálogo participa además en la venta remota compartida.

El JSON `ACCOUNTING_LEDGER` con `schemaVersion = 4` no incluye cabeceras/líneas de venta, deudas ni abonos. Sus
saldos, movimientos genéricos y auditoría pueden reflejar el efecto sobre inventario, pero no
constituyen una exportación restaurable de la venta. No se debe borrar el teléfono contando con ese
JSON ni considerar el pull de Firebase como copia integral del dispositivo.

## Fuera de alcance v1

- Escaneo por cámara y decodificación de códigos con ML Kit. El modelo Barcode Scanning no se
  empaqueta porque Ventas e Inventario utilizan exclusivamente un lector físico HID para códigos.
- Consulta de catálogos GS1, validación universal de checksum o certificación de simbología.
- Restauración integral del teléfono, de borradores o de preferencias desde Firebase; el pull solo
  materializa hechos remotos compartidos y sus saldos.
- Anulación, devolución o reversión de una venta ya confirmada.
- Conversión automática entre monedas o precio de venta calculado automáticamente desde el costo.
- Utilidad contable realizada, gastos operativos, impuestos de la empresa o reportes por periodo;
  la pantalla de ganancias es una proyección del inventario actual.
- Venta sin existencia o venta gratuita.
- Emisión de boleta/factura electrónica de venta, envío a SUNAT, padrón general de clientes,
  procesamiento de cobros, caja o conciliación bancaria. **Deudores** sí permite anotar pagos
  recibidos por otros medios, pero no mueve dinero ni procesa credenciales financieras.
- Cálculo tributario comercial de la venta: el importe escrito es el precio final usado por esta
  salida de inventario; la UI v1 no separa IGV, descuentos ni otros tributos de venta.

Estos límites son distintos del flujo de compras: una compra usa su protocolo propio de outbox,
respaldo opcional y anulación; una venta enlazada usa autoridad cloud síncrona y pull idempotente,
pero todavía no ofrece anulación ni restauración integral. No se deben extrapolar capacidades de un
flujo al otro.
