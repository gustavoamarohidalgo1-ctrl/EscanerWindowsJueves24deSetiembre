# Recuperación del lector — 19 de septiembre de 2026

El síntoma aclarado por el usuario es que el lector pita, la aplicación muestra productos similares y hay que escanear varias veces para obtener la coincidencia esperada. No se trata de productos que desaparezcan del catálogo. El cambio reconoce automáticamente una lectura incompleta cuando la evidencia del catálogo basta y pide confirmar el producto cuando puede corresponder a otra identidad.

La recuperación se incorpora a Ventas. Inventario y registro de productos mantienen sus búsquedas existentes. La política final reside en `app/src/main/java/com/facturastock/app/domain/usecase/BarcodeRecovery.kt`, compartida por el ViewModel y la validación transaccional de Room; sustituye a la utilidad anterior `SalesBarcodeRecovery.kt` de la capa de presentación.

## Qué lecturas se resuelven y cuáles requieren confirmación

Ventas consulta primero el código de barras exacto y después el SKU exacto. Una coincidencia exacta normalmente conserva ese producto, pero ahora se revisa un caso concreto de ambigüedad: un exacto numérico que no sea un GTIN válido puede ser también una lectura con una o dos cifras omitidas de otro producto cuyo código largo sí sea un GTIN válido. En ese caso se pide elegir; el exacto nunca se reemplaza automáticamente por el largo. Las opciones vendibles incluyen el exacto y los candidatos compatibles. La tarjeta del exacto dice «Código exacto registrado», sin describirlo como cero cifras ausentes. Los exactos GTIN válidos y los códigos personalizados sin esa evidencia concreta conservan su comportamiento normal.

Cuando no hay coincidencia exacta, la política automática aplica estas condiciones:

1. La lectura contiene únicamente dígitos ASCII y mide entre 6 y 14 caracteres. Para recuperar omisiones, el código corto debe conservar todos sus dígitos en el mismo orden dentro del largo. Se permiten una o dos cifras ausentes, incluso centrales y también cuando el código incompleto sea el guardado.
2. El código largo debe ser un GTIN de 8, 12, 13 o 14 dígitos con dígito de control válido. Dos GTIN válidos que representen identidades diferentes impiden la recuperación automática.
3. Debe quedar un único producto candidato en el catálogo completo del negocio. Para detectar competidores se consideran hasta tres omisiones antes de filtrar archivo, existencias, longitud estándar o checksum. Un producto agotado, archivado o con código defectuoso puede impedir que se elija otro. No se desempata por orden, stock ni menor diferencia.
4. Otro código de la misma longitud que difiera en una sustitución o en un intercambio de cifras adyacentes también impide la elección automática. Este veto incluye códigos de barras y SKU numéricos de otros productos, aunque estén agotados o archivados. Un SKU numérico de otro producto también veta la recuperación si es compatible por una a tres omisiones en cualquiera de los dos sentidos, o si representa el mismo GTIN con ceros iniciales. El SKU nunca genera por sí mismo un candidato automático. Un SKU del propio candidato no constituye otra identidad. Estos patrones sólo impiden recuperar: no autorizan corregir sustituciones o intercambios.
5. Se admite la equivalencia entre representaciones GTIN válidas que sólo difieran por ceros iniciales, comparándolas en 14 posiciones. Los códigos originales permanecen intactos. Esta equivalencia también exige unicidad y respeta los vetos anteriores.
6. El producto elegido debe estar activo y tener una ubicación vendible. Si no hay disponibilidad, se informa del problema; no se sustituye por otro producto que sí tenga stock.

Por ejemplo, `77536004930` conserva el orden de `7753176004930` después de omitir dos cifras centrales. Puede recuperarse si es el único candidato y no existe otro código que active los vetos. Tres omisiones, checksum inválido, códigos insuficientes o varias identidades mantienen una elección explícita cuando hay opciones disponibles.

## Catálogo fresco y validación al guardar

La decisión utiliza `ProductRepository.listForBusiness`, una lectura puntual del catálogo completo. No depende de que la proyección observada por la interfaz ya haya recibido un alta o cambio. Las sugerencias también se construyen desde ese catálogo fresco; de esta forma un producto recién guardado puede participar aunque su actualización visual vaya detrás. La disponibilidad se consulta por producto cuando hace falta.

La identidad inferida se vuelve a comprobar después de consultar existencias. Para guardar una línea, `SaleBarcodeRecoveryExpectation` transporta la lectura original, el barcode esperado del producto y su versión. `RoomSaleRepository` verifica ese código y esa versión y vuelve a evaluar la política sobre todos los productos del negocio dentro de la misma transacción que escribe la línea. Un competidor nuevo, una modificación del candidato o la pérdida de unicidad devuelve `BarcodeRecoveryChanged` antes de insertar. El ViewModel pasa entonces a revisión con el motivo de cambio de catálogo. Esta comprobación transaccional complementa las lecturas previas de la interfaz; no exige migraciones ni un índice persistente nuevo.

La guarda se aplica a las altas cuya identidad se infirió automáticamente. Una elección manual de producto tiene su propio flujo y sus validaciones habituales. Reconocer un artículo ya presente conserva la cantidad elegida: reescanear no agrega otra unidad automáticamente y vuelve a validar la inferencia antes de mostrar el reconocimiento.

Si la recuperación encuentra varios almacenes, se conserva su contexto —lectura, candidato, negocio, carrito y generación de sesión— mientras se elige la ubicación. La confirmación vuelve a validar identidad y versión; el guardado conserva la misma prueba transaccional. Cambiar el código o introducir otro candidato durante el selector obliga a revisar. Elegir un almacén ya no borra la procedencia de recuperación.

La pausa o detención de la sesión del lector invalida las lecturas pendientes y cierra una selección de almacén nacida de recuperación, para que reanudar no permita confirmar una inferencia de la sesión anterior. Salir del modo lector, cambiar de negocio o reemplazar el carrito también invalida el contexto. La edición de un campo pausa la captura mientras conserva los controles normales del formulario.

Se mantiene la corrección de la carga inicial: el carrito puede abrirse antes de llegar la primera proyección del catálogo, pero la cola espera esa proyección antes de procesar sus lecturas. Un fallo de esa carga vacía la cola y deja visible el error. La consulta puntual posterior cubre además los cambios del catálogo que todavía no se han reflejado en la interfaz.

## Información visible y accesibilidad

La franja sigue usando sólo dos líneas, con texto completo en semántica cuando se recorta visualmente. Esto conserva altura para el carrito en una ventana pequeña y con fuente al 200 %.

- Al recuperar y agregar: «Recuperado: [nombre]», seguido de cantidad y almacén. Si ya estaba en el carrito: «Ya en venta (recuperado): [nombre]». La lectura original queda en la descripción accesible. Una lectura exacta normal posterior elimina el aviso de recuperación.
- Mientras se elige almacén: «Recuperado: [nombre]» y «Elige un almacén; aún no se agregó». El selector muestra nombre y código guardado del producto además de ubicaciones y stock. Tras elegir, la confirmación conserva la etiqueta de recuperación y la lectura original accesible.
- Con motivo `AMBIGUOUS`: «Confirma el producto» y «La lectura coincide con otros códigos». La ayuda explica que también se consideran productos agotados o archivados, aunque sólo se muestre una opción vendible.
- Con motivo `CATALOG_CHANGED`: «Revisa el producto» y «El catálogo cambió durante la lectura».
- Sin un motivo especial se conserva el flujo anterior de código desconocido y selección. Los errores y las pausas tienen prioridad sobre una confirmación anterior; no se presenta el éxito de otra lectura como resultado de la actual.

No se crean alias, nuevas tablas ni asociaciones permanentes para recordar lecturas incompletas. No se reescriben automáticamente códigos guardados. Las reglas de checksum y ceros iniciales siguen las referencias consultadas en la etapa anterior: [cálculo del dígito de control de GS1](https://www.gs1.org/services/how-calculate-check-digit-manually) y [representación GTIN en 14 posiciones](https://support.gs1.org/support/solutions/articles/43000734355-what-is-the-required-format-of-gtin-in-gs1-edi-standards-). Verifican estructura, no qué envase está delante del lector.

## Precisión y límites del refinamiento

La evidencia está en [PRECISION_AUDIT.md](../build/reports/scanner-refinement-2026-09-19/PRECISION_AUDIT.md), con scripts y resultados agregados reproducibles. Se trabajó sobre la copia SQLite del respaldo en modo de sólo lectura, se comprobó que su SHA-256 no cambiara y no se publicaron nombres, IDs ni códigos reales del catálogo.

La auditoría amplió el modelo de error después de encontrar 139 sustituciones y 9 intercambios sintéticos que la política anterior podía atribuir a otro producto. La nueva política los incorpora como veto. En las 33.933 omisiones de una o dos cifras, las recuperaciones del producto de origen pasan de 24.391 a 24.261: 130 casos adicionales quedan para revisión.

El conjunto ampliado contiene **167.712 casos**: 33.933 omisiones, 61.542 sustituciones, 5.205 intercambios adyacentes y 67.032 inserciones. La recuperación aproximada refinada no eligió otra identidad en ese conjunto. Es evidencia limitada a esos datos y modelos sintéticos; no mide una tasa de acierto del lector físico ni garantiza el flujo entero.

Se evaluaron por separado 51 lecturas por omisión que coincidían exactamente con el código guardado de otro producto. La revisión de exactos sospechosos detecta 47; **cuatro colisiones exactas persisten**. Una corresponde a un exacto GTIN válido diferente. El refinamiento conserva esos exactos cuando no dispone de evidencia suficiente para solicitar revisión. Tampoco cubre productos todavía desconocidos, errores múltiples arbitrarios ni demuestra la identidad física del artículo. No se promete certeza absoluta.

El benchmark sobre bytecode Kotlin real mide únicamente los comparadores en JDK 21 del host. En el bytecode final, con 637 productos sintéticos la mediana de recuperación fue 0,086 ms; con 10.000, 1,348 ms. El host compartía recursos con Gradle y Android; el p95 refleja esa contención y está documentado en el informe, sin interpretarlo como una regresión. Excluye Room, preparación de datos, UI y hardware del lector, por lo que no representa la latencia final en la tablet. Los agregados y límites completos están en el informe de precisión.

## Validación de la etapa anterior

Los siguientes resultados pertenecen al primer cambio, guardado en `build/reports/scanner-first-read-2026-09-19/`; no certifican por sí solos el refinamiento descrito arriba:

- 1.789 pruebas unitarias local y 1.967 cloud sin fallos, incluidas 14 de la política anterior, ocho de integración y siete de carga inicial.
- 72 pruebas Android ejecutadas: 71 aprobadas. Incluyeron recuperación HID con dos omisiones, relectura sin duplicados, cobro de S/8,50, stock final de nueve unidades, invariancia del código, foco, cola, sugerencias y fuente al 200 %.
- Una prueba de reapertura de Reportes después de anular una venta agotó la espera de 15 segundos. La repetición aislada y el APK original del respaldo, instalado sólo en el emulador, reprodujeron el fallo después de verificar anulación, stock e historial. Ese problema no quedó resuelto por el cambio del lector.
- `ciStaticAnalysis` aprobado y lint local/cloud sin errores, con 75 advertencias por variante. Se compilaron APK y APK de pruebas y se comprobó que firma, identificador, versión, SDK y permisos coincidían con el original.
- Una prueba inicial con tres líneas de feedback dejó al carrito sin altura con fuente grande; se corrigió a dos líneas y la prueba posterior comprobó visibilidad antes del desplazamiento.
- Se verificaron por SHA-256 los 23 archivos del respaldo y permanecieron intactos.

## Validación final del refinamiento

- 1.815 pruebas unitarias local y 1.993 cloud: **3.808 aprobadas**, sin fallos, errores ni pruebas omitidas. La política tiene 30 pruebas por variante; `SalesViewModelTest` tiene 149. Registros en `full-build-tests.log`, `unit-test-summary.json` y XML conservados.
- **106 pruebas Android aprobadas** en emulador aislado, nunca en la tablet. Incluyen lector HID/IME, flujos reales de venta, recuperación de dos omisiones con cobro y stock, relectura sin duplicar, foco, pantalla compacta al 200 %, motivos de selección y 28 pruebas de `RoomSaleRepositoryTest`, diez de ellas nuevas para la validación atómica. Registro `android-tests.log` y resumen `android-test-summary.json`.
- Se excluyó deliberadamente `ScannerSaleJourneyTest#reportVoidCancelsOnlyAfterConfirmationReturnsStockAndSurvivesRecreation`: su fallo ajeno a esta mejora ya se reprodujo con el APK original en la etapa anterior. No se presenta como aprobado ni resuelto; la evidencia es `../scanner-first-read-2026-09-19/android-report-original-apk.log`.
- Compilación de aplicación local, APK de pruebas y ambas variantes de pruebas JVM aprobada. `ciStaticAnalysis` aprobado con la base Git completa configurada. Lint local y cloud terminó con **cero errores y 75 advertencias por variante**, el mismo número que la etapa anterior. Logs finales: `full-build-tests.log`, `static-analysis.log` y `lint.log`.
- Se compararon firma, paquete, versión, SDK y permisos contra el APK del respaldo: coinciden. El hash del APK entregable coincide con la compilación probada. Evidencia en `apk-validation.json` y `APK-SHA256.txt`.
- Los 23 archivos del respaldo conservaron sus SHA-256. Durante la validación en emulador no se modificó la aplicación ni la base de datos de la tablet. El emulador aislado se cerró después de las pruebas.

El instalador final es `build/reports/scanner-refinement-2026-09-19/FacturaStock-lector-refinado.apk`. Esa carpeta contiene los registros, scripts y resúmenes de validación, incluido `validation-summary.json`, y sustituye al instalador anterior como entrega de este refinamiento. La mejora está implementada y probada. Posteriormente, el usuario autorizó su instalación en la tablet; se completó la actualización y apertura de la aplicación, según el apartado siguiente. La lectura con el dispositivo físico sigue pendiente de prueba por el usuario.

## Instalación autorizada en la tablet

El 19 de septiembre de 2026 se instaló el APK refinado en la Huawei AGS6-W09 mediante actualización (`adb install -r`), sin desinstalar ni borrar datos. El instalador devolvió `Success` y el SHA-256 del APK instalado coincide con el artefacto probado.

Antes de actualizar se guardó un respaldo nuevo en `/Users/gustavo/Desktop/BackUpPlis/Antes_instalar_lector_refinado_2026-09-19_11-26-02`. La base pasó `PRAGMA quick_check` y, después de la actualización, se compararon todas las filas de las 37 tablas: permanecieron idénticas. Cambiaron los bytes de los archivos SQLite/WAL entre las capturas, pero el contenido lógico se conservó íntegro.

Se abrió `MainActivity`, se comprobó que quedó como actividad reanudada y que el proceso permanecía activo, sin `FATAL EXCEPTION` en los registros de apertura. Los resultados y la comparación están en `build/reports/scanner-refinement-2026-09-19/tablet-install/`. No se simularon ventas ni se alteraron existencias en la tablet.
