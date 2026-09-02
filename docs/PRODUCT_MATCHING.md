# Vinculación de productos y coincidencia contra el catálogo

`ProductMatchingUseCase` resuelve cada línea de factura contra el catálogo sin duplicados. La
cascada evalúa, en este orden, y se detiene en el primer nivel con coincidencias exactas:

1. **Código de barras exacto** (`BARCODE`, 1000‰): solo llega si el usuario lo escanea o escribe.
2. **Código de proveedor** (`SUPPLIER_CODE`, 1000‰): el código impreso en la factura contra los
   alias confirmados.
3. **Alias confirmado por descripción** (`CONFIRMED_ALIAS`, 950‰): la descripción impresa contra
   los alias. Todo alias persistido proviene de una confirmación humana previa; si el mismo texto
   apunta a productos de varios proveedores, se prefiere el proveedor de la factura y el resto
   queda como alternativa explicable. Sin preferencia clara, el resultado es `Ambiguous`.
4. **SKU** (`SKU`, 1000‰): el código impreso contra el SKU del catálogo.
5. **Nombre exacto** (`EXACT_NAME`, 900‰): igualdad sobre la forma normalizada (sin espacios
   extremos, espacios internos colapsados, minúsculas). Varios productos con el mismo nombre
   normalizado producen `Ambiguous`.
6. **Sugerencias difusas** (`SIMILAR_NAME`, 600–1000‰): solape de tokens más ratio de
   Levenshtein sobre el texto plegado (sin tildes), con umbral 600‰. **Nunca se seleccionan
   automáticamente.**

## Reglas de decisión

- Un exacto único produce `AutoLinked` y la interfaz lo aplica sin intervención; los exactos
  siempre aparecen primero y la salida se acota a cinco candidatos, cada uno con su razón.
- `Ambiguous` exige elección humana: la vinculación nunca adivina entre exactos rivales.
- Los productos `ARCHIVED` no se proponen en ningún nivel.
- La búsqueda manual de la revisión de compras reúne exactos (en el orden de la cascada) seguidos
  de difusas, sin duplicar productos y con el mismo tope de cinco.
- La búsqueda manual de **ventas** usa la entrada separada `searchByName`: compara exclusivamente
  `Product.name`, entrega nombre exacto y similares con el mismo tope y nunca interpreta el texto
  como barcode, SKU ni alias de proveedor. El lector HID conserva su ruta exacta por barcode.

## Escenario demostrativo

`DemoPurchaseScenario` fija datos exclusivamente sintéticos para verificar la cascada sin una
dependencia de red: 36 líneas se vinculan por SKU con seis productos existentes, una descripción
exacta compartida por dos productos produce `Ambiguous` y una descripción deliberadamente no
sembrada queda disponible para creación. Los candidatos de nombre homónimo se ordenan por id
estable. Textos, códigos y límites están documentados en `docs/DEMO_SCENARIO.md`.

## Creación sin abandonar la revisión

`CreateLinkedProductUseCase` crea el producto desde la propia pantalla de vinculación. Antes de
insertar detecta duplicados por código de barras, SKU y nombre normalizado, en ese orden, y
devuelve el existente para que la interfaz ofrezca vincularlo en su lugar. El producto nuevo
registra nombre, unidad de inventario, precio de venta positivo con moneda, SKU/código de barras
opcionales y, opcionalmente, unidad
de compra con su factor hacia inventario: ambos se definen juntos o no se definen, y el factor es
positivo y exacto. `Product.inventoryUnitsFor` convierte cantidades de compra a inventario (una
caja con factor 12 convierte 1 caja en 12 unidades); sin factor la conversión es identidad.

## Alias confirmados

`SaveSupplierAliasUseCase` es la única vía que persiste alias, y solo se invoca tras una
confirmación humana de vinculación (elegir candidato o crear producto confirmado). Nunca se
guardan alias desde el OCR. Un alias idéntico al nombre normalizado del producto se omite por
redundante, y un choque de unicidad degrada a "no guardado" en lugar de fallar la revisión. Sin
proveedor resoluble (ni `supplierId` en el borrador ni RUC en el catálogo) no se guarda alias.

## Persistencia y concurrencia

Los enlaces (`linkedProductId`, `linkedUnitId`, `linkConfidence`) viven en el autosave revisionado
de líneas: cada confirmación pasa por el CAS por revisión de `SaveInvoiceLinesEditUseCase`, y un
`STALE_REVISION`/`CONFLICT` recarga el estado en lugar de pisar cambios ajenos. Los catálogos
productivos no se borran: se archivan conservando sus IDs y referencias históricas, y los
productos o unidades `ARCHIVED` dejan de proponerse para enlaces nuevos. Las FKs y guards de
persistencia siguen defendiendo datos legados o SQL de mantenimiento. La unidad de compra y su
factor se persisten juntos en `products`; el precio de venta se persiste como unidades menores y
código ISO de moneda, se propaga dentro del snapshot de vinculación y se crea atómicamente al
publicar la compra. Una unidad archivada puede seguir explicando compras
anteriores, pero no se ofrece al crear un producto nuevo.
