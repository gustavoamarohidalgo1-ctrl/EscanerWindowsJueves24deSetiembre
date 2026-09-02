# Extracción local de cabecera

`InvoiceHeaderParser` es una capa pura de dominio. Recibe un `InvoiceOcrSnapshot`, devuelve los
IDs del borrador y de la ejecución que originaron el resultado y no accede a Android, ML Kit,
Room, red ni configuración regional implícita.

## Contrato y evidencia

La salida contiene tipo de comprobante, RUC del emisor, razón social, serie/correlativo, fecha de
emisión y moneda. Cada `HeaderField` separa una propuesta `selected` de sus `alternatives`. Una
propuesta conserva el texto OCR, la caja efectiva, confianza, advertencias, razones de ranking y
una o más `CandidateEvidence`. El resultado sigue ligado a `draftId` y `runId` para que una futura
aplicación a Room pueda rechazar snapshots sustituidos.

El recorrido usa una sola capa textual: líneas, o bloque/página solo cuando no existen líneas. La
caja efectiva sale de la caja de línea, la unión de elementos, las cuatro esquinas o, como último
recurso, el bloque. Etiquetas y valores se asocian en la misma línea, en la misma fila visual o
directamente debajo. Las posiciones de lista solo sirven como fallback determinista; no deciden
qué RUC pertenece al emisor.

## Selección conservadora

- Las zonas `EMISOR`/`PROVEEDOR` tienen prioridad sobre una cabecera anterior a la sección de
  cliente. `CLIENTE`, `ADQUIRIENTE`, `RECEPTOR`, `DESTINATARIO` y el RUC opcional del negocio
  comprador nunca se seleccionan como proveedor.
- Dos RUC sin rol distinguible quedan sin selección y ambos se conservan como alternativas. El
  orden OCR y la confianza no rompen el empate.
- El checksum del RUC es solo una comprobación matemática local. Un formato o dígito discordante
  se conserva con advertencia; no confirma existencia, vigencia ni identidad y no consulta SUNAT.
- `O→0` e `I/L/|→1` se permiten solo dentro de un token anclado explícitamente a `RUC` o a un
  componente numérico. El texto original permanece en la evidencia y la corrección exige revisión.
- El número de comprobante conserva serie y correlativo como texto para no perder ceros. No se
  infiere el tipo desde la primera letra de la serie.
- `FECHA DE EMISIÓN` vence a vencimiento, traslado o entrega. El calendario estricto rechaza
  fechas imposibles.
- Una moneda etiquetada vence a los símbolos de importes. Un `S/` sin etiqueta queda como fallback
  advertido; nunca se usa la moneda de ajustes para completar un campo ausente.

## Verificación

Los tests JVM usan diecinueve escenarios: cinco disposiciones geométricas completas y casos
dirigidos de empate de RUC, receptor único, fecha imposible, conflicto de moneda y documento vacío. Incluyen
bloques desordenados, valores en columnas, etiquetas sobre valores, razón social envuelta,
`cornerPoints` sin `boundingBox`, páginas posteriores y errores OCR deterministas.
