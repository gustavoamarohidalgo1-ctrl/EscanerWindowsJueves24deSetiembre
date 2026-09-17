# Revisión del escáner físico — 4 de septiembre de 2026

## Actualización 1.0.3: lecturas consecutivas en Ventas

La tablet conectada por Wi-Fi permitió distinguir dos situaciones. Primero mostró una lectura
incompleta (`753176004930` frente al `7753176004930` guardado). Más tarde leyó correctamente el
código terminado en 4930, pero el producto ya tenía guardado el terminado en 4909: el usuario
había confirmado reemplazar su código. La APK instalada era idéntica a la entrega 1.0.2. No se
alteraron esos productos ni se agruparon existencias; queda por aclarar si ambos códigos deben
identificar el mismo producto o presentaciones distintas.

Se reprodujo un bloqueo independiente de la causa del código desconocido: `isAssociating`
desactivaba el receptor USB y el campo nativo, de modo que tampoco entraban códigos conocidos
posteriores. Ahora la asociación pendiente permite volver a escanear. Una nueva lectura válida
resuelve su producto y limpia sólo la elección/búsqueda anterior; la cola ya aceptada continúa.
Se mantienen los bloqueos al escribir, guardar ediciones o mostrar diálogos de reemplazo, almacén
y cobro. No se modifican códigos, precios acordados ni líneas existentes como efecto de reintentar.
El mensaje de código desconocido indica que se puede volver a escanear.

También se corrigió el cierre de teclas del ensamblador. Android documenta que `KeyEvent.downTime`
puede corresponder al último DOWN de otra tecla cuando se superponen pulsaciones. El UP ahora
libera la tecla por dispositivo/código y su propia hora, sin cerrar una pulsación posterior.
Una regresión falló antes del cambio y otra protege frente a UP antiguos. Se conservan la
supresión de autorepetición real, los sufijos duplicados y el rechazo íntegro de tramas inválidas.
Esto corrige un defecto verificable del contrato Android; no se capturó un flujo físico que
permita atribuir específicamente la pérdida del 7 de la tablet a ese defecto.

Además, Ventas busca el SKU del mismo negocio cuando no encuentra un código de barras exacto.
Los códigos importados desde factura pueden guardarse sólo en SKU. La coincidencia de barras
conserva prioridad y se reutilizan las comprobaciones de producto activo, existencias y precio;
resolver por SKU no cambia ninguna asociación. Este caso no explica los productos de la tablet,
que tienen códigos de barras guardados y SKU vacío.

Validación: 135 pruebas JVM aprobadas y 20 pruebas Android aprobadas en el emulador aislado
`emulator-5556`. Incluyen una ráfaga de 12 tramas HID en una sola llamada a la Activity, sin
esperar entre lecturas: tres productos, repeticiones y un desconocido intercalado. El resultado
son tres líneas con cantidades 4/3/4, campo habilitado y enfocado, sin asociación pendiente ni
cambios de stock. También pasan el registro de Inventario, códigos completos repetidos, SKU,
IME, cancelación, edición manual y recuperación del campo. Los ensayos automatizados usan datos
de prueba; no se ejecutan pruebas Hilt sobre la tablet.

Se realizó `clean` y compilación sin caché ni compilación incremental. Los 152 archivos revisados
de Inventario, persistencia y esquemas permanecen idénticos al punto de partida. Room continúa
en versión 27 y el APK 1.0.3 (código 4) conserva el paquete y certificado de las entregas anteriores.
Evidencias: `/tmp/facturastock-sales-sequence-fix-20260904/`.

La APK se instaló como actualización en la tablet autorizada por Wi-Fi. Se comprobaron los hashes
de filas de las 34 tablas antes de actualizar, después y tras abrir/probar la app: permanecen
idénticos, con integridad SQLite y claves foráneas correctas. En la tablet se enviaron dos códigos
de prueba desconocidos mediante eventos Android de teclado virtual. El segundo entró sin cancelar
el primero y el campo conservó habilitación y foco; después se canceló esa asociación de prueba.
No se confirmó ninguna venta ni se modificó el carrito. La prueba no equivale a disparar
físicamente el lector USB. La inyección directa al controlador de entrada fue denegada por Android;
no se cambiaron permisos ni protecciones del dispositivo.

Entrega final en `/Users/gustavo/Desktop/LOLFA/FacturaStock.apk`, compilada a las 12:44:54 y copiada
a las 12:52:06 (Lima). El APK instalado, el generado y el entregado tienen el mismo SHA-256:
`675003c22ade4dec9ddd8d4522b82f23c555abace1f94510c75eafa29a80cb55`.
Informes resumidos en `build/reports/sales-sequence-fix/`; la copia de los datos de diagnóstico
de la tablet se conserva únicamente en el directorio temporal local de la revisión.

## Actualización 1.0.2: recepción USB en Ventas

El usuario confirmó que Inventario funciona en la tablet con su lector USB, pero Ventas no detecta
el producto. Ventas seguía escuchando sólo los eventos de teclado físico del receptor global; no
tenía el campo enfocado que permite a Inventario recibir texto IME, eventos virtuales o confirmar
lecturas sin Enter/Tab. La búsqueda de productos y el registro de inventario no requieren cambios.

Se reprodujo la diferencia en la APK 1.0.1 instalada en un emulador aislado: el recorrido registra
dos productos correctamente, entra a Contado → Escáner físico y falla al esperar el campo receptor
de Ventas, que todavía no existe. La prueba no certifica el protocolo exacto del dispositivo USB
del usuario; reproduce la ausencia de esa vía de entrada en la versión entregada.

Ventas reutiliza ahora `ScannerCodeInput` con el botón «Agregar a la venta». El receptor permanece
fuera de la lista desplazable del carrito para mantener su conexión de entrada. Enter/Tab agregan
el producto; sin sufijo, el código queda visible hasta confirmar. La cola de lecturas y la búsqueda
con precio persistido conservan su comportamiento. Al editar cantidad, precio o cliente, o abrir
un diálogo de cobro, se pausa la captura. «Reiniciar lector» descarta la lectura parcial y limpia
el aviso del escáner sin borrar el carrito ni cancelar códigos ya aceptados.

La única extensión del componente compartido es una etiqueta configurable para el botón; su
valor predeterminado sigue siendo «Abrir registro». Se conservan los archivos de Inventario.

Las 125 pruebas JVM dirigidas pasan, incluidas tres regresiones de recuperación del lector en
Ventas. El nuevo recorrido Android pasó después del arreglo: recibe Enter virtual, CRLF y Tab,
pausa la recepción al enfocar cantidad, invalida conexiones anteriores al reanudar, muestra el
código USB sin sufijo sin agregarlo prematuramente y cobra S/30.50 con saldos finales 7 y 9.
La misma prueba falló en 1.0.1 al no encontrar el campo receptor en Ventas.

En la APK 1.0.2 terminaron aprobadas las 16 pruebas Android seleccionadas: cuatro recorridos con
Activity, navegación y SQLite reales, y 12 pruebas del campo nativo. Se verificó también el flujo
de Inventario y que sus archivos mantienen exactamente su contenido anterior. Las pruebas se
ejecutaron en `emulator-5556` aislado; no se modificaron los datos del emulador del usuario.
Evidencias en `build/reports/sales-usb-fix/`. Sigue pendiente la prueba con el lector USB físico.

Compilación y Lint finales aprobados, con cero errores y 66 advertencias existentes. APK 1.0.2
(código 3), misma firma, entregada en `/Users/gustavo/Desktop/Leak/FacturaStock.apk`.
SHA-256 verificado contra la APK probada:
`b9a5c84c3060a9d115bb8d6aa0fd1541014afafb4cd751a14aba28638861c483`.

### Revisión adicional: conservación de información al actualizar

Se compararon los APK 1.0.1 y 1.0.2: mismo paquete `com.facturastock.app`, mismo certificado de
firma y aumento de código 2 → 3. Room mantiene `facturastock.db`, esquema 27 y su identidad;
el cambio del lector no modifica la persistencia ni añade una migración destructiva.

Además, se instaló 1.0.1 en `emulator-5556`, se registraron dos productos y se confirmó una venta
por S/22.00. Tras detener la app, se instaló 1.0.2 con reemplazo, sin desinstalar ni limpiar datos:
la base quedó idéntica byte por byte. Se abrió después la app actualizada mediante su Activity de
producción y se comprobó de nuevo la base: ninguna tabla cambió, permanecieron ambos productos,
los saldos 8/9 y la venta publicada. Integridad SQLite correcta y cero infracciones de claves
foráneas. Evidencia en `build/reports/apk-update-review/upgrade-result.json`.

La APK de `Leak` conserva el hash de la entrega verificada. No se requirieron nuevos cambios de
código. Esta comprobación se hizo con datos de prueba; no se accedió a la tablet ni a los datos
del emulador del usuario. Para conservar datos debe instalarse como actualización sobre la app
actual, sin desinstalarla ni utilizar «Borrar datos».

## Actualización 1.0.1: Inventario en tablet con lector USB

El usuario confirmó que el fallo ocurre en su tablet con un lector USB y que no aparece el
formulario tanto con productos nuevos como existentes. La versión instalada en un emulador local
no permite deducir qué versión ni qué eventos recibe esa tablet.

Se encontraron y corrigieron estos huecos en el recorrido:

- El listado principal de Inventario no escuchaba códigos; sólo lo hacía la pantalla separada de
  registro. Ambas pantallas comparten ahora la misma sesión de lectura y abren el formulario.
- Un código existente abría un detalle de consulta. Ahora resuelve y edita el mismo producto,
  conserva su identidad y estado, y vuelve al lector al guardar o cancelar. Abrir o cancelar no
  altera existencias; la cantidad queda vacía y se etiqueta como existencias totales opcionales.
- La captura dependía exclusivamente de eventos de teclado físico y de un sufijo. Un campo nativo
  enfocado recibe también texto IME, eventos virtuales y bloques ACTION_MULTIPLE. Enter/Tab abren el
  registro; sin sufijo, el código queda visible y se confirma con «Abrir registro». No se confirma
  automáticamente una lectura parcial por longitud o por tiempo.
- Una lectura inválida sin sufijo podía dejar al ensamblador esperando indefinidamente sin mostrar
  el rechazo. Se notifica el error al detectarlo y «Reiniciar lector» descarta toda la lectura.
  Nunca se registra un prefijo truncado ni la cola de un código rechazado.
- La recepción se suspende al salir, abrir el formulario o bloquear la app. Las conexiones de
  entrada anteriores consultan el permiso de desbloqueo en cada evento, antes de recomponer, y
  quedan invalidadas tras desactivar el campo. Los resultados tardíos no cambian otra pestaña.

Validación de esta actualización:

- 122 pruebas JVM aprobadas: ensamblador/receptor, registro de inventario, recuperación del lector,
  edición de productos existentes, cancelación, consultas fallidas, ventas y precios.
- 26 pruebas Android aprobadas en el emulador aislado `emulator-5556` (API 37), en 152 segundos.
  Incluyen 12 pruebas del campo nativo y sus conexiones IME, tres recorridos con Activity, navegación
  y SQLite reales, tres del ciclo de vida del receptor, seis del adaptador Android y dos del formulario.
  Los recorridos verifican entrada desde el listado sin otra pantalla, confirmación sin sufijo,
  reinicio tras lectura inválida, dos altas, reapertura del producto existente sin duplicarlo ni
  alterar stock, y venta de tres unidades por S/22.00 con saldos finales 8 y 9.
- Los eventos son sintéticos. No se ha podido probar directamente el lector USB de la tablet del
  usuario ni certificar todas las versiones Android. No se modificó el emulador del usuario.
- APK `localDebug` 1.0.1, código 2, compilada con la misma firma que la versión entregada antes.
  Evidencias en `build/reports/inventory-usb-fix/`.
- Compilación y Lint finales aprobados: cero errores y las 66 advertencias existentes.

La primera ejecución de Lint señaló `AppCompatCustomView` en el campo nativo. Se comprobó que el
detector sólo observa la dependencia transitiva de AppCompat y la superclase; no revisa el tema.
La Activity usa un tema Android framework en todas sus variantes y el `AndroidView` aplica
explícitamente los colores y tintes de Compose. Se documentó una supresión local de ese chequeo
en la clase, sin cambiar el tema, los controles ni los umbrales globales de Lint. Esta anotación y
su comentario fueron el único cambio de código posterior a las 26 pruebas Android. Después se
instaló la APK definitiva y se repitieron las 12 pruebas del campo y los tres recorridos completos:
`OK (15 tests)`, en 153 segundos. No quedaron comprobaciones pendientes de código o compilación.

Entrega actual: `/Users/gustavo/Desktop/FOr/FacturaStock.apk`, únicamente la APK en esa carpeta.
SHA-256: `5c4dfdbd977db80658a23857d012206001ed37d3bb4647470c9e6fcc8a5ff784`.
Se verificó que el archivo entregado coincide con la APK probada y conserva la firma anterior.
La comprobación con el lector USB físico de la tablet sigue pendiente.

Las secciones siguientes conservan el historial de versiones anteriores; sus límites de captura
y rutas de entrega no describen la versión 1.0.1.

## Corrección posterior: segunda lectura y venta del primer producto

Después de guardar un alta escaneada, el catálogo quedaba abierto sin receptor de lecturas.
Guardar o cancelar ahora vuelve al registro de inventario y reactiva el lector. Los errores
conservan el formulario y la edición manual mantiene su navegación habitual.

Ventas ahora utiliza el precio del producto encontrado por código, aunque la lista del catálogo
todavía no haya recibido su actualización. Si falta la proyección de un producto recién guardado,
consulta únicamente su detalle de inventario, comprueba negocio, sesión, stock y almacenes activos,
y permite agregarlo sin esperar al siguiente refresco global. Se conserva el precio acordado en
las líneas existentes y se ignoran consultas que terminan después de salir del lector.

Validación de esta corrección:

- 110 pruebas JVM aprobadas, incluidas seis regresiones nuevas de ventas. La prueba de precio
  atrasado falló antes del arreglo (precio esperado 8.75, recibido null) y pasa con la corrección.
- 17 pruebas Android distintas aprobadas en `emulator-5556`, aislado de los datos del usuario.
  El recorrido completo usa `MainActivity.dispatchKeyEvent` y eventos Android de teclado:
  dos altas consecutivas, códigos con ceros iniciales, Enter/Tab/Enter numérico y sufijos dobles;
  luego tres lecturas, cobro de S/22.00 y comprobación de los saldos finales 8 y 9.
  También se verifica guardar, cerrar/reabrir SQLite, vender y reabrir de nuevo sin duplicar salidas.
- La prueba de recorrido se ajustó para volver del subflujo de registro antes de usar la navegación
  principal y para desplazar la lista hasta el botón de cobro; las otras 16 pruebas pasaron en la
  primera ejecución. La ejecución final del recorrido terminó con `OK (1 test)`.
- APK `localDebug` y APK de pruebas compilados. Lint: cero errores y las 66 advertencias existentes.
- Evidencias en `build/reports/scanner-fix/`. No se borraron ni modificaron los datos de
  `emulator-5554`. Los eventos de prueba son sintéticos; no equivalen a probar el lector físico.

La APK entregada conserva la firma anterior y se encuentra en
`/Users/gustavo/Desktop/Appsf/FacturaStock.apk`.

## Alcance

Recepción Android de lectores USB/Bluetooth que funcionan como teclado HID, ensamblado del código,
navegación y captura en Ventas y en Inventario → Registrar productos. Se conserva el registro
local con nombre, cantidad, costo unitario y precio de venta. No se incorpora cámara ni facturas.

No se ha identificado todavía la marca/modelo ni la conexión del lector que produjo los errores
reportados. Los defectos enumerados se encontraron en el código y se reproducen mediante pruebas;
no se atribuyen todos los errores del dispositivo del usuario a una única causa.

## Defectos corregidos

1. **Códigos incompletos convertidos en otros códigos.** Una pausa superior a un segundo o un cambio
   de dispositivo borraba el prefijo y permitía entregar la cola. Ahora se rechaza la trama entera.
   Tampoco se puede recuperar la cola de una lectura demasiado larga después de una pausa.
2. **Separadores borrados silenciosamente.** Un control interno podía convertir `A + GS + B` en `AB`.
   Se conserva la información hasta validar y se informa un error, sin buscar otro producto.
3. **Sufijos repetidos enviados a botones.** Después de terminar, un segundo Enter/Tab podía llegar
   a la interfaz. Se absorben los sufijos repetidos durante 250 ms y los eventos UP asociados,
   incluso al cerrar el receptor. Los diálogos también protegen el terminador sobrante.
4. **Drivers que agrupan caracteres.** La adaptación Android ignoraba ACTION_MULTIPLE. Ahora acepta
   texto agrupado de teclados físicos, conserva ceros y caracteres repetidos, e interpreta CR/LF/Tab.
5. **Receptor con callbacks anteriores.** La recomposición podía conservar callbacks viejos mientras
   el lector seguía activo. Se mantienen actualizados sin interrumpir una lectura.
6. **Resultados de Inventario después de salir.** Al pausar o cerrar el registro se cancela la
   búsqueda; una generación de solicitud impide que una cancelación vieja libere una nueva.
   Se vuelve a comprobar el negocio antes de abrir el producto o su formulario.
7. **Lecturas de Ventas durante escrituras y edición.** Se revisa la recepción para serializar las
   lecturas y separar los campos editables de la entrada del lector. Las lecturas pendientes se
   limitan y se invalidan cuando cambia la sesión o el destino.
8. **Formulario escaneado perdido tras recrear el proceso.** El borrador se conserva en el estado
   guardado, asociado a su negocio, y se elimina al guardar o cancelar.
9. **Cifras repetidas perdidas en ráfagas rápidas.** Dos pulsaciones completas de la misma tecla
   dentro del mismo milisegundo podían confundirse con un evento duplicado. Su liberación ahora
   cierra la huella anterior; se conserva `0011` incluso con esas marcas de tiempo idénticas.

## Validación

- Compilación `localDebug` y APK de pruebas correctos con JBR 21.
- 128 pruebas JVM dirigidas: ensamblado y contrato del código, ventas, inventario, recuperación del
  formulario de catálogo y estados iniciales. Cero fallos, errores o pruebas omitidas.
- 38 pruebas instrumentadas Android aprobadas: adaptador de teclado, receptor y ciclo de vida,
  pantallas de ventas/inventario, formulario escaneado y persistencia atómica en una base en memoria.
  Los cinco fallos iniciales de `SalesScreenTest` correspondían a textos y datos de prueba de la
  interfaz anterior; se actualizaron las expectativas y la selección/visibilidad de elementos.
  La segunda ejecución completa terminó con `OK (38 tests)`.
- Se actualizó la app en `emulator-5554` (Pixel Tablet, Android 35) conservando sus datos.
- Con eventos de teclado de la consola del emulador se verificó que los caracteres llegan a
  `MainActivity.dispatchKeyEvent`: dispositivo 0 no virtual, origen teclado, Unicode correcto,
  app desbloqueada y receptor de registro activo. El comando de consola para enviar Enter no
  produjo eventos de entrada en este emulador, por lo que esa comprobación manual no acredita
  una lectura completa de extremo a extremo. Los terminadores se verifican con eventos Android
  sintéticos en las pruebas instrumentadas.

La prueba con el lector físico del usuario sigue requiriendo su marca/modelo, tipo de conexión y
una muestra del error. La app espera que Android lo reconozca como teclado y que cada lectura
termine con Enter, Enter numérico o Tab. Un dispositivo en modo serie/SPP o un servicio que sólo
pegue texto necesita una integración distinta; estas pruebas no certifican esos modos.
