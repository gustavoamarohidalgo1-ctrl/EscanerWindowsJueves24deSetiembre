**FacturaStock — análisis profundo del código actual, 19 de septiembre de 2026**

FacturaStock tiene una base técnica elaborada, pruebas abundantes y controles de integridad útiles. Sin embargo, encontré defectos que afectan autorización, existencias, valoración y sincronización. Mi recomendación es estabilizar esos contratos antes de extender el uso compartido entre teléfonos o confiar en los reportes como única referencia de utilidad. La variante local también tiene problemas concretos; desactivar la nube no elimina los fallos de edición de stock, unidades y costos.

El análisis corresponde al commit e1d03a2, rama main, con el árbol inicialmente limpio. Se revisaron fuentes productivas, pruebas, configuración de compilación, reglas Firebase, CI y documentación. Las auditorías anteriores se utilizaron como pistas y se contrastaron con la implementación vigente. Se ejecutaron comprobaciones nuevas y reproducciones aisladas. No se modificaron fuentes productivas, dependencias, reglas ni datos reales del negocio. Este informe es el único archivo nuevo fuera de directorios de salida ignorados.

**Qué producto existe realmente**

Es una app Android nativa en español: compras desde fotografía/OCR o revisión manual, catálogo, inventario, ventas, productos por peso, lector físico de códigos, cuentas por cobrar, abonos y reportes/PDF. El código configura versión 1.0.12, versionCode 13, API mínima 26, compileSdk/targetSdk 36 y Room v29.

Hay dos módulos Gradle: app y benchmark. La separación funcional dentro de app se realiza mediante paquetes, interfaces e inyección Hilt. Kotlin, Compose y Material 3 resuelven presentación; Room conserva los datos; CameraX y ML Kit procesan documentos; WorkManager gestiona tareas diferidas. El servidor usa Node, Firebase Auth, Firestore, Storage y Functions.

~~~mermaid
flowchart TD
    UI[Compose y ViewModels] --> UC[Casos de uso y modelos de dominio]
    UC --> PORT[Interfaces de repositorio]
    PORT --> DB[Room y archivos privados]
    DB --> OUT[Outbox durable]
    OUT --> CLOUD[Functions: compras, ventas, pagos y catálogo]
    CLOUD --> FIRE[Firestore y Storage]
    FIRE --> PULL[Lectura incremental y aplicación local]
    PULL --> DB
    OUT --> SPARK[Alternativa Spark: escrituras directas]
    SPARK --> FIRE
~~~

La variante local elimina INTERNET y Firebase. La variante cloud conserva datos locales y agrega sincronización. El carrito puede prepararse sin conexión, pero las ventas y abonos de un negocio cloud vinculado requieren autorización remota antes del commit local. Las compras siguen otra estrategia: se confirman localmente y se envían mediante outbox. Esa diferencia importa para el hallazgo de saldos pendientes.

Spark es un build type independiente, depurable y firmado con clave debug, que utiliza Firebase real y escrituras directas. No debe confundirse con cloudRelease ni con la seguridad transaccional de Functions. Referencia: [configuración de variantes](/Users/gustavo/Desktop/ProyectoMayda/app/build.gradle.kts:267).

El inventario de fuentes cuenta 619 archivos Kotlin productivos o de soporte de variantes en app, con 140.748 líneas; 243 archivos JVM de pruebas, con 72.256 líneas; 127 archivos instrumentados, con 52.227 líneas; 11 archivos JS productivos, con 8.024 líneas, y 14 archivos de pruebas backend, con 10.744 líneas. Los conteos incluyen comentarios y blancos y excluyen código generado, dependencias y benchmark. Hay 29 esquemas Room exportados. Estos tamaños describen el proyecto; no son una medida de calidad ni de cobertura.

**Fortalezas comprobadas**

- El dinero y las cantidades emplean unidades menores, BigDecimal o aritmética decimal equivalente, con redondeo y límites explícitos. Los problemas encontrados son principalmente de significado y procedencia del dato, no de precisión binaria.
- Room usa WAL, migraciones explícitas 1→29 y evita migración destructiva. La publicación de una compra agrupa documento, líneas, movimientos, saldo, auditoría y outbox en una transacción.
- El checkout conserva intención durable e identidad de operación. Hay control de versión, idempotencia, recuperación de ACK perdido y anulaciones compensatorias que preservan historia.
- Outbox tiene claims, leases, reintentos y dependencias causales. Cache y cursor se guardan juntos. Las operaciones de cuenta protegen cambios concurrentes de identidad.
- En Blaze se deniegan escrituras cliente directas del negocio; Functions verifica identidad, correo, roles y App Check. El borrado de cuentas y los respaldos documentales incluyen recuperación durable, cuotas y límites.
- El OCR es local; existen protección de rutas, tratamiento de imágenes retenidas, exclusión del backup automático y consentimientos para diagnóstico. El bloqueo biométrico, sin embargo, tiene el defecto descrito abajo.
- CI tiene comprobaciones de arquitectura, secretos, pruebas, cobertura, emuladores, migraciones, UI, rendimiento y artefactos. Pasar esos controles aporta evidencia valiosa, pero no demuestra que todos los contratos entre componentes sean compatibles.

**Hallazgos prioritarios**

P1 indica alta prioridad por autorización o integridad. P2 indica un defecto relevante de disponibilidad, valoración o experiencia. “Reproducción” identifica ejecución en esta revisión; “trazado” identifica comprobación por código, sin atribuirle una prueba de dispositivo que no se ejecutó.

**1. P1 — El bloqueo de la aplicación permite entrar cuando la comprobación de autenticación falla.**

MainActivity utiliza BIOMETRIC_STRONG junto con DEVICE_CREDENTIAL. Cualquier resultado distinto de SUCCESS llama a una recuperación que marca la sesión desbloqueada y persiste biometricLockEnabled=false. No distingue hardware temporalmente indisponible, estado desconocido, opciones incompatibles ni ausencia de credenciales.

Hay un caso determinista de compatibilidad: AndroidX documenta que esta combinación no se admite en API 28–29, versiones incluidas en el rango soportado por la app. Por ello, activar el bloqueo en Android 9 o 10 no ofrece la protección esperada; la comprobación lo desactiva sin una autenticación exitosa. Los otros estados de error también recorren esa rama. Esto afecta la protección adicional de la app, no demuestra una vulneración del bloqueo del sistema operativo.

Evidencia: [comprobación](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/MainActivity.kt:337), [desbloqueo y desactivación](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/MainActivity.kt:380), [contrato oficial de AndroidX](https://developer.android.com/reference/androidx/biometric/BiometricManager). Validación por código y documentación oficial; no se ejecutó un dispositivo API 28–29.

Corrección propuesta: conservar el bloqueo ante errores transitorios o desconocidos, ofrecer reintento y seleccionar una ruta de credencial compatible por versión. Desactivar una protección configurada requiere un mecanismo de recuperación autenticado y explícito. Agregar pruebas de cada estado de canAuthenticate y de API 28/29.

**2. P1 — Guardar un producto desde Catálogos puede reponer stock vendido o alterar sólo la copia local de un negocio cloud.**

El formulario general precarga existencias. Al guardar, aunque sólo se cambie el nombre, vuelve a aplicar setStock con esa cantidad antigua. Abrir con 10, vender una unidad y guardar el nombre puede devolver el saldo a 10. El control de versión del producto no detecta que cambió el saldo. Cambiar la ubicación a otro almacén tampoco realiza una transferencia compensada del stock anterior.

Además, el guardado del producto y el ajuste son operaciones separadas. Este camino evita las protecciones del editor especializado de Inventario: el ajuste no comprueba el vínculo cloud ni genera sincronización de inventario. Un teléfono puede quedar con una cantidad que los otros no reciben y que el siguiente snapshot remoto reemplaza.

Evidencia por trazado: [precarga](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/catalogs/CatalogsViewModel.kt:943), [guardado](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/catalogs/CatalogsViewModel.kt:1151), [delta hacia el valor del formulario](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomProductInventoryRepository.kt:60). No se ejecutó una reproducción Room nueva de esta carrera.

Corrección propuesta: unificar todos los caminos de edición en el repositorio transaccional especializado. Guardar metadatos no debe modificar existencias. Una corrección o transferencia necesita intención explícita, versión de saldo y política cloud coherente.

**3. P1 — Publicar y anular una compra puede bloquear la sincronización de un teléfono nuevo.**

El servidor conserva un documento por purchaseId en syncChanges. La anulación sustituye la secuencia del alta. Android, en cambio, exige recibir todas las secuencias consecutivas y la reconciliación exige cobertura íntegra desde 1.

Reproducción HTTP contra Functions: publicar compra seq1, anularla seq2 y consultar desde cursor0 devuelve solamente [2], estado VOIDED y nextCursor2. El caso de uso Android rechaza esa página antes de persistir; cursor0 permanece igual y el reintento no resuelve nada. Afecta también a teléfonos que estuvieron desconectados entre alta y anulación. Uno que ya guardó seq1 puede aceptar seq2.

Evidencia: [documento por compra](/Users/gustavo/Desktop/ProyectoMayda/functions/index.js:1083), [sobrescritura al anular](/Users/gustavo/Desktop/ProyectoMayda/functions/index.js:1559), [exigencia del cliente y reconciliación](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/SyncUseCases.kt:307). Spark conserva la misma semántica mutable. La reproducción de servidor y dos caracterizaciones JVM ejecutadas confirmaron el fallo y el control con cursor previo. Están en la carpeta de evidencia.

Corrección propuesta: elegir entre eventos inmutables y réplica del último estado. Alinear servidor, cliente, cache y reconciliación con ese contrato. Eliminar únicamente una comprobación de contigüidad dejaría otras suposiciones incompatibles.

**4. P1 — Un snapshot remoto puede borrar del saldo el efecto de una compra local pendiente.**

Escenario: ambos teléfonos conocen 10 unidades. A publica localmente una compra +5; su envío queda en reintento o conflicto. B vende 2 y la nube queda en 8. El pull de A sustituye 15 por 8, aunque la compra y su movimiento +5 siguen publicados localmente. Si el envío termina después, un evento posterior puede recuperar el saldo; si queda en conflicto o fallo, la incoherencia puede persistir.

El aplicador copia cantidades y costo del snapshot sin incorporar efectos locales pendientes. El worker hace pull aunque haya reintentos, conflictos o fallos en la pasada; también existe pull manual. “Enviar antes de descargar” no cubre esas situaciones ni una compra concurrente.

Evidencia por trazado: [sustitución del saldo](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSharedInventoryApplicationRepository.kt:300), [orden del worker](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/sync/PurchaseBackupSyncWorker.kt:396), [pull manual](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/sync/SyncViewModel.kt:538). Falta reproducción integrada con dos bases/dispositivos; el recorrido y la ausencia de barrera están verificados en código.

Corrección propuesta: separar saldo remoto confirmado y efectos pendientes, reconciliando por identidad de operación; alternativamente, impedir de forma coherente la aplicación sobre saldos afectados hasta resolver las operaciones pendientes. Probar también costos y anulaciones locales pendientes.

**5. P1 — Un administrador expulsado puede recuperar acceso mediante una invitación que él mismo dejó pendiente.**

Reproducción en emulador: ADMIN cambia y verifica su email, se invita con el correo nuevo, OWNER lo elimina y ADMIN acepta la invitación pendiente. Recupera rol ADMIN con el mismo UID. La membresía guardaba el correo antiguo; la revocación sólo cancela la invitación asociada a ese snapshot. La aceptación no valida que siga vigente la autorización del invitador.

Evidencia: [búsqueda por email guardado](/Users/gustavo/Desktop/ProyectoMayda/functions/membership.js:328), [revocación](/Users/gustavo/Desktop/ProyectoMayda/functions/membership.js:737), [aceptación](/Users/gustavo/Desktop/ProyectoMayda/functions/membership.js:576). La verificación del email se simuló con Admin Auth del emulador; las operaciones de negocio usaron callables HTTP y tokens del emulador.

Corrección propuesta: vincular invitaciones a identidad y vigencia de autorización; al revocar, invalidar las pendientes relacionadas. Actualizar sólo el campo email de la membresía no resuelve toda la causalidad.

**6. P1 — Functions acepta una compra que referencia un producto remoto inexistente.**

Reproducción con OPERATOR, compra v4 y productProvenance EXISTING: el UUID no tiene documento de catálogo, pero postPurchase devuelve RECORDED y crea un saldo. Las lecturas transaccionales no incluyen los productos referenciados.

Android necesita resolver ese producto para aplicar el evento; el fallo revierte la página y evita avanzar el cursor. La escritura inválida está reproducida; el bloqueo potencial del consumidor se verificó por trazado, sin prueba completa entre dos dispositivos.

Evidencia: [lecturas transaccionales](/Users/gustavo/Desktop/ProyectoMayda/functions/index.js:1099), [resolución cliente](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSharedInventoryApplicationRepository.kt:745).

Corrección propuesta: validar referencias y pertenencia dentro de la transacción; dar a la outbox una dependencia catálogo→compra y un tratamiento recuperable cuando aún falta publicar una entidad.

**7. P1 — Dar de alta un producto por kilos puede convertir posteriores productos convencionales en kilos.**

El alta especial crea KGM. UnitDao ordena por código, por lo que KGM queda antes de NIU. El formulario de código nuevo elige la primera unidad activa sin presentar un selector. Tras registrar arroz por kilos, 12 botellas pueden guardarse con unidad KGM. Matching repite la elección al crear productos nuevos.

Evidencia: [creación de KGM](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/catalogs/CatalogsViewModel.kt:789), [selección convencional](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/catalogs/CatalogsViewModel.kt:880), [orden SQL](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/local/dao/UnitDao.kt:35), [matching](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/matching/InvoiceMatchingViewModel.kt:401).

Corrección propuesta: definir explícitamente la unidad convencional, respetar la unidad de origen y permitir confirmarla. Una prueba de caracterización externa se volvió a ejecutar y confirmó el defecto con acciones reales del ViewModel y repositorios fake; no sustituye una prueba Room/UI.

**8. P1 — Costo desconocido puede terminar presentado como costo cero y utilidad completa.**

El alta general permite cantidad sin costo. setStock crea un ajuste con costo nulo, pero el saldo inicia averageUnitCost en cero y lo conserva. El checkout toma ese promedio y publica costo histórico 0. El reporte lo trata como costo conocido. Ejemplo: tres unidades sin costo registrado, venta de una a S/10 y utilidad presentada S/10.

Evidencia por trazado: [ajuste y promedio](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomProductInventoryRepository.kt:194), [conservación cuando falta costo](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomProductInventoryRepository.kt:258), [checkout](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSaleRepository.kt:845), [reporte](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSaleRepository.kt:1218).

Corrección propuesta: conservar la disponibilidad/procedencia del costo en saldo e historia. Cero confirmado y desconocido son estados distintos; cambiar sólo el texto del reporte no corrige los datos históricos.

**Otros defectos relevantes**

**9. P2 — El ingreso rápido desde matching descarta ajustes de valoración.** El parser conserva descuento, impuesto y total de línea, pero ScannedItemMatch sólo transporta cantidad y costo unitario. El resolver multiplica ambos y no recibe la política NET/GROSS. Una línea 10×S/10, descuento S/20 y total S/80 puede ingresar valorizada en S/100. El usuario podría corregir el costo manualmente, pero la interfaz no advierte qué dato se descartó. Trazado: [parser](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/ParseInvoiceUseCase.kt:219), [proyección](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/MatchScannedInvoiceLinesUseCase.kt:59), [valoración](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/domain/usecase/InvoiceMatchingStockResolver.kt:54). Transportar ajustes o exigir revisión adicional cuando existan discrepancias. No emitir compra tributaria en este flujo no justifica valorar mal sus existencias.

**10. P2 — Un historial moderno de más de 100 movimientos puede bloquear abrir otro almacén.** Cuando bootstrapComplete no es true, como ocurre en negocios nuevos que no pasaron por bootstrap, el helper que autoriza crear un saldo inexistente consulta 101 movimientos del producto, sin restringir ubicación, y rechaza antes de distinguir historia moderna de legacy. Los negocios migrados con bootstrap completo omiten esta guarda. Reproducción aislada sobre la función real: 101 movimientos modernos del almacén principal y destino nuevo → INVENTORY_HISTORY_SCAN_LIMIT. La metadata existente impide usar bootstrap para resolverlo. [Límite](/Users/gustavo/Desktop/ProyectoMayda/functions/inventorySync.js:478), [bootstrap](/Users/gustavo/Desktop/ProyectoMayda/functions/inventoryBootstrap.js:392). Sustituir la inferencia sobre tamaño de historia por estado explícito de migración y consultas pertinentes.

**11. P2 — Spark permite un evento de venta huérfano y malformado.** Reproducción HTTP autenticada como OWNER: escribir metadata seq1 y un evento SALE con sale vacío y balances vacíos devuelve 200; el feed existe sin venta real. [Validación insuficiente](/Users/gustavo/Desktop/ProyectoMayda/firestore.spark.rules:500), [vínculo entre feed y metadata](/Users/gustavo/Desktop/ProyectoMayda/firestore.spark.rules:1094). Es un problema de integridad dentro del negocio del dueño, no acceso cruzado entre negocios. Reforzar el contrato de escrituras y la relación con documentos reales o limitar el alcance de esta modalidad. No atribuir este resultado a las reglas Blaze.

**12. P2 — Un teléfono que recibe compras remotas puede mostrar falsas divergencias de inventario.** El pull aplica el saldo de compra sin materializar su movimiento; el diagnóstico reconstruye stock exclusivamente desde movimientos locales, empezando en cero. Recibir +10 en un teléfono vacío deja stock10 e historia vacía y puede producir QUANTITY_DIVERGENCE y UNEXPLAINED_OPENING_BALANCE. [Aplicación parcial](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomSharedInventoryApplicationRepository.kt:155), [diagnóstico](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomInventoryReadRepository.kt:279). Trazado, sin reproducción instrumentada. Conservar hechos suficientes o hacer explícita la naturaleza parcial de la réplica; evitar ajustes sintéticos que dupliquen compras propias.

**13. P2 — La revisión de matching tiene caminos sin una salida útil.** Desvincular conserva la fila; no hay exclusión persistida de falsos positivos ni eliminación de extras manuales y el guardado exige completar todas las filas. Además, la búsqueda sólo ve catálogo persistido: dos filas del mismo artículo nuevo no pueden reutilizar fácilmente el producto provisional y pueden duplicarlo o chocar por barcode. [Acciones de matching](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/feature/matching/InvoiceMatchingViewModel.kt:245), [exigencia de cobertura](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/repository/RoomInvoiceMatchingCommitRepository.kt:108). Diseñar exclusiones auditables y un catálogo provisional compartido dentro de la revisión. Son hallazgos por código, sin recorrido visual en esta sesión.

**Dependencias y mantenimiento**

La auditoría npm actual reportó 13 paquetes afectados: 3 de severidad alta y 10 moderada. Al excluir dependencias de desarrollo quedan 2: sharp, alta, y qs, moderada. No son 13 vulnerabilidades necesariamente distintas: npm también cuenta paquetes afectados por dependencias transitivas. El comando npm audit con audit-level high retorna fallo, por lo que el gate de seguridad no está verde con el lockfile actual.

Sharp está fijado a 0.35.3; el mantenedor publica corrección desde 0.35.4 para problemas en libheif. El servidor valida firma JPEG antes de decodificar, lo que restringe la superficie; esta revisión no demostró explotación del fallo AVIF/HEIF en ese endpoint. Corresponde actualizar y validar, sin afirmar ejecución remota de código demostrada. [Advisory del mantenedor](https://github.com/lovell/sharp/security/advisories/GHSA-rgj7-g3m4-5g8c), [validación JPEG local](/Users/gustavo/Desktop/ProyectoMayda/functions/documentBackup.js:142). El aviso de qs y su versión corregida están en [el advisory del mantenedor](https://github.com/ljharb/qs/security/advisories/GHSA-4mjr-xmp4-gh2g). No se aplicó npm audit fix ni se cambiaron versiones.

El principal riesgo de mantenimiento es la concentración de responsabilidades: SalesViewModel tiene 2.592 líneas, app/build.gradle.kts 2.377, FacturaStockApp 1.726, la base Room 1.712 y varios archivos de UI superan 1.500. El tamaño por sí solo no prueba un defecto, pero aquí coincide con caminos alternativos que aplican reglas distintas. Antes de una división amplia en módulos, conviene unificar mutaciones de inventario, definir el contrato de sync y separar coordinación de venta/escáner/pago en componentes comprobables.

El historial Git disponible tiene sólo dos commits amplios. Eso limita bisect, identificación del cambio que introdujo un fallo y revisiones pequeñas. Conviene introducir cambios focalizados con la prueba que los justifica. La documentación es abundante, pero parte describe estados anteriores; debe distinguirse contrato vigente, limitación vigente e informe histórico. Por ejemplo, el documento de snapshot menciona v24–v27 mientras el esquema actual es v29.

**Respaldo y recuperación: limitación de producto confirmada**

La restauración completa del dispositivo todavía no es una función disponible. Hay productor/validador de archivo, preflight, journal y primitivas de sustitución, pero requestActivation devuelve NOT_READY. Faltan la pausa global de escritores, el cierre de Room, activación y recuperación coordinadas y el flujo de usuario. [Compuerta real](/Users/gustavo/Desktop/ProyectoMayda/app/src/main/java/com/facturastock/app/data/restore/FullDeviceSnapshotRestoreReadiness.kt:201).

El backup automático Android y la transferencia de dispositivo están excluidos por manifiesto/reglas. Eso protege privacidad, pero significa que una instalación local no tiene recuperación integral terminada si se pierde el teléfono o se borran sus datos. Exportar información y sincronizar registros no equivalen a restaurar completamente la instalación. Esta es una limitación reconocida, no una funcionalidad que se haya roto durante la revisión.

**Verificación ejecutada en esta revisión**

| Comprobación | Resultado y alcance |
| --- | --- |
| Pruebas JVM local | 1.760 ejecutadas de nuevo; 0 fallos, 0 errores, 0 omitidas |
| Pruebas JVM cloud | 1.938 ejecutadas de nuevo; 0 fallos, 0 errores, 0 omitidas; comparte muchas pruebas con local, no sumar como casos únicos |
| Android Lint local/cloud | Ejecutado: 0 errores y 75 advertencias en cada variante; los conjuntos se solapan |
| Cobertura | koverVerifyLocalDebug aprobado; exige 80% de líneas del dominio filtrado, no 80% de toda la aplicación |
| Análisis estático | ciStaticAnalysis aprobado usando HEAD^ como baseline Spotless; algunas subtareas de formato reutilizaron resultados válidos de Gradle |
| Firebase Blaze | 176 pruebas aprobadas y 1 caso Spark omitido por diseño; emuladores Auth, Firestore, Functions y Storage |
| Firebase Spark | 14 pruebas aprobadas en ejecución separada; el primer arranque paralelo falló por entorno y se repitió tras reiniciar |
| Reproducciones backend | Confirmados reingreso ADMIN, producto ausente y feed [2] después de alta/anulación |
| Reglas Spark adversariales | Confirmado HTTP200 al evento SALE huérfano con dueño autenticado |
| Caracterizaciones JVM aisladas | 3 aprobadas: fallo persistente del primer pull, control con cursor previo y alta convencional que toma KGM; PASS confirma el comportamiento descrito |
| Límite de historial | Helper real rechaza 101 movimientos modernos con INVENTORY_HISTORY_SCAN_LIMIT |
| Scripts auxiliares | Pasaron los cinco scripts test-*.rb, test-resolve-spotless-base.sh y verificación de assets Play |
| Escáner de secretos | Sin credenciales de alta confianza ni archivos de clave detectados; no equivale a auditoría IAM/producción |
| Dependencias npm | Fallo del gate high: 13 paquetes afectados; 2 en producción |

La primera invocación Gradle consideró las tareas JVM UP-TO-DATE. Para no presentar resultados históricos como una ejecución nueva, se volvieron a ejecutar con una configuración temporal que desactiva la reutilización de las tareas Test. Se conservaron sus XML antes de correr las caracterizaciones aisladas. Los logs registran las tareas ejecutadas y reutilizadas.

Entorno local: Java 21.0.11, Node 24.18.0. Functions y CI declaran Node 22; el emulador utilizó el runtime disponible. Por tanto estas ejecuciones no certifican equivalencia con el runtime Node 22 de despliegue. No había dispositivo conectado en adb. No se ejecutaron pruebas instrumentadas de Room/UI, cámara física, lector HID, muerte de proceso en Android, benchmarks, firma ni distribución. No se inspeccionaron Firebase desplegado, IAM ni datos productivos. Tampoco se midió cobertura global o rendimiento real en esta sesión.

Evidencia local: [carpeta del análisis](/Users/gustavo/Desktop/ProyectoMayda/build/reports/analisis-2026-09-19), [resumen JVM](/Users/gustavo/Desktop/ProyectoMayda/build/reports/analisis-2026-09-19/unit-test-summary.json). Las caracterizaciones que afirman comportamientos defectuosos se interpretan al revés de una regresión correctiva: que pasen confirma el defecto descrito, no su solución.

**Orden recomendado de corrección y criterios de aceptación**

1. Corregir autenticación y revocación: un fallo biométrico no concede acceso; una expulsión invalida las autorizaciones pendientes correspondientes. Validar API 28/29 y cambios de email.
2. Unificar edición de inventario y referencias cloud: guardar nombre no cambia saldo; una transferencia conserva cantidad total; una compra remota no puede dejar referencias de catálogo inválidas.
3. Alinear el protocolo completo de sincronización: alta→anulación→primer pull debe completar; una compra local pendiente no desaparece del saldo operativo al recibir otro evento. Probar con dos dispositivos, conflicto y ACK perdido.
4. Corregir semántica de unidades y costos: kilos no cambian las altas convencionales; desconocido no es cero; descuentos y ajustes llegan a valoración. Verificar reportes después de esas secuencias.
5. Resolver límite de historial, reglas Spark y diagnóstico de réplica. Mantener explícito qué operaciones soporta cada modo.
6. Cerrar dependencias reportadas y volver a ejecutar el gate de seguridad. Después, completar recuperación integral y pruebas instrumentadas del flujo de pérdida/restauración antes de presentarlo como respaldo recuperable.

El objetivo inmediato es lograr que las garantías existentes se apliquen a todos los caminos accesibles. Agregar más controles aislados o más funcionalidades antes de corregir estas discrepancias aumentaría el número de combinaciones que hoy las pruebas no cubren.
