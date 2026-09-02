# Auditoría Fase D — Revisión humana y preparación (prompts 24–26)

Fecha: 21 de agosto de 2026 (`America/Lima`). Revisión **de código, no de compilación**: este equipo no
tiene JDK (`java -version` responde «Unable to locate a Java Runtime»), así que ninguna prueba se
ejecutó aquí. Continúa la [auditoría de Fase C](2026-08-21-fase-c-auditoria.md).

La capa está implementada y es madura: `feature/linereview` (5 archivos, 2633 líneas),
`feature/linking` (6 archivos, 2213) y `feature/summary` (6 archivos, 1540), sobre
`ReviewInvoiceLinesUseCases.kt` (728), `PreparePurchaseUseCase.kt` (546), `ProductMatchingUseCase.kt`
(359), `CreateLinkedProductUseCase.kt` (201) y el modelo puro `PreparedPurchase.kt` (215).

El prompt 23 no venía en el enunciado recibido, así que esta auditoría cubre 24, 25 y 26. La pantalla
de prompt 26 es `feature/summary`, no `feature/preparation`: esa última es la confirmación posterior
(duplicados y anulación de sobrescritura), que pertenece a otra fase.

## Resultado por prompt

| # | Prompt | Estado | Base de la conclusión |
| --- | --- | ---: | --- |
| 24 | Crear editor móvil de líneas | CUMPLE | La lista de revisión de `InvoiceLineReviewScreen.kt` es un solo `LazyColumn` (línea 125) con `key = { line -> line.lineId.value }` (154) y `contentType`, más `rememberLazyListState` (94), así que la identidad no es el índice; el segundo `LazyColumn` del archivo (823) es el selector de unidad dentro de `LineEditorDialog` y no compite con la lista. El contrato declara los ocho campos editables exactos en `FieldId` (descripción, código, cantidad, unidad, costo, descuento, IGV, total) y `ValueOrigin { OCR, CALCULATED, WRITTEN, MISSING }` conservando `ocrValue` y `calculatedValue` junto al valor efectivo, de modo que las tres procedencias se distinguen sin sobrescribirse. `MAX_LINES = 100` acota «agregar»; eliminar exige `RequestDelete`→`ConfirmDelete` y `RestoreDeletedLine` revive el tombstone durable; `MoveLineUp`/`MoveLineDown` reordenan; `visibleLines` combina búsqueda sin tildes (`normalizeForSearch`: NFD + `\p{M}+` + `lowercase(Locale.ROOT)`) con el filtro `pendingOnly`. La persistencia por cambio pasa por `SaveInvoiceLinesEditUseCase` y la rotación por las claves de `SavedStateHandle` (consulta, filtro, línea en edición, candidata a borrar, último borrado). 27 pruebas unitarias entre `InvoiceLineReviewViewModelTest` (10), `InvoiceLineReviewContractTest` (4), `InvoiceLineReviewUseCasesTest` (6), `InvoiceLinesEditTest` (4) y `InvoiceLinesReviewPersistenceRegressionTest` (3), más 5 instrumentadas |
| 25 | Vincular o crear productos | CUMPLE tras la corrección | `ProductMatchingUseCase` ejecuta la cascada en el orden pedido: `exactByBarcode → exactByAlias(código) → exactByAlias(nombre) → exactBySku → exactByName → fuzzySuggestions`. La difusa nunca se auto-selecciona: llega como `Suggestions`, jamás como `AutoLinked`. `MAX_CANDIDATES = 5` y cada candidato viaja con su `ProductMatchReason`, que la pantalla traduce a un chip visible (`ReasonChip`, `ProductLinkingScreen.kt:382`; textos `linking_reason_*` en `strings.xml:707-712`). Por línea hay buscar/vincular (`SearchChanged`, `CandidateConfirmed`, `ChangeLink`), crear (`OpenCreateForm`…`CreateSubmitted`) y dejar pendiente (`SkipLine`). `CreateForm` pide nombre, unidad, SKU y barcode opcionales, unidad de compra y factor; `NewLinkedProduct` exige `(purchaseUnitId == null) == (purchaseFactor == null)` con factor positivo y `purchaseEquivalence` muestra la caja de 12 como 12 unidades antes de guardar. Los duplicados se detectan por barcode → SKU → nombre normalizado y el alias solo se persiste tras una `ConfirmedSupplierAlias`. 31 pruebas previas (`ProductMatchingUseCaseTest` 12, `CreateLinkedProductUseCaseTest` 10, `ProductLinkingViewModelTest` 9) y 4 instrumentadas. **Faltaba** la prueba del ordenamiento difuso y la del tope de candidatos: se añadieron (ver abajo) |
| 26 | Validar y preparar la compra | CUMPLE tras la corrección | `PurchaseReadinessValidator.assess` reúne **todos** los bloqueos en una pasada —cabecera ausente o inválida, sin líneas, descripción, cantidad, importes, revisión pendiente, producto sin resolver y aceptación del redondeo— y `validateCatalogResolutions` comprueba además que producto y unidad sigan activos, sean del mismo negocio y la unidad coincida con la de inventario o de compra. `PreparedPurchase` es inmutable y congela proveedor, documento, moneda, líneas con `productId`/`unitId`, totales, `acceptedWarnings`, `preparedAt` y `logicalHash`; el hash es SHA-256 del contenido canónico **sin** marcas de tiempo, así que preparar dos veces produce la misma lógica y `AlreadyPrepared` sin reescritura. La publicación es una transacción con CAS `NEEDS_REVIEW → READY_TO_POST` verificando `expectedDraftUpdatedAt` y las revisiones de cabecera y líneas. El resumen se muestra en `feature/summary` con hash, advertencias y las dos acciones explícitas (`REOPEN`, `REGISTER`). Nada mueve inventario: la publicación de stock es una etapa posterior. 27 pruebas previas (`PreparePurchaseUseCaseTest` 14, `PreparedPurchaseTest` 3, `PreparedPurchaseCodecTest` 5, `PurchaseSummaryViewModelTest` 5) y 3 instrumentadas. **Faltaba** la prueba directa de los invariantes de la instantánea: se añadió (ver abajo) |

### Criterios uno a uno

- **24.** CRUD y reordenamiento sobreviven al reinicio: `delete is confirmed then its durable tombstone
  restores the same stable id after restart` y `reorder is persisted and a fresh ViewModel reads the
  same stable order`. Cien líneas se desplazan con fluidez: `one hundred active lines disable add
  without changing the durable revision`, `one hundred stable lines remain addressable without using
  their list index as identity` y la instrumentada
  `oneHundredStableCardsScrollLazilyWhileTheExactSummaryStaysFixed`. La diferencia se recalcula al
  instante y con signo: `summary reports an exact signed three-cent difference`. Edición, filtro,
  eliminación y restauración: `pending filter distinguishes unresolved OCR from a human confirmation`,
  `search ignores case and accents and keeps the persisted order` y
  `written contributor selects the calculation while OCR and written values stay separate`.
- **25.** Exactos primero: `barcode exacto vincula en primer lugar con confianza maxima`,
  `codigo de proveedor resuelve por alias antes que por SKU`, `SKU exacto vincula cuando no hay alias`,
  `nombre exacto vincula cuando no hay codigos ni alias` y `busqueda manual lista exactos primero y
  acota a cinco`. Ambiguos exigen elección: `nombre exacto duplicado exige eleccion`, `alias de varios
  proveedores sin preferencia exige eleccion` y `sugerencia difusa nunca se selecciona
  automaticamente`. Inexistente: `producto inexistente devuelve NoMatch`. Alias futuro: `alias futuro
  resuelve sola una linea repetida tras la confirmacion`. Crear no abandona la revisión: `crear un
  producto cierra el dialogo vincula la linea y conserva el resto`. Caja de 12: `crea producto con
  unidad de compra y convierte caja de 12 a 12 unidades`.
- **26.** Válido llega a READY: `compra valida llega a READY_TO_POST con instantanea congelada`.
  Inválido enumera todos los bloqueos: `invalida enumera todos los bloqueos a la vez`, más
  `sin lineas activas bloquea con NO_LINES`, `linea sin importe total bloquea la preparacion`,
  `linea pendiente de confirmacion bloquea aunque sus campos sean validos` y `producto inexistente o
  unidad ajena no se consideran resueltos`. Idempotencia: `preparar dos veces sin cambios produce la
  misma logica` y `hash logico identico para el mismo contenido aunque cambie el timestamp`. Editar
  invalida: `volver a editar invalida la preparacion y la nueva preparacion cambia el hash` y
  `volver a editar invalidates the snapshot before navigation is emitted`. Stock intacto:
  `preparar no toca el catalogo ni mueve stock` y `registrar compra only opens confirmation and keeps
  the prepared snapshot untouched`.

## Lo que faltaba y se aplicó

Los tres prompts estaban implementados. Los huecos reales eran de cobertura sobre invariantes que
**sostienen** un criterio explícito del enunciado, más un invariante documentado pero no exigido.

1. **Ordenamiento difuso sin prueba propia (Prompt 25: «hasta cinco candidatos y por qué
   coinciden»).** `ProductNameSimilarity` decide qué se sugiere y en qué orden, y no tenía ninguna
   prueba: un `grep` de `ProductNameSimilarity` bajo `app/src/test` no devolvía nada. Se añadió
   [`ProductNameSimilarityTest.kt`](../../app/src/test/java/com/facturastock/app/domain/usecase/ProductNameSimilarityTest.kt)
   con 7 pruebas: un nombre idéntico salvo tildes, mayúsculas y espacios puntúa 1000; un texto vacío o
   en blanco puntúa 0; descripciones ajenas quedan bajo `MIN_FUZZY_SCORE_PERMILLE`; un nombre de
   catálogo que extiende la descripción de la factura cruza el umbral; la similitud es simétrica; una
   letra sola no cuenta como token informativo; y la puntuación es determinista y vive en 0..1000.
2. **Tope de candidatos documentado pero no exigido (Prompt 25: «hasta cinco candidatos»).** El KDoc
   de `ProductLinkingContract.State.candidates` prometía «nunca más de 5», pero el `init` no lo
   comprobaba: la promesa dependía de que cada camino del ViewModel recordara acotar. Se añadieron dos
   `require` —tope de `ProductMatchingUseCase.MAX_CANDIDATES` y ningún producto repetido en la misma
   línea— y se creó
   [`ProductLinkingContractTest.kt`](../../app/src/test/java/com/facturastock/app/feature/linking/ProductLinkingContractTest.kt)
   con 8 pruebas: el tope y la unicidad de producto; claves y posiciones de línea estables y únicas;
   dejar pendiente resuelve la línea pero una sin coincidencia no deja continuar; una operación en
   curso o una carga pendiente suspenden el avance; la línea actual se ubica por su clave y no por su
   índice; crear exige nombre y unidad; la unidad de compra y su factor se definen juntos y en
   positivo; y una caja de doce declara doce unidades antes de guardar.
3. **Invariantes de la instantánea congelada (Prompt 26: «`PreparedPurchase` inmutable»).**
   `PreparedPurchaseTest` cubría el hash y el mínimo de una línea, pero no el resto del contrato que
   impide publicar una instantánea incoherente. Se añadieron 6 pruebas a
   [`PreparedPurchaseTest.kt`](../../app/src/test/java/com/facturastock/app/domain/model/PreparedPurchaseTest.kt):
   posiciones correlativas desde cero y sin líneas repetidas; una línea congelada necesita descripción
   y confianza en rango; ningún importe —total, total de línea, costo unitario o ajuste— escapa de la
   moneda del documento; `preparedAt` no puede ser anterior al epoch; un ajuste aceptado exige monto no
   nulo y motivo recortado de longitud suficiente; y solo un motivo válido habilita la aceptación del
   redondeo (`isValidReason`, el predicado del bloqueo `ADJUSTMENT_REASON_REQUIRED`).

Los tres archivos de prueba son de dominio o de contrato puro: sin `Float`/`Double`, sin registro y sin
dependencias de Android; el `require` añadido vive en la capa de presentación y no toca el dominio.

## Decisiones sostenidas sin cambio

1. **`ProductNameSimilarity` conserva su aritmética `Double`.** Vive en `domain` y pasa
   `verifyDomainBoundaries` porque el verificador busca `\b(?:Float|Double)\b` y `toDouble()` no
   ofrece frontera de palabra antes de la D. No es una violación del espíritu de la regla: la
   puntuación solo **ordena** sugerencias y nunca participa en dinero ni en cantidades, que siguen en
   `Long` de unidades menores y `BigDecimal`. Reescribirla en enteros cambiaría el orden de los
   candidatos ya observado por las pruebas sin ganar exactitud donde importa. Queda anotado como deuda
   consciente.
2. **`NO_MATCH` no cuenta como resuelto; `SKIPPED` sí.** `RESOLVED_STATUSES` incluye `AUTO_LINKED`,
   `CONFIRMED` y `SKIPPED`. Dejar pendiente es una decisión humana explícita y el enunciado la admite;
   una línea sin coincidencia todavía no ha recibido ninguna, así que no debe habilitar «continuar».
3. **La preparación no se auto-invalida en silencio: la edición está cerrada mientras está
   congelada.** `RoomInvoiceLinesReviewRepository` rechaza un guardado cuyo borrador no esté en
   `NEEDS_REVIEW`, y toda mutación efectiva del borrador o de sus imágenes llama a
   `invalidatePreparedPurchaseForEdit`. Editar exige «Volver a editar» (`reopen`), que borra la
   instantánea y devuelve el borrador a `NEEDS_REVIEW`; así nunca existe un `READY_TO_POST` sobre
   contenido cambiado.
4. **`PreparePurchaseUseCase` no publica stock.** El enunciado lo pide de forma explícita y el KDoc lo
   declara. La instantánea solo congela; el asiento de inventario es una etapa posterior con su propia
   transacción.
5. **`AutoLinked.alternatives` sigue existiendo aunque la pantalla no lo use.** Un `grep` de
   `alternatives` en `feature/linking` no devuelve nada: cuando la cascada resuelve sola, la UI no
   ofrece rivales. El dato queda en el dominio para el audit trail y para una futura pantalla de
   cambio de enlace; eliminarlo perdería información ya calculada sin simplificar nada visible.

## No verificable en este entorno

Todo lo que exige Gradle: `testDebugUnitTest` —incluidas las 21 pruebas nuevas, que están **escritas
pero no ejecutadas**—, `connectedDebugAndroidTest`, `ciStaticAnalysis`, Spotless, Lint, Kover (el piso
del 80 % sobre `com.facturastock.app.domain.*`) y los diez verificadores del `build.gradle.kts`. Lo que
sí se pudo comprobar aquí de forma mecánica sobre los archivos tocados: balance de llaves y paréntesis,
ausencia de nombres de prueba duplicados, ninguna línea por encima de 120 columnas y que ningún camino
de producción construye hoy más de cinco candidatos ni repite producto, de modo que los `require`
añadidos no pueden romper una pantalla existente.

La evidencia previa en disco que respalda la ejecución de la suite está citada en
[`../ACEPTACION_V1.md`](../ACEPTACION_V1.md); no la produjo esta sesión.
