# Auditoría Fase C — Interpretación de facturas (prompts 18–22)

Fecha: 21 de agosto de 2026 (`America/Lima`). Revisión **de código, no de compilación**: `java -version`
responde «Unable to locate a Java Runtime», así que ninguna prueba se ejecutó aquí. Continúa la
[auditoría de Fase B](2026-08-21-fase-b-auditoria.md).

La capa está implementada y es madura: `InvoiceTotalsParser.kt` (1858 líneas),
`InvoiceHeaderParser.kt` (1215), `InvoiceLineItemsParser.kt` (1195), `PeruvianValueParser.kt` (732),
`InvoiceParser.kt` (570), más los modelos puros `ParsedInvoice.kt` (267), `Candidate.kt` (223) y
`PeruvianTextNormalizer.kt` (46). La respaldan 151 pruebas unitarias de dominio (127 previas más las
24 nuevas de esta sesión) y `GoldenCorpusTest` sobre 7 facturas sintéticas.

## Resultado por prompt

| # | Prompt | Estado | Base de la conclusión |
| --- | --- | ---: | --- |
| 18 | Normalizar texto y valores peruanos | CUMPLE tras la corrección | `PeruvianTextNormalizer` aplica NFKC y colapso de espacios Unicode y deja las mayúsculas **solo** en `comparisonText`; `rawText` viaja intacto hasta la evidencia. `Candidate` prohíbe por construcción la suposición silenciosa: un valor resuelto no puede conservar alternativas ni advertencias pendientes, y uno sin resolver debe declarar por qué requiere revisión. Las correcciones OCR están acotadas a tres razones tipadas (`NUMERIC_CONFUSABLE_IN_NUMERIC_CONTEXT`, `UNIT_CONFUSABLE_IN_UNIT_CONTEXT`, `FIXED_VOCABULARY_CONFUSABLE`) y siempre marcan revisión. `PEN_MARKER` (`PeruvianValueParser.kt:709`) reconoce `PEN` y `S/`; el mapa de alias (líneas 723-731) traduce `UND`/`UN`→`NIU`, `KG`→`KGM` y conserva `LTR`. Pruebas: `PeruvianValueParserTest` (14) con «money fixtures produce exact PEN minor units for both separator conventions», «ambiguous separator is surfaced and never chosen silently», «dates accept strict unambiguous forms and reject impossible calendar values» y «parsing does not depend on the process default locale». **Faltaba** la prueba directa de los invariantes de `Candidate`: se añadió (ver abajo) |
| 19 | Extraer cabecera y validar RUC localmente | CUMPLE | `InvoiceHeaderParser` combina etiquetas y geometría y mantiene los candidatos rivales. `RucValidator` valida estructura (11 dígitos ASCII) y dígito verificador mod-11 con pesos 5,4,3,2,7,6,5,4,3,2; su KDoc declara que la regla local **no** confirma existencia y que la clase nunca consulta SUNAT. Un RUC estructuralmente inválido se conserva con advertencia («a structurally invalid labeled RUC is retained without inventing a repair»). El emisor nunca se confunde con el cliente: «a sole recipient RUC is never selected by label or active buyer match» y «recipient legal-name label cannot populate issuer fields». Criterios uno a uno: `F001-12345` en «layout A … splits the document number in the right box»; dos RUC en «two RUC without party role stay unresolved with two explained alternatives»; ausencias en «an empty textual page produces six empty header fields»; cinco disposiciones distintas en `layout A`…`layout E`, con OCR ruidoso en `layout B` (19 pruebas) |
| 20 | Detectar filas de productos mediante geometría | CUMPLE | `InvoiceLineItemsParser` reconoce sinónimos de encabezado, infiere columnas desde las cajas, agrupa por proximidad vertical y une continuaciones. Un campo ausente queda vacío, no en cero («format 3 leaves columns absent from a three-column schema empty without incomplete warning», «format 6 preserves incomplete rows and never borrows a neighboring cell»). Los totales, el QR y el pie quedan fuera («format 9 excludes summaries QR payload and footer from item rows», «RUC DNI bare QR and quantity-only barcode never become products or continuations») y, en el sentido contrario, un producto cuyo nombre empieza por TOTAL sigue siendo producto («a product beginning with TOTAL does not terminate the table»). Diez formatos sintéticos `format 1`…`format 10` cubren alineación, geometría de elementos, filas irregulares, columnas ausentes y encabezados repetidos multipágina (22 pruebas) |
| 21 | Extraer totales, IGV y redondeo | CUMPLE | `InvoiceTotalsParseResult` separa estructuralmente `ReadInvoiceTotals` de `CalculatedInvoiceTotals`, así que lo leído y lo calculado no pueden confundirse. La tasa de referencia del 18 % es configurable y solo se usa cuando el documento la sostiene («configured reference rate is applied only when an IGV label supports it», «document without an IGV label never receives automatic tax», «exempt-only document reconciles without inventing IGV»). La conciliación expone la diferencia exacta sin tocar lo impreso: «reconciliation exposes an exact three cent difference without changing the read total». El redondeo lleva signo y razón, y `InvoiceRoundingAdjustment` rechaza en `init` una dirección que contradiga el importe firmado. Ausencia y cero impreso se distinguen («missing fields stay empty while an explicitly printed zero remains present», «subtotal and total alone do not imply that missing tax was zero»). 57 pruebas entre `InvoiceTotalsParserTest` (40) y diez disposiciones `layout 1`…`layout 10` (17) |
| 22 | Unificar parser y confianza explicable | CUMPLE tras la corrección | `ParseInvoiceUseCase` corre el parseo puro en `dispatcherProvider.default` con `ensureActive()` por fase y por línea, y publica cabecera, líneas y audit trail en **una** transacción cuyos cuatro resultados posibles se traducen a error de dominio (`STALE_SNAPSHOT`, `DRAFT_NOT_READY`, `CONFLICT`). La proyección pasa a `NEEDS_REVIEW` y, ante monedas incompatibles, `preferredCurrency = projectedCurrencies.singleOrNull()` evita elegir bando. La idempotencia es estructural: `deterministicLineId` deriva el UUID de `draftId/runId/parserVersion/position` por SHA-256, así que reprocesar reescribe las mismas filas («reprocessing is a no-op with stable line ids and original publication timestamp»). `REQUIRED_DOCUMENT_FIELDS` exige RUC, número, fecha y total; los bloqueos son exactamente descripción y cantidad por línea; `eligibleForAutomaticConfirmation` exige HIGH sin advertencias de revisión ni bloqueos, de modo que LOW/UNKNOWN jamás se autoconfirman. Pruebas de extremo a extremo (6) sobre facturas completa, incompleta, contradictoria, reprocesada y de baja confianza, más `GoldenCorpusTest`. **Faltaba** la prueba directa del contrato determinista del audit trail: se añadió (ver abajo) |

## Lo que faltaba y se aplicó

Los cinco prompts estaban implementados; los dos huecos reales eran de cobertura, y en ambos casos
sobre los invariantes que **sostienen** un criterio explícito del enunciado. Estaban ejercitados de
forma indirecta por los parsers, pero nada fijaba el contrato: relajar un `require` no habría hecho
fallar ninguna prueba.

1. **Invariantes del candidato (Prompt 18: «ambigüedad devuelve candidato dudoso, nunca suposición
   silenciosa»).** Ninguna prueba construía un `Candidate` directamente —un `grep` de `Candidate(`
   bajo `app/src/test` no devolvía nada—, así que las cuatro reglas que impiden la suposición
   silenciosa no tenían prueba propia. Se añadió
   [`CandidateTest.kt`](../../app/src/test/java/com/facturastock/app/domain/normalization/CandidateTest.kt)
   con 14 pruebas: un candidato sin resolver debe explicar por qué requiere revisión (y una
   advertencia informativa como `UNIT_ALIAS_NORMALIZED` **no** alcanza como explicación); uno resuelto
   no puede conservar alternativas ni advertencias pendientes; el valor elegido no se repite como
   alternativa; advertencias y alternativas duplicadas se rechazan; la confianza vive en 0..1000; la
   procedencia OCR exige imagen y página juntas, cero o cuatro esquinas, posiciones no negativas y
   ángulo en -1800..1800; una corrección OCR debe cambiar un fragmento no vacío; y la normalización
   nunca reescribe el texto original.

2. **Contrato determinista del audit trail (Prompt 22: «las advertencias son deterministas»).** El
   orden lo imponen `FIELD_ORDER`, `WARNING_ORDER` y `BLOCKER_ORDER`, pero los caminos existentes
   entregaban listas ya ordenadas, así que nunca se comprobó que una lista desordenada se rechace ni
   que la elegibilidad automática se retire por las razones correctas. Se añadió
   [`ParsedInvoiceAuditTest.kt`](../../app/src/test/java/com/facturastock/app/domain/normalization/ParsedInvoiceAuditTest.kt)
   con 10 pruebas: `parserVersion` positivo y huella SHA-256 en minúsculas de 64 dígitos; los campos
   de cabecera preceden a los de línea y no puede haber dos trazas para el mismo campo y posición;
   advertencias y bloqueos únicos y ordenados; `eligibleForAutomaticConfirmation` cierto **solo** en
   HIGH limpio y falso en MEDIUM, LOW y UNKNOWN, retirado por una advertencia que requiere revisión o
   por un bloqueo de línea, pero no por una advertencia informativa; un campo sin elección permanece
   UNKNOWN y bajo revisión; un candidato OCR conserva su texto original y su evidencia; un bloqueo
   conserva la evidencia de su fila.

Ambos archivos son de dominio puro: sin `Float`/`Double`, sin registro y sin dependencias de Android.

## Decisiones sostenidas sin cambio

1. **La tasa del 18 % sigue siendo solo referencia.** El Prompt 21 lo pide de forma explícita:
   aplicarla cuando el documento no la sostiene sería inventar impuesto. `AppConfiguration` la
   mantiene configurable y el parser la usa únicamente ante una etiqueta de IGV.
2. **Un conflicto de moneda no se resuelve por mayoría.** `preferredCurrency` usa `singleOrNull()`, así
   que ante lecturas incompatibles la proyección deja los importes fuera en vez de forzar una moneda;
   el conflicto íntegro queda en el audit trail. Elegir un bando contradiría «nunca una suposición
   silenciosa».
3. **`ParsedInvoiceConfidence` no calcula probabilidades.** Son cuatro categorías con umbrales
   estables del parser v1 (900..1000 HIGH, 700..899 MEDIUM, resto LOW, ausente UNKNOWN). El enunciado
   pide confianza explicable, no un número derivado.
4. **`parserVersion` permanece en 1.** Los cambios de esta sesión son solo pruebas: no alteran ninguna
   salida del parser, así que subir la versión invalidaría audit trails ya publicados sin motivo.

## No verificable en este entorno

Todo lo que exige Gradle: `testDebugUnitTest` —incluidas las 24 pruebas nuevas, que están **escritas
pero no ejecutadas**—, `ciStaticAnalysis`, Spotless, Lint, Kover (el piso del 80 % sobre
`com.facturastock.app.domain.*`) y los diez verificadores del `build.gradle.kts`. Lo que sí se pudo
comprobar aquí de forma mecánica sobre los archivos nuevos: balance de llaves y paréntesis, ausencia
de nombres de prueba duplicados y ninguna línea por encima de 120 columnas.

La evidencia previa en disco que respalda la ejecución de la suite está citada en
[`../ACEPTACION_V1.md`](../ACEPTACION_V1.md); no la produjo esta sesión.
