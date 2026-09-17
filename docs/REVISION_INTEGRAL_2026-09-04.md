**Seguimiento:** este informe conserva los hallazgos anteriores a las correcciones. El estado actualizado y la validación están en [Estabilización del 5 de septiembre](ESTABILIZACION_2026-09-05.md).

**Revisión integral de FacturaStock — 4 de septiembre de 2026**

FacturaStock tiene una base técnica sólida y bastante trabajo de ingeniería: dominio decimal exacto, persistencia transaccional, OCR local, protección de archivos, sincronización y pruebas amplias. La madurez es desigual entre recorridos. El nuevo ingreso de productos desde facturas omite varias garantías que sí conserva el registro tradicional de compras; la recuperación de algunas operaciones cloud también necesita trabajo. Mi prioridad sería estabilizar estos recorridos antes de añadir funcionalidades o ampliar su uso con existencias reales.

**Alcance y evidencia.** Se revisó el árbol de trabajo de `/Users/gustavo/Desktop/ProyectoMayda`, sobre el commit `0b2f23d8b33847086d08354fafc5f3134a31d508`, incluyendo sus cambios locales: 103 archivos versionados modificados y 33 archivos sin seguimiento al hacer el inventario. Tres revisiones paralelas cubrieron persistencia/dominio, Android/OCR/lector y backend/seguridad; la revisión principal contrastó integración, compilación, pruebas, CI y capacidades de recuperación. Los informes anteriores se usaron como contexto y se verificaron contra las fuentes actuales.

No se corrigió código productivo ni se desplegaron servicios. Las reproducciones añadidas viven en un directorio temporal y usan datos sintéticos. No se instaló nada en la tablet ni se alteraron sus datos. Los hallazgos indican si proceden de ejecución o de seguimiento estático; una prueba con repositorios simulados no se presenta como prueba de Room o de dispositivo físico.

**Producto y arquitectura observados.**

Es una aplicación Android nativa en Kotlin/Compose para compras, ventas, inventario, costos y cuentas por cobrar. Room conserva el estado local; CameraX y ML Kit implementan captura/OCR; Hilt conecta dependencias. La sincronización añade Firebase Auth, Firestore, Storage y Functions según la modalidad.

| Modalidad | Comportamiento observado | Límite relevante |
| --- | --- | --- |
| `local` | Room y archivos privados; elimina INTERNET del manifiesto. | La restauración completa y portable del dispositivo todavía no es una función disponible. |
| `cloud` con Functions | El teléfono conserva una proyección; el servidor autoriza stock, ventas y pagos compartidos. | Una respuesta de red perdida exige reconciliar el resultado antes de permitir editar la operación. |
| `cloudSpark` | Implementación alternativa mediante cliente Firestore y reglas propias. | No tiene equivalencia completa de capacidades con Functions y su suite de reglas falta en CI normal. |

El flujo clásico de compra revisa documento, líneas, unidades, costos y duplicados antes de preparar y publicar. El recorrido OCR actual puede ir directamente a asociación de productos, crear movimientos `ADJUSTMENT` y eliminar el borrador. Por tanto, ingresar existencias desde una imagen no produce las mismas garantías ni el mismo historial que publicar una compra completa.

La organización lógica por capas es útil, pero casi todo el producto vive en un módulo `app`; `benchmark` es el segundo módulo Gradle. Las cifras de fuentes, excluyendo dependencias y código generado, son:

| Área | Archivos | Líneas |
| --- | ---: | ---: |
| Kotlin compartido, `src/main` | 529 | 117.656 |
| Kotlin `src/cloud` | 41 | 11.422 |
| Kotlin `src/local` | 14 | 473 |
| Pruebas JVM compartidas | 198 | 57.693 |
| Pruebas JVM `testCloud` | 22 | 5.265 |
| Pruebas instrumentadas compartidas | 97 | 40.386 |
| Pruebas instrumentadas `androidTestCloud` | 2 | 774 |
| JavaScript productivo de Functions | 11 | 7.790 |

Room está en versión 27, exporta 32 entidades y conserva los esquemas 1–27. Estas cifras miden tamaño, no calidad ni cobertura.

**Validación de esta revisión.**

| Comprobación | Resultado de esta sesión |
| --- | --- |
| Pruebas JVM `localDebug`, forzadas a ejecutar | 1.491 aprobadas; cero fallos, errores u omisiones. |
| Pruebas JVM `cloudDebug`, forzadas a ejecutar | 1.668 aprobadas; cero fallos, errores u omisiones. |
| Android Lint, ambas variantes | Cero errores; 43 advertencias en cada una. |
| Kover del dominio | 87,07 % de líneas y 65,29 % de ramas; pasó el mínimo configurado del 80 % de líneas. |
| Tres reproducciones temporales de asociación | Las tres ejecutaron y confirmaron el comportamiento defectuoso descrito abajo. |
| Suite Functions en emuladores | 167 aprobadas y una omitida, correspondiente a Spark; cero fallos. |
| Reglas Spark por separado | 13 pruebas del repositorio aprobadas; una reproducción adicional aprobada. |
| Borrado de cuenta con fallo inyectado | Se comprobó el estado parcial y la recuperación mediante una segunda solicitud manual. |
| `ciStaticAnalysis` | Falló exclusivamente por formato: ocho archivos Kotlin y `app/build.gradle.kts`. Los demás controles estáticos ejecutados pasaron. |
| Escáner de secretos | Sin coincidencias de alta confianza ni archivos de clave prohibidos. |
| Contratos/políticas de release y assets Play | Aprobaron los scripts ejecutados. |
| Historial de esquemas Room frente a HEAD | Append-only; sin nuevos esquemas ni cambios en los existentes. |

Las 3.159 ejecuciones JVM incluyen muchos escenarios compartidos que corren en ambos flavors; no son 3.159 casos independientes. Kover está configurado exclusivamente para `domain.*`, por lo que el 87,07 % no representa toda la aplicación. De las 43 advertencias Lint locales, 27 son recursos sin uso; también hay convenciones Compose, visibilidad de pruebas y avisos de dependencias. No se deducen vulnerabilidades de esas advertencias.

La ejecución usó JBR 21.0.11 y Node 24.18.0; el backend declara Node 22. No se comprobó aquí esa matriz exacta de runtime. Gradle utilizó dependencias locales con `--offline`. No se ejecutaron instrumentación Android, cámara/lector físicos, Play Integrity real, rendimiento en hardware, auditoría actualizada de dependencias ni distribución firmada. Los emuladores Firebase usaron un proyecto demo y configuración temporal.

Evidencia: [resumen JSON](/Users/gustavo/Desktop/ProyectoMayda/build/reports/revision-integral/2026-09-04/summary.json), [compilación, pruebas y Lint](/Users/gustavo/Desktop/ProyectoMayda/build/reports/revision-integral/2026-09-04/gradle.log), [controles estáticos y reproducciones](/Users/gustavo/Desktop/ProyectoMayda/build/reports/revision-integral/2026-09-04/static-and-reproductions.log), [JUnit de las reproducciones](/Users/gustavo/Desktop/ProyectoMayda/build/reports/revision-integral/2026-09-04/matching-reproductions.xml), [Functions](/Users/gustavo/Desktop/ProyectoMayda/build/reports/revision-integral/2026-09-04/main-tests-retry.log), [Spark](/Users/gustavo/Desktop/ProyectoMayda/build/reports/revision-integral/2026-09-04/spark-tests.log). Los archivos bajo `build/` son evidencias locales ignoradas por Git.

**Hallazgos prioritarios.** P1 identifica riesgo importante para la integridad o recuperación del recorrido afectado; P2, un problema funcional u operativo relevante. No son vulnerabilidades clasificadas con CVSS.

**1. P1 — La asociación de facturas pierde la unidad y la moneda del OCR.**

`MatchScannedInvoiceLinesUseCase` extrae `quantity.value` y `unitCost.amount`. El modelo que continúa no conserva la unidad de la factura ni la moneda del costo. La confirmación envía esos números a inventario usando la moneda de configuración.

Si la factura contiene dos cajas de doce unidades a 120 por caja, el recorrido puede enviar dos unidades de inventario a costo 120, en lugar de resolver 24 unidades a costo 10. Asimismo, un costo en USD puede terminar registrado como el mismo número en PEN. La existencia de un factor de compra en el producto no resuelve por sí sola en qué unidad venía la factura.

Evidencia: [extracción de valores:41](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/MatchScannedInvoiceLinesUseCase.kt:41), [modelo sin unidad/moneda:12](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/model/InvoiceMatchingModels.kt:12), [confirmación con moneda configurada:93](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/ConfirmInvoiceMatchingUseCase.kt:93). La reproducción temporal del caso de cajas observa los argumentos enviados por el caso de uso real; la pérdida de moneda se verificó por lectura, sin simular conversión monetaria.

Corrección propuesta: conservar cantidad, costo, moneda, unidad de origen y conversión resuelta hasta el commit; exigir decisión explícita cuando falte información. Reutilizar las validaciones del flujo clásico de compras. La regresión debe comprobar tanto unidades de compra distintas de las de inventario como moneda incompatible.

**2. P1 — Una factura parcialmente legible puede guardarse y perder sus líneas pendientes.**

El botón de guardado se habilita si no quedan descripciones sin producto y al menos una línea tiene cantidad positiva. La confirmación omite las cantidades ausentes o no positivas, aplica las demás y elimina todo el borrador. Una factura con arroz=2 y aceite=cantidad desconocida, ambos vinculados, puede terminar con solo el arroz registrado y sin borrador para resolver el aceite.

Además, la pantalla muestra cantidad y costo como texto para productos existentes. Las acciones permiten cambiar el producto o introducir cantidad al crear uno nuevo, pero no corregir directamente un número OCR erróneo en una coincidencia existente.

Evidencia: [habilitación:33](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/matching/InvoiceMatchingContract.kt:33), [omisión de líneas:75](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/ConfirmInvoiceMatchingUseCase.kt:75), [visualización de cantidades:301](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/matching/InvoiceMatchingScreen.kt:301). La reproducción temporal utiliza el caso de uso real y comprueba que se envía una sola entrada y desaparece el borrador fake. El test existente de cantidad no leída cubre el caso donde ninguna línea puede aplicarse, que no detecta este lote mixto.

Corrección propuesta: cada fila debe terminar con cantidad/costo revisados o exclusión explícita antes de cerrar la factura. Permitir editar los valores OCR y conservar la evidencia de la decisión.

**3. P1 — Un fallo después de aplicar existencias deja una confirmación incompleta que puede duplicarse.**

La confirmación realiza cuatro pasos independientes: crear productos, aplicar existencias, guardar aliases y borrar el borrador. Un fallo consultando proveedores después del stock deja el borrador disponible, aunque las existencias ya se hayan actualizado. Al reasociar la misma línea de A a B, cambia la clave de idempotencia porque contiene el producto, y el siguiente intento puede registrar también B. Si se cambia cantidad/costo conservando A, el repositorio acepta la clave existente sin verificar que el contenido sea el mismo.

Evidencia: [orden de operaciones:92](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/ConfirmInvoiceMatchingUseCase.kt:92), [clave mutable:85](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/ConfirmInvoiceMatchingUseCase.kt:85), [replay sin comparación del contenido:163](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomProductInventoryRepository.kt:163). La reproducción temporal inyecta el fallo tras el primer envío y observa dos solicitudes de stock con claves distintas tras reasociar; la persistencia efectiva de ambos movimientos se deriva del código Room y no se ejecutó en dispositivo en esta revisión.

Corrección propuesta: una operación de repositorio debe confirmar productos, decisiones, movimientos y cierre lógico del borrador dentro de una transacción. Usar IDs estables de línea y verificar el payload en los reintentos. La eliminación física de imágenes puede quedar como limpieza posterior recuperable.

**4. P1 — Una venta confirmada remotamente puede quedar bloqueada si se pierde la respuesta y se edita el carrito.**

El checkout llama a `postSale` antes de guardar una intención durable que congele el carrito. Si el servidor confirma pero se pierde la respuesta, la venta sigue como `DRAFT` local y la interfaz vuelve a permitir editarla. El reintento con contenido distinto es rechazado por el servidor. El pull exige que el borrador local conserve la versión y las líneas originales, por lo que también rechaza esa página y no avanza su cursor.

El resultado puede ser una venta ya publicada y stock descontado en la nube, junto con un carrito local que no logra confirmarse ni recuperarse por sincronización.

Evidencia estática, de extremo a extremo: [envío remoto:461](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSaleRepository.kt:461), [rechazo de replay divergente:715](/Users/gustavo/Desktop/ProyectoMayda/functions/saleSync.js:715), [conflicto al materializar el borrador:500](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSharedInventoryApplicationRepository.kt:500). No se reprodujo esta pérdida de respuesta con Android y Firebase integrados durante la revisión.

Corrección propuesta: guardar una intención pendiente con contenido sellado antes de enviar y reconciliar por ID de operación. Mientras el resultado sea incierto, conservar ese contenido y resolverlo; las nuevas ediciones deben esperar o pertenecer a un carrito distinto. Probar pérdida de respuesta, muerte de proceso, reintento y pull posterior.

**5. P2 — La revisión manual de asociación no sobrevive a la muerte del proceso.**

Las asociaciones manuales, productos pendientes y campos del formulario solo viven en `StateFlow`. `SavedStateHandle` únicamente aporta el ID del borrador; una nueva instancia vuelve a calcular las asociaciones desde las líneas OCR originales. Si Android elimina el proceso mientras la app está en segundo plano, se pierde ese trabajo. Una simple rotación puede conservar el ViewModel y no detectar el problema.

Evidencia estática: [lectura del estado guardado:59](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/matching/InvoiceMatchingViewModel.kt:59), [reconstrucción automática:117](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/matching/InvoiceMatchingViewModel.kt:117).

Corrección propuesta: persistir un snapshot de revisión por borrador, con identidad estable de filas y productos pendientes; recuperar también formularios parciales. La prueba debe crear otra instancia del ViewModel y, después, cubrir muerte real del proceso en un emulador aislado.

**6. P2 — Volver desde asociación puede mostrar un OCR terminado como si siguiera trabajando.**

La flecha de retorno de asociación hace `popBackStack` y vuelve a la instancia OCR que ya terminó. Su estado conserva páginas completadas, no está ejecutando y no tiene error. La pantalla dibuja el indicador de carga cuando no hay error, pero oculta Cancelar y Retomar bajo esas condiciones. El usuario no tiene una acción visible para regresar a asociación.

Evidencia estática: [retorno:1207](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/navigation/FacturaStockApp.kt:1207), [indicador y condiciones de botones:271](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/ocr/OcrRoute.kt:271).

Corrección propuesta: definir el destino de retorno, retirar OCR de la pila al finalizar o representar un estado completado con continuación. Añadir un recorrido integrado OCR exitoso → asociación → flecha atrás.

**7. P2 — El borrado de cuenta interrumpido no se reconcilia automáticamente.**

Android guarda una marca antes de llamar al borrado remoto. Ante una respuesta incierta, continúa con cierre de sesión y limpieza local, sin volver a consultar o reanudar el resultado remoto. El backend puede quedar entre el bloqueo de la cuenta y la eliminación completa.

Se reprodujo con emuladores reales de Firebase e inyección de fallo en `recursiveDelete`: el usuario Auth seguía existiendo, el negocio estaba bloqueado y la marca durable tenía `cleanupComplete=false`. El proceso programado inspeccionado no seleccionaba ese caso. Volver a iniciar sesión era posible, pero listar membresías devolvía `ACCOUNT_DELETION_IN_PROGRESS`. Una nueva solicitud explícita de borrado sí completó la operación: no es un bloqueo irreversible, sino una recuperación manual poco visible.

Evidencia: [marca y envío Android:241](/Users/gustavo/Desktop/ProyectoMayda/app/src/cloud/java/com/facturastock/app/data/account/FirebaseAccountRepository.kt:241), [limpieza que no reenvía:949](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/account/AccountViewModel.kt:949), [eliminación backend:351](/Users/gustavo/Desktop/ProyectoMayda/functions/accountDeletion.js:351), [filtro del proceso de limpieza:661](/Users/gustavo/Desktop/ProyectoMayda/functions/documentBackup.js:661).

Corrección propuesta: una operación de borrado durable en el servidor, reintentable por etapas, y una consulta de estado desde Android. Distinguir cierre de sesión, borrado pendiente y borrado confirmado. Cubrir también una interrupción antes de que la solicitud llegue al servidor.

**8. P2 — CI no ejecuta las reglas alternativas Spark.**

El trabajo Firebase utiliza la configuración normal. La prueba Spark depende de una variable de activación y se omite en esa ejecución. Su aprobación por separado en una máquina no convierte las reglas alternativas en requisito de CI.

Evidencia: [comando del workflow:267](/Users/gustavo/Desktop/ProyectoMayda/.github/workflows/ci.yml:267), [activación condicional:8](/Users/gustavo/Desktop/ProyectoMayda/functions/test/sparkSecurityRules.test.mjs:8).

Corrección propuesta: ejecutar la suite Spark en un job o entrada de matriz independiente con `firebase.spark.json`, y exigir ese resultado para los artefactos que incluyan esa modalidad. La UI también debe reflejar sus capacidades: el borrado de cuenta actual necesita Functions, que el runtime Spark no ofrece.

**9. P2 — El árbol actual falla la compuerta de formato de CI.**

Se ejecutó `ciStaticAnalysis` con HEAD como base explícita de Spotless. Fallaron `spotlessKotlinCheck` y `spotlessKotlinGradleCheck`; el resto de controles estáticos sí terminó correctamente. Los archivos señalados son `AndroidKeyboardWedgeAdapterTest.kt`, `ProductDurabilityTest.kt`, `RoomProductRegistrationRepositoryTest.kt`, `ScannedProductRegistrationScreenTest.kt`, `PhysicalScannerRegistrationTest.kt`, `ScannerKeyModifiers.kt`, `RoomProductRegistrationRepository.kt`, `InventoryScannerRegistrationTest.kt` y `app/build.gradle.kts`.

Esto no explica errores de ejecución de la app, pero impide dar por aprobada su calidad de CI aun con las pruebas y Lint verdes. La corrección consiste en aplicar el formato exigido a esos archivos, revisar el diff y repetir la compuerta. No se aplicó formato durante este análisis. El [log conserva los archivos y diferencias exactas](/Users/gustavo/Desktop/ProyectoMayda/build/reports/revision-integral/2026-09-04/static-and-reproductions.log:72).

**Correcciones previas que ya aparecen en el código actual.**

No corresponde repetir todos los defectos de los informes anteriores como si siguieran abiertos:

- Los campos nuevos conservan la coma decimal y reutilizan el parser de formularios; el antiguo filtro que convertía `12,50` en `1250` fue retirado.
- El replay de inventario aplica los saldos en orden aunque la venta ya esté publicada localmente. Existen regresiones Room para misma página, páginas separadas y reintentos con cursor anterior; no se ejecutaron esas pruebas instrumentadas en esta revisión.
- El finalizador documental ya conserva los bytes cuando otra invocación pudo confirmar el respaldo. Las pruebas backend de esta revisión incluyen las regresiones del finalizador y la carrera de subida.
- Los requisitos de configuración Spark están separados de los controles compartidos y ligados a `preCloudSparkBuild`. El fallo previo que exigía credenciales para el prebuild local no permanece en ese código.
- El contrato de arranque/release ya está actualizado: `scripts/test-release-contracts.rb` pasó en esta revisión.

**Fortalezas que conviene mantener.**

La ruta clásica de compras confirma cabecera, líneas, existencias, movimientos, auditoría, outbox y estado del borrador mediante transacción y revalida el contenido preparado. Los importes usan unidades menores enteras y los decimales tienen reglas explícitas; no se encontraron motivos para sustituir ese dominio. [Publicación transaccional](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/local/dao/PurchasePostingDao.kt:86).

Room tiene migraciones explícitas, historial de esquemas y restricciones que protegen datos publicados. El consumidor remoto confirma hechos y cursor juntos. Los problemas detectados se concentran en la coordinación de recorridos, no en una ausencia general de transacciones.

Captura/OCR ya contempla tokens de ejecución, cancelación, imágenes durables y resultados tardíos. Ventas serializa operaciones, utiliza versiones para evitar escrituras concurrentes y controla cola, foco y ciclo de vida del lector. La optimización reciente evita reconstruir el catálogo completo en cada edición del carrito.

La seguridad móvil es deliberada: imágenes cifradas, almacenamiento privado, restricciones de exportación/red, bloqueo opcional y exclusión de respaldos del sistema. El escáner de secretos aprobado solo acredita sus patrones; no equivale a una auditoría completa de dependencias ni de infraestructura productiva.

CI contempla pruebas JVM, migraciones y UI instrumentadas, integración Android/Firebase, extremos de SDK, rendimiento y controles de distribución. Es una base valiosa; hay que mantener su matriz alineada con los flujos y modalidades que realmente se entregan.

**Capacidades y mantenimiento pendientes.**

La restauración completa del dispositivo sigue sin estar disponible. El coordinador devuelve `NOT_READY` aun con archivos válidos porque falta detener escritores, cerrar Room y coordinar la activación y recuperación del snapshot. La exportación contable y los feeds cloud tienen alcance parcial. Dado que la variante local no usa red y excluye backups del sistema, no debe presentarse como si ya tuviera una recuperación integral ante pérdida del dispositivo. [Coordinador de restauración:194](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/restore/FullDeviceSnapshotRestoreReadiness.kt:194), [alcance documentado](/Users/gustavo/Desktop/ProyectoMayda/docs/FULL_DEVICE_SNAPSHOT_FOUNDATION.md:3).

El tamaño de varios archivos aumenta el costo de modificar el producto: `SalesViewModel` tiene 1.655 líneas, navegación 1.563, `RoomSaleRepository` 1.305, el Gradle de app 2.376 y CI 974. No es un error por sí mismo. Después de estabilizar comportamiento, conviene separar coordinación de checkout, sesiones de lector, persistencia de revisión y validaciones de distribución. Evitaría una reescritura general o dividir módulos solo para mejorar cifras.

La documentación mezcla estados de distintas fechas y recorridos. Por ejemplo, algunos documentos describen la revisión clásica como recorrido OCR principal, mientras la navegación actual lleva a asociación. La matriz de aceptación debe identificar cada recorrido vigente y sus capacidades por modalidad. Las evidencias históricas de hardware y las cifras antiguas de pruebas no certifican este árbol de trabajo.

Un límite secundario de Spark quedó reproducido: admite crear 101 negocios, pero la consulta de membresías devuelve como máximo 100 sin paginación, ocultando uno. Su prioridad práctica es menor para un negocio pequeño; se resuelve paginando o imponiendo de verdad la cuota al crear. [Consulta limitada:50](/Users/gustavo/Desktop/ProyectoMayda/app/src/cloud/java/com/facturastock/app/data/spark/SparkBusinessMembershipRepository.kt:50), [reproducción](/Users/gustavo/Desktop/ProyectoMayda/build/reports/revision-integral/2026-09-04/spark-quota-repro.test.mjs).

**Orden de trabajo recomendado y criterios de cierre.**

1. Resolver juntos los tres problemas de ingreso OCR: unidades/moneda, revisión completa y confirmación durable. Cierre: lote mixto no pierde filas; caja/unidad y moneda incompatible se resuelven explícitamente; fallos antes/después del stock y reintentos no duplican ni ignoran contenido.
2. Persistir y reconciliar la intención de checkout cloud. Cierre: servidor confirma, se pierde la respuesta, muere el proceso y al volver existe exactamente una venta, con stock y cursor coherentes.
3. Recuperar la revisión de asociación y corregir su retorno. Cierre: una nueva instancia restaura decisiones y formulario, y volver desde asociación ofrece un destino útil.
4. Completar la reanudación del borrado de cuenta y la matriz de capacidades Spark. Cierre: interrupciones recuperables sin repetir manualmente todo el recorrido y suite Spark obligatoria en CI.
5. Definir la recuperación integral y ejecutar un piloto del candidato concreto. Cierre: restauración comprobada de datos y fotos, más lector USB, cámara, accesibilidad y rendimiento medidos en el dispositivo objetivo.

Mi valoración es que el proyecto merece una fase de estabilización centrada en integridad y recuperación. La arquitectura existente aporta herramientas para hacerlo; el mayor retorno está en extender sus garantías a los recorridos nuevos.
