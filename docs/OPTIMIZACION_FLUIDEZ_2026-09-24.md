# Optimización de fluidez — 24 de septiembre de 2026

Objetivo: menos trabajo por interacción en los recorridos diarios de la tienda (cambiar de pestaña,
buscar y escanear en Ventas, abrir Inventario), sin cambiar reglas de negocio ni datos.

## Método

- Emulador Pixel Tablet (API 35) con los datos reales de la tienda restaurados desde el respaldo
  `run-as` del 24/09 antes de cada corrida; APK `localBenchmark` (R8, como la tablet) compilado con
  `speed`.
- Perfetto con `sched`, atrace y *frame timeline*; muestreo ART sobre `localProfile` para atribuir
  métodos. Corridas alternadas referencia/optimizada.
- El renderizado del emulador (GPU emulada a 2560×1600, compilación de *shaders* y colas de búfer)
  domina el tiempo de cuadro y no representa la GPU Mali de la tablet. Por eso las conclusiones se
  basan en trabajo de la app y contadores deterministas (cuadros dibujados, remaquetaciones del
  campo del lector, recomposiciones), no en totales de CPU del emulador, que varían mucho entre
  corridas de una misma versión.

## Cambios

1. **Cambio de pestaña sin fundido cruzado.** `NavHost` usaba su transición por defecto: un
   fundido de 700 ms en el que se componen y dibujan las dos pantallas completas en capas
   semitransparentes (~42 cuadros). Entre Vender, Inventario y Reportes el cambio ahora es inmediato;
   hacia pantallas secundarias queda un fundido de 150 ms.
2. **Búsqueda de Ventas sin parpadeo.** Con cada tecla la lista de productos se vaciaba y se animaba
   un indicador de carga hasta que llegaban los resultados. Ahora se conservan los resultados de la
   consulta anterior mientras llega la nueva, si una extiende o recorta a la otra; una consulta sin
   relación, o un cambio de catálogo o negocio, sigue descartándolos. Las opciones proyectadas se
   reutilizan mientras no cambien la búsqueda ni el catálogo.
3. **Campo del lector idempotente.** El bloque `update` del `EditText` reasignaba siempre la pista,
   los colores y el subrayado. `setHint` fuerza `checkForRelayout`, y eso ocurría en cada
   recomposición, incluido cada carácter del lector. Ahora sólo se aplica lo que cambió.
4. **Carrito estable durante un escaneo.** Las líneas del carrito se deshabilitaban y rehabilitaban
   con cada escaneo (dos recomposiciones y repintados). Durante una lectura quedan habilitadas: sus
   ediciones pasan por el mismo mutex y se aplican después. Otras mutaciones siguen bloqueándolas.
5. **Revisión automática de Inventario diferida.** Al abrir Inventario se leían y recalculaban todos
   los movimientos (~350 ms en el emulador; más de un segundo en la tablet) justo mientras se dibujaba
   la pantalla. La revisión espera ahora 2,5 s tras la primera lista.
6. **Ventas sin saltos al agregar o cobrar.** Cada mutación que no es un escaneo (tocar un producto,
   guardar una línea, concluir la venta) insertaba la tarjeta «Procesando…» arriba de la lista: todo
   el contenido bajaba y volvía a subir. La tarjeta sólo aparece si la operación supera 400 ms; los
   botones siguen deshabilitándose al instante.

## Evidencia

Contadores deterministas en un recorrido enfocado (búsqueda de «arroz» ×4, 8 escaneos de códigos
reales), dos corridas por versión:

| Contador | Referencia | Optimizada |
| --- | --- | --- |
| Remaquetaciones del campo durante la búsqueda | 120 / 120 | 80 / 80 (−33 %) |
| Remaquetaciones del campo en 8 escaneos | 328 / 328 | 224 / 224 (−32 %) |
| Cuadros dibujados durante la búsqueda | 433 / 382 | 181 / 234 (−45 a −58 %) |

Recorrido diario (cuadros / cuadros de más de 16,7 ms / peor cuadro en ms):

| Paso | Referencia | Optimizada |
| --- | --- | --- |
| Abrir Inventario | 39/21/101 · 41/19/82 | 24/6/54 · 23/6/53 |
| Ir a Vender | 47/11/63 · 50/15/38 | 14/5/30 · 14/3/59 |
| Volver a Inventario | 50/20/66 · 37/20/150 | 31/10/71 · 28/10/75 |

Al abrir Inventario la CPU de la app bajó entre 30 y 50 % en la primera tanda (diagnóstico
diferido).

## Límites

- El desplazamiento de listas no cambió: dentro de cada cuadro la composición de tarjetas nuevas es
  pequeña (se precomponen en tiempo libre) y el resto es coste fijo del marco de dibujo. Su fluidez
  en la tablet depende sobre todo de la GPU.
- Tras cada actualización Android borra la caché de *shaders* de Skia: la primera vez que se dibuja
  cada elemento puede haber un tirón (en el emulador, hasta 300 ms). Desaparece después del primer
  uso de cada pantalla.
- No se midió en la tablet física. Las cifras del emulador indican dirección, no magnitud absoluta.
- `HomeScreenAccessibilityTest` (4 casos) ya fallaba antes de estos cambios: busca la pantalla
  Inicio, pero la app arranca en Vender desde la venta directa. Queda pendiente actualizarla.
