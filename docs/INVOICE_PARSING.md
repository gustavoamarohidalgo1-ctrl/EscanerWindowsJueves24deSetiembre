# Parseo estructurado y confianza explicable

`ParseInvoiceUseCase` convierte un único `InvoiceOcrSnapshot` en un `ParsedInvoice`. El caso de
uso carga el negocio propietario del borrador, captura la configuración que afecta el cálculo y
ejecuta, en este orden, cabecera, líneas y totales. Los tres resultados deben conservar el mismo
`draftId` y `runId`; nunca se mezclan páginas de ejecuciones OCR distintas.

## Trazabilidad

`ParsedInvoice` mantiene los resultados tipados de los tres parsers y un `ParsedInvoiceAudit`
durable. Cada campo del audit trail contiene:

- la elección mediante `selectedCandidateIndex`;
- todos los candidatos y su valor canónico;
- confianza numérica y categoría;
- advertencias y razones de selección;
- texto OCR original, imagen, página, geometría y ruta bloque/línea/elemento.

Los valores calculados se marcan como `CALCULATED`, conservan las razones/componentes usados y
referencian evidencia de las lecturas de origen. No se presentan como texto impreso.

## Confianza y revisión

La versión 1 usa umbrales cerrados y deterministas:

| Confianza OCR | Categoría |
|---|---|
| ausente | `UNKNOWN` |
| 0–699 | `LOW` |
| 700–899 | `MEDIUM` |
| 900–1000 | `HIGH` |

Una advertencia pendiente puede limitar una elección a `LOW`, incluso si el motor OCR informó un
valor numérico mayor. La confianza global es el mínimo de RUC emisor, número, fecha, total leído,
descripción y cantidad de cada línea; una factura sin líneas queda `UNKNOWN`.

- RUC, número, fecha o total ausentes/no resueltos generan revisión.
- Descripción o cantidad ausente/no resuelta bloquea solo su línea y conserva la fila.
- Una elección `LOW` o `UNKNOWN` siempre exige confirmación explícita.
- Las advertencias informativas se persisten, pero no bloquean por sí solas.
- El parser nunca confirma una compra. Toda primera publicación termina en `NEEDS_REVIEW`.

Las advertencias y los bloqueos se deduplican y se ordenan por claves estables, incluida la ruta de
evidencia. El orden no depende de `Set`, textos localizados ni del orden accidental de lectura.

## Publicación, versión e idempotencia

El contexto recibe un SHA-256 de la ejecución OCR, versión del parser, RUC comprador, moneda de
respaldo, tasa IGV de referencia y política de redondeo. `parserVersion` describe la semántica del
algoritmo; `payloadCodecVersion` describe únicamente el formato binario.

Room guarda un único `invoice_parsed_results` por borrador, ligado mediante clave foránea al par
exacto `(draftId, runId)` del snapshot. Antes de abrir la transacción se codifica el audit trail y
se calcula su SHA-256. Dentro de una sola transacción se valida el snapshot, se inserta el resultado,
se reemplazan —no anexan— las líneas, se proyectan únicamente valores leídos/resueltos y se ejecuta
el CAS `OCR_READY → NEEDS_REVIEW`.

Repetir exactamente run, versión, contexto y payload devuelve `ALREADY_PUBLISHED`: no cambia IDs,
líneas ni timestamps. Un resultado distinto no sobrescribe silenciosamente un borrador ya en
revisión. Los `LineId` OCR son deterministas a partir de draft, run, versión y posición.

El payload usa un codec binario acotado, UTF-8 estricto, versión propia y hash verificado al leer.
La migración 5→6 crea la tabla y la clave foránea sin recurrir a migración destructiva.
