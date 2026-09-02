# Calidad y preprocesamiento de facturas

El flujo conserva dos representaciones con responsabilidades distintas:

- **Original privado saneado:** copia importada sin metadatos sensibles. Es inmutable; giro y
  recorte se guardan como receta en Room.
- **Versión OCR:** lote JPEG regenerable bajo
  `draft_images/<draftId>/ocr/runs/<runId>/<imageId>-v1.jpg`. Solo un manifiesto
  `current-v1.manifest` terminado publica el lote; un fallo o cancelación no expone páginas
  parciales ni reemplaza el último lote válido. Nunca sustituye el original ni se usa como
  vista previa.

## Análisis heurístico

`ImageQualityAnalyzer` decodifica una muestra de hasta 768 px por lado y aplica la misma
orientación y recorte que recibirá el OCR. Informa medidas enteras y advertencias, no una
certificación de calidad:

| Señal | Regla inicial |
| --- | --- |
| Resolución | Recomienda al menos 900 px en el lado corto y 1200 px en el largo |
| Desenfoque posible | Energía media de Laplaciano inferior a 180 |
| Subexposición posible | Luminancia media menor de 65/255 o más de 55 % de píxeles muy oscuros |
| Sobreexposición posible | Media desde 240/255 con nitidez baja, o al menos 99 % de píxeles muy claros |
| Inclinación posible | Proyección de renglones entre −10° y 10°; avisa desde 2° solo con mejora medible frente a 0° |
| Recorte incompleto posible | Más de 12 % del contenido oscuro detectado toca la banda del borde |

Estos valores son puntos de partida y deben calibrarse con facturas reales. Papel térmico,
logos, fondos de color y páginas casi vacías pueden producir falsos positivos. Por eso ninguna
heurística bloquea: la interfaz explica la medida y ofrece **Continuar de todos modos** o
**Repetir página advertida**.

## Receta OCR balanceada (versión 1)

El orden es fijo y regenerable:

1. Leer cabeceras sin cargar píxeles.
2. Convertir el recorte del marco orientado a coordenadas fuente y decodificar solo esa
   región. Elegir sobre ella una muestra potencia de dos usando el lado mayor; ningún lado
   decodificado supera 2048 px. Una entrada completa de 8000×8000 usa muestra 4 y queda en
   2000×2000, mientras un recorte pequeño conserva la resolución útil que sí cabe en el límite.
3. Aplicar la orientación EXIF rescatada durante la importación (o EXIF legacy) y los giros de
   usuario. Si un codec no admite región, se usa un fallback acotado y se recorta después.
4. Componer transparencia sobre blanco y convertir en el mismo bitmap a escala de grises
   mediante `(77R + 150G + 29B) / 256`.
5. Aplicar contraste lineal moderado de 112 % alrededor de 128. No se usa umbral binario duro.
6. Comprimir directamente a archivo temporal como JPEG, calidad 88, sin crear un
   `ByteArray` del resultado; publicar mediante movimiento atómico.

La cota normal de la página de trabajo es 2048×2048×4, aproximadamente 16 MiB. Giro o recorte
pueden mantener brevemente dos bitmaps, aproximadamente 32 MiB más buffers del codec, pero no
se decodifica el original 8000×8000 completo. Las páginas se procesan secuencialmente y el
preprocesador usa un `Mutex` singleton para impedir dos lotes simultáneos.

El análisis y el trabajo de píxeles se ejecutan en `DispatcherProvider.default`; escritura y
publicación usan `DispatcherProvider.io`. Hay comprobaciones de cancelación antes/después del
decode y por fila durante luminancia, contraste y escritura. Todo bitmap y temporal tiene un
`finally`; cancelar elimina el `run` no publicado sin tocar originales ni el último lote válido.

## Pruebas y límites conocidos

- JVM: decisiones UDF, continuar/repetir, procesamiento secuencial y cancelación.
- Instrumentadas: luz, inclinación, giro, recorte, escala de grises, reducción proporcional,
  original intacto y PNG sintético 8000×8000 sin bitmap fuente gigante.
- Decode y `Bitmap.compress` son operaciones nativas no interrumpibles de inmediato. La cota
  de tamaño limita su latencia; el stream de compresión comprueba cancelación en cada escritura.
- Las orientaciones EXIF espejadas heredadas se reducen actualmente a su rotación base porque
  el modelo persistido solo expresa cuartos de vuelta. Incorporar espejo requerirá versionar la
  receta y la entidad.
