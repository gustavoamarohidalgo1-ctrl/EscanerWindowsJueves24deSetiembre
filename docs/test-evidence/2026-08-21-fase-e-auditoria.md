# Auditoría Fase E — Compra e inventario (prompts 27–35)

Fecha: 21 de agosto de 2026 (`America/Lima`). Esta evidencia corresponde a una revisión de código
y a ejecuciones automatizadas en JVM y en un AVD Pixel 10a, API 37. No es evidencia de piloto con
personas, despliegue Firebase de producción ni publicación en Google Play.

La auditoría comenzó sobre una implementación amplia, pero encontró brechas reales en los prompts
29, 30, 31 y 32. Esas brechas se corrigieron durante esta misma revisión. El resultado final usa
`FacturaStockDatabase` **v18**, conserva los esquemas exportados `1.json`…`18.json` y mantiene las
migraciones históricas explícitas.

## Matriz final

| # | Resultado | Evidencia del contrato final |
| --- | --- | --- |
| 27 | **CUMPLE** | Existen las seis entidades solicitadas, con claves foráneas `RESTRICT`, unicidad documental, `sourceDraftId` único, claves idempotentes únicas y libro de movimientos append-only. `MIGRATION_17_18` añade `purchase_lines.productProvenance` con `UNKNOWN_LEGACY`, sin reescribir borradores ni fingir procedencia histórica. El esquema 18 está exportado y las 17 rutas de migración hacia v18 pasan. |
| 28 | **CUMPLE** | Proveedores, productos, unidades y almacenes tienen lista, búsqueda, alta, edición, detalle y archivado/restauración offline. No hay borrado destructivo; las referencias históricas usan claves foráneas restrictivas. SKU, barcode, RUC, código de unidad y nombre de almacén tienen unicidad por negocio. Existe entrada manual cuando OCR no resuelve y una prueba Room con 5.000 productos. |
| 29 | **CUMPLE tras corrección** | Cada línea conserva una decisión tributaria explícita (`INCLUDED`, `EXCLUDED`, `EXEMPT`) y su evidencia; `UNKNOWN`, IGV ausente en una línea gravada y `EXEMPT` con IGV positivo bloquean la preparación. El costo leído y el aplicado siguen separados. `InventoryCostingService` usa `BigDecimal`, factor de compra, descuento, política neta/bruta y redondeo explícito, incluidos saldo cero, negativo y cantidades fraccionarias. |
| 30 | **CUMPLE tras corrección** | La coincidencia principal normaliza negocio, proveedor/RUC, tipo, serie y número; fecha, total e imagen son señales secundarias. Un exacto abre y bloquea contra la compra existente. La excepción exige motivo y un rol obtenido fuera de la UI: OWNER/ADMIN en cloud y OWNER local. Rol, target, actor y motivo quedan en la compra local; el evento append-only conserva solo un payload saneado. |
| 31 | **CUMPLE tras corrección** | La confirmación, incluidos productos creados durante la revisión, alias, compra, líneas, 1 movimiento por línea inventariable, saldos, auditoría, sesión y outbox, se ejecuta dentro de una sola transacción Room. El producto nuevo se prepara con UUID estable y no se inserta antes del commit. La idempotencia usa claves estables y un hash lógico canónico con campos de longitud prefijada; un fallo tardío no deja producto, alias, línea ni stock parcial. |
| 32 | **CUMPLE tras corrección** | Éxito, lista y detalle observan Room por `Flow`. La compra aparece de inmediato, conserva filtros al recrearse y muestra total, líneas, ajuste, sync, movimientos, auditoría e imagen según retención. La procedencia congelada permite mostrar por separado productos creados, vinculados y legados desconocidos, sin inferir el pasado. |
| 33 | **CUMPLE** | Inventario agrega por producto y almacén, muestra promedio, valor y alertas, y abre la compra desde cada movimiento. El diagnóstico recalcula desde el libro y compara contra la proyección cacheada sin corregirla ni editar movimientos históricos. Hay cobertura de múltiples almacenes, factores y divergencias de cantidad/costo. |
| 34 | **CUMPLE** | La anulación exige autorización, motivo, confirmación e impacto previo; marca `VOIDED`, inserta compensaciones idempotentes, actualiza saldos, auditoría y outbox en una transacción. El original, sus líneas y su imagen permanecen consultables. Una segunda anulación responde como ya aplicada y no crea otra reversa. |
| 35 | **CUMPLE** | El fixture sintético de una página produce exactamente 38 líneas: 36 existentes, 1 ambigua y 1 nueva. La suma calculada es S/97,80, el objetivo S/97,83 y el ajuste obligatorio +S/0,03 conserva motivo. El recorrido E2E resuelve impuestos/productos antes de confirmar, crea exactamente 38 movimientos y el doble toque converge a una sola compra. |

## Brechas encontradas y aplicadas

1. La revisión de líneas todavía permitía que la publicación asumiera impuesto excluido. Se añadió
   tratamiento y evidencia tributaria por línea a los modelos editables/preparados, codecs, Room,
   UI, fixture demo y validaciones de posting. Los snapshots legados se reabren como desconocidos:
   nunca se convierten silenciosamente en IGV cero.
2. Crear un producto durante el matching escribía catálogo antes de confirmar la compra. Ahora se
   guarda un `StagedPurchaseProduct` con identidad estable y el alta real ocurre dentro del mismo
   `withTransaction` que el resto del grafo. Las colisiones tardías de SKU/barcode abortan todo.
3. La autorización de excepciones de duplicado se apoyaba en un propietario fabricado. El flavor
   cloud usa la sesión y el último rol de membresía confirmado, falla cerrado ante enlace/rol
   ausente y reconcilia degradaciones o revocaciones; el flavor local declara su política OWNER.
4. Éxito y detalle agregaban creados y vinculados. `productProvenance` quedó congelado por línea y
   las consultas cuentan productos distintos en tres grupos: creados, existentes y legado
   desconocido.
5. El hash lógico preparado concatenaba texto libre con separadores. La forma vigente codifica
   cada campo con longitud y nulabilidad explícitas, manteniendo únicamente el hash legado para
   validar codecs históricos.
6. El respaldo cloud anterior no transportaba decisión tributaria, procedencia ni la excepción
   autorizada. Las compras nuevas usan payload v3/documento v2; el backend sigue aceptando el
   replay exacto v2/documento v1. El servidor vuelve a validar identidad, target, slot y rol de la
   excepción y no persiste el motivo libre.
7. La reconciliación cloud reducía la identidad documental a una cadena y podía escoger una compra
   arbitraria cuando existían principal y excepciones. Ahora usa una identidad estructurada,
   conserva ceros del correlativo, prefiere UUID exacto, acepta un candidato único y reporta dos o
   más candidatos como ambigüedad visible y auditada por conteo.

## Evidencia automatizada fresca

Las siguientes corridas se ejecutaron durante esta auditoría; los conteos corresponden a la salida
de Gradle/JUnit de cada grupo focal y no deben sumarse porque algunos tests aparecen en más de un
grupo.

| Grupo | Resultado |
| --- | --- |
| Costing, preparación, confirmación, producto staged y codecs | **70/70 JVM**, 0 fallos |
| Autorización local/cloud de duplicados y actualización de membresía | **49/49 JVM**, 0 fallos |
| Lecturas y conteos de procedencia de compra | **11/11 JVM**, 0 fallos |
| Reconciliación con identidad estructurada | **41/41 JVM** y **1/1 Compose**, 0 fallos; compilación de AndroidTest correcta |
| Preparación con contrato tributario final | **19/19 JVM**, 0 fallos |
| Mapper cloud v2/v1 + v3/v2 y errores cerrados | **11/11 JVM cloud** y **2/2 en dispositivo** para legado desconocido, 0 fallos |
| `postPurchase` y Emulator Suite completo | **32/32 focal** y **80/80 Auth + Firestore + Functions + Storage**, 0 fallos |
| Instrumentación transversal de Fase E, incluida búsqueda Room con 5.000 productos | **26/26 en dispositivo**, 0 fallos |
| Posting Room + Prepared Room + rutas `1→18` + compras + demo exacta + ML Kit real | **96/96 en Pixel 10a API 37**, 0 fallos u omitidos; BUILD SUCCESSFUL en 47 s |
| Formato del proyecto | `./gradlew --no-daemon spotlessCheck`: **BUILD SUCCESSFUL en 17 s** |
| Análisis estático agregado | `./gradlew --no-daemon ciStaticAnalysis`: **BUILD SUCCESSFUL en 12 s**; 12 tareas (8 ejecutadas, 4 `UP-TO-DATE`), con Spotless, logging de Functions y 7 fronteras/políticas Android verdes |

El cierre JVM/lint/Kover se ejecutó con:

```bash
./gradlew --no-daemon \
  :app:testLocalDebugUnitTest \
  :app:testCloudDebugUnitTest \
  :app:koverVerifyLocalDebug \
  :app:lintLocalDebug \
  :app:lintCloudDebug
```

Resultado: **BUILD SUCCESSFUL en 2 min 09 s**. `localDebug` ejecutó **874/874** tests en 116
suites y `cloudDebug` **933/933** en 124 suites, sin fallos, errores ni omitidos. Kover verify pasó;
el XML regenerado con `:app:koverXmlReportLocalDebug` contabiliza **9.552/10.589 líneas = 90,21 %**
para un piso de 80 %. Lint local y cloud terminó con **0 errores y 52 advertencias** por flavor.

Los intentos agregados previos sí detectaron fixtures legacy que todavía omitían tax/procedencia.
Se corrigieron declarando datos explícitos en los fixtures, sin cambiar los defaults fail-closed ni
convertir `UNKNOWN`/`UNKNOWN_LEGACY` en valores aceptables para compras nuevas.

## Caveats y límites de esta evidencia

- El fixture y todos los datos usados son sintéticos. No se consultó SUNAT y la app no afirma haber
  validado externamente un RUC.
- La búsqueda Room con 5.000 productos sí formó parte de la instrumentación transversal 26/26; no
  constituye por sí sola una medición de rendimiento en toda combinación de hardware y carga.
- El protocolo cloud requiere desplegar primero Functions compatibles con payloads 2 y 3 y recién
  después distribuir la app que emite v3. No se modifica a ciegas una outbox v2 ni se rellena
  procedencia/impuesto histórico que Room no conoce.
- Ninguna de estas pruebas demuestra un backend Firebase productivo, credenciales reales, una pista
  Play, una factura real ni la ejecución humana del guion de piloto.
- Una reconciliación es diagnóstica: las ambigüedades se muestran, no se resuelven automáticamente, y
  ningún saldo local se corrige desde la nube.

## Conclusión de Fase E

Los contratos funcionales de los prompts 27–35 quedan implementados y cuentan con pruebas focales y
un cierre agregado JVM/lint/Kover verde. La declaración de release y de piloto sigue separada:
depende del despliegue controlado, las credenciales/tiendas reales y el guion humano documentado en
[PILOTO_CERRADO.md](../PILOTO_CERRADO.md).
