**FacturaStock — análisis del estado actual, 22 de septiembre de 2026**

El proyecto tiene una base técnica seria y una cobertura de pruebas extensa. Sin embargo, existen fallos comprobados de autorización, sincronización e integridad de inventario que impiden considerar estable su operación compartida entre teléfonos. La variante local reduce algunos riesgos de sincronización, pero conserva el problema del bloqueo de acceso, una inconsistencia al anular ventas con distintas monedas de costo y cobro, y una limitación importante de recuperación de datos. Priorizaría estabilización y recuperación antes de ampliar funciones.

La revisión corresponde a la carpeta /Users/gustavo/Desktop/ProyectoMayda, commit e1d03a2 y sus cambios locales. Al iniciar había 34 archivos modificados y 14 archivos sin seguimiento, incluidos cambios de ventas, inventario, registro manual, pruebas e informes anteriores. Se conservaron. Este análisis no cambia fuentes funcionales, dependencias, reglas, configuración ni datos del negocio. Añade este informe y evidencia en build/reports/auditoria-2026-09-22. Las auditorías previas sirvieron como pistas; las conclusiones siguientes se contrastaron con código o ejecuciones nuevas.

**Qué producto existe y cómo está organizado.**

FacturaStock es una aplicación Android nativa en español para ventas, inventario, compras, productos por peso, lectura de códigos, cuentas por cobrar y reportes. La configuración declara versión 1.0.12, versionCode 13, minSdk 26 y compileSdk/targetSdk 36. Tiene dos módulos Gradle: app y benchmark. La separación de presentación, dominio y datos se realiza principalmente mediante paquetes e interfaces dentro de app.

Kotlin, Compose y Material 3 resuelven la interfaz; Hilt conecta dependencias; Room almacena el libro local; CameraX y ML Kit permiten captura y OCR; WorkManager coordina trabajo diferido. Firebase añade identidad, miembros, catálogos, documentos y sincronización. El esquema vigente de Room es v29, con 35 tablas y 29 esquemas históricos exportados. README todavía afirma v27: es documentación desactualizada, no la versión real de la base.

~~~mermaid
flowchart TD
    UI[Compose y ViewModels] --> DOMAIN[Casos de uso y modelos]
    DOMAIN --> REPOS[Interfaces y repositorios]
    REPOS --> ROOM[Room y archivos privados]
    ROOM --> OUTBOX[Cola durable de operaciones]
    OUTBOX --> FUNCTIONS[Functions: validación y transacciones]
    FUNCTIONS --> FIREBASE[Firestore y Storage]
    FIREBASE --> PULL[Descarga incremental y aplicación local]
    PULL --> ROOM
    REPOS --> SPARK[Variante Spark: escrituras directas]
    SPARK --> FIREBASE
~~~

La variante local elimina el permiso INTERNET y las implementaciones Firebase. La variante cloud permite preparar trabajo localmente, pero confirmar ventas de un negocio enlazado requiere autorización remota del stock. Las compras usan publicación local seguida de envío mediante outbox. Estas dos estrategias necesitan una reconciliación explícita; ejecutarlas en cierto orden no elimina todas las carreras.

Spark es otro modo de backend, con escrituras directas y reglas propias. Su build type es depurable y usa firma debug. No hereda automáticamente las garantías de Functions ni representa un cloudRelease distribuible.

El menú principal actual contiene Ventas, Inventario y Reportes. Compras, OCR y catálogo general siguen implementados, pero varias entradas dependen de demo, historial o navegación conservada. Conviene documentar el recorrido realmente accesible, no presentar cada pantalla existente como una función principal disponible.

El inventario de archivos, excluyendo código generado y dependencias, es: 621 archivos Kotlin de producción/soporte de variantes de app (141.472 líneas), 241 archivos JVM de pruebas (73.751 líneas), 130 archivos instrumentados (53.736 líneas), 11 archivos JavaScript productivos (8.024 líneas) y 14 archivos de pruebas backend (10.744 líneas). Los conteos incluyen comentarios y blancos; describen tamaño, no calidad ni cobertura.

**Fortalezas verificadas.**

- Dinero en unidades menores y cantidades/costos con decimales exactos; no se basa la contabilidad en coma flotante binaria.
- Transacciones Room, índices, restricciones y triggers para conservar relaciones e historia publicada. Hay migraciones explícitas y no se recurre a migración destructiva.
- Checkout con intención durable, versión, hash e idempotencia; outbox con claims y reintentos; cursor y caché actualizados conjuntamente.
- El editor actual de Inventario distingue campos modificados y utiliza versiones del snapshot. El alta manual reserva identidad para evitar duplicación tras recreación.
- OCR local con identidad de ejecución persistida y control de resultados tardíos. Existen reglas de privacidad, archivos privados, exclusión de backup automático y recopilación de diagnóstico condicionada al consentimiento.
- Functions aplica autenticación, verificación de correo, roles, App Check y transacciones; las reglas normales deniegan escrituras cliente directas. Estas defensas existen, aunque no cubren las secuencias defectuosas descritas abajo.
- CI verifica arquitectura, secretos, pruebas, cobertura de dominio, emuladores, migraciones, UI, rendimiento y artefactos. Es una base útil para incorporar regresiones de los contratos que hoy faltan.

**Hallazgos prioritarios.** P1 indica problemas de autorización, consistencia o continuidad operativa con impacto importante. P2 indica fallos relevantes con alcance más acotado. Se especifica si la evidencia procede de ejecución o trazado; una reproducción aislada no equivale a una prueba completa sobre Android.

**1. P1 — El bloqueo de acceso se desactiva cuando la comprobación biométrica falla.**

MainActivity llama a recuperación ante cualquier resultado distinto de BIOMETRIC_SUCCESS. Esa recuperación marca la sesión como desbloqueada y guarda biometricLockEnabled=false, sin una autenticación exitosa. Incluye errores transitorios, estado desconocido y combinaciones no admitidas. [Comprobación y rama](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/MainActivity.kt:337), [desbloqueo](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/MainActivity.kt:380).

Además, la combinación BIOMETRIC_STRONG | DEVICE_CREDENTIAL no se admite en API 28–29, que forman parte del rango soportado por la app. Así lo especifica la [documentación oficial de AndroidX](https://developer.android.com/reference/androidx/biometric/BiometricManager). La incompatibilidad lleva al mismo desbloqueo. Verificado por código y documentación; no se ejecutó autenticación biométrica física. El alcance es la protección adicional de la app, no un bypass del bloqueo del sistema operativo.

Corrección: conservar la sesión bloqueada ante errores, seleccionar una ruta de credencial compatible con cada API y ofrecer recuperación autenticada. Pruebas necesarias: hardware temporalmente indisponible, opciones incompatibles, estado desconocido y API 28/29.

**2. P1 — Un administrador expulsado puede recuperar su membresía mediante una invitación pendiente.**

Reproducción nueva contra emuladores: un ADMIN cambia y verifica su correo, crea una invitación al correo nuevo, el OWNER lo elimina y ese mismo UID acepta la invitación. La eliminación devuelve éxito y elimina la membresía; la aceptación también devuelve éxito y restablece ADMIN. El fixture simula el cambio/verificación del correo mediante Admin Auth; las operaciones de negocio se ejecutaron con callables HTTP autenticadas.

La detección de miembros y la cancelación usan el correo histórico guardado en la membresía. La aceptación no invalida la invitación en función de la revocación de ese UID o la vigencia de la autorización que la originó. [Consulta por correo](/Users/gustavo/Desktop/ProyectoMayda/functions/membership.js:328), [aceptación](/Users/gustavo/Desktop/ProyectoMayda/functions/membership.js:576), [revocación](/Users/gustavo/Desktop/ProyectoMayda/functions/membership.js:737). [Evidencia HTTP](/Users/gustavo/Desktop/ProyectoMayda/build/reports/auditoria-2026-09-22/backend/email-revocation.jsonl).

Corrección: definir revocación e invitaciones sobre identidades estables y comprobar su vigencia al aceptar. Cubrir cambio de correo, auto-invitación y aceptación posterior a expulsión.

**3. P1 — Publicar y anular una compra puede impedir que otro teléfono sincronice.**

El servidor mantiene syncChanges por purchaseId y sobrescribe el documento al anular. El cliente exige todas las secuencias consecutivas. Reproducción: alta seq1, anulación seq2, lectura desde cursor0; el servidor devuelve únicamente seq2. Android espera seq1 y rechaza la página. Reintentar no recupera el evento reemplazado. Un dispositivo que ya recibió seq1 evita este caso concreto.

[Documento mutable](/Users/gustavo/Desktop/ProyectoMayda/functions/index.js:1083), [sobrescritura](/Users/gustavo/Desktop/ProyectoMayda/functions/index.js:1559), [contigüidad y cobertura exigidas](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/SyncUseCases.kt:306). [Reproducciones del backend](/Users/gustavo/Desktop/ProyectoMayda/build/reports/auditoria-2026-09-22/backend/reproduce-contracts.jsonl).

Corrección: adoptar eventos inmutables o una réplica explícita del último estado, y alinear servidor, cursor, caché y reconciliación. Quitar solamente una comprobación del cliente dejaría otras suposiciones incompatibles.

**4. P2 — El servidor acepta compras que Android después no puede interpretar.**

Se reprodujeron postPurchase exitosos con issueDate=2026-99-99 y currency=ZZZ. Functions valida su forma con expresiones regulares; Android exige fecha real y moneda reconocida. Los documentos entran al feed, pero el mapper rechaza la página entera. Una operación autenticada inválida puede bloquear el avance de la sincronización de ese negocio. Requiere un cliente modificado o una futura regresión; la UI actual no genera normalmente estos valores.

[Validación insuficiente](/Users/gustavo/Desktop/ProyectoMayda/functions/index.js:464), [decodificación Android](/Users/gustavo/Desktop/ProyectoMayda/app/src/cloud/java/com/facturastock/app/data/sync/SyncPullMappers.kt:56). La aceptación se ejecutó contra emuladores y el rechazo contra clases Android compiladas en un harness JVM. [Resultado del mapper](/Users/gustavo/Desktop/ProyectoMayda/build/reports/auditoria-2026-09-22/backend/mapper-contracts.log).

Corrección: compartir un contrato semántico versionado, no solo forma de JSON. Cualquier documento aceptado por el servidor debe superar el parser cliente. También hace falta una estrategia controlada para reparar datos inválidos ya almacenados sin saltarse silenciosamente el libro.

**5. P1 — Un saldo remoto puede ocultar el efecto de una compra local pendiente.**

Ejemplo: saldo compartido 10; una compra local suma 5 y su envío queda pendiente o en conflicto; otro teléfono vende 2 y genera snapshot remoto 8. El aplicador sustituye el saldo local 15 por 8, aunque la compra y su movimiento +5 permanecen en el libro local. Para representar ambos efectos debería conservarse un componente pendiente y mostrarse 13. Un envío posterior exitoso puede corregirlo; un conflicto permanente no lo garantiza.

El worker hace pull después de una pasada que también puede contener reintentos o conflictos. El aplicador no reconcilia efectos pendientes antes de reemplazar el saldo. También puede aparecer una compra nueva entre el envío y la descarga. [Worker](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/sync/PurchaseBackupSyncWorker.kt:396), [sustitución](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSharedInventoryApplicationRepository.kt:340).

Se comprobó el recorrido por código y se ejecutó el SQL productivo del reemplazo en SQLite en memoria: acepta pasar de 15 a 8. Esto no sustituye una prueba integrada con dos instalaciones. [Evidencia de persistencia](/Users/gustavo/Desktop/ProyectoMayda/build/reports/auditoria-2026-09-22/persistence/persistence_probe.txt). Corrección: separar saldo remoto confirmado y deltas locales pendientes, o establecer una barrera coherente por producto/almacén mientras se resuelve la operación.

**6. P1 — Un carrito compartido de 101 líneas puede quedar bloqueado sin posibilidad de corregirlo.**

Android admite el carrito y guarda una intención durable antes del envío, pero Functions limita la venta a 100 líneas. El rechazo definitivo INVALID_ARGUMENT / SALE_LINES_LIMIT se convierte en Rejected. Ese resultado conserva la intención pendiente; edición y eliminación quedan prohibidas, y volver a Ventas recupera el mismo carrito. Reintentar envía las mismas 101 líneas. No se encontró una salida de recuperación para este rechazo.

[Límite remoto](/Users/gustavo/Desktop/ProyectoMayda/functions/saleSync.js:313), [intención previa al envío](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSaleRepository.kt:671), [condiciones que permiten liberarla](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSaleRepository.kt:502). El canonicalizador real de Functions rechazó 101 líneas; el esquema y las guardas seleccionadas permitieron almacenarlas, registrar la intención y después bloquear su edición. [Prueba del límite](/Users/gustavo/Desktop/ProyectoMayda/build/reports/auditoria-2026-09-22/persistence/sale_limit_probe.txt). Es una reproducción por componentes más trazado completo, no una ejecución Android/Firebase de extremo a extremo.

Corrección: validar límites antes de congelar y diferenciar rechazos definitivos sin commit de respuestas ambiguas. Los primeros deben permitir corregir el carrito; las segundas necesitan conservar identidad y consultar o reintentar con seguridad.

**Otros defectos y límites relevantes.**

**7. P2 — Spark rechaza toda página de compras no vacía por una clave ausente.** El adaptador asPullWire omite syncedBy; el mapper compartido exige esa clave y valor null. El harness ejecutado con clases compiladas confirma que la forma Spark falla y la misma forma con syncedBy:null pasa. Afecta Spark; no es un fallo equivalente del adaptador de Functions. [Adaptador](/Users/gustavo/Desktop/ProyectoMayda/app/src/cloud/java/com/facturastock/app/data/sync/FirebaseRemoteLedgerRepository.kt:216), [contrato exacto](/Users/gustavo/Desktop/ProyectoMayda/app/src/cloud/java/com/facturastock/app/data/sync/SyncPullMappers.kt:316). Debe conservarse null, sin filtrar al feed la identidad de cuenta.

**8. P2 — Una venta con costo de inventario en USD y cobro en PEN puede publicarse, pero no anularse.** El checkout conserva correctamente la moneda del costo del saldo; el validador de anulación exige que coincida con la moneda del cobro. Trata una historia permitida al publicar como InvalidHistory al devolver. [Moneda conservada al vender](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSaleRepository.kt:846), [restricción al anular](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSaleVoidRepository.kt:173). Se trazaron ambos caminos y se comprobaron las guardas SQL productivas en SQLite aislado. Hay que separar moneda del costo y moneda del reembolso en repositorio y triggers; no inventar una conversión.

**9. P2 — Reportes puede conservar el día anterior si su primera emisión llega después de medianoche.** El rango se calcula antes de consultar Room. Si se abre a las 23:59:59 y la primera respuesta llega a las 00:00:01 sin abandonar la pantalla, se publica el rango antiguo y no se programa actualización porque su final ya pasó. Se recupera al reabrir o cambiar período. [Temporizador](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/reports/ReportsViewModel.kt:515). Evidencia por trazado; el test existente introduce un Resumed adicional y cubre otro escenario. Corregir reabriendo una observación expirada con protección contra bucles.

**10. P2 — El campo del lector consume Tab y Shift+Tab aunque esté vacío.** El router HID puede dejar pasar navegación sin una lectura pendiente, pero el EditText vuelve a consumir esas teclas como terminadores. Esto impide salir del campo con teclado físico. [Consumo incondicional](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/common/ScannerCodeInput.kt:347). Verificado por código, pendiente prueba con teclado real. Distinguir fin de una lectura activa de navegación normal y respetar modificadores.

**11. P2 — El editor general de Catálogos conserva una ruta que puede reponer stock antiguo.** Precarga cantidad y, al guardar incluso un cambio de nombre, ejecuta saveProduct seguido de setStock en operaciones separadas. Abrir con 10, vender 2 y guardar puede reponer el saldo a 10. Si el segundo paso falla, los metadatos ya quedaron guardados. [Guardado](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/catalogs/CatalogsViewModel.kt:1211).

El alcance importa: el editor habitual Inventario → Editar ya utiliza snapshots y no tiene esa misma conducta. El catálogo general carece de entrada directa en el menú y se alcanza por recorridos como matching OCR confirmado. Debe retirarse o unificarse esa ruta secundaria con el editor actual. También hay un problema P3 de paginación en ese catálogo: una emisión reactiva de la primera página reemplaza las páginas acumuladas mediante Cargar más. [Observación](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/catalogs/CatalogsViewModel.kt:496).

**12. La recuperación integral todavía no es una función terminada.** El coordinador de restauración devuelve NOT_READY aun con snapshot y recibo válidos; falta detener escritores, cerrar Room y activar de forma coordinada la base restaurada. [Compuerta actual](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/restore/FullDeviceSnapshotRestoreReadiness.kt:194).

Además, el backup automático y la transferencia de dispositivo están excluidos por el manifiesto y las reglas de extracción. La exportación JSON es parcial: excluye ventas, deudas, abonos, imágenes y otros datos operativos; la UI lo declara. [Alcance de exportación](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/model/PrivacyModels.kt:379), [exclusiones Android](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/res/xml/data_extraction_rules.xml:1). La sincronización no debe interpretarse como restauración integral de una instalación perdida. Completar y ensayar ese recorrido es prioritario antes de depender del teléfono como único registro.

**13. P2 — El árbol de dependencias no supera el gate de seguridad configurado.** npm audit devolvió 13 paquetes afectados: 3 de severidad alta y 10 moderada. Al excluir desarrollo permanecen sharp, alta, y qs, moderada. Son paquetes afectados, no 13 fallos explotables independientes de esta app. [Auditoría completa](/Users/gustavo/Desktop/ProyectoMayda/build/reports/auditoria-2026-09-22/npm-audit.json), [solo producción](/Users/gustavo/Desktop/ProyectoMayda/build/reports/auditoria-2026-09-22/npm-production-audit.json).

Sharp está fijado a 0.35.3 y el mantenedor identifica versiones corregidas desde 0.35.4. [Aviso oficial](https://github.com/lovell/sharp/security/advisories/GHSA-rgj7-g3m4-5g8c). El endpoint exige firma/formato JPEG y limita tamaño y dimensiones, lo que restringe exposición; no se demostró explotación. Conviene actualizar y validar de forma controlada. No ejecutar audit fix indiscriminadamente: algunas propuestas afectan versiones mayores de herramientas. El gate CI usa audit-level=high, por lo que este resultado lo bloquea.

**14. P2 — El historial actual incumple la propia política CI de esquemas Room.** Ejecutar scripts/verify-room-schema-history.sh falla con: “schema history may add only the next version, 28.json; added=28 29”. El commit actual agrega v28 y v29 respecto a su padre, pero el script solo acepta una versión nueva. Los eventos schedule y workflow_dispatch toman HEAD^ como baseline, por lo que también encuentran esa condición. [Restricción](/Users/gustavo/Desktop/ProyectoMayda/scripts/verify-room-schema-history.sh:56), [baseline](/Users/gustavo/Desktop/ProyectoMayda/scripts/resolve-spotless-base.sh:29), [ejecución](/Users/gustavo/Desktop/ProyectoMayda/build/reports/auditoria-2026-09-22/room-schema-history.txt).

Esto no demuestra una migración defectuosa: es una incompatibilidad entre historial y política de validación. Hay que decidir si se permiten varias versiones consecutivas e inmutables por cambio y adaptar la comprobación manteniendo la protección append-only. No es necesario reescribir historia publicada para resolverlo.

**Validación ejecutada en esta revisión.**

| Comprobación | Resultado | Qué acredita |
| --- | --- | --- |
| JVM localDebug | 1.843 pruebas; 0 fallos, errores u omitidas | Ejecución nueva de la suite local |
| JVM cloudDebug | 2.021 pruebas; 0 fallos, errores u omitidas | Ejecución nueva; comparte muchas pruebas con local |
| Lint local/cloud | 0 errores; 76 advertencias por variante | Análisis estático de ambas variantes |
| Kover local | 87,44% de líneas; mínimo 80% aprobado | 12.845 cubiertas de 14.690; filtrado al dominio |
| ciStaticAnalysis | Aprobado, baseline HEAD^ explícito | Formato y fronteras arquitectónicas; varias subtareas reutilizadas |
| APK debug local/cloud | Ambos compilados correctamente | Empaquetado; no instalación ni ejecución en dispositivo |
| Fuentes instrumentadas local/cloud | Compilación Kotlin correcta | No equivale a ejecutar las pruebas instrumentadas |
| Backend Firebase | 176 aprobadas; 1 omitida; 0 fallos | Suite convencional con emuladores |
| Reglas Spark | 14 aprobadas; 0 fallos | Suite separada con reglas Spark |
| Reproducciones adversariales | Alta/anulación, datos inválidos y reingreso tras expulsión confirmados | HTTP autenticado en emuladores; datos sintéticos |
| Contrato del mapper | Spark sin clave falla; control válido pasa | Clases productivas compiladas, harness JVM |
| Integridad de saldo/anulación/carrito | Conductas defectuosas confirmadas con SQL y canonicalizador reales | Componentes aislados; no Room/Firebase E2E |
| Scripts y assets | Seis suites de contratos/políticas y assets Play aprobados | Algunas pruebas usan dobles; no certifican un release instalado |
| Escáner de secretos | Sin credenciales de alta confianza detectadas | Alcance del scanner del repositorio |
| npm audit | Falla por alertas altas | Gate de seguridad pendiente |
| Historial de esquemas | Falla por agregar 28 y 29 en un cambio | Gate de historial pendiente |

[Log Gradle principal](/Users/gustavo/Desktop/ProyectoMayda/build/reports/auditoria-2026-09-22/gradle.log), [resumen JVM](/Users/gustavo/Desktop/ProyectoMayda/build/reports/auditoria-2026-09-22/unit-test-summary.json), [análisis estático y empaquetado](/Users/gustavo/Desktop/ProyectoMayda/build/reports/auditoria-2026-09-22/static-build.log), [cobertura de dominio](/Users/gustavo/Desktop/ProyectoMayda/build/reports/auditoria-2026-09-22/kover-local.xml). La ejecución Gradle principal finalizó BUILD SUCCESSFUL; reutilizó 62 tareas válidas y ejecutó 40, incluidas ambas tareas de pruebas. La segunda ejecución, estática y de empaquetado, también terminó correctamente. No se suman las dos variantes como casos únicos.

La revisión se ejecutó con Java 21.0.11 y Node 24.18.0. El backend declara Node 22 y CI usa su matriz propia; los resultados locales no certifican identidad de entorno de distribución. No se instalaron paquetes en el dispositivo conectado, no se ejecutaron pruebas Android instrumentadas, cámara, lector físico, TalkBack, macrobenchmarks, firma ni distribución. Tampoco se inspeccionó Firebase productivo o el estado de ejecuciones remotas de GitHub.

**Mantenibilidad y próximos pasos.**

La dificultad principal está en contratos duplicados entre caminos: servidor frente a parser, saldo confirmado frente a saldo pendiente, edición nueva frente a edición general, publicación frente a anulación. SalesViewModel tiene 2.720 líneas; FacturaStockApp, 1.737; CatalogsViewModel, 1.650; y app/build.gradle.kts, 2.377. El tamaño no prueba lentitud, pero aumenta el costo de mantener garantías equivalentes.

Recomiendo primero centralizar contratos y después extraer coordinadores de checkout, escáner y registro. Separar módulos sin resolver esos contratos solo desplazaría el problema. El historial Git tiene dos commits amplios, lo que dificulta localizar regresiones. Conviene registrar cambios pequeños con su prueba de secuencia y distinguir documentación vigente de auditorías históricas.

El proyecto ya usa trabajo fuera del hilo principal, consultas e índices, lecturas incrementales y presupuestos de rendimiento. Esta revisión no aporta una medición nueva en dispositivo; no permite afirmar cuánto tarda una pantalla ni cuánto mejora la optimización reciente. Del mismo modo, el corpus de fixtures de OCR/parser no acredita exactitud sobre fotografías reales de proveedores.

El orden de trabajo recomendado es:

1. Cerrar acceso y revocación: ningún error concede sesión; una invitación previa no revierte una expulsión.
2. Unificar contratos de sincronización y límites: todo dato aceptado por backend puede interpretarse, y un primer pull después de alta/anulación termina correctamente.
3. Proteger saldo y contabilidad: una operación local pendiente conserva su efecto; costo y cobro mantienen monedas separadas al anular; todos los editores usan las mismas garantías.
4. Completar recuperación: restaurar desde una instalación limpia y probar interrupción, espacio insuficiente y verificación posterior del libro.
5. Resolver dependencias, política CI y detalles de teclado/reportes; después medir el artefacto real en los dispositivos objetivo.

Las pruebas de mayor valor son recorridos completos: carrito de 101 líneas → rechazo → corrección; publicar → anular → sincronizar teléfono nuevo; compra local pendiente → movimiento de otro equipo; expulsar → aceptar invitación; compra USD → venta PEN → anulación; abrir reporte → primera respuesta después de medianoche; editar metadatos → cambio concurrente de stock → guardar. Las suites verdes actuales no garantizan esas secuencias.
