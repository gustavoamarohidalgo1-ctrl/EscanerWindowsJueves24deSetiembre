# Lecturas consecutivas: rapidez y confirmación en la tablet

## Objetivo

Facilitar varios disparos seguidos del escáner, conservando cada cantidad y permitiendo
comprobar el resultado sin desplazarse por el carrito. La prioridad indicada por el usuario
fue la rapidez de las lecturas consecutivas.

## Cambios

- Una franja fija junto al lector muestra el producto agregado, su cantidad acumulada y el
  almacén. Solo confirma una lectura después de guardar la línea en el carrito.
- Mientras quedan lecturas pendientes, se muestra su procesamiento en orden. El cargador de
  cada lectura ya no se inserta en la lista ni desplaza el carrito.
- Los errores, la edición en curso y las elecciones pendientes tienen prioridad sobre el
  último agregado; la pantalla no muestra un éxito anterior como si resolviera un fallo nuevo.
- La cantidad de la franja sigue los cambios guardados del carrito y se retira si se elimina
  esa línea. Cambiar de venta o de modo limpia la confirmación anterior.
- Las emisiones atrasadas del mismo carrito y negocio no pueden reemplazar una versión más
  reciente ya guardada. El siguiente disparo utiliza la cantidad y versión actuales.
- Los códigos parecidos aparecen con menos contenido previo y una explicación breve, manteniendo
  la selección explícita, el código guardado y la elección de almacén.
- El cobro de una venta nueva exige resolver o cancelar la lectura pendiente y reconocer un
  error del lector. El reintento de un checkout ya persistido conserva su funcionamiento.
- Se confirma correctamente una lectura IME cuyo terminador llega mediante composición y
  `finishComposingText`, sin enviar borradores sin sufijo ni aceptar conexiones obsoletas.

## Conservación del prefijo leído en Huawei

El diagnóstico en la tablet mostró que, al preparar la entrada sobre el prefijo
físico `000`, el teclado de Huawei lo marcaba como texto en composición, aunque el cursor
seguía al final. Al escribir `789`, Android sustituía esa composición y dejaba `789`,
en lugar de conservar `000789`.

El campo ahora distingue el espejo de una lectura HID del texto que el usuario o el IME
está editando. Al crear una conexión sobre el espejo físico, retira únicamente esas marcas
automáticas de composición y conserva la selección. En cuanto comienza una edición real,
deja de tratarlo como espejo. La composición legítima conserva su rango: tras el prefijo
`000`, componer `789` y reemplazarlo por `456` debe producir `000456`, incluso al recrear
la conexión. La regresión comprueba este comportamiento y la conservación del prefijo.
Las notificaciones que repiten el mismo contenido no se consideran una edición.

El tipo de entrada utiliza `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` para solicitar texto
literal visible y evitar que el teclado reconvierta el código como una palabra. No se ocultan
los caracteres. Se conservan `NO_SUGGESTIONS` y `MULTI_LINE`; este último permite validar los
terminadores originales sin que Android los convierta en espacios.

## Eficiencia y exactitud

La cola admite 32 lecturas pendientes y serializa sus guardados. Las coincidencias exactas
siguen usando los índices de negocio y código de Room; no se añadió una caché que pudiera
conservar precios o productos desactualizados. Cada lectura correcta incrementa la cantidad
una vez, incluso si varias corresponden al mismo producto.

La prueba de ráfaga envía 32 tramas completas al receptor de Android, verifica una sola línea
con 32 unidades y un total de S/160,00, comprueba que el catálogo no cambió y que el stock sigue
en 100 antes del cobro. También verifica que la confirmación permanece visible al desplazar
el carrito. Las pruebas unitarias detienen cada guardado y comprueban las 32 versiones y
cantidades intermedias, además del resultado final.

## Verificación en el dispositivo

La tablet Huawei AGS6_W09 utiliza Android 10 / API 29. Android identifica un teclado externo
`SCANNER`. La pantalla estaba en horizontal, 1920 × 1200 píxeles, densidad 280 y letra normal.

Los recorridos con productos ficticios se ejecutan en el paquete temporal
`com.facturastock.scannerqa`, con un directorio de datos y un APK de instrumentación propios.
El manifiesto de instrumentación se comprueba antes de ejecutarlo. El recorrido que prepara
SQLite rechaza expresamente el paquete real de una tablet física. La aplicación del usuario
se actualiza por separado mediante instalación con conservación de datos.

Las mediciones registran desde el envío de eventos hasta el guardado de las 32 unidades y
la actualización de la interfaz. Son pruebas automáticas del receptor y de la persistencia;
no miden la velocidad óptica del escáner ni constituyen una comparación con una versión anterior.

Evidencias y resultados: `build/reports/scanner-refinement-2026-09-08/`.

## Resultados finales

| Verificación | Resultado |
| --- | --- |
| Pruebas unitarias local | 1605 correctas, sin fallos ni omisiones |
| Pruebas unitarias cloud | 1783 correctas, sin fallos ni omisiones |
| Instrumentación en Huawei, Android 10 | 57 correctas |
| Instrumentación en emulador, Android 15 | 79 correctas |
| `ciStaticAnalysis` | Correcto |
| Android Lint local y cloud | 0 errores y 66 advertencias por variante |
| Ráfaga en Huawei con dispositivo `SCANNER`, id 6 | 32 lecturas, 32 unidades guardadas, 1799 ms |
| Ráfaga en emulador con teclado `qwerty2` | 32 lecturas, 32 unidades guardadas, 2281 ms |

Los dos casos de conservación del prefijo y composición legítima están incluidos en las
57 pruebas de Huawei. Las pruebas de interfaz incluyen letra al 200 % en un espacio reducido.
El emulador compartía el equipo con la compilación y análisis de código; los tiempos de ambas
plataformas son mediciones de estas ejecuciones, no una comparación de rendimiento entre ellas.

Registros de la versión final: `final-validated-build.log`, `tablet-validated-tests.log`,
`emulator-validated-tests.log`, `tablet-burst-final.json` y `emulator-burst-final.json`.

## Instalación comprobada

Se actualizó `com.facturastock.app` mediante `adb install -r` el 8 de septiembre de 2026
a las 13:05:33, hora de la tablet. Android confirmó la instalación y abrió `MainActivity`.
Se dejó abierta la venta de contado con escáner físico y se comprobó la franja «Lector listo»
en la interfaz de la aplicación instalada, sin introducir productos de prueba en ella.
La fecha de instalación original y el directorio de datos se conservaron (inode 126747).
Se retiraron los dos paquetes QA temporales y se cerró el emulador de pruebas.

El SHA-256 del APK instalado coincide exactamente con el archivo compilado:

`089ceaa2df1c0f03bc72bd05abbdcd0c4153663fd148330d10a0afeb3c4e469f`

La versión declarada continúa siendo 1.0.4 (código 5). La identificación concreta de esta
actualización queda en el hash anterior y en `installation-verification.json`.
