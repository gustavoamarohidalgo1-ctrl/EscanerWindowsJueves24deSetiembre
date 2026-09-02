# Auditoría Fase A — Fundamentos Android (prompts 1–10)

Fecha: 21 de agosto de 2026 (`America/Lima`). Revisión **de código, no de compilación**: esta máquina
no tiene JDK (`java -version` responde «Unable to locate a Java Runtime»), así que ningún criterio que
exija Gradle se pudo ejecutar aquí.

## Resultado por prompt

| # | Prompt | Estado | Base de la conclusión |
| --- | --- | ---: | --- |
| 1 | Proyecto Android base | CUMPLE con reserva | `namespace`/`applicationId` `com.facturastock.app`, minSdk 26, JVM 17, una sola `MainActivity`, manifiesto con **solo** `CAMERA` y `allowBackup="false"`. La reserva es de ejecución: `assembleDebug` no se pudo correr |
| 2 | Dependencias y versiones reproducibles | CUMPLE | `gradle/libs.versions.toml` sin `+`, `SNAPSHOT` ni rangos; están las 15 familias exigidas (Compose BOM, Navigation, Lifecycle, coroutines, Hilt, KSP, Room, CameraX, ML Kit texto y barcode, DataStore, WorkManager, Coil, JUnit/Turbine/AndroidX Test). ML Kit va integrado en el APK, sin API key |
| 3 | Arquitectura y tipos de dominio | CUMPLE | Paquetes `core/data/domain/di/navigation/ui/feature`; `Money` (minorUnits `Long` + ISO 4217), `Quantity`, `UnitCost`; `DraftStatus`, `PurchaseStatus`; errores sellados en `FacturaStockError.kt`; `DispatcherProvider`, `AppClock`, `UuidGenerator` inyectables; PEN/`es_PE` configurables. `Float`/`Double` prohibidos en `domain` por `verifyDomainBoundaries` |
| 4 | Diseño móvil accesible | CUMPLE | Tema claro/oscuro, `Spacing.minimumTouchTarget = 48.dp` usado en 20 puntos de UI, los diez componentes reutilizables presentes, `@ThemePreviews` y `@LargeFontPreview` con `fontScale = 2f` exigidos por `verifyUiConventions`, y `HomeScreenAccessibilityTest` afirmando nombre, rol, encabezado y 48 dp |
| 5 | Navegación completa | CUMPLE | `AppRoutes` cubre el flujo entero (origen → cámara → vista previa → procesamiento → cabecera → líneas → vinculación → resumen → confirmación → éxito → detalle); las rutas transportan **solo** IDs, con un `require` que valida que los argumentos declarados coincidan con el patrón; descarte confirmado por diálogo con retroceso protegido en `FacturaStockApp.kt:265`; deep link interno único y validado en `InternalDeepLinks.resolve` |
| 6 | Hilt y contrato UDF | CUMPLE | `FacturaStockApplication` con Hilt y 7 módulos; 22 pares `*Contract`/`*ViewModel`; ningún `MutableStateFlow` expuesto públicamente; 19 ViewModels leen sus IDs desde `SavedStateHandle`; el trabajo de disco ocurre en repositorios con `withContext` sobre el dispatcher IO inyectado (124 usos) |
| 7 | Esquema Room inicial | CUMPLE | Las nueve entidades exigidas existen entre 21; dinero en enteros; `InvoiceImageEntity` guarda ruta relativa validada (`!startsWith("/")`, sin `..`), `sha256`, dimensiones, rotación, página y recorte normalizado, **nunca un BLOB**; claves foráneas, índices y unicidad por negocio; `exportSchema = true` con historial `1.json`–`17.json` y migración destructiva prohibida por `verifyRoomSchemaPolicy` |
| 8 | DAOs y repositorios | CUMPLE | 21 DAOs; el reemplazo de líneas es transaccional en `RoomInvoiceDraftRepository.replaceLines:317` y `reorderLines:335` vía `database.withTransaction`; `updatedAt` se sella en la misma transacción; `StorageErrorTranslation.kt` traduce fallos de SQLite/disco a `StorageError`; mapeadores separados; 23 archivos de fakes. **Cero** imports de `com.facturastock.app.data` desde `ui`, `feature` o `navigation` |
| 9 | — | NO APLICA | No venía en el texto entregado |
| 10 | Inicio y recuperación de borradores | CUMPLE | `HomeContract` y `HomeTestTags` cubren CTA de escaneo, los tres accesos, la lista de borradores, borrado con confirmación, OCR interrumpido con reanudar/repetir, Snackbar con acción y navegación por estado; hay `HomeViewModelTest`, `HomeScreenTest` y `HomeScreenAccessibilityTest` |

## Lo que se corrigió

Los dos huecos encontrados eran de documentación, no de código:

1. **Criterio de Prompt 1 sin equivalente escrito.** El README solo documentaba tareas calificadas por
   flavor (`:app:assembleLocalDebug`). Se añadieron las tareas ancla `assembleDebug` y
   `testDebugUnitTest`, aclarando que abarcan los dos flavors y por eso son más lentas.
2. **Criterio de Prompt 2 sin fecha.** La tabla «Versiones principales» no decía cuándo se verificó la
   selección. Se añadió la fecha (21 de agosto de 2026) y la afirmación explícita de que no hay `+`,
   `SNAPSHOT` ni rangos en `gradle/libs.versions.toml`.

## Decisión sostenida sin cambio

El manifiesto **no** fija `android:screenOrientation`. Prompt 1 pide «orientación principal vertical
sin excluir pantallas grandes»: bloquear `portrait` excluiría tablets y plegables, así que la app
declara `android:resizeableActivity="true"` y el diseño es vertical por composición, no por candado.
Cambiarlo sería incumplir la segunda mitad del criterio.

## No verificable en este entorno

`assembleDebug`, `testDebugUnitTest`, `ciStaticAnalysis`, los diez verificadores Gradle, Lint, Kover y
la instrumentación en dispositivo. La evidencia previa en disco (`app/build`) que respalda estos
puntos está citada en [`../ACEPTACION_V1.md`](../ACEPTACION_V1.md); no la produjo esta sesión.
