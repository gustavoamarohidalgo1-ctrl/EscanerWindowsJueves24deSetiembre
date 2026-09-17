# Análisis técnico de FacturaStock — 4 de septiembre de 2026

**Actualización posterior.** Este documento conserva el diagnóstico previo a los cambios. Las
correcciones y pruebas realizadas después se registran en
[Optimización del 4 de septiembre](OPTIMIZACION_2026-09-04.md); varios hallazgos de decimales,
replay de saldos, finalización de respaldos y configuración local ya tienen correcciones.

**Dictamen.** FacturaStock tiene una base sólida para una aplicación Android de gestión comercial: dominio decimal, operaciones locales transaccionales, restricciones de integridad en SQLite, OCR local y una infraestructura de pruebas extensa. El estado actual necesita estabilización antes de confiarle existencias y costos reales sin supervisión. Los principales defectos están en el nuevo ingreso de productos desde facturas y en la coordinación entre confirmaciones remotas y persistencia local.

El problema principal no es la ausencia de arquitectura. Es que algunos recorridos nuevos no conservan las garantías que ya tienen las compras y ventas tradicionales: unidades y moneda explícitas, decisiones durables, publicación atómica e idempotencia del contenido completo.

**Alcance y método.** Se revisó el árbol de trabajo actual de `/Users/gustavo/Desktop/ProyectoMayda`, basado en el commit `0b2f23d8b33847086d08354fafc5f3134a31d508`, incluidos los cambios sin commit y archivos nuevos. La revisión se repartió entre Android/dominio, interfaz/OCR/escáner y backend/seguridad; se contrastó con compilación de pruebas, Lint, Kover y emuladores Firebase. Los informes anteriores se trataron como contexto, no como prueba de que el código actual estuviera correcto o incorrecto.

No se modificó código de la aplicación ni se desplegaron servicios. No se ejecutaron pruebas instrumentadas sobre la aplicación instalada en el emulador Android existente. Las pruebas Firebase usaron proyectos `demo-*` y datos sintéticos. Los hallazgos identifican cuándo la evidencia procede de ejecución, mocks o trazado estático; esto no es una certificación de producción.

**Qué producto hay realmente.** Es una aplicación Android nativa de compras, ventas, existencias y cuentas por cobrar, orientada a trabajo local y con respaldo/sincronización opcional. Incluye catálogos, captura de imágenes, OCR, interpretación de facturas, asociación de productos, preparación/publicación/anulación de compras, venta con lector HID, inventario por almacén, costos y reportes. Hay tres modalidades operativas relevantes:

| Modalidad | Autoridad y funcionamiento | Consideración |
| --- | --- | --- |
| `local` | Room y archivos privados; el manifiesto elimina INTERNET. | Debe funcionar sin configuración Firebase, pero la validación Gradle actual rompe ese contrato. |
| `cloud` con CALLABLES | Room mantiene la proyección del teléfono; Functions autoriza operaciones y mantiene inventario/ventas compartidos. | El estado de red puede ser incierto después de un commit remoto; esa incertidumbre necesita persistencia propia. |
| `cloudSpark` / SPARK_DIRECT | Transacciones del cliente Firestore y reglas de propietario único. | Es otro modelo de permisos y capacidades; no tiene equivalencia completa con CALLABLES. |

El nuevo recorrido OCR lleva a una pantalla de asociación que registra ajustes de inventario y elimina el borrador. No equivale a publicar una compra mediante el flujo completo de preparación. Esa decisión tiene consecuencias para unidades, costos, trazabilidad documental y recuperación. La documentación debe describir este comportamiento de forma consistente.

**Tamaño y organización comprobados.** Hay dos módulos Gradle (`app`, `benchmark`). `app/src/main` contiene 526 archivos Kotlin y 116.320 líneas; `src/cloud`, 41 archivos y 11.422 líneas. Las fuentes JVM de pruebas suman 221 archivos Kotlin; las instrumentadas, 96. Functions contiene 11 archivos JavaScript productivos y 7.774 líneas. Room está en versión 27, con 32 entidades y esquemas históricos 1–27.

La separación UI → ViewModel → casos de uso → interfaces de repositorio → adaptadores está respaldada por verificaciones estáticas. Al vivir casi todo en `app`, las fronteras internas no son módulos independientes de compilación. Hay concentración de responsabilidades: `FacturaStockApp.kt` tiene 1.560 líneas, `SalesViewModel.kt` 1.476, `RoomSaleRepository.kt` 1.256, y `app/build.gradle.kts` 2.362. Estos tamaños son un indicador de costo de mantenimiento, no un defecto por sí mismos.

**Validación ejecutada en esta revisión.**

| Comprobación | Resultado observado |
| --- | --- |
| `:app:testLocalDebugUnitTest` | 1.435 pruebas; cero fallos, errores u omisiones. |
| `:app:testCloudDebugUnitTest` | 1.612 pruebas; cero fallos, errores u omisiones. |
| `:app:lintLocalDebug` y `:app:lintCloudDebug` | Ambas aprobaron; 63 advertencias y cero errores en cada variante. |
| `:app:koverVerifyLocalDebug` | Aprobó el mínimo configurado del 80 %. |
| Cobertura Kover del dominio | Líneas: 12.387/14.222 = 87,10 %. Ramas: 6.493/9.949 = 65,26 %. |
| Firebase Auth/Firestore/Functions/Storage | 160 pruebas aprobadas; una omitida, correspondiente a Spark. |
| Reglas Spark ejecutadas aparte | 13 pruebas aprobadas; cero omisiones. |
| Escáner de secretos del repositorio | No encontró credenciales de alta confianza ni archivos de clave prohibidos. |
| Políticas de release y assets Play | Aprobaron las comprobaciones ejecutadas. |
| Historia de esquemas Room | Append-only respecto de HEAD; sin cambios en esquemas generados. |
| `ruby scripts/test-release-contracts.rb` | Falló: todavía exige `StartupJourney.waitForHomeReady`. |
| Validación offline con configuración aislada sin Firebase | Falló en `verifyCloudSparkBoundaries`: exige `firebase.projectId`. |
| Reproducciones locales del backend | Tres casos con código productivo, repositorios/Storage simulados y red bloqueada. |

Las 3.047 ejecuciones JVM incluyen pruebas compartidas que se repiten entre variantes; no son 3.047 escenarios distintos. Kover mide únicamente `domain.*`, no toda la aplicación. Las advertencias de Lint incluyen recursos sin uso y avisos de versiones; no se interpretan aquí como vulnerabilidades confirmadas ni como una recomendación automática de actualización.

La ejecución local usó JBR 21 y Node 24.18.0. El proyecto declara Node 22 y CI configura ese runtime; la suite emulada pasó con Node 24, pero no se comprobó en esta sesión la equivalencia exacta con Node 22. Tampoco se validaron dispositivo físico HID, distribución firmada, Play Integrity real ni rendimiento en hardware.

Evidencia local: [resumen de resultados](/Users/gustavo/Desktop/ProyectoMayda/build/reports/project-analysis/2026-09-04/summary.json), [JUnit Firebase](/Users/gustavo/Desktop/ProyectoMayda/build/reports/project-analysis/2026-09-04/firebase.xml), [JUnit Spark](/Users/gustavo/Desktop/ProyectoMayda/build/reports/project-analysis/2026-09-04/spark.xml), [Kover](/Users/gustavo/Desktop/ProyectoMayda/app/build/reports/kover/reportLocalDebug.xml), [reproducciones backend](/Users/gustavo/Desktop/ProyectoMayda/build/reports/project-analysis/2026-09-04/backend-repro.txt). Las evidencias bajo `build/` son artefactos locales ignorados por Git.

**Hallazgos prioritarios.** P1 indica riesgo importante para integridad o recuperación que conviene corregir antes de utilizar el flujo afectado con datos reales; P2 indica un defecto funcional u operativo relevante. No se ha demostrado una vía de acceso entre negocios ajenos.

**1. P1 — El formulario nuevo elimina la coma decimal y cambia el valor.**

Los campos precio y cantidad de la pantalla de asociación conservan sólo dígitos y punto. Escribir o pegar `12,50` se transforma en `1250`; `1,5` se transforma en `15`. Después el ViewModel acepta el resultado como decimal. Es un cambio silencioso de magnitud en una entrada válida para un usuario que emplee coma decimal.

Evidencia: trazado directo de [InvoiceMatchingScreen.kt:590](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/matching/InvoiceMatchingScreen.kt:590), cantidad en línea 602. No se ejecutó la interacción en dispositivo. Ya existe un tratamiento reutilizable de separadores en [ProductFormValidation.kt:11](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/catalogs/ProductFormValidation.kt:11).

Corrección: conservar el texto introducido, normalizar con el parser compartido y mostrar validación ante ambigüedad. Añadir regresiones con coma, punto y pegado de texto; evitar borrar caracteres que cambian el significado numérico.

**2. P1 — La asociación de factura pierde unidad y moneda antes de afectar inventario.**

`MatchScannedInvoiceLinesUseCase` extrae el número de cantidad y `UnitCost.amount`, descartando la información de unidad y moneda. `ConfirmInvoiceMatchingUseCase` añade directamente ese número y utiliza `config.currency`.

Ejemplos derivados del código: una factura de dos cajas de doce unidades añade 2 al inventario base, en lugar de resolver la conversión a 24; un costo OCR de 10 USD puede almacenarse como 10 PEN si ésa es la moneda configurada. El costo por caja tampoco se convierte a costo por unidad. Multiplicar siempre por `purchaseFactor` no basta: primero hay que identificar en qué unidad viene la factura.

Evidencia estática: [MatchScannedInvoiceLinesUseCase.kt:40](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/MatchScannedInvoiceLinesUseCase.kt:40), [ConfirmInvoiceMatchingUseCase.kt:79](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/ConfirmInvoiceMatchingUseCase.kt:79).

Corrección: mantener `Quantity`, `UnitCost`, unidad OCR y conversión resuelta hasta el commit. Rechazar incompatibilidades monetarias sin resolución explícita. Reutilizar las validaciones del flujo de compras existente y presentar cantidad/costo base antes de confirmar.

**3. P1 — Un pull atrasado puede dejar un saldo local incorrecto aunque avance el cursor.**

El aplicador remoto omite los saldos de una venta cuando ya está `POSTED` localmente. Sin embargo, las compras anteriores del feed sí reemplazan el saldo.

Secuencia concreta: el teléfono tiene cursor N−2 y stock 10; una compra remota N−1 sube el saldo a 15; una venta N descuenta 2 y su respuesta deja localmente 13 y la venta publicada. El siguiente pull aplica la compra anterior, escribiendo 15, y omite el saldo 13 de la venta porque ya está publicada. Avanza el cursor a N. Un pull sin nuevas operaciones conserva el saldo erróneo.

Evidencia estática de alta confianza: [RoomSharedInventoryApplicationRepository.kt:167](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSharedInventoryApplicationRepository.kt:167), [aplicación del saldo remoto:339](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSharedInventoryApplicationRepository.kt:339), [ACK de venta:803](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSaleRepository.kt:803). No se reprodujo con Room en dispositivo durante esta sesión.

Corrección: separar la idempotencia del documento de venta del orden de sus saldos. Usar una secuencia remota durable por proyección o una estrategia equivalente que reconcilie ACK adelantados al cursor. Regresión mínima: compra anterior y venta ya publicada en la misma página; saldo final 13, cursor N.

**4. P1 — Una respuesta de venta perdida permite editar el borrador y bloquear la recuperación.**

Se envía `postSale` antes de guardar una intención durable que congele el contenido. Si el servidor confirma pero la respuesta se pierde, el repositorio devuelve `OnlineRequired`. Después la interfaz vuelve a permitir cambios. Si el usuario modifica el carrito, la recuperación del documento remoto exige que el borrador conserve las líneas y versión originales, encuentra divergencia y rechaza la página sin avanzar el cursor.

Evidencia estática: [RoomSaleRepository.kt:459](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSaleRepository.kt:459), [SalesViewModel.kt:1158](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/sales/SalesViewModel.kt:1158), [validación del borrador:499](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSharedInventoryApplicationRepository.kt:499).

Corrección: persistir la intención y payload antes de enviar; conservar un estado de confirmación pendiente hasta consultar/reintentar el recibo. Una edición posterior debe pertenecer a otro carrito. La recuperación actual de un borrador idéntico es útil, pero no protege el supuesto de identidad tras un resultado incierto.

**5. P1 — Confirmar la asociación no cierra stock y borrador en una operación durable.**

Crear productos, añadir stock, guardar aliases y borrar el borrador son pasos separados. Una interrupción después del commit de existencias deja el borrador abierto. La clave idempotente contiene producto y posición de línea: reasociar a otro producto crea una clave nueva y permite otra entrada; conservar el producto pero modificar cantidad/costo reutiliza la clave y se considera éxito sin comparar el contenido.

Evidencia estática: [ConfirmInvoiceMatchingUseCase.kt:84](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/ConfirmInvoiceMatchingUseCase.kt:84), [orden de commits:92](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/ConfirmInvoiceMatchingUseCase.kt:92), [RoomProductInventoryRepository.kt:163](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomProductInventoryRepository.kt:163).

Corrección: decisiones selladas por línea estable, recibo de publicación y cierre lógico del borrador dentro de la transacción de inventario. La limpieza física de imágenes puede realizarse después. El replay debe comprobar el payload completo, no sólo encontrar la clave.

**6. P1 — Un intento de subida fallido puede eliminar un respaldo documental confirmado.**

`finalizeReservedDocumentUpload` borra el objeto ante cualquier fallo de su transacción. Dos intentos pueden compartir la misma reserva/imagen: uno confirma; otro pendiente pierde membresía o falla al finalizar y borra el objeto ya confirmado. Los marcadores quedan `COMPLETE`, y el siguiente retry puede devolver éxito sin restaurar los bytes.

Se ejecutó código productivo con mocks: negocio activo, compra publicada, operación y respaldo completos, miembro ausente → `NOT_A_MEMBER`, objeto eliminado y marcadores todavía `COMPLETE`. Es una reproducción del finalizador en esa condición, no de toda la concurrencia contra Storage real.

Evidencia: [documentBackup.js:802](/Users/gustavo/Desktop/ProyectoMayda/functions/documentBackup.js:802), [replay:938](/Users/gustavo/Desktop/ProyectoMayda/functions/documentBackup.js:938), [harness reproducible](/Users/gustavo/Desktop/ProyectoMayda/build/reports/project-analysis/2026-09-04/backend-repro.mjs).

Corrección: proteger la propiedad/generación y el estado confirmado del objeto al limpiar intentos fallidos. Separar una purga autorizada de un fallo del solicitante. Definir reparación de un marcador completo cuyo objeto falte.

**7. P1 — Editar existencias desde catálogo no respeta la autoridad cloud.**

El registro nuevo por escáner valida que el negocio no esté enlazado y usa una transacción conjunta. La edición convencional del catálogo sigue llamando a `setStock` después de guardar el producto, sin ese control. `setStock` escribe saldo/movimiento local y no publica un ajuste remoto.

Ejemplo: saldo cloud 10; en un teléfono se edita a 20; los demás conservan 10 y una actualización remota puede volver a reemplazar el saldo local. Además, en esta ruta el guardado de catálogo y ajuste son operaciones separadas.

Evidencia estática: [CatalogsViewModel.kt:716](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/catalogs/CatalogsViewModel.kt:716), [RoomProductInventoryRepository.kt:60](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomProductInventoryRepository.kt:60). Contraste con el control explícito correcto de [RoomProductRegistrationRepository.kt:56](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomProductRegistrationRepository.kt:56).

Corrección: centralizar la autorización para modificar existencias en dominio/repositorio. Ofrecer un ajuste remoto idempotente o deshabilitar esa modificación en negocios enlazados mientras no exista.

**8. P2 — Filas con cantidad ausente pueden desaparecer al confirmar un lote parcialmente válido.**

La interfaz cuenta como listos los productos asociados y permite guardar si alguna fila tiene cantidad positiva. La confirmación omite cantidades nulas/no positivas y elimina el borrador entero. Con arroz=2 y aceite=sin cantidad, ambos asociados, puede confirmar sólo arroz y perder el borrador de aceite. Las filas ya asociadas no ofrecen edición directa de cantidad/costo.

Evidencia estática: [InvoiceMatchingContract.kt:30](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/matching/InvoiceMatchingContract.kt:30), [ConfirmInvoiceMatchingUseCase.kt:75](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/ConfirmInvoiceMatchingUseCase.kt:75).

Corrección: resolver cada fila mediante cantidad válida o exclusión explícita antes de cerrar. Permitir corregir valores y conservar evidencia de las decisiones.

**9. P2 — La revisión manual de asociación se pierde con la muerte del proceso.**

`SavedStateHandle` guarda el ID del borrador, pero las asociaciones, productos preparados y formularios sólo viven en `StateFlow`. Al recrear el ViewModel se reconstruyen desde el OCR original. Revisar muchas líneas y cambiar de aplicación puede terminar perdiendo el trabajo si Android libera el proceso.

Evidencia estática: [InvoiceMatchingViewModel.kt:62](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/matching/InvoiceMatchingViewModel.kt:62), reconstrucción en línea 129. No se encontraron pruebas del ViewModel ni de esta pantalla; sí de los casos de uso.

Corrección: snapshot durable de la revisión, con IDs de línea estables y control de revisión; recuperación coherente con el cierre transaccional descrito en el hallazgo 5.

**10. P2 — La inicialización del inventario remoto puede quedar sin camino normal de recuperación.**

Hay dos variantes verificadas:

- Con historia antigua del producto A, una compra moderna del producto B sólo revisa la historia de B. Puede crear metadata global y dejar A sin migrar. El bootstrap posterior rechaza porque la metadata ya existe.
- Con 101 movimientos modernos de un producto en un almacén, intentar inicializar su saldo en otro almacén devuelve `INVENTORY_HISTORY_SCAN_LIMIT`. El límite se comprueba antes de distinguir historia antigua o almacén. El bootstrap tampoco se habilita cuando ya existe metadata.

Evidencia: [inventorySync.js:471](/Users/gustavo/Desktop/ProyectoMayda/functions/inventorySync.js:471), [creación de metadata:1382](/Users/gustavo/Desktop/ProyectoMayda/functions/index.js:1382), [rechazo de bootstrap:392](/Users/gustavo/Desktop/ProyectoMayda/functions/inventoryBootstrap.js:392). El harness ejecutó el helper y el handler de bootstrap con mocks; la escritura intermedia de la primera variante está representada y no se ejecutó como compra integrada. El caso de 101 movimientos se reprodujo directamente contra el helper productivo.

Corrección: estado durable de migración por negocio o claves pendientes que permita distinguir inventario moderno de legado. El crecimiento normal de movimientos no debería exigir una migración imposible.

**11. P2 — El modo offline y CI dependen indebidamente de configuración Spark.**

`verifyOfflineFirstBoundaries` depende de `verifyCloudSparkBoundaries`, que exige project ID, application ID y API key. Esas verificaciones se ejecutan antes de compilar incluso la variante local. La configuración del equipo oculta el problema.

Se reprodujo en una raíz temporal con las mismas fuentes, SDK configurado y sin valores Firebase: `:app:verifyOfflineFirstBoundaries` falló con `cloudSpark requiere firebase.projectId en local.properties`. No se retiraron claves del proyecto original.

Evidencia: [app/build.gradle.kts:2087](/Users/gustavo/Desktop/ProyectoMayda/app/build.gradle.kts:2087), [validación Spark:1592](/Users/gustavo/Desktop/ProyectoMayda/app/build.gradle.kts:1592), [salida reproducida](/Users/gustavo/Desktop/ProyectoMayda/build/reports/project-analysis/2026-09-04/clean-config.log).

Corrección: limitar los requisitos de configuración real a las tareas de la variante que los necesita; mantener las comprobaciones de aislamiento de código/dependencias independientes de credenciales.

**12. P2 — Hay un contrato de release desactualizado y Spark queda fuera de la ejecución normal de CI.**

`test-release-contracts.rb` exige `StartupJourney.waitForHomeReady`, pero el recorrido cambió a `waitForSalesReady`. El script falló al ejecutarlo. Esto bloquea una comprobación de CI; no demuestra que el benchmark mida mal, sino que el contrato todavía espera el recorrido anterior.

Además, el workflow de Firebase usa la configuración principal y no activa `FACTURASTOCK_SPARK_RULES_TEST`. La suite común deja ese test omitido. Las 13 pruebas Spark aprobaron al ejecutarlas separadamente en esta revisión, pero el workflow inspeccionado no las ejecuta.

Evidencia: [test-release-contracts.rb:234](/Users/gustavo/Desktop/ProyectoMayda/scripts/test-release-contracts.rb:234), [StartupJourney.kt:73](/Users/gustavo/Desktop/ProyectoMayda/benchmark/src/main/java/com/facturastock/app/benchmark/StartupJourney.kt:73), [CI:267](/Users/gustavo/Desktop/ProyectoMayda/.github/workflows/ci.yml:267), [omisión condicionada:8](/Users/gustavo/Desktop/ProyectoMayda/functions/test/sparkSecurityRules.test.mjs:8).

Corrección: actualizar el contrato al comportamiento de arranque decidido y ejecutar Spark como matriz/job separado, con su configuración propia. Un verde de CALLABLES no verifica las reglas alternativas.

**Fortalezas que conviene conservar.**

- Compras publicadas: preparación sellada, validación dentro de transacción, líneas, movimientos, auditoría y outbox confirmados conjuntamente. [PurchasePostingDao.kt:86](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/local/dao/PurchasePostingDao.kt:86).
- Restricciones SQLite que protegen historia publicada, versiones/CAS para concurrencia y esquema evolutivo sin recuperación destructiva. [SalesPersistenceInvariants.kt:118](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/local/SalesPersistenceInvariants.kt:118).
- `Money` en unidades menores, cantidades/costos decimales exactos y errores explícitos de moneda/escala. [Money.kt:25](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/model/Money.kt:25).
- El lector HID ya contempla ciclo de vida, descarte de tramas inválidas, sufijos y resultados tardíos. Ventas serializa lecturas y revalida contexto; esta robustez no debe confundirse con la madurez del nuevo matching.
- CALLABLES aplica identidad, verificación de correo, roles y comprobaciones transaccionales; el backend contempla idempotencia, cuotas y secuencias. No apareció un bypass demostrado entre negocios en el alcance revisado.
- Seguridad móvil explícita: almacenamiento privado, imágenes cifradas, restricciones de exportación/red y prevención de respaldos del sistema sobre datos sensibles. El escáner de secretos pasó; eso no equivale a una auditoría completa de dependencias.
- CI contempla migraciones, pruebas instrumentadas, rendimiento, artefactos y publicación controlada. El objetivo debe ser que esos controles sigan alineados con los recorridos actuales.

**Límites funcionales y de mantenimiento.** La restauración completa del dispositivo todavía no es un flujo productivo terminado. El coordinador sólo devuelve `NOT_READY` porque falta detener los escritores y cerrar/reiniciar Room de forma coordinada; existen primitivas de archivo y journal, pero no completan esa capacidad. [FullDeviceSnapshotRestoreReadiness.kt:177](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/restore/FullDeviceSnapshotRestoreReadiness.kt:177). Esto no invalida los respaldos parciales/cloud existentes, pero debe quedar claro qué recuperación está disponible.

En Spark, la acción de borrar cuenta requiere Functions, que ese modo no habilita, y devuelve `Unavailable`. [FirebaseAccountRepository.kt:226](/Users/gustavo/Desktop/ProyectoMayda/app/src/cloud/java/com/facturastock/app/data/account/FirebaseAccountRepository.kt:226). La interfaz debe expresar capacidades reales por modalidad. Igualmente, el nuevo matching permite invertir trabajo en un negocio enlazado y sólo al confirmar responde `CloudBound`; hace falta resolver esa disponibilidad al inicio o dirigir a un flujo compatible.

El crecimiento del código aconseja extraer gradualmente componentes cohesionados: confirmación de ventas remotas, aplicación de feeds y reglas de registro de productos. No recomiendo una reescritura general ni una migración masiva de arquitectura antes de corregir los defectos concretos. La prioridad es reducir rutas alternativas que implementan las mismas reglas con garantías distintas.

**Orden recomendado de trabajo y criterios de cierre.**

1. Corregir interpretación decimal, conservación de unidad/moneda y tratamiento de filas incompletas. Cierre: ninguna entrada modifica su magnitud silenciosamente; todas las líneas quedan resueltas y auditables antes del commit.
2. Hacer durable la revisión y publicación del matching, y centralizar la autorización para ajustar stock. Cierre: interrupciones y reintentos no duplican ingresos, no ignoran cambios y no dejan stock local divergente en negocios cloud.
3. Resolver la intención de venta remota y el orden de aplicación de saldos. Cierre: ACK perdido, edición posterior, compra anterior al ACK y recuperación desde un cursor atrasado convergen al mismo estado remoto/local.
4. Corregir la limpieza de respaldos y los estados de inicialización de inventario. Cierre: un finalizador fallido no destruye una publicación ajena confirmada; un negocio moderno crece entre almacenes sin activar migraciones irrecuperables.
5. Restaurar reproducibilidad de builds/CI y alinear capacidades/documentación. Cierre: local compila sin claves Firebase, contratos de arranque pasan, Spark tiene ejecución explícita y el usuario distingue respaldo parcial de restauración completa.

Antes de ampliar cobertura por porcentaje, añadiría regresiones de esas secuencias concretas. El 87,10 % de cobertura de líneas del dominio es una buena base, pero los fallos más relevantes atraviesan pantalla, repositorio, red y base de datos. Las pruebas deben atravesar esas mismas fronteras.
