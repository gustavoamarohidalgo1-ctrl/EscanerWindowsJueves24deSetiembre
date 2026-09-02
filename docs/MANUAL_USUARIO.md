# Manual de usuario de FacturaStock

Este manual alcanza para completar el recorrido entero sin ayuda de nadie más: desde configurar el
negocio hasta ver el inventario actualizado. Todo lo que se describe aquí corresponde a la
aplicación Android instalada en el teléfono; los nombres entre comillas son exactamente los que
aparecen en pantalla.

Dos ideas guían el diseño y conviene tenerlas presentes desde el principio:

1. **El escáner automático guarda solo productos seguros.** Nunca convierte la foto en una compra
   ni cambia las existencias, costos o precios.
2. **El trabajo local funciona sin internet.** Productos, borradores y OCR se guardan en el teléfono.
   Solo el checkout y los abonos de un negocio cloud enlazado exigen conexión para mantener una
   única versión compartida del inventario y de las cuentas por cobrar.

## Índice

1. [Configurar el negocio](#1-configurar-el-negocio)
2. [Permisos](#2-permisos)
3. [Escanear productos de una factura](#3-escanear-productos-de-una-factura)
4. [Avisos de calidad de la captura](#4-avisos-de-calidad-de-la-captura)
5. [Revisar la cabecera de la factura](#5-revisar-la-cabecera-de-la-factura)
6. [Revisar los productos de la factura](#6-revisar-los-productos-de-la-factura)
7. [Crear o vincular productos](#7-crear-o-vincular-productos)
8. [Redondeo y diferencias de centavos](#8-redondeo-y-diferencias-de-centavos)
9. [Confirmar la compra](#9-confirmar-la-compra)
10. [Registrar una venta](#10-registrar-una-venta)
   - [Venta a crédito, deudores y abonos](#venta-a-crédito-deudores-y-abonos)
11. [Trabajar sin conexión](#11-trabajar-sin-conexión)
12. [Sincronización y respaldo](#12-sincronización-y-respaldo)
13. [Comprobantes duplicados](#13-comprobantes-duplicados)
14. [Anular una compra](#14-anular-una-compra)
15. [Inventario](#15-inventario)
16. [Privacidad de tus datos](#16-privacidad-de-tus-datos)
17. [Practicar sin riesgo: modo demostración](#17-practicar-sin-riesgo-modo-demostración)

La barra inferior tiene cinco destinos permanentes: **Inicio**, **Catálogos**, **Compras**,
**Inventario** y **Ajustes**.

## 1. Configurar el negocio

La primera vez que se abre la app aparece **«Configura tu negocio»**. Es una compuerta: no se puede
usar el resto hasta completarla, porque cada compra necesita saber a qué negocio pertenece, con qué
impuesto calcular y en qué almacén entra la mercadería.

Cuatro secciones:

| Sección | Campo | Qué escribir |
| --- | --- | --- |
| Negocio | **Nombre comercial** | Obligatorio. Como conoce la gente al negocio |
| Negocio | **RUC (opcional)** | 11 dígitos si se tiene. Se puede dejar vacío |
| Impuestos y costos | **IGV (%)** | Porcentaje entre 0 y 100. En Perú normalmente 18 |
| Impuestos y costos | **Política de costos** | «Neto (sin IGV)» o «Bruto (IGV incluido)». Define si el costo que se guarda en inventario incluye el impuesto |
| Regional | **Moneda** / **Zona horaria** | Fijas en «PEN — sol peruano» y «America/Lima (GMT−5)» |
| Inventario | **Almacén principal** | Nombre del lugar donde entra la mercadería. Ejemplo: «Tienda» |

Al tocar **«Comenzar»** se crea el negocio, la unidad de medida base, el almacén y la configuración
de impuestos, todo dentro del teléfono.

Sobre el RUC: la app comprueba el **dígito de control** con una operación matemática local. Si no
coincide muestra **«RUC por verificar»** con el texto «El dígito de control del RUC no coincide en la
comprobación local. Verifica el número; si es correcto, guarda de nuevo para conservarlo». Es un
aviso, no un bloqueo: la app **no consulta SUNAT** y no puede afirmar si un RUC existe o está
activo.

Todo esto se cambia después en **Ajustes → Negocio**. Un detalle importante: al modificar el IGV o
la política de costos aparece **«Aplicar solo a cálculos futuros»** — las compras ya registradas no
se recalculan nunca. Eso es intencional: una compra publicada es historia y no se reescribe.

## 2. Permisos

FacturaStock pide **un solo permiso**: la cámara, y solo en el momento en que se intenta tomar la
primera foto. No pide contactos, ni ubicación, ni acceso a todo el almacenamiento.

- Si se concede, se abre la cámara con el marco de encuadre.
- Si se rechaza, aparece **«Sin acceso a la cámara»**. El botón **«Conceder permiso»** vuelve a
  preguntar; si Android ya no permite preguntar, se habilita desde los ajustes del sistema del
  teléfono. El escaneo directo de dos pasos necesita este permiso porque no añade un selector de
  galería como tercer paso.

El lector de códigos de una venta tampoco usa el permiso de cámara. Debe estar conectado por USB
o emparejado en los ajustes de Android como teclado físico HID; la app no administra ese
emparejamiento ni solicita permisos de Bluetooth para hacerlo.

## 3. Escanear productos de una factura

Desde **Comprobantes** el recorrido tiene exactamente dos acciones:

1. Tocar **«Abrir cámara»**.
2. Colocar toda la factura dentro del marco y tocar **«Tomar foto»**.

Después, la app procesa sin pedir más pasos: «Preparando imágenes…», «Leyendo páginas…», «Uniendo
resultados…» y «Guardando productos…». Solo acepta descripciones de filas con confianza alta y una
señal estructural de producto. Las lecturas dudosas se omiten; los nombres repetidos o que ya están
en el catálogo no se duplican.

El resultado abre **Productos**. Se guardan el nombre y una unidad segura; no se guardan proveedor,
RUC, número del comprobante, cantidades, costos, precio de venta ni almacén. Por ello la operación
**no registra una compra y no aumenta el inventario**. Cada producto nuevo queda con existencia cero
hasta que una operación posterior registre stock.

El OCR ocurre dentro del teléfono y la foto de este recorrido no se sube como comprobante. Tras un
éxito se eliminan la foto y el borrador temporal. En la variante con nube, el producto se guarda
primero en Room y su outbox se envía cuando la cuenta está enlazada y **«Respaldar registros en la
nube»** está activo; si no hay conexión, queda pendiente sin perderse.

Si no se detectan productos seguros o el guardado falla, se puede reintentar y la foto temporal se
conserva. Si el OCR se interrumpe por cierre de la app, **Inicio** permite reanudarlo de forma
idempotente.

Las secciones 4 a 9 documentan pantallas conservadas para borradores de compra anteriores y para
pruebas de compatibilidad. El escáner principal de dos pasos no las abre ni publica una compra.

## 4. Avisos de calidad de la captura

Antes de procesar, la app mide la imagen y puede mostrar **«La captura podría mejorar»**. El propio
aviso aclara su alcance: «Estas señales son aproximadas, no un diagnóstico. Puedes continuar con el
OCR o repetir la primera página advertida».

Los avisos indican siempre la página y el motivo medido, con el número y la referencia:

| Aviso | Qué significa | Qué hacer |
| --- | --- | --- |
| **posible desenfoque** («nitidez medida X, referencia Y») | La foto salió movida o desenfocada | Apoyar el teléfono, esperar a que enfoque y repetir |
| **resolución efectiva baja** | La factura ocupa pocos píxeles | Acercarse más, que la factura llene el marco |
| **posible falta de luz** | Brillo medio bajo y muchos píxeles oscuros | Encender el flash o buscar mejor luz |
| **posible exceso de luz** | Brillo alto y píxeles quemados | Apagar el flash, evitar el reflejo directo |
| **posible inclinación** | La hoja está torcida | Repetir de frente o usar «Girar 90°» y «Recortar» |
| **hay contenido cerca del borde** | Puede faltar un trozo de la factura | Repetir incluyendo los cuatro bordes |

Hay dos botones: **«Continuar de todos modos»** y **«Repetir página advertida»**. La recomendación
práctica es simple: si el aviso es de **desenfoque** o de **contenido cortado**, conviene repetir,
porque son los dos que más errores de lectura producen. Los avisos de luz e inclinación suelen ser
tolerables.

Continuar con un aviso no genera ningún problema oculto: solo significa que probablemente haya más
campos por corregir a mano en el paso siguiente.

## 5. Revisar la cabecera de la factura

La pantalla **«Revisar factura»** muestra la imagen arriba y los campos leídos abajo, en tres
bloques: **Proveedor**, **Comprobante** y **Totales impresos**.

| Bloque | Campos |
| --- | --- |
| Proveedor | **RUC**, **Proveedor o razón social** |
| Comprobante | **Tipo de comprobante** (Factura, Boleta de venta, Nota de crédito, Nota de débito), **Serie**, **Número**, **Fecha de emisión**, **Moneda** |
| Totales impresos | **Subtotal**, **IGV**, **Otros cargos**, **Total** |

La app puede reconocer una **Nota de crédito** para que el tipo leído no se convierta
silenciosamente en factura, pero la versión 1.0 no la registra como una compra nueva. El resumen la
bloquea con una explicación específica: hay que abrir la compra original y usar **«Anular esta
compra»** para generar la reversión auditada del inventario.

Cada campo puede llevar una etiqueta de confianza. Cuando el OCR no está seguro se lee «Baja
confianza. Verifica este campo con la factura», «Confianza desconocida…» o «El OCR marcó este campo
para revisión manual». En esos casos hay que hacer una de dos cosas: corregir el valor, o tocar
**«Confirmar dato verificado»** si ya se comprobó que la lectura es correcta. Al confirmarlo, el
campo pasa a «Dato verificado por ti».

Para comprobar sin dudas de dónde salió un número existe **«Ver texto OCR»**: abre «Evidencia del
OCR» con el «Texto original leído», la página donde se encontró y las **«Otras lecturas
candidatas»**. También se puede tocar **«Ampliar documento»** y hacer zoom con los dedos o con los
botones «Ampliar» y «Reducir».

Dos avisos propios de esta pantalla:

- **RUC con dígito verificador que no coincide**: «El RUC tiene 11 dígitos, pero su dígito
  verificador no coincide. Compruébalo con la factura». Se puede continuar.
- **El total no cuadra con la suma impresa**: «El total difiere exactamente en S/ X de subtotal +
  IGV + cargos. **No se modificó ningún importe**». La app nunca corrige un importe por su cuenta:
  informa la diferencia exacta y deja la decisión a la persona.

Cada cambio se guarda al escribirlo («Guardando este cambio…»). Si el guardado falla, el texto
**permanece en pantalla** y aparece **«Reintentar guardado»**; nunca se pierde lo escrito.

Para avanzar se toca **«Revisar productos»**. Si algo falta o está mal, la app dice exactamente qué:
«Para revisar productos, corrige «Serie»: es obligatorio o tiene un formato inválido», o «Verifica
«Total» con la factura y confirma el dato antes de revisar productos».

## 6. Revisar los productos de la factura

En **«Revisar productos»** aparece una tarjeta por cada línea de la factura, con el contador
**«Líneas pendientes: N de M»**. El objetivo del paso es dejar ese contador en cero.

Cada línea tiene: **Descripción**, **Código**, **Cantidad**, **Unidad**, **Costo unitario**,
**Descuento (importe)**, **IGV (importe)** y **Total de línea**, más su nivel de confianza
(«Confianza alta / media / baja / desconocida» con el porcentaje).

Al tocar **«Editar»** se abre «Editar producto». Ahí, cada importe indica de dónde viene:

| Etiqueta | Significado |
| --- | --- |
| «Valor usado: OCR» | Se está usando lo que se leyó de la foto |
| «Valor usado: calculado» | La app lo derivó de los demás campos de la línea |
| «Valor usado: escrito por ti» | Se corrigió a mano |
| «Sin valor» | Falta el dato |

Debajo se muestran las referencias «OCR original: …» y «Cálculo exacto: …», o «Coincide con el valor
usado». Así siempre se puede comparar lo escrito contra lo leído y contra la aritmética exacta.

Herramientas de la lista:

- **Buscar por descripción o código** y el filtro **«Solo pendientes»** para no perderse en
  facturas largas.
- **«Agregar producto»** para una línea que el OCR no detectó (límite: 100 productos por factura).
- **«Eliminar»** una línea, con confirmación; si fue un error, aparece **«Restaurar»**.
- **«Mover antes» / «Mover después»** para respetar el orden impreso.
- **«Confirmar línea revisada»** en cada tarjeta. Una línea confirmada pasa a «Revisado por ti».

Al pie está el bloque de comparación, que es la pieza clave del control aritmético:

| Fila | Qué muestra |
| --- | --- |
| **Suma de líneas** | El total exacto de todas las líneas |
| **Total de factura** | El total de la cabecera |
| **Diferencia exacta** | La resta entre ambos, sin redondeos |

Si coinciden se lee «Los importes coinciden exactamente». Si no, «La diferencia frente al total de la
factura es S/ X». Con todas las líneas confirmadas se toca **«Vincular productos»**.

## 7. Crear o vincular productos

Una línea de factura es texto del proveedor; el inventario necesita un **producto** del catálogo
propio. La pantalla **«Vinculación»** une las dos cosas, una línea a la vez, con el contador
**«Líneas resueltas: N de M»**.

Cada línea llega en uno de estos estados:

| Estado | Qué pasó |
| --- | --- |
| **«Vinculada automáticamente»** | La app encontró una coincidencia confiable y ya la aplicó |
| **«Elige un producto»** | Hay varios candidatos parecidos: hay que decidir |
| **«Sin coincidencia»** | No hay nada parecido en el catálogo |
| **«Vinculada»** | Confirmada por la persona |
| **«Dejada pendiente»** | Se postergó |

Cuando la app propone un candidato, dice **por qué**: «Código de barras», «Código del proveedor»,
«Alias confirmado», «SKU», «Nombre exacto» o «Nombre parecido». Las tres primeras razones son
identificadores fuertes; «Nombre parecido» merece una mirada antes de aceptarlo.

Acciones disponibles: **«Vincular»**, **«Cambiar»** (rehacer una vinculación automática),
**«Dejar pendiente»** y **«Crear producto»**. También se puede escribir en **«Buscar producto»**
para encontrar uno que la app no propuso.

### Crear un producto nuevo

Cuando la factura trae algo que nunca se compró, se toca **«Crear producto»**:

| Campo | Obligatorio | Nota |
| --- | --- | --- |
| **Nombre del producto** | Sí | Se sugiere la descripción de la factura; conviene dejarlo como se quiera ver en el inventario |
| **Unidad de inventario** | Sí | La unidad en que se contará el stock (unidad, kilogramo, litro…) |
| **SKU (opcional)** | No | Código interno propio |
| **Código de barras (opcional)** | No | Acelera futuras vinculaciones automáticas |
| **Unidad de compra (opcional)** | No | Si el proveedor vende en caja, paquete o docena |
| **Unidades por unidad de compra** | Si se eligió unidad de compra | El factor. Una caja de 12 usa factor 12 |

Con **«Crear y vincular»** el producto entra al catálogo y la línea queda vinculada en un solo paso.
La equivalencia se muestra explícita: «1 unidad de compra = 12 unidades de inventario».

Si el producto ya existía con otro nombre, la app lo detecta: **«Ya existe un producto equivalente»**
— ««Quinua tricolor 500 g» ya está en tu catálogo. Puedes vincular la línea al producto existente» —
y ofrece **«Vincular al existente»**. Conviene aceptarlo: evita catálogos con productos repetidos y
stock partido en dos.

Al vincular una línea, el texto del proveedor queda guardado como **alias** de ese producto. La
siguiente factura del mismo proveedor se vinculará sola por «Alias confirmado».

Si mientras se trabajaba cambió algo, aparece **«Cambios recargados»** y hay que revisar las líneas
antes de continuar; nada se aplica a ciegas.

## 8. Redondeo y diferencias de centavos

Es normal que la suma exacta de las líneas no coincida al centavo con el total impreso: el proveedor
redondea en su sistema. FacturaStock **nunca inventa el ajuste ni lo esconde**. Hace tres cosas:
lo calcula exacto, lo muestra, y exige una decisión con motivo escrito.

En **«Resumen de compra»**, si hay diferencia, aparece:

> **«Registrar el ajuste +S/ 0,03 para que la suma coincida con el total objetivo.»**

La convención es siempre la misma y se lee tal cual: **suma de líneas + ajuste = total de la
factura**. Un ajuste positivo significa que el total impreso es mayor que la suma de las líneas.

Para aceptarlo hay que escribir el **«Motivo del ajuste»**:

- Obligatorio, **entre 10 y 500 caracteres** («Obligatorio: entre 10 y 500 caracteres»), con
  contador en pantalla.
- Queda guardado junto a la compra **para siempre** y se puede leer después en el detalle, en
  «Diferencia aceptada» y «Motivo del ajuste».
- Un motivo útil describe el hecho, no la acción: «Redondeo de centavos del proveedor en el total
  impreso» sirve; «ajuste» no.

Mientras no se acepte el ajuste con un motivo válido, la compra no avanza. Los pendientes se ven
listados: «Confirma el ajuste de conciliación para continuar» y «Escribe un motivo válido para el
ajuste de conciliación».

Lo que la app **no** hace, y conviene saberlo: no reparte la diferencia entre las líneas, no cambia
ningún costo unitario y no toca el inventario por el ajuste. El ajuste vive como un dato propio de la
compra. Los costos que entran al inventario son los revisados línea por línea.

Todos los importes se manejan en **centavos enteros**, no en decimales aproximados. Por eso la
«Diferencia exacta» es exacta: no hay error de coma flotante acumulándose factura tras factura.

## 9. Confirmar la compra

**«Resumen de compra»** es el último punto donde todo es editable. Muestra proveedor, comprobante,
líneas, totales, las **«Advertencias aceptadas»** y un **«Identificador»** propio de la compra.

Si queda algo pendiente, se listan en **«Pendientes por resolver»** con la ubicación precisa:
«Línea 12: falta vincular un producto», «Línea 5: falta confirmar la revisión», «Cabecera: corrige
«Fecha de emisión»». Cuando no queda nada: «No hay pendientes. La compra está lista para
prepararse».

El registro tiene **dos pasos a propósito**:

1. **«Preparar compra»** congela una instantánea: «Instantánea congelada, lista para registrar».
   Desde aquí todavía se puede volver atrás con **«Volver a editar»**.
2. **«Registrar compra»** publica. Antes de escribir nada aparece la pantalla **«Confirmación»** con
   **«Importes que se publicarán»**: «Verifica estos importes antes de registrar la compra de forma
   irreversible», el «Total objetivo», el «Ajuste aceptado» y su «Motivo».

Al registrar, la app hace en **una sola operación indivisible**: crea la compra, sus líneas, los
movimientos de inventario y la bitácora. Si algo falla, no queda nada a medias. Los mensajes lo dicen
literalmente: «La compra no se registró y el borrador se conservó», «No se aplicaron cambios
parciales». Si otra operación tocó los datos al mismo tiempo: «Otra operación modificó los datos al
mismo tiempo. No se guardó nada parcial; puedes intentarlo de nuevo».

Al terminar aparece **«Compra registrada»** con **«Guardado en este celular»**, el ajuste aplicado si
lo hubo, cuántos productos se crearon o vincularon, y dos atajos: **«Ver detalle de compra»** y
**«Ver inventario actualizado»**.

Después de este punto la compra es **historia de solo lectura**: «Historial de solo lectura. Los
datos publicados no se pueden editar». Para corregir un error existe la anulación (sección 14), que
no borra nada.

Si se intenta salir del recorrido antes de registrar, la app avisa: **«¿Descartar el borrador?»** con
«Seguir editando» o «Descartar borrador». No se pierde trabajo por accidente.

## 10. Registrar una venta

Desde **Inicio** se toca **«Vender»**. La venta usa el negocio activo y conserva un carrito
local; agregar productos todavía no descuenta stock. Hay dos formas de entrada:

- **«Buscar»**: escribir el nombre o parte del nombre y elegir entre las coincidencias
  similares del catálogo.
- **«Escáner físico»**: recibir un código de un lector USB/Bluetooth configurado como
  teclado (*keyboard wedge*).

La búsqueda manual compara únicamente nombres: no interpreta una consulta numérica como código de
barras, SKU ni código de proveedor. Los códigos se reciben únicamente al cambiar a
**«Escáner físico»**.
Al volver atrás, la app termina de guardar cualquier cambio válido que esté pendiente antes de
cerrar la pantalla. Si una cantidad o precio no se puede guardar, muestra
**«¿Descartar cambios sin guardar?»**: **«Cancelar»** vuelve a la venta para corregirlos y
**«Descartar cambios y salir»** abandona únicamente esas ediciones locales tras una confirmación
explícita.

El lector se conecta o empareja desde Android, no desde FacturaStock. Debe aparecer como teclado
físico HID y enviar **Enter**, **Enter de teclado numérico** o **Tab** al terminar cada lectura. No
se abre la cámara y este flujo no usa ML Kit. Una distribución de teclado o una configuración del
fabricante incorrectas puede producir caracteres distintos; conviene probar primero con un código
conocido.

Los códigos admitidos tienen de **1 a 128 caracteres ASCII imprimibles**. Se recortan los espacios
de los bordes, pero se conservan ceros iniciales, mayúsculas/minúsculas y espacios interiores. No se
aceptan controles, marcas bidireccionales ni Unicode no ASCII. La app no exige un checksum universal
de EAN/UPC/GTIN: aceptar el texto no certifica su asignación.

### Código conocido o asociación nueva

Si el código ya está guardado, se propone el producto correspondiente. Si tiene stock en más de un
almacén hay que elegir de cuál saldrá; la app nunca trata el total agregado como una ubicación.

Si el código no existe, aparece **«Asociar código a un producto recibido»**. Hay que buscar y elegir
explícitamente un producto con existencia positiva. Solo entonces el código queda guardado en el
producto. Si ese producto ya tenía otro, un diálogo muestra ambos valores y exige confirmar el
reemplazo; cancelar no modifica el catálogo ni el carrito.

Si el producto no tenía código, elegirlo confirma una asociación nueva y no muestra un diálogo de
«reemplazo». Cuando la app informa que el código quedó asociado, el cambio ya es durable. Si luego
no puede agregarlo al carrito por stock o concurrencia, el código permanece en el producto y la
pantalla informa por separado el fallo del carrito; no vuelve a ofrecer una cancelación engañosa.

El carrito conserva una sola línea por producto y almacén. Una lectura repetida no crea otra línea
silenciosa; hay que revisar la cantidad de la línea existente.

### Cantidad, precio y confirmación

En cada línea se revisa el almacén, se ajusta la **Cantidad** y se revisa el **Precio unitario de
venta**. Cuando el producto tiene un precio guardado en la misma moneda, la app lo propone; todavía
se puede cambiar para esa venta sin modificar el precio del catálogo. El precio debe ser mayor que
cero y **nunca se calcula automáticamente desde el costo promedio**. La venta gratuita no forma
parte de esta versión.

Cuando todas las líneas tienen precio se toca **«Revisar y confirmar venta»**. La confirmación vuelve
a comprobar productos, almacenes, carrito y existencias. Si falta stock, bloquea la venta: una venta
no puede dejar existencia negativa. Si otra operación cambió el carrito o el stock, hay que recargar
y revisar antes de reintentar.

Al confirmar, el descuento de stock, los movimientos de salida, la auditoría y el estado final de la
venta se escriben juntos. La misma confirmación repetida —incluido un doble toque— no descuenta dos
veces.

En la versión `local`, o si ese negocio nunca se enlazó a la nube, la venta funciona sin internet.
En un negocio cloud compartido, confirmar requiere conexión: el servidor reserva el stock antes de
que Room marque la venta como publicada. Así dos teléfonos no pueden vender simultáneamente la
última unidad. La venta completa y el saldo final aparecen en el otro dispositivo al sincronizar;
si se corta la red, el carrito queda como borrador y no se descuenta solo en un teléfono.

El JSON contable v4 todavía no incluye cabeceras/líneas de venta, deudas ni abonos, y esta versión tampoco ofrece
anulación, devolución o reversión de una venta confirmada. El alcance técnico completo está en
[`SALES_AND_BARCODE_SCANNER.md`](SALES_AND_BARCODE_SCANNER.md).

Este registro de venta controla líneas, precio final, total y salida de inventario. No emite una
boleta/factura electrónica de venta, no envía a SUNAT, no lleva una caja general y no gestiona un
desglose tributario comercial. Los métodos de pago solo se registran para los abonos de una deuda.

### Venta a crédito, deudores y abonos

Para registrar productos fiados, en **«Tipo de venta»** se elige **«A crédito»** y se escribe el nombre de la
persona. Después se agregan los productos por búsqueda manual o con el mismo lector físico HID. El
nombre y al menos una línea válida son obligatorios al confirmar. **«Contado»** no crea deuda.

La venta a crédito descuenta las existencias y crea la cuenta pendiente en una sola confirmación.
También se puede iniciar desde **Inicio → Deudores → Registrar deuda**; ese botón abre el mismo
carrito de Ventas con **«A crédito»** preseleccionado para que la deuda siempre conserve sus
productos reales.

En **Deudores** se puede:

- buscar por nombre y filtrar **Pendientes**, **Pagadas** o **Todas**;
- ver saldo actual, importe original y cantidad de productos;
- abrir el detalle para revisar productos, cantidades, precios e historial de abonos;
- tocar **«Registrar pago»**, ingresar un importe y elegir Efectivo, Yape, Plin, Transferencia u
  Otro. La nota y referencia son opcionales.

Un abono parcial reduce el saldo y uno por el importe pendiente marca la deuda como pagada. No se
puede cobrar cero, un valor negativo ni más que el saldo. La venta, la deuda y los abonos ya
publicados no se editan ni eliminan; esta versión tampoco permite revertir una venta a crédito o un
abono. Más detalle en [`DEBTORS_AND_CREDIT_SALES.md`](DEBTORS_AND_CREDIT_SALES.md).

### Precio de venta y ganancias por producto

Durante **Productos** en la revisión de una compra, al crear o vincular el artículo recibido la app
solicita el precio al que se venderá y lo guarda con su moneda. Ese precio puede corregirse después
desde **«Ganancias por producto»**; debe ser mayor que cero.

La pantalla de ganancias compara ese precio con el costo promedio ponderado que dejaron las compras
publicadas y muestra ganancia por unidad, margen y ganancia potencial sobre el stock actual. Es una
**estimación de inventario**, no una utilidad contable realizada: no descuenta gastos operativos ni
impuestos de la empresa y no resume ventas por periodo. Si falta precio o stock, o aparecen monedas
distintas, la pantalla explica el motivo y no convierte ni mezcla importes.

## 11. Trabajar sin conexión

El flavor `local` y los negocios que nunca se enlazaron funcionan en modo avión. En un negocio
cloud compartido se puede seguir trabajando y preparar borradores sin señal, pero la confirmación
de venta y el registro de abonos esperan conexión para coordinar stock y saldo. Sin señal se puede:

- Configurar el negocio y los catálogos.
- Tomar la foto o importar la imagen.
- Ejecutar el reconocimiento de texto — el OCR corre **dentro del teléfono**, con un modelo que
  viene incluido en la app. No consulta ningún servidor.
- Revisar la cabecera y las líneas, vincular y crear productos.
- Aceptar el ajuste, preparar y **registrar la compra**.
- Preparar una venta; confirmarla también si el negocio no está enlazado a inventario compartido.
- Ver el inventario actualizado y el detalle de la compra.

En cada punto la app lo dice: «Guardado en este celular», «La compra, sus líneas y el inventario
están disponibles sin conexión y no se borran si falla el respaldo», «El borrador funciona sin
conexión; el respaldo se crea al publicar la compra».

Hay una sola versión de la app que además **no tiene permiso de internet en absoluto** (la variante
sin nube). En ella, Cuenta y Sincronización muestran «Esta versión de FacturaStock guarda todo solo
en este celular: no hay cuenta ni respaldo en la nube. Tus compras, ventas, deudas, abonos y borradores siguen funcionando
sin conexión».

Las compras y cambios de catálogo quedan en cola y se envían cuando vuelve la señal. Una venta de
un negocio compartido no se publica en una cola optimista: exige respuesta de la autoridad cloud.
Si no hay internet, la app muestra que se necesita conexión y conserva el carrito para reintentar.

## 12. Sincronización y respaldo

La sincronización en la nube es **opcional** y solo existe en la versión `cloud`. Comparte compras,
catálogo, inventario, ventas publicadas, deudas y abonos con los miembros autorizados del negocio.
Las compras usan una cola durable; las ventas y los abonos se autorizan en línea y después se
replican por un feed incremental.

### Activarlo

**Ajustes → Cuenta y respaldo**:

1. **«Crear cuenta»** con correo y contraseña (mínimo 6 caracteres), o **«Entrar»** si ya se tiene.
2. **Verificar el correo.** Aparece **«Verifica tu correo»** con **«Reenviar correo»** y
   **«Ya verifiqué»**. Sin verificar el correo el respaldo **no** se activa.
3. **Crear o elegir el negocio en la nube** y dejarlo como negocio activo de este teléfono.
4. En **Ajustes → Privacidad y diagnóstico**, activar **«Respaldar registros en la nube»** en los
   dos teléfonos. El respaldo documental es independiente y no hace falta para compartir catálogo,
   inventario, ventas, deudas o pagos.

Los roles determinan qué puede hacer cada persona: **Propietario**, **Administrador**, **Operador**
y **Lector**. Se administran en **«Miembros»** (invitar por correo, cambiar rol, eliminar) y las
invitaciones recibidas en **«Mis invitaciones»**. Estas reglas las aplica el servidor, no el
teléfono: cambiar algo en la app no otorga permisos que el rol no tenga.

Antes de aceptar una invitación se muestra qué se comparte: «los miembros autorizados podrán
compartir el respaldo comercial y ver la lista del equipo, incluidos correo e ID, según su rol».

### Cómo se comporta

**Ajustes → Sincronización** muestra el estado real:

| Sección | Qué se ve |
| --- | --- |
| **Respaldo pendiente** | Cada operación con su estado: «Pendiente», «Enviando», «Completada», «Falló», «Conflicto», «Resuelta», más los intentos y el «Próximo intento» |
| **Conflictos** | La versión «En este celular» junto a la «En la nube» |
| **Sincronización y reconciliación** | «Última sincronización», **«Sincronizar ahora»** y **«Comparar con la nube»** |
| **Documentos** | Estado derivado de la cola local: «Solo en este celular», «Pendiente», «Enviando», «Respaldada», «Error», «Eliminación de la nube pendiente» o «Eliminada de la nube» |

Al tocar **«Sincronizar ahora»**, la app trae primero el catálogo y luego el feed contiguo de
inventario. Cada página aplica en una sola transacción el saldo final, los movimientos, la auditoría
y las ventas, deudas o abonos que se originaron en otro teléfono. Si falta una referencia o el
cursor tiene un salto, no aplica una página parcial: muestra conflicto y conserva el cursor anterior.

La pantalla distingue los dos consentimientos. Sin ambos, no se crean subidas documentales. Cuando
están activos y la política todavía conserva la imagen, puede viajar una copia JPEG derivada; nunca
se envían el texto OCR ni una ruta local. El estado sale de Room, no de una lectura directa de red.

**Al perder la señal** (modo avión, túnel, sin datos) las operaciones quedan en «Pendiente». No hay
que hacer nada. **Al reconectar**, el envío se reanuda solo con esperas crecientes entre intentos.
Si se quiere forzar, existe **«Reintentar envío»** y **«Sincronizar ahora»**.

**Si la sesión vence** aparece **«Sesión expirada»**: «Tu sesión dejó de ser válida. Vuelve a entrar
para reanudar el respaldo; tus datos locales se conservan». La app primero intenta renovar la sesión
por su cuenta; solo si no puede, muestra el aviso. La cola **no se pierde**: las operaciones esperan
y se reanudan al entrar de nuevo con **«Volver a entrar»**.

**Cerrar sesión** no borra datos locales: compras, ventas, deudas, abonos y borradores permanecen en Room. Sin
embargo, si el negocio conserva un enlace cloud durable, no se podrá confirmar una venta nueva hasta
volver a entrar y reactivar la sincronización; permitirla solo localmente dividiría el inventario.

### Conflictos

Un conflicto ocurre cuando la nube ya tiene ese comprobante, normalmente porque se registró desde
otro teléfono. La app **nunca decide sola**. Muestra los dos lados —fecha, proveedor, total, estado—
y ofrece **«Conservar versión de la nube»**, con la consecuencia escrita antes de tocar nada:

> «La operación local queda marcada como resuelta y no se volverá a enviar. Tu compra local **NO se
> borra**: el documento ya existe en la nube, publicado desde otro dispositivo.»

La decisión queda en la bitácora de la compra como «Conflicto de respaldo resuelto (se conservó la
nube)».

**«Comparar con la nube»** es una herramienta de diagnóstico: informa cuántas compras coinciden,
cuáles están solo en la nube y qué diferencias de saldo hay. El aviso es explícito: «La comparación
es un diagnóstico: no modifica tus datos locales ni los de la nube». Con **«Registrar revisión»**
queda constancia de que se revisó.

## 13. Comprobantes duplicados

Registrar dos veces la misma factura duplicaría el stock y el gasto. Por eso, **antes** de publicar,
la app revisa el historial del propio teléfono: «Buscando comprobantes similares en este
dispositivo…».

Tres resultados posibles:

**«Sin coincidencias locales»** — se continúa con normalidad.

**«Este comprobante ya está registrado»** (duplicado exacto). Coinciden negocio, proveedor, tipo,
serie y número. **La confirmación queda bloqueada por defecto.** Se muestra la compra existente con
su proveedor, comprobante, fecha, total y estado, y el botón **«Ver compra existente»** para
comprobarlo antes de decidir.

**«Encontramos una compra similar»** (posible duplicado). No es idéntico, pero hay señales
secundarias. La app dice exactamente cuáles coinciden: negocio, proveedor o RUC, tipo, serie y
número, correlativo con ceros distintos, fecha, total, imagen. Aquí no se bloquea: se advierte.

El caso más frecuente de falso positivo es el **correlativo con ceros distintos** (`F001-38` frente a
`F001-00000038`): suele ser el mismo documento.

### Registrar de todos modos

Si de verdad son documentos distintos, existe **«Registrar de todos modos»** → **«Autorizar
excepción»**, con dos condiciones:

- **Solo Propietario o Administrador** puede autorizarla. Con otro rol: «El rol actual no autoriza
  excepciones de comprobantes duplicados».
- **Motivo obligatorio de al menos 10 caracteres** (hasta 500). «El motivo quedará en la auditoría
  local junto con la compra coincidente».

La excepción queda registrada de forma permanente en el detalle de la compra como **«Excepción de
duplicado autorizada»**. No es un atajo silencioso: es una decisión firmada.

Si la coincidencia cambia mientras se revisa: «La coincidencia cambió mientras revisabas. Vuelve a
comprobar el historial». Y si el control no puede ejecutarse, la app **no publica**: «No confirmaremos
la compra sin revisar antes el historial local. Inténtalo de nuevo».

## 14. Anular una compra

Una compra registrada **no se borra ni se edita**. Si se registró por error, o el proveedor emitió
una nota de crédito, se **anula**: la app agrega movimientos inversos y conserva toda la historia.

Se entra desde **Compras → (la compra) → «Anular esta compra»**. El aviso lo resume: «Revisa primero
la reversión exacta del inventario. La compra original y su historia no se eliminarán».

La pantalla **«Anular compra»** muestra, antes de tocar nada:

| Bloque | Contenido |
| --- | --- |
| **Autorización** | El rol con el que se actúa. Solo **Propietario** o **Administrador** pueden anular |
| **Impacto en inventario** | Por cada producto y almacén: «Existencia actual», «Reversión» y **«Existencia resultante»** |
| **Motivo de anulación** | Obligatorio, **entre 10 y 500 caracteres**, con contador |
| **Confirmación** | La casilla «Confirmo que revisé los saldos resultantes y deseo anular esta compra sin borrar su historia» |

Si la reversión dejaría stock negativo, se avisa sin ocultarlo: **«La anulación dejará existencias
negativas»** — «Se permite continuar, pero estos saldos quedarán visibles y señalados para su
regularización; no se ocultarán ni cambiarán automáticamente». Esto pasa cuando ya se vendió o
consumió parte de lo comprado. Es un dato a regularizar, no un error de la app.

Con **«Confirmar anulación»** se aplica todo junto. Después, la compra aparece como **«Compra
anulada»**: «El documento, sus líneas, los movimientos inversos y la auditoría se conservan como
historia de solo lectura».

Situaciones que la app maneja explícitamente:

- «Esta compra ya fue anulada. Puedes consultar su historia en el detalle.»
- «Tu rol actual no autoriza anulaciones. Solicita a un propietario o administrador que realice esta
  operación.»
- «Las existencias cambiaron. Actualizamos el impacto; revísalo y confirma nuevamente.» — con
  **«Recalcular impacto»**. La anulación nunca se aplica sobre un cálculo viejo.
- Si falla: «La anulación no se completó. No se aplicaron cambios parciales».

## 15. Inventario

**Inventario** muestra las existencias y su valorización por almacén. Las compras publicadas agregan
existencias y las ventas confirmadas las descuentan; nada se ingresa a mano en esta pantalla.

En la parte superior se puede elegir **Existencias** o **Ganancias por producto**. Ganancias usa el
precio de venta guardado y el costo promedio ponderado de las compras para mostrar la estimación por
unidad, el margen y la proyección sobre el stock actual. Desde esa misma vista se puede corregir el
precio de venta. Si falta precio o stock, o si las monedas no son comparables, la app muestra el
motivo y no reemplaza el dato desconocido por cero. Estas cifras son estimaciones antes de
descuentos e impuestos, no utilidades realizadas ni un reporte contable fiscal.

Por producto se ve **«Existencia total»**, **«Costo promedio total»** y **«Valor estimado total»**, y
el desglose por cada almacén. En **Existencias** se puede elegir **«Buscar»** o **«Escáner
físico»**. El buscador acepta producto, SKU o almacén. El escáner recibe el código de un
lector USB o Bluetooth configurado como teclado y abre directamente la trazabilidad del producto
asociado. Un código desconocido se informa sin crear ni modificar productos; la asociación se hace
desde el catálogo o durante el flujo de venta. Esta función no usa la cámara.

Al abrir un producto se llega a **«Trazabilidad del producto»**, con las existencias por almacén y el
**libro de movimientos** completo. Las entradas por compra, anulaciones, ajustes y salidas `SALE`
conservan la variación, el costo registrado y la fecha. Cada movimiento es **histórico y de solo
lectura**; cuando tiene origen de compra se puede **«Abrir compra»**. Esta versión no ofrece una
pantalla de detalle ni reversión de la venta publicada.

La app señala por sí misma los datos que merecen revisión, bajo **«Revisar datos de inventario»**:

| Aviso | Qué significa |
| --- | --- |
| «La existencia es negativa» | Se consumió más de lo comprado, o hubo una anulación posterior |
| «El producto está archivado» / «El almacén está archivado» | Sigue teniendo stock pese a estar desactivado |
| «Hay costos en monedas distintas; no se combinaron» | La app no mezcla monedas para inventar un promedio |
| «Un movimiento no conserva costo verificable» | No se puede demostrar ese costo con evidencia |
| «El saldo cacheado diverge del libro» | El resumen rápido no cuadra con el historial |

Para el último caso existe **«Diagnóstico del libro»** → **«Recalcular y comparar»**: recalcula todo
desde los movimientos y compara. El resultado es «Coincide» o «Revisión necesaria», con el detalle
«Cantidad: cache X · libro Y». Es puramente informativo: **«Recalcula desde movimientos y compara sin
modificar saldos ni historia»**. Si sale «Revisión necesaria», conviene reportarlo con captura de esa
pantalla (ver [`RUNBOOK.md`](RUNBOOK.md)).

## 16. Privacidad de tus datos

Lo esencial en tres frases: el registro y el OCR nacen en el teléfono; cualquier respaldo exige un
**opt-in apagado de fábrica** y respaldar documentos requiere un segundo opt-in; los diagnósticos
también están **apagados de fábrica**.

| Dato | Dónde vive |
| --- | --- |
| Foto del comprobante | En almacenamiento privado. No sale por defecto. Con ambos respaldos activos puede transferirse una copia JPEG derivada por HTTPS; Firebase Storage la cifra de forma administrada en reposo |
| Texto reconocido por el OCR | Solo en el teléfono. El OCR corre en el dispositivo |
| Compra, líneas, movimientos, inventario | En el teléfono. En la nube **solo** con respaldo activo y correo verificado |
| Lectura cruda del lector HID | Solo en memoria mientras Ventas o el modo Escáner físico de Inventario están activos; no se guarda ni se envía |
| Código asociado a un producto | En el catálogo local. Puede respaldarse como dato del producto si el respaldo de catálogos está activo |
| Venta y líneas de venta | En Room. Para un negocio cloud enlazado, Functions recibe la venta al confirmar y la replica a los demás miembros junto con el inventario; no se envía el código HID crudo |
| Nombre del deudor, saldo y abonos | En Room. En un negocio cloud enlazado se comparten con los miembros autorizados y Functions controla cada modificación; no se guardan en Analytics ni Crashlytics |
| Motivos escritos (ajustes, anulaciones, excepciones) | En el teléfono, junto a la compra |
| Configuración comercial local (moneda, IGV y política de costos) | Solo en el teléfono. El nombre y la membresía del negocio cloud sí viven en Firebase cuando se crea o enlaza uno |

En **Ajustes → Privacidad y diagnóstico** están estos controles reales:

- **«Abrir política de privacidad»**: abre el documento completo.
- **«Conservación de imágenes»**: permite eliminar tras el OCR, al confirmar, exactamente a los
  30 o 90 días, o conservar hasta un borrado manual. Esta elección solo toca la foto: compra,
  líneas, inventario y auditoría permanecen.
- **«Respaldar registros en la nube»**: opt-in apagado de fábrica. Al apagarlo se guarda la
  preferencia y se solicita cancelar el trabajo comercial programado; la app muestra si Android
  confirmó esa cancelación. Nada local se borra. Las solicitudes explícitas de purga conservan un
  canal mínimo de salida: pausar el respaldo no puede impedir retirar una copia ya solicitada.
- **«Respaldar documentos cifrados»**: segundo opt-in, también apagado y deshabilitado mientras
  el respaldo general está apagado. La copia de trabajo local se protege con AES-GCM; por la red
  viaja el JPEG derivado sobre HTTPS y Firebase Storage aplica cifrado administrado en reposo. No
  es E2E y no habilita el envío de texto OCR ni de motivos libres.
- **«Exportar libro contable»**: abre el selector de documentos de Android y escribe un JSON
  `ACCOUNTING_LEDGER` con `schemaVersion=4` solo
  después de elegir el destino. Incluye negocio, proveedores, productos, unidades, almacenes,
  alias, compras/líneas, saldos, movimientos, auditoría completa del negocio y metadatos de
  imágenes retenidas. Excluye bytes y rutas de fotos, borradores/artefactos OCR, preferencias,
  cuenta/membresías, estado de sincronización, caché, temporales, credenciales y tokens. El
  esquema v4 **no incluye cabeceras/líneas de venta, deudas ni abonos**: los saldos y movimientos
  pueden reflejar parte de su efecto, pero no permiten reconstruirlos. Por eso no es una copia
  integral de ventas o cuentas por cobrar. El
  resultado muestra conteos reales; si no puede obtener una instantánea estable, no abre/escribe el
  destino y pide reintentar. Si falla al escribir y Android no puede confirmar que el destino quedó
  vacío o eliminado, pide buscarlo y borrarlo manualmente.
- **«Borrar imágenes de este celular»**: tras confirmar, incluye originales de borradores abiertos
  y fotos de compras terminales de todos los negocios guardados en la instalación. Informa por
  separado intentadas, eliminadas, ya ausentes y no confirmadas. No borra registros contables. Para
  una compra que pudo respaldarse deja primero una solicitud de purga durable, pero no afirma que la
  nube ya la eliminó hasta ver su ACK en Sincronización.
- **«Limpiar caché y temporales»**: elimina temporales vencidos, caché, directorios huérfanos y
  versiones OCR de trabajo; informa cada contador real.
- **«Bloquear al volver a la app»**: bloqueo opcional con biometría fuerte o la credencial del
  dispositivo (PIN, patrón o contraseña) como recuperación. Si el dispositivo deja de ofrecer ambos,
  la app desactiva el ajuste y lo informa para no dejar a la persona encerrada fuera de sus datos.
- **«Enviar diagnósticos operativos»**: **desactivado por defecto**. Si se activa, se envían
  únicamente estados, códigos de error e identificadores internos, y el servicio de Google puede
  añadir datos técnicos de app, sesión y dispositivo. Textualmente: «**Nunca enviamos facturas,
  imágenes, texto OCR, importes, motivos, RUC ni correos**». Se puede desactivar en cualquier
  momento. El SDK de Crashlytics existe solo en la variante con nube, pero su colección automática
  permanece desactivada y FacturaStock no le entrega excepciones.

Tras confirmar, la app cifra inmediatamente las fotos retenidas con AES-GCM y una clave no exportable de
AndroidKeyStore. Antes de esa pasada, y durante un borrador activo, siguen protegidas por el sandbox
privado de Android. La pantalla descifra los bytes en memoria y no crea una copia temporal en claro.

Quien haya creado una cuenta en la nube puede eliminarla desde **Ajustes → Cuenta y respaldo →
«Eliminar mi cuenta»**. El diálogo explica exactamente el alcance antes de confirmar: se borran los
negocios donde se era el único miembro; en los compartidos se retira el acceso y se anonimizan las
referencias personales, conservando la historia comercial del negocio; y **las compras, ventas, deudas, abonos y borradores
locales permanecen en este dispositivo**. Si el negocio tiene otros miembros y se es su propietario, primero
hay que transferir la propiedad. Para autorizar la acción irreversible se vuelve a pedir la
contraseña: se usa solo para reautenticar con Firebase, no se guarda ni se envía a la Function. El
servidor acepta únicamente un inicio de sesión de los últimos cinco minutos; si ya venció, la app
pide reautenticar en lugar de continuar con un token refrescado.

**Dos límites importantes de esta versión:**

1. **Exportar no es restaurar.** El JSON permite conservar una copia legible, pero esta versión no
   tiene importador. Si se desinstala la app, se borran los datos desde Android o se pierde el
   teléfono, volver a enlazar el negocio y activar el respaldo recupera catálogo, inventario,
   ventas, deudas y abonos compartidos presentes en el feed cloud; no reconstruye desde el JSON ni
   restaura el libro local completo, sus fotos, ajustes o borradores.
2. **Borrado local y purga cloud son estados separados.** El botón de imágenes locales solo confirma
   el dispositivo. Si se respaldó un documento, su operación de purga debe alcanzar y mostrar su
   propio estado antes de afirmar que desapareció de la nube.

## 17. Practicar sin riesgo: modo demostración

Para aprender el recorrido sin tocar datos reales existe **Ajustes → Modo demostración**: «Explora la
app con datos sintéticos sin tocar tu información real».

Al activarlo («Activar modo demostración») se crean un negocio, unidades, un almacén, proveedores y
productos de ejemplo. Mientras esté activo se ve el aviso permanente **«Modo demostración activo»**:
«Estás viendo datos 100 % sintéticos. Lo que registres aquí no afecta tu negocio real».

Dentro del modo demostración está **«Abrir factura demo de 38 líneas»**: una factura sintética
completa que ejercita a propósito los tres casos difíciles de este manual — una línea que exige
**elegir** entre varios productos, una que exige **crear** un producto nuevo, y una **diferencia de
+S/ 0,03** que obliga a aceptar el ajuste con motivo. Es la mejor forma de practicar.

Al salir («Salir del modo demostración») se eliminan **todos** los datos de demostración y vuelve la
información real intacta.

## Preguntas rápidas

| Duda | Respuesta |
| --- | --- |
| ¿Necesito internet? | OCR, compras y borradores funcionan sin señal. Una venta o abono solo exige internet cuando el negocio está enlazado a inventario y deudas cloud compartidos |
| ¿El lector usa la cámara? | No. **Escáner físico** recibe un lector USB/Bluetooth reconocido por Android como teclado físico HID, tanto en Ventas como en **Inventario → Existencias**. Sin lector, usa **Buscar**; Ventas busca por nombre e Inventario por producto, SKU o almacén. Ninguno interpreta ese texto manual como una lectura HID |
| ¿Mi venta aparece en el otro celular? | Sí, si ambos usan `cloud`, tienen el mismo negocio enlazado, el respaldo comercial activo y sincronizan. El JSON contable v4, por separado, todavía no incluye ventas, deudas ni abonos completos |
| ¿La deuda y un abono aparecen en el otro celular? | Sí, bajo las mismas condiciones cloud. Si dos teléfonos intentan cobrar el mismo saldo, uno debe sincronizar y revisar antes de reintentar |
| ¿Se sube la foto de mi factura? | No por defecto. Solo con respaldo general y documental activos puede viajar un JPEG derivado por HTTPS; Storage lo cifra de forma administrada en reposo, no E2E |
| Me equivoqué en una compra registrada, ¿la borro? | No se borra: se **anula** (sección 14), y queda el rastro |
| El total no cuadra por céntimos | Es normal. Acepta el ajuste con un motivo (sección 8) |
| La app dice que la factura ya está registrada | Revísala con «Ver compra existente». Si de verdad es otra, un Propietario o Administrador autoriza la excepción con motivo (sección 13) |
| ¿Esto valida mi factura ante SUNAT? | **No.** El OCR y la comprobación del RUC son locales y matemáticos; no consultan a SUNAT ni certifican nada |
| Se cerró la app a mitad del escaneo | Nada se perdió. En Inicio, el borrador espera con «Reanudar OCR» |
| ¿Puedo cambiar de teléfono? | Tras enlazar el nuevo equipo y activar el respaldo se recuperan catálogo, inventario, ventas cloud, deudas y abonos compartidos, pero no existe restauración integral de compras, fotos, ajustes y borradores. No borres el equipo anterior sin exportar y verificar |
