# Corpus dorado de parsing y cobertura del dominio

El corpus dorado es una red de regresión **acotada** para comprobantes sintéticos y
anonimizados es-PE. Separa deliberadamente la entrada visual, el OCR esperado y el resultado
esperado del parser. Un caso verde garantiza solo el contrato versionado de esa fixture; no
afirma universalidad sobre comprobantes reales ni validación ante SUNAT.

## Dos niveles de prueba

El nivel JVM determinista ejecuta este flujo:

```text
OCR sintético esperado -> InvoiceOcrSnapshot -> InvoiceParser -> resultado JSON cerrado
```

El PNG se materializa para auditar la entrada, pero el test JVM **no** lo entrega a ML Kit y no
presenta el OCR esperado como una medición del motor. Esto permite ejecutar el oracle sin red,
emulador, modelo descargable ni reloj real.

El nivel Android es el smoke físico independiente
`MlKitInvoiceTextRecognizerTest.bundledLatinModelRecognizesSyntheticInvoiceWithoutNetworkPermission`.
Comprueba en dispositivo que el modelo latino integrado reconoce texto y conserva geometría sin
permiso `INTERNET`. No exige igualdad literal con el corpus: la segmentación y confianza del OCR
físico pueden variar entre versiones del modelo y dispositivos.

## Contrato versionado

Cada caso consta de tres recursos explícitos bajo
`app/src/test/resources/golden-corpus/`:

```text
cases/<id>.json                 manifiesto e imagen de entrada
expected-ocr/<id>.json          snapshot OCR sintético esperado
expected-results/<id>.json      resultado cerrado esperado del parser
```

Los modelos serializados y sus invariantes viven en
`app/src/test/java/com/facturastock/app/corpus/GoldenCorpus.kt`. Todos los JSON usan
`schemaVersion = 1`; la deserialización rechaza claves desconocidas.

### Manifiesto e imagen

El manifiesto declara ID, título, categoría, RUC comprador opcional y referencias inequívocas a
los otros dos recursos. `inputImage` contiene:

| Campo | Contrato |
| --- | --- |
| `textLayerResource` | Capa textual usada solo para sintetizar los píxeles. No es una salida de ML Kit. |
| `outputFileName` | Debe ser exactamente `<id>.png`. |
| `profile` | `CLEAN`, `BLURRED` o `SKEWED`. |
| `blurRadiusPx` | Radio real del box blur; debe ser mayor que cero solo en `BLURRED`. |
| `horizontalSkewPermille` | Desplazamiento por scanline; debe ser distinto de cero solo en `SKEWED`. |

`GoldenCorpusImage` rasteriza una fuente 5×7 propia y escribe PNG RGB sin `java.awt`. Blur e
inclinación modifican los píxeles, no solo metadata del caso. `GoldenCorpusImageTest` exige que
limpia, borrosa e inclinada produzcan matrices distintas, que el blur cree grises y que la limpia
permanezca binaria.

### OCR esperado

El recurso `expected-ocr` declara dimensiones de página, ángulo horario global y celdas con
texto, caja, confianza y geometría opcional solo por esquinas. Su `source` obligatorio es
`SYNTHETIC_EXPECTATION`: nunca se interpreta como una captura observada de ML Kit.

Las celdas se materializan como bloques y líneas de `InvoiceTextDocument`. El snapshot usa IDs e
instantes fijos (`UUID(0, 2..4)`, `2026-08-10T14:59:00Z`) y el contexto fijo
`parserVersion = 1`, fingerprint SHA-256 de `golden-corpus-v1`, moneda de respaldo PEN y tasa
IGV de referencia 18 %.

### Resultado esperado

El recurso `expected-results` es un oracle cerrado: todos estos campos son obligatorios, aunque
su valor sea `null`:

- cabecera completa: RUC, razón social, tipo, número, fecha y moneda;
- `lineCount`, todas las líneas, descripción, cantidad y total en unidades menores;
- operación gravada, exonerada, inafecta, subtotal, IGV y total;
- confianza global;
- lista canónica de warnings, ordenada y sin duplicados.

La comparación es igualdad integral de la data class. Los warnings son un conjunto exacto: no
se aceptan códigos inesperados ni se confunden con un oracle de subconjunto.

## Los siete casos

| Caso | Qué mide |
| --- | --- |
| `factura-limpia-basica` | Cabecera, una línea y resumen con IGV 18 %. |
| `factura-multilinea` | Continuación de descripción, dos líneas y aritmética 72.00 + 12.96 = 84.96. |
| `boleta-sin-igv` | Boleta exonerada, sin campo IGV. |
| `factura-decimales-coma` | Coma decimal en cantidad e importes. |
| `factura-miles-y-decimales` | Separador de miles con coma y decimales con punto. |
| `captura-borrosa-baja-confianza` | Blur real del PNG y OCR esperado a 500 ‰; confianza global `LOW`. |
| `captura-inclinada` | Skew real del PNG y OCR esperado con paralelogramos solo por esquinas. |

## Ejecución e informe

Nivel JVM hermético:

```bash
./gradlew --no-daemon :app:testLocalDebugUnitTest \
  --tests "com.facturastock.app.corpus.*"
```

El test materializa y conserva evidencia en:

```text
app/build/reports/golden-corpus/input-images/<id>.png
app/build/reports/golden-corpus/actual-results/<id>.json
app/build/reports/golden-corpus/golden-corpus-report.md
```

El informe identifica por caso las tres entradas, compara campo por campo y falla ante cualquier
diferencia, incluidos valores inesperados que antes quedaban fuera del oracle.

El smoke Android requiere dispositivo o emulador y se ejecuta por separado:

```bash
./gradlew :app:connectedLocalDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.facturastock.app.data.ocr.MlKitInvoiceTextRecognizerTest
```

## Añadir un caso

1. Crear el manifiesto `cases/<id>.json`.
2. Crear `expected-ocr/<id>.json` con una expectativa sintética explícita.
3. Crear `expected-results/<id>.json` con el resultado cerrado completo, incluidos `null` y todos
   los warnings exactos.
4. Ejecutar el corpus y revisar el diff y `actual-results/<id>.json`. Nunca se actualiza el oracle
   sin justificar primero el cambio de contrato del parser.

La conducta normativa del parser se documenta en
[`INVOICE_PARSING.md`](INVOICE_PARSING.md),
[`INVOICE_HEADER_PARSING.md`](INVOICE_HEADER_PARSING.md) y
[`PERUVIAN_NORMALIZATION.md`](PERUVIAN_NORMALIZATION.md).

## Cobertura del dominio con Kover

El plugin Kover 0.9.1 está aplicado al módulo `:app` y filtrado a
`com.facturastock.app.domain.*` en `app/build.gradle.kts`. La regla «Cobertura mínima del dominio
crítico» exige al menos **80 % de líneas cubiertas** (`LINE`, `COVERED_PERCENTAGE`).

```bash
./gradlew :app:koverXmlReportLocalDebug :app:koverHtmlReportLocalDebug
./gradlew :app:koverVerifyLocalDebug
```

Los informes quedan en `app/build/reports/kover/`. La verificación falla si la cobertura medida
del dominio crítico cae por debajo del umbral; la documentación no congela un porcentaje puntual
que pueda quedar obsoleto.
