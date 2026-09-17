# Recuperación de códigos incompletos — 8 de septiembre de 2026

## Resultado funcional

En la venta con lector, una lectura sin coincidencia exacta ofrece **Códigos parecidos** cuando
uno de los códigos puede haberse quedado sin uno, dos o tres dígitos. El cajero compara el
producto y toca **Agregar a la venta**. El código guardado se conserva y no se crea una
asociación automática.

Ejemplo: el producto tiene `7753176004930` y llega `753176004930`. La pantalla muestra el
producto, el código completo guardado, una diferencia de un dígito, el stock y el almacén.
También reconoce `53176004930` y `3176004930` como posibles omisiones de dos y tres dígitos.
Si varios productos coinciden, la persona elige entre ellos.

**Volver a escanear** y **Cancelar** descartan la selección pendiente. Se mantiene una vía
explícita para asociar manualmente un código cuando corresponda, con un aviso de comprobar
primero que esté completo. Los códigos sin candidatos conservan el flujo anterior.

## Causa de software reproducida

El ensamblador confundía ciertos bloques consecutivos `ACTION_MULTIPLE` de un solo carácter
con la reentrega del mismo evento físico: compartían tiempo, tecla e índice dentro del bloque.
Una prueba con `0`, `0`, `1`, `1` y terminador, entregados en el mismo milisegundo, producía
`01` en lugar de `0011`.

El adaptador ahora identifica los caracteres provenientes de un bloque de texto. Cada bloque
se conserva íntegro; la deduplicación de eventos físicos `DOWN` sigue funcionando por separado.
La corrección aplica al receptor compartido por ventas y registro de inventario.

El recorrido Android también detectó que liberar el foco incondicionalmente al tocar una
sugerencia podía dejar el receptor IME sin foco tras guardar la línea. Los botones nuevos
conservan el foco del lector y solo lo liberan si se estaba editando un campo de búsqueda o
del formulario. La prueba de recorrido exige poder continuar con la siguiente lectura IME.

Esta prueba confirma un defecto del programa, pero no identifica por sí sola la causa de cada
lectura perdida del lector físico del usuario. No se capturó su secuencia real de eventos ni
se modificó la configuración del dispositivo.

## Reglas de comparación

- La búsqueda exacta por código de barras tiene prioridad; después, el SKU exacto.
- Las sugerencias se calculan solo si ambas búsquedas exactas fallan.
- Se permiten omisiones en cualquier posición, conservando el orden de los dígitos restantes.
  La comparación funciona en ambas direcciones: lectura incompleta o código guardado incompleto.
- Solo códigos numéricos ASCII: corto de al menos 5 dígitos, largo de al menos 8, máximo 128.
- Se conservan los ceros iniciales. No se inventan dígitos ni se normalizan códigos para forzar
  una coincidencia; tampoco se sugieren sustituciones o transposiciones.
- Hasta 5 productos activos del negocio actual con existencias positivas, ordenados por menor
  cantidad de omisiones y, ante empate, por código e identificador. Se muestran las ubicaciones
  disponibles de cada producto para elegir el almacén exacto.
- No se presenta un porcentaje de certeza: un parecido no demuestra identidad.

El cálculo reutiliza el catálogo local ya observado, en el dispatcher de trabajo de CPU,
con memoria acotada para los cinco mejores candidatos y comprobación periódica de cancelación.
No requiere red, nuevas tablas, migraciones ni consultas de base de datos por candidato.

## Integridad de la selección

La selección usa una acción distinta de la asociación manual. Antes de guardar la línea se
revalidan producto activo, código, ubicación, existencias y contexto de la venta. Se utiliza
el precio del producto guardado y se respeta la validación existente del carrito.

Reescanear o cancelar invalida la sugerencia anterior. Un cambio de carrito limpia la sesión
pendiente; los cambios de catálogo o existencias retiran opciones que ya no corresponden.
Los dobles toques no crean dos altas. Un fallo al guardar mantiene la opción para reintentar.
La venta y el descuento de stock siguen pasando por el checkout existente.

## Archivos principales

- `core/input/AndroidKeyboardWedgeAdapter.kt` y `KeyboardWedgeInput.kt`: conservación de bloques.
- `domain/usecase/BarcodeSimilarity.kt`: comparación conservadora de dígitos omitidos.
- `feature/sales/SalesBarcodeSuggestions.kt`: filtrado y selección de cinco productos.
- `feature/sales/SalesContract.kt` y `SalesViewModel.kt`: estado, acción y validación de selección.
- `feature/sales/SalesScreen.kt`: candidatos, confirmación y acceso a asociación manual.
- `res/values/sales_barcode_suggestions_strings.xml`: textos de la pantalla.

Las pruebas específicas están en `BarcodeSimilarityTest`, `SalesBarcodeSuggestionsTest`,
`SalesViewModelTest`, `KeyboardWedgeInputTest`, `AndroidKeyboardWedgeAdapterTest`,
`BarcodeSuggestionsScreenTest` y `ScannerSaleJourneyTest`.

## Validación

Los comandos, resultados y captura del recorrido se conservan en
`build/reports/barcode-recovery-2026-09-08/`. Las pruebas de dispositivo se ejecutan en un
emulador aislado; no instalan ni cambian datos en la tableta física.

La imagen de evidencia corresponde al componente de ventas con productos ficticios en una
Activity de prueba. La Activity principal conserva su protección de capturas de pantalla.

Resultados comprobados:

- **1.595 pruebas unitarias local y 1.773 cloud**, sin fallos.
- **65 pruebas Android**, sin fallos, sobre adaptador, campo de lectura, registro del receptor,
  pantalla de ventas y recorridos con Room.
- La comparación verifica las 377 combinaciones de omitir entre 1 y 3 dígitos de un código de
  13 caracteres, en ambos sentidos, además de límites, caracteres inválidos y ceros iniciales.
- Un recorrido con dos candidatos exige seleccionar el producto correcto, después acepta una
  lectura exacta y confirma una venta de **S/22,00** con stock final **8 y 9**, respectivamente.
  Conserva los códigos y versiones de ambos productos.
- Otro recorrido comprueba **Reescanear**, **Cancelar** y cancelar después de enfocar búsqueda
  manual, continuando inmediatamente mediante IME.
- La interfaz permite alcanzar y usar el botón con ventana de 320 × 360 dp y letra al 200 %.
- APK local de depuración compilada; formato y análisis estático completados. Lint registra
  los mismos 66 avisos previos por variante, sin errores nuevos.

El manifiesto `validation.json` recoge recuentos y huella SHA-256 del APK. La comprobación con
el modelo real del lector sigue pendiente; estas pruebas no sustituyen esa validación física.
