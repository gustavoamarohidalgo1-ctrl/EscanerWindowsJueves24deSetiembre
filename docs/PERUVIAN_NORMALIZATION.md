# Normalización peruana de texto OCR

La capa `domain.normalization` es pura y no conoce ML Kit, Android, Room ni red. Recibe un
fragmento OCR ya seleccionado —opcionalmente con geometría, jerarquía, confianza, página e
imagen— y devuelve un candidato regional. `InvoiceHeaderParser` compone esos principios para
buscar campos dentro de un snapshot completo; su ranking se documenta en
[`INVOICE_HEADER_PARSING.md`](INVOICE_HEADER_PARSING.md). Ninguna de las dos capas valida datos
contra SUNAT.

## Texto y evidencia

`CandidateSource.rawText` nunca se modifica. `PeruvianTextNormalizer` genera tres derivados:

- `unicodeText`: compatibilidad Unicode NFKC;
- `normalizedText`: espacios Unicode colapsados, con el uso de mayúsculas original;
- `comparisonText`: el anterior en mayúsculas con `Locale.ROOT`, solo para comparar.

La caja, las cuatro esquinas, el ángulo y la ruta bloque/línea/elemento proceden del mismo fragmento
OCR; la confianza se conserva junto al candidato. Toda corrección de un
carácter confundible registra el fragmento anterior y posterior. Se permite únicamente dentro
del parser cuyo tipo ya aporta contexto: por ejemplo, `O→0` en un token numérico o `6→G` si el
resultado es una unidad admitida. Una corrección nunca se convierte automáticamente en valor
aceptado: el candidato queda pendiente de revisión.

## Candidatos y ambigüedad

Un candidato cierto tiene un único `value`. Un candidato dudoso tiene `value = null`, al menos
una advertencia que exige revisión y, cuando se conocen, sus `alternatives`. Así `1,234` no se
decide por locale: conserva las lecturas `1234` y `1.234`.

Los parsers consumen tokens completos mediante gramáticas cerradas. Admiten agrupación de miles
válida con coma o punto y decimal con el separador opuesto. La agrupación mediante espacios se
rechaza porque un salto de columna o línea OCR normalizado también se ve como un espacio. Se
rechazan signos, exponentes, agrupaciones rotas, desbordamientos y pérdida de decimales. `S/` y
`PEN` se interpretan como PEN; una moneda aportada solo por contexto o dos monedas incompatibles
quedan dudosas. Cada importe conserva únicamente los marcadores de moneda adyacentes: nunca se
hace un producto cartesiano entre todos los números y monedas de un fragmento.

Los importes se materializan en centavos mediante `Money` y `RoundingMode.UNNECESSARY`. Las
cantidades deben ser mayores que cero; el costo unitario puede ser cero y conserva su escala.

## Fechas y unidades

Las fechas usan el calendario estricto de `LocalDate`. Se aceptan formas año-mes-día y día-mes-año
con un separador consistente. Una fecha imposible se rechaza. Si día y mes admiten dos órdenes,
ambas fechas quedan como alternativas; un orden válido solo como mes-día-año se marca como no
peruano. No se inventa el siglo para años de dos dígitos.

Las unidades canónicas son `NIU`, `KGM` y `LTR`. `UND` y `UN` normalizan a `NIU`, y `KG` a
`KGM`, con una advertencia informativa de alias. Como `UN` también es un artículo en español,
solo se acepta cuando ocupa todo el fragmento normalizado. Los tests JVM cubren separadores
peruanos e internacionales, Unicode, ruido OCR contextual, calendarios bisiestos,
cajas/confianza y todos los aliases admitidos.
