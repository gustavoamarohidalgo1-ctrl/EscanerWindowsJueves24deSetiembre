# Escenario demostrativo reproducible

El escenario F035 ejercita el recorrido normal de una compra sin usar información real, red ni
un seed que salte importación, OCR o revisión. Su contrato canónico vive en
`DemoPurchaseScenario`; el catálogo y el generador local de la factura consumen los mismos
valores para evitar que una copia de texto cambie silenciosamente el resultado de matching.

## Evidencia sintética

- Negocio: `[DEMO] Bodega de demostración`, sin RUC.
- Proveedor: `[DEMO] Distribuidora Lima`, RUC ficticio con formato y checksum válidos
  `20111111112`.
- Documento: `F035-00000038`, fecha fija `14/08/2026`.
- Las 38 líneas, nombres, SKU, códigos de proveedor, importes e imagen se generan para la demo.
  No proceden de una factura, persona o empresa real.
- Cada registro mutable sembrado pertenece al negocio demo y sus ids hijos se derivan de
  `demoBusinessId + clave semántica`. Así el grafo es estable dentro de una ejecución y sigue
  aislado de una reentrada, cuyo `demoBusinessId` es distinto.

## Catálogo sembrado y resultado esperado

El catálogo conserva seis productos activos. Los dos primeros comparten exactamente el nombre
normalizado; sus SKU y códigos de barras siguen siendo distintos y únicos.

| Clave | Nombre | SKU | Resultado cuando llega su SKU |
| --- | --- | --- | --- |
| `product:ambiguous:a` | `[DEMO] Arroz extra 1 kg` | `DEMO-ARR-1-A` | `AutoLinked(SKU)` |
| `product:ambiguous:b` | `[DEMO] Arroz extra 1 kg` | `DEMO-ARR-1-B` | `AutoLinked(SKU)` |
| `product:sugar` | `[DEMO] Azúcar rubia 1 kg` | `DEMO-AZU-1` | `AutoLinked(SKU)` |
| `product:oil` | `[DEMO] Aceite vegetal a granel` | `DEMO-ACE-LT` | `AutoLinked(SKU)` |
| `product:existing` | `[DEMO] Leche evaporada 400 g` | `DEMO-LEC-400` | `AutoLinked(SKU)` |
| `product:soda` | `[DEMO] Gaseosa personal 500 ml` | `DEMO-GAS-500` | `AutoLinked(SKU)` |

La factura controla los tres desenlaces sin alterar la cascada productiva:

1. Las líneas 1–36 ciclan los seis SKU anteriores. El SKU exacto precede al nombre y vincula
   cada línea con un único producto existente.
2. La línea 37 usa código ajeno `PROV-AMB-037` y descripción
   `[DEMO] Arroz extra 1 kg`. Al no existir barcode, alias ni SKU coincidente, el nombre exacto
   encuentra dos productos y devuelve `Ambiguous`; la elección humana es obligatoria.
3. La línea 38 usa código ajeno `PROV-NEW-038` y descripción
   `[DEMO] Quinua tricolor 500 g`. Esa combinación no se siembra, por lo que nunca se
   auto-vincula y el usuario puede crear el producto desde la revisión.

La consulta por nombre exacto ordena candidatos por `productId`, de modo que incluso los dos
homónimos se presentan de forma estable. El producto nuevo solo aparece después de una creación
confirmada por el usuario; no se anticipa en el seed.

## Documento y conciliación controlada

`DemoInvoiceFixture` y `DemoInvoiceImageGenerator` comparten una única definición geométrica. El
resultado es un JPEG de **una página** (1600 × 2600 px) con exactamente **38 filas**, subtotal
**S/82,88**, IGV **S/14,92** y estos dos totales deliberadamente distintos:

| Concepto | Importe |
| --- | ---: |
| Suma exacta de las 38 líneas | S/97,80 |
| Total objetivo leído del comprobante | S/97,83 |
| Ajuste requerido (`líneas + ajuste = total`) | **+S/0,03** |

La diferencia no se corrige silenciosamente. Preparación permanece bloqueada hasta que estén
resueltos los 38 productos, la persona marque la aceptación y escriba un motivo de 10 a 500
caracteres. El escenario usa el motivo sintético
`[DEMO] Diferencia controlada entre suma calculada y total del comprobante`.

Desde el codec v3, importe y motivo forman parte de `PreparedPurchase.logicalHash`, sobreviven a
recreación/reintento, viajan juntos en auditoría y outbox y se muestran en Resumen, Confirmación,
Éxito y Detalle. Las instantáneas v2 se siguen leyendo por compatibilidad, pero no inventan un
motivo que nunca fue capturado.

## Entrada y límites

El acceso parte del CTA **Abrir factura demo de 38 líneas**, visible únicamente en Ajustes con
modo demostración activo. `StartDemoInvoiceScenarioUseCase` deriva un `draftId` estable del
negocio demo y un `imageId` estable para la página canónica, e importa el JPEG mediante la misma
entrada de bytes de cámara y el mismo
almacenamiento privado. `Ready(draftId, captureId)` abre la vista previa tipada; desde allí OCR,
parsing y revisión continúan por el flujo ordinario. Si ese borrador ya fue publicado,
`AlreadyCompleted(purchaseId)` abre su detalle de solo lectura. Un mutex de caso de uso y la
compuerta busy del ViewModel evitan que el doble toque añada otra página o emita dos
navegaciones.

No se expone un deep link mutable, no se inserta un borrador ya parseado y no se publica una
compra automáticamente. `NotInDemoMode` y cualquier conflicto de pertenencia/cardinalidad se
muestran como error local y no navegan.

## Pipeline y evidencia automatizada

La demo determinista conserva las mismas fronteras productivas:

1. el CTA materializa el borrador `CREATED`, genera el JPEG canónico y lo importa por
   `ImportDraftImageUseCase`;
2. `DemoAwareInvoiceTextRecognizer` exige tanto el borrador como el `imageId` canónico para usar
   el OCR fake geométrico; si la página se reemplaza, el nuevo ID fuerza el camino ML Kit;
3. parser, revisiones, matching, preparación, confirmación, lecturas de Compras e Inventario usan
   sus implementaciones reales y Room;
4. una prueba instrumentada separada entrega el mismo JPEG a ML Kit y exige texto reconocible
   cuando se ejecuta en un dispositivo con el modelo latino incluido.

La evidencia principal está en
`app/src/androidTest/java/com/facturastock/app/demo/DemoInvoiceEndToEndTest.kt`. Recorre captura,
OCR fake, parser, revisión, los tres resultados de matching, los bloqueos previos, el ajuste con
motivo, posting, lista, detalle e inventario. Al final exige una compra, 38 líneas publicadas,
**38 movimientos `PURCHASE`** y un reintento `AlreadyPosted` que no altera ninguno de esos
conteos. `DemoInvoiceFixtureTest` verifica de forma local la página, los 38 renglones y la
aritmética 9780 → 9783; las pruebas Compose verifican que el ajuste positivo y su motivo sean
visibles antes y después de publicar.

Salir del modo demo quita primero la selección activa y elimina el negocio sintético en cascada.
Los negocios reales nunca se sobrescriben y los textos `[DEMO]` hacen visible en toda pantalla
que la evidencia no es productiva.
