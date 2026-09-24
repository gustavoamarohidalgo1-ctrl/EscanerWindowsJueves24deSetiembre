# Ventas, inventario y lector externo de códigos

Este documento delimita el alcance funcional de ventas y del registro de inventario mediante
códigos en FacturaStock. Describe el contrato del repositorio; no acredita por sí solo una
compilación ni una instalación en el dispositivo del negocio.

## Alcance

El carrito de venta es **local y offline-first**. Para el negocio activo se conserva un único
`DRAFT` por moneda; cerrar la pantalla o perder la conexión no publica ni descuenta existencias. El
checkout confirma íntegramente en Room y puede hacerlo sin internet. El carrito permite:

- agregar un producto escribiendo su nombre y eligiendo entre coincidencias similares;
- recibir el código de un lector físico USB o Bluetooth configurado como teclado
  (*keyboard wedge*) e incorporar cada producto una sola vez mediante el escáner;
- registrar un código desconocido desde la venta y volver al mismo carrito;
- elegir el almacén exacto cuando el producto tiene existencias en más de una ubicación;
- revisar cantidad y el precio unitario de venta sugerido antes de confirmar;
- elegir **«Contado»** o **«A crédito»** en **«Tipo de venta»**; el crédito exige el nombre de la persona y crea una cuenta por
  cobrar ligada a las líneas publicadas.

Solo se ofrecen productos activos con existencia positiva. Una línea siempre identifica el par
concreto producto–almacén: el flujo no presenta el stock agregado de varias ubicaciones como si fuera
una única existencia vendible. Si el saldo es positivo pero menor que una unidad, la cantidad inicial
usa exactamente ese saldo fraccionario en vez de intentar vender una unidad inexistente.

## Entrada a la venta

**Vender**, la pantalla inicial de la app, comienza con **Tipo de venta**: **Contado** o
**A crédito**. Al tocar una opción se abre una pantalla distinta para elegir **Escáner físico**
o **Venta manual**. El lector todavía no recibe códigos mientras se muestran estos selectores.

**Escáner físico** abre la pantalla del lector y del carrito. **Venta manual** abre el catálogo
con productos que se pueden tocar para agregarlos, junto con una búsqueda opcional por nombre.
El catálogo manual no aparece en la pantalla del escáner, salvo cuando un código desconocido
requiere elegir explícitamente un producto para asociarlo.

**Atrás**, tanto en pantalla como con el gesto o botón de Android, vuelve primero a la elección
del modo y después al tipo de venta. Volver a elegir un modo conserva las líneas del carrito;
estos pasos no publican una venta ni descuentan stock. Para cambiar entre lector y catálogo se
vuelve a la pantalla de modos.

## Flujo manual

1. Abrir **«Vender»**, que también es la pantalla inicial de la app.
2. Tocar **«Contado»** o **«A crédito»**.
3. En la siguiente pantalla, tocar **«Venta manual»**.
4. Tocar un producto del catálogo; se puede escribir su nombre o parte del nombre para filtrarlo.
   Elegir la ubicación de la que saldrá el stock cuando corresponda.
5. Revisar la cantidad y el **precio unitario de venta** sugerido en el carrito.
6. Revisar el total y usar **«Revisar y confirmar venta»**.

La venta manual no interpreta el texto como SKU, código de barras ni código de proveedor. Incluso
si la consulta contiene solo números, se compara con el nombre del producto y nunca activa una
búsqueda encubierta por código. Para leer códigos se debe cambiar explícitamente a
**«Escáner físico»**.

## Un escaneo por producto; cantidad manual

En modo **Escáner físico**, la primera lectura de un producto que no está en el carrito añade
una unidad, o el saldo fraccionario disponible si es menor que una unidad. Las unidades que se
van a vender se ajustan en el campo **Cantidad** del carrito. Repetir el disparo no aumenta esa
cantidad ni crea otra línea: la pantalla informa **Ya está en la venta** y muestra la cantidad
guardada.

La comprobación usa la identidad del producto (`productId`) en todo el carrito. También se aplica
si otra lectura llega por su SKU, si se elige una sugerencia de código parecido o si el producto
ya figura en otro almacén. Una relectura no cambia el almacén, el precio ni la cantidad elegidos.
Si se retira el producto por completo del carrito, una lectura posterior permite añadirlo de nuevo.
Cambiar de modo o volver a abrir la misma venta conserva esta regla porque se consulta el carrito
actual, sin mantener una lista permanente de códigos bloqueados.

**Venta manual** conserva su comportamiento: tocar otra vez un producto y almacén puede aumentar
su cantidad, con las validaciones de existencias. La regla de un solo ingreso por producto
mediante el escáner no modifica la identidad producto–almacén de las líneas ni sus controles de stock.

## Recuperar una lectura incompleta durante la venta

En **Vender → Escáner físico**, la búsqueda exacta por código de barras conserva prioridad;
también se admite el SKU exacto de un producto importado. Si no hay coincidencia exacta, se
buscan hasta cinco productos con códigos numéricos que puedan diferir por **uno, dos o tres
dígitos omitidos**, conservando el orden de los demás. Las omisiones pueden estar al principio,
en medio o al final. También se contempla que el código guardado haya quedado incompleto.

La pantalla **Códigos parecidos** muestra el código leído y, para cada opción, el nombre,
código guardado, número de dígitos de diferencia, existencias, almacén y precio disponible.
Solo incluye productos activos del negocio actual con existencias positivas. Los candidatos
con menos omisiones aparecen primero; cada almacén se elige por separado.

Tocar **Agregar a la venta** añade exclusivamente el producto y almacén elegidos, con su precio
guardado, si ese producto todavía no está en el carrito. Si ya está, identifica su línea y conserva
la cantidad elegida. **No cambia ni asocia el código de barras**. Si hay dudas, **Volver a escanear**
descarta la elección pendiente y permite repetir la lectura. Para cambiar una asociación se
mantiene el acceso explícito **Asociar este código manualmente**, con aviso de comprobar antes
el código completo. Si no hay sugerencias, permanece el flujo habitual de búsqueda y asociación.

La comparación conserva ceros iniciales y requiere al menos cinco dígitos en el código corto
y ocho en el largo, con un máximo de 128 caracteres. No infiere sustituciones ni intercambios
de dígitos, ni trata una coincidencia parecida como identificación segura. Reescanear, cancelar,
cambiar de venta o perder las existencias invalida las opciones anteriores.

## Registrar un código desconocido desde Ventas

Una lectura sin coincidencia exacta ofrece **Registrar producto**. El botón está disponible una
vez que terminan las lecturas pendientes y las ediciones del carrito. Abrirlo conserva la venta
actual y reutiliza el formulario de Inventario con el código leído precargado. Mientras el
formulario está abierto, el lector de Ventas y el cobro quedan en pausa.

Se completan nombre, cantidad inicial de inventario, precio de compra y precio de venta. La
cantidad del formulario es el stock que ingresa; la cantidad que se venderá se ajusta después
en el carrito. Se aplican las mismas validaciones y la misma transacción que al registrar desde
Inventario.

Al guardar, la app vuelve a la misma venta, relee el producto guardado y sus existencias, y lo
incorpora una sola vez con su precio de venta. Si requiere elegir almacén, muestra esa elección.
Un producto que ya esté en el carrito conserva su cantidad. Cancelar el formulario no crea el
producto ni suma stock, y devuelve la lectura desconocida para reescanear, asociar o registrar.

El registro en inventario y la incorporación al carrito son operaciones distintas. Si el producto
se guardó pero no pudo añadirse a la venta, aparece **Producto registrado** con el aviso de revisar
el error y volver a escanearlo. El producto y sus existencias iniciales se conservan; reintentar
la lectura no vuelve a registrar ese stock. La solicitud y su resultado se correlacionan con el
negocio y el carrito originales para impedir que una respuesta antigua modifique otra venta.

## Registro desde Inventario

**Inventario → Registrar productos** abre una pantalla dedicada a recibir lecturas del escáner
físico HID. La pantalla principal muestra el inventario y los accesos de registro; el registro
por lector se inicia desde este botón.

Una lectura completa y válida busca el código exacto dentro del negocio activo. Si el producto
ya existe, abre su detalle sin duplicarlo ni sumar existencias. Si el código es nuevo, abre
automáticamente un formulario con el código precargado y de solo lectura. Se completan cuatro
datos:

1. nombre del producto;
2. cantidad inicial, expresada en su unidad de inventario;
3. precio de compra unitario;
4. precio de venta unitario.

El código conserva sus ceros iniciales, mayúsculas y minúsculas. Al guardar queda vinculado al
producto para que **Vender → Escáner físico** lo reconozca mediante la misma búsqueda exacta.
La cantidad debe ser positiva, el costo no negativo y el precio de venta positivo. El registro
requiere unidad y almacén activos del negocio y una moneda compatible para ambos precios.

Guardar confirma en una transacción local el producto, su código y precio de venta, las
existencias iniciales, el costo y el movimiento de inventario. Si falla alguna escritura, se
revierte todo el registro. Un reintento del mismo producto o un código ya registrado no vuelve
a sumar existencias. Abrir o cancelar el formulario no crea productos ni mueve stock.

Mientras se consulta un código, se abre el formulario o se muestra el detalle, la captura queda
en pausa. Guardar correctamente o cancelar el formulario de un código nuevo vuelve automáticamente
a **Registrar productos** y habilita la siguiente lectura. Un fallo de guardado conserva el
formulario para corregirlo o reintentar. La edición normal de un producto permanece en el catálogo.
Al volver desde el detalle de un código existente también se habilita una lectura nueva. Salir de esa
pantalla o pausar la app descarta cualquier fragmento pendiente. El formulario permite completar
los datos con teclado sin interpretar su texto como nuevas lecturas.

Este flujo usa el lector físico; no fotografía facturas ni decodifica códigos por cámara.

## Venta a crédito y Deudores

El tipo de venta se elige en la primera pantalla, **«Tipo de venta»**. **«Contado»** no crea una
deuda. **«A crédito»** exige un nombre normalizado de 2 a 120 caracteres; el lector y la
búsqueda manual siguen disponibles aunque el nombre todavía esté vacío, pero el checkout permanece
bloqueado hasta completarlo.

Entrar desde **Deudores → Registrar deuda** abre la elección del modo con **«A crédito»**
preseleccionado y conserva ese tipo al volver entre el lector y el catálogo.

La confirmación a crédito es atómica con la venta y el inventario. Room crea una deuda `OPEN`, con
saldo igual al total y una identidad determinística derivada de `saleId`. No existe una ruta para
crear una deuda sin una venta `POSTED` exacta.

El acceso **Deudores** lista cuentas abiertas/pagadas, muestra los productos originales y conserva
un libro append-only de abonos. Los cobros parciales o totales usan control optimista de versión e
idempotencia. El contrato
funcional, de concurrencia y privacidad está en
[`DEBTORS_AND_CREDIT_SALES.md`](DEBTORS_AND_CREDIT_SALES.md).

## Lector USB o Bluetooth tipo teclado

En Ventas, la franja junto al receptor confirma una incorporación nueva **después de guardar**,
con su cantidad actual y almacén. Una relectura muestra **Ya está en la venta**, sin otra escritura
del carrito. La franja permanece visible al desplazar la lista; si se edita o elimina esa línea,
refleja el carrito guardado.

Las lecturas se atienden en orden, con hasta 32 pendientes contando la que se procesa. Repeticiones
consecutivas del mismo código se agrupan mientras su lectura siga pendiente o en proceso: una
ráfaga de 32 códigos idénticos puede resolverse con una consulta y un guardado. Los códigos distintos
conservan su orden. Tampoco se elimina la última lectura de una secuencia como
`A → desconocido → A`: debe poder cerrar la elección desconocida, aunque A ya esté en el carrito.
Esta agrupación no introduce un cargador que mueva la lista por cada disparo.

Una lectura que todavía requiere seleccionar producto o almacén, o un error del lector pendiente
de reconocer, impide iniciar un cobro nuevo. Reescanear, elegir la opción correspondiente o
cancelar/reiniciar la lectura permite continuar. Un checkout ya persistido conserva su ruta de
reintento. El cambio de comportamiento y el estado de validación de esta revisión se documentan en
[`SCANNER_SINGLE_ADD_AND_REGISTRATION_2026-09-08.md`](SCANNER_SINGLE_ADD_AND_REGISTRATION_2026-09-08.md).
[`SCANNER_REFINEMENT_2026-09-08.md`](SCANNER_REFINEMENT_2026-09-08.md) conserva la revisión anterior.

FacturaStock no administra el emparejamiento. Android debe reconocer el dispositivo como un teclado
físico HID/*keyboard wedge*:

- **USB:** conectar el lector al teléfono o tableta mediante el adaptador USB/OTG apropiado.
- **Bluetooth:** emparejarlo desde los ajustes de Android y seleccionar, si el fabricante lo exige,
  el perfil HID/teclado.
- Configurar el lector para enviar al final **Enter**, **Enter de teclado numérico** o **Tab**. Sin
  terminador, la aplicación no trata la ráfaga como una lectura completa.

La entrada física se atiende cuando la app está desbloqueada y se ha abierto
**Vender → Contado/A crédito → Escáner físico** o **Inventario → Registrar productos**. Cambiar de pantalla,
pausar la app o desactivar ese modo descarta cualquier lectura parcial. Solo puede existir un
receptor activo y la última lectura completa no queda almacenada en el ensamblador. En Ventas,
enfocar un campo de nombre, cantidad o precio pausa el lector para permitir escribir; se puede
volver a **Escáner físico** para continuar.

El adaptador admite eventos físicos `DOWN`/`UP` y texto agrupado `ACTION_MULTIPLE`. Conserva cada
carácter para validar la trama completa, incluidos los caracteres que deban rechazarse. Enter,
Enter numérico y Tab cierran la lectura; en texto agrupado, CR y LF se interpretan como Enter.
Un cierre adicional del mismo dispositivo dentro de los siguientes **250 ms**, como el LF de
un sufijo CRLF, se consume sin entregar una segunda lectura ni activar el botón que tenga foco.
También se consume la liberación de una tecla ya capturada aunque la lectura haya abierto otra
pantalla o un diálogo.

Una pausa de más de **un segundo** entre caracteres o un cambio de dispositivo en mitad de la
lectura invalida toda la trama. Los controles internos, como un separador GS, y los caracteres
fuera del contrato ASCII también la invalidan. Superar **128 caracteres** invalida la trama por
longitud. En estos casos se descarta el resto hasta Enter o Tab y se muestra un mensaje para
repetir la lectura completa: no se entrega el sufijo restante como otro código válido. Las
repeticiones y duplicados del mismo evento no duplican caracteres.

Cada bloque de texto `ACTION_MULTIPLE` se conserva completo, incluso cuando contiene un solo
dígito y coincide en tiempo con el bloque anterior. La detección de eventos `DOWN` físicos
duplicados se mantiene separada: evita confundir dos caracteres repetidos legítimos con una
reentrega del mismo evento físico.

La compatibilidad descrita es la del protocolo de teclado físico reconocido por Android. No
incluye lectores en modo serial/SPP, integraciones propietarias ni eventos de teclados virtuales.
No implica certificación de todos los modelos USB/Bluetooth, distribuciones de teclado o
configuraciones de fabricante.

### Checklist para el modelo físico

La marca y el modelo del lector del usuario siguen pendientes de confirmación. Las pruebas con
eventos sintéticos comprueban el software, pero no acreditan la compatibilidad de ese lector.
Antes de usar un modelo en producción, registrar sin datos comerciales reales:

1. marca/modelo del lector, teléfono o tableta, versión de Android y adaptador OTG si corresponde;
2. que Android lo muestre como teclado físico y que no requiera permisos dentro de FacturaStock;
3. una lectura conocida con letras, ceros iniciales y terminador Enter o Tab, comprobando el valor
   exacto que llega;
4. en Ventas, un código conocido, uno desconocido que se asocia de forma explícita y un producto
   presente en dos almacenes;
5. en **Inventario → Registrar productos**, que un código conocido abra su detalle y que uno nuevo
   abra el formulario de cuatro campos con el código exacto; cancelar no debe escribir nada y
   guardar debe registrar una sola vez producto, precios, cantidad y costo;
6. varias lecturas del mismo producto en Ventas: debe aparecer una sola vez, sin aumentar la cantidad;
   editar la cantidad, volver a leer su código o SKU y comprobar que se conserva; retirar el producto
   y comprobar que otra lectura permite añadirlo; al confirmar se descuenta solo la cantidad elegida;
7. pausa, rotación y bloqueo de la app en mitad de una lectura, verificando que el fragmento se
   descarte;
8. navegación con teclado/TalkBack: Tab o Enter sin lectura pendiente, fuera de la ventana del
   sufijo duplicado, deben seguir llegando a la interfaz; editar nombres, cantidades y precios
   con teclado físico no debe iniciar una búsqueda de código;
9. si el modelo usa CRLF, texto agrupado o pausas configurables, verificar una entrega por
   lectura y que una trama interrumpida o inválida muestre el error sin aceptar su fragmento;
10. desde una lectura desconocida en Ventas, abrir **Registrar producto**: cancelar vuelve a la venta
    sin crear; guardar registra una sola vez y vuelve al mismo carrito con el producto incorporado.

Si cualquiera falla, conservar la búsqueda manual disponible y no declarar compatible ese
modelo hasta corregir su perfil, sufijo o distribución de teclado.

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

Si el código ya pertenece a un producto del negocio, la app reconoce ese producto. Si todavía no
está en el carrito y existe en varios almacenes, exige elegir la ubicación exacta antes de
incorporarlo. Si ya está en cualquier línea del carrito, conserva su cantidad y ubicación.

Si no existe una coincidencia exacta, primero se ofrecen los códigos parecidos que cumplan
las reglas descritas arriba. Elegir una sugerencia conserva el código original. El flujo
separado de asociación manual funciona así:

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

Al registrar un producto desde el escáner de Inventario se solicita un **precio de venta** mayor
que cero y se guarda en el catálogo del negocio con su moneda. También puede actualizarse
después desde **«Ganancias por producto»**. **Nunca se deriva automáticamente del costo promedio de
inventario:** siempre es una decisión humana y la venta gratuita no forma parte del alcance v1.

Al agregar el producto a una venta, el carrito propone el precio guardado cuando su moneda coincide
con la del carrito. La persona puede cambiarlo para esa venta; esa edición no modifica el catálogo ni
el precio que se propondrá después. Si falta precio o la moneda no coincide, la línea queda pendiente
y el checkout continúa bloqueado hasta ingresar un precio válido.

**«Ganancias por producto»** muestra una proyección, no utilidad contable realizada. Para cada
producto suma el stock de sus almacenes y calcula el costo unitario promedio ponderado usando los
costos registrados en sus entradas de inventario:

`ganancia unitaria = precio de venta - costo promedio ponderado`

`margen % = ganancia unitaria / precio de venta × 100`

`ganancia potencial = ganancia unitaria × stock actual`

Puede mostrar valores negativos. No mezcla ni convierte monedas: si los saldos tienen monedas
distintas, o el precio de venta usa otra moneda, muestra el motivo y no inventa una cifra. Tampoco
presenta una proyección cuando falta precio, no hay stock valorizado o los datos persistidos no son
comparables. Los importes se calculan con aritmética decimal exacta; el margen tiene redondeo
explícito solo para presentación.

Agregar o editar líneas solo modifica el carrito local. El stock cambia al confirmar el checkout en
una única transacción Room que:

1. vuelve a comprobar negocio, productos, unidades y almacenes activos;
2. vuelve a calcular el contenido y los totales del carrito;
3. exige precio en todas las líneas y existencia suficiente por producto y almacén;
4. descuenta los saldos mediante control optimista de versión;
5. escribe movimientos `SALE` negativos y un evento de auditoría local;
6. marca la venta como `POSTED` con una clave idempotente ligada a la versión y al contenido.

El carrito no admite dos líneas para el mismo par producto–almacén. En modo escáner, un producto
que ya esté en cualquier línea no se incorpora otra vez; la cantidad se modifica manualmente.
Las lecturas distintas recibidas mientras se procesa otra se atienden en orden y las repeticiones
consecutivas pendientes se agrupan. Si la cola está llena, se informa que esa lectura debe repetirse.
Si el carrito o el inventario cambió concurrentemente, la operación falla sin aplicar un checkout parcial
y debe recargarse. Repetir la misma confirmación o tocar dos veces no crea un segundo descuento. El
checkout **no permite stock negativo**.

Las cantidades y precios editados se guardan antes de salir. Si Room informa que el borrador fue
eliminado o publicado por otra operación mientras había una edición local, la app intenta persistirla
contra la versión capturada; si no puede, mantiene el valor visible y exige una confirmación explícita
antes de descartarlo.

## Offline, privacidad y respaldo

Los borradores, búsquedas, lecturas y ediciones se guardan en Room dentro del sandbox de la
aplicación y siguen funcionando sin internet. La confirmación también es offline: la app no tiene
permiso de internet y no comparte ventas con otros dispositivos. Las ventas no usan la outbox de
compras. El código compartido conserva la rama de autorización remota que usaba un negocio enlazado
a la variante cloud (retirada el 24 de septiembre de 2026), pero la variante `local` no puede crear
ese enlace.

La ráfaga cruda del lector solo se mantiene en memoria mientras se ensambla en Ventas o Inventario y
no se registra en logs. Un código nuevo leído en Inventario, o elegido para
registrar desde Ventas, pasa al formulario recuperable para completar el registro. La solicitud de
registro desde Ventas y su resultado se conservan en `SavedStateHandle` hasta resolver el retorno.
Al guardar el formulario, o al completar una asociación
explícita en Ventas, se persiste como campo `barcode` del producto. Consultar un código existente
no modifica ese campo.

El JSON `ACCOUNTING_LEDGER` con `schemaVersion = 4` no incluye cabeceras/líneas de venta, deudas ni abonos. Sus
saldos, movimientos genéricos y auditoría pueden reflejar el efecto sobre inventario, pero no
constituyen una exportación restaurable de la venta. No se debe borrar el dispositivo contando con
ese JSON: la única copia completa es el respaldo `adb run-as` descrito en [`RUNBOOK.md`](RUNBOOK.md).

## Fuera de alcance v1

- Escaneo por cámara dentro de estos flujos de Ventas y Registrar productos, lectores seriales/SPP
  e integraciones propietarias que no entreguen entrada de teclado físico Android.
- Consulta de catálogos GS1, validación universal de checksum o certificación de simbología.
- Restauración integral del dispositivo, de borradores o de preferencias dentro de la app, y
  sincronización de ventas entre dispositivos.
- Devolución parcial de una venta. Las ventas admiten anulación completa desde Reportes, mediante
  recibo y movimientos compensatorios.
- Conversión automática entre monedas o precio de venta calculado automáticamente desde el costo.
- Utilidad contable realizada, gastos operativos, impuestos de la empresa o reportes por periodo;
  la pantalla de ganancias es una proyección del inventario actual.
- Venta sin existencia o venta gratuita.
- Emisión de boleta/factura electrónica de venta, envío a SUNAT, padrón general de clientes,
  procesamiento de cobros, caja o conciliación bancaria. **Deudores** sí permite anotar pagos
  recibidos por otros medios, pero no mueve dinero ni procesa credenciales financieras.
- Cálculo tributario comercial de la venta: el importe escrito es el precio final usado por esta
  salida de inventario; la UI v1 no separa IGV, descuentos ni otros tributos de venta.

Estos límites son distintos del flujo de compras: una compra usa su protocolo propio de outbox
(hoy sin transporte remoto) y anulación; una venta se confirma solo en Room, sin outbox, y se anula
desde Reportes. Ninguno de los dos ofrece restauración integral. No se deben extrapolar capacidades
de un flujo al otro.
