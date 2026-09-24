# FacturaStock

FacturaStock es una aplicación Android nativa, en español, destinada a facilitar el registro de
compras y ventas y la gestión de inventario desde un teléfono o una tablet. La base técnica utiliza
Kotlin, Jetpack Compose y Material 3. Existe un único flavor, `local`: la app funciona sin red,
elimina el permiso `INTERNET` y guarda todo en su base Room dentro del dispositivo. No hay cuenta,
sincronización ni respaldo en la nube. La usa un solo negocio, instalada directamente (sin Google
Play) en una tablet Huawei; su actualización y su respaldo se describen en
[`docs/RUNBOOK.md`](docs/RUNBOOK.md).

La variante `cloud` (Firebase, Functions y sincronización entre teléfonos) se retiró el 24 de
septiembre de 2026. La lógica interna de outbox y sincronización permanece en el código compartido,
pero desconectada: el flavor `local` enlaza implementaciones no disponibles o sin efecto. Los
informes fechados de `docs/` anteriores a esa fecha pueden mencionarla como historia.

Las versiones fueron verificadas el **7 de agosto de 2026** con Android Studio Quail 1 (`2026.1.1`), en la zona horaria `America/Lima`. No se usan versiones dinámicas, rangos, `SNAPSHOT`, RC, beta ni alpha. Plugins, SDK y librerías están centralizados en [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Requisitos

- Android Studio compatible con Android Gradle Plugin 8.13.2.
- JDK 17 o una versión compatible con Gradle 8.13.
- Android SDK Platform 36 y Android SDK Build-Tools 36.0.0.
- Un dispositivo o emulador con Android 8.0 (API 26) o posterior.

El SDK local se configura en `local.properties`. Ese archivo es específico de cada equipo y no debe versionarse.

## Setup desde cero

Estos pasos son suficientes para compilar y ejecutar el producto sin ninguna cuenta, credencial
ni servicio externo. `local` es el único flavor y un producto completo por sí mismo.

### 1. Clonar y configurar el SDK

```bash
git clone <url-del-repositorio> facturastock
cd facturastock
printf 'sdk.dir=%s\n' "$HOME/Library/Android/sdk" > local.properties   # macOS
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > local.properties           # Linux
```

Verificar que existan la plataforma y las build tools exactas que exige el proyecto:

```bash
ls "$(sed -n 's/^sdk.dir=//p' local.properties)/platforms/android-36"
ls "$(sed -n 's/^sdk.dir=//p' local.properties)/build-tools/36.0.0"
```

`36.0.0` no es negociable: [`build-tablet-optimized-apk.sh`](scripts/build-tablet-optimized-apk.sh)
usa `zipalign` y `apksigner` de exactamente esas build tools para preparar el APK de la tablet y se
detiene si faltan.

### 2. Compilar y probar el producto offline

```bash
./gradlew :app:assembleLocalDebug
./gradlew :app:testLocalDebugUnitTest
adb install -r app/build/outputs/apk/local/debug/app-local-debug.apk
adb shell am start -n com.facturastock.app/.MainActivity
```

Si esto funciona, el setup está completo. Todo lo que sigue es opcional. Antes de ejecutar un
comando `adb`, comprobar con `adb devices` que el destino es un emulador o un teléfono de pruebas y
no la tablet del negocio (ver [Actualizar la tablet del negocio](#actualizar-la-tablet-del-negocio)).

Como `local` es el único flavor, las tareas ancla por tipo de compilación (`./gradlew assembleDebug`,
`./gradlew testDebugUnitTest`) construyen la misma variante. Scripts, CI y esta guía usan el nombre
explícito (`…LocalDebug`, `…LocalRelease`).

### 3. `local.properties` y variables de entorno

`local.properties` solo necesita `sdk.dir`. Las antiguas claves `firebase.*` y `privacy.policyUrl`,
y las variables `FACTURASTOCK_FIREBASE_*`, `FACTURASTOCK_PRIVACY_POLICY_URL` y
`FACTURASTOCK_SIGNING_*`, ya no se leen. `FACTURASTOCK_VERSION_CODE` y `FACTURASTOCK_VERSION_NAME`
siguen siendo opcionales; sin ellas se usan los valores fijados en `app/build.gradle.kts`.

No existe ni debe agregarse un `google-services.json`: `verifyLocalOcrConfiguration` falla el
build si aparece uno en cualquier parte del repositorio, y `verifyOfflineFirstBoundaries` rechaza
cualquier dependencia `com.google.firebase`.

## Versiones principales

Todas las versiones están fijadas de forma exacta en
[`gradle/libs.versions.toml`](gradle/libs.versions.toml): no hay `+`, ni `SNAPSHOT`, ni rangos
dinámicos, de modo que dos compilaciones del mismo commit resuelven el mismo grafo. La tabla se
verificó contra los catálogos publicados el **21 de agosto de 2026**.

| Componente | Versión |
| --- | ---: |
| Gradle / Android Gradle Plugin | 8.13 / 8.13.2 |
| Kotlin / KSP | 2.3.21 / 2.3.11 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Compose BOM | 2026.06.01 |
| Activity / Navigation / Lifecycle | 1.13.0 / 2.9.8 / 2.10.0 |
| Coroutines / Hilt | 1.11.0 / 2.58 |
| Room / CameraX | 2.8.4 / 1.6.1 |
| DataStore / WorkManager | 1.2.1 / 2.11.2 |
| Coil Compose | 3.4.0 |
| ML Kit Text Recognition | 16.0.1; lector HID de Ventas e Inventario sin modelo Barcode |
| JUnit / Turbine / AndroidX Test | 4.13.2 / 1.2.1 / 1.7.0 |

La selección es estable y compatible, no simplemente la versión numéricamente más alta: Lifecycle 2.11 y AndroidX Hilt 1.4 requieren `compileSdk 37`; Hilt 2.59 o superior requiere AGP 9; y Coil 3.5 publica su runtime con Kotlin 2.4. Por eso se fijaron Lifecycle 2.10.0, AndroidX Hilt 1.3.0, Hilt 2.58 y Coil 3.4.0 para conservar la matriz AGP 8.13.2, API 36 y Kotlin 2.3.21.

`targetSdk 36` corresponde a Android 16. La app no se publica en Google Play.

## Compilar y probar

El proyecto tiene un único flavor, `local` (dimensión `backend`): la app offline-first sin
`INTERNET` ni servicios remotos. Desde la raíz del proyecto:

```bash
./gradlew :app:assembleLocalDebug
./gradlew :app:testLocalDebugUnitTest
./gradlew :app:lintLocalDebug
```

El APK local resultante se crea en:

```text
app/build/outputs/apk/local/debug/app-local-debug.apk
```

### Corpus dorado y cobertura del dominio

El corpus dorado ejecuta las fixtures sintéticas de `app/src/test/resources/golden-corpus`
contra `InvoiceParser` y deja el informe comparativo y las imágenes de referencia en
`app/build/reports/golden-corpus/`:

```bash
./gradlew :app:testLocalDebugUnitTest --tests "com.facturastock.app.corpus.*"
```

La cobertura de línea del dominio crítico (`com.facturastock.app.domain.*`) se mide con
Kover y exige un mínimo del 80 %; los informes quedan en `app/build/reports/kover/`:

```bash
./gradlew :app:koverXmlReportLocalDebug :app:koverVerifyLocalDebug
```

El formato de las fixtures y la regla de verificación se documentan en
[`docs/GOLDEN_CORPUS.md`](docs/GOLDEN_CORPUS.md).

Para instalarlo y abrirlo con un dispositivo o emulador de pruebas conectado:

```bash
adb install -r app/build/outputs/apk/local/debug/app-local-debug.apk
adb shell am start -n com.facturastock.app/.MainActivity
```

La experiencia está diseñada principalmente en vertical para teléfonos. La actividad sigue siendo redimensionable y la aplicación no excluye pantallas grandes.

## Migraciones de base de datos

Room es la fuente de verdad y **nunca** se destruye para migrar: `verifyRoomSchemaPolicy` prohíbe
`fallbackToDestructiveMigration` en cualquier forma dentro de las fuentes de producción. El esquema
vigente es la **versión 29** y su historial completo (`1.json` … `29.json`) vive en
`app/schemas/com.facturastock.app.data.local.FacturaStockDatabase/`. Las tablas heredadas de la
variante cloud (por ejemplo `remote_sync_states` o `cloud_business_bindings`) siguen en el esquema:
retirar la nube no implicó ninguna migración.

La política de WAL, transacciones, aislamiento entre negocios, corrupción y límites de
restauración se documenta en
[`docs/DATABASE_RELIABILITY.md`](docs/DATABASE_RELIABILITY.md).

La política se aplica en tres capas independientes:

| Capa | Qué exige |
| --- | --- |
| `verifyRoomSchemaPolicy` (Gradle, antes de compilar) | `exportSchema = true`, un JSON por cada versión `1..N` con `database.version` coincidente, y una `MIGRATION_n_n+1` **declarada y registrada** en `.addMigrations(...)` para cada paso consecutivo |
| `verify-room-schema-history.sh` (CI) | el historial es append-only: solo se puede agregar `N+1.json`, nunca modificar, renombrar ni borrar un JSON anterior |
| `test -z "$(git status --porcelain ...)"` (CI) | tras regenerar con KSP el árbol queda limpio; cambiar una entidad sin versionar su esquema rompe el build |

### Agregar una migración

1. Modificar las entidades en `data/local`.
2. Subir `version` en `@Database` de `FacturaStockDatabase.kt` a `N+1`.
3. Escribir `val MIGRATION_N_N+1 = object : Migration(N, N+1) { ... }` y **registrarla** en el
   bloque `.addMigrations(...)`. Declararla sin registrarla falla el gate igual que no escribirla.
4. Regenerar el esquema exportado y comprobar que aparece exactamente un archivo nuevo:

```bash
./gradlew --no-daemon :app:kspLocalDebugKotlin
git status --porcelain --untracked-files=all -- app/schemas
```

5. Ejecutar la verificación estática y las pruebas instrumentadas de migración, **siempre contra un
   emulador**:

```bash
./gradlew --no-daemon :app:verifyRoomSchemaPolicy
bash scripts/verify-room-schema-history.sh "$(git rev-parse HEAD^)"
ANDROID_SERIAL=emulator-5554 ./gradlew --no-daemon :app:connectedLocalDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.facturastock.app.data
```

`MigrationTest`, `FullPathMigrationTest`, `PurchasePostingMigrationTest` y
`PurchaseDuplicateOverrideMigrationTest` usan `MigrationTestHelper` contra los esquemas exportados,
que se empaquetan como assets de `androidTest`. Una migración que compile pero pierda datos falla
aquí, no en el dispositivo del negocio.

**Nunca se ejecuta `connected…AndroidTest` con la tablet del negocio conectada.** Al terminar, el
Android Gradle Plugin desinstala la app probada (`com.facturastock.app`), y en la tablet eso
borraría todos sus datos. Fijar `ANDROID_SERIAL` al emulador y desconectar la tablet antes de
correr cualquier prueba instrumentada.

## Recuperación

Compras, ventas, deudas y pagos viven en Room, dentro del dispositivo. La copia local es autónoma:
no existe una copia en la nube ni otro teléfono del cual recuperar datos.

| Situación | Qué se conserva | Procedimiento |
| --- | --- | --- |
| Sin conexión | Todo | La app no usa red. Compras, ventas, deudas, abonos e inventario se confirman en Room. La outbox interna de compras queda en `PENDING_SYNC` porque no tiene destino remoto; eso no es un error |
| Actualizar la app | Todo, si se instala encima con la misma firma | Respaldo `run-as` primero, luego `adb install -r` con el APK de `scripts/build-tablet-optimized-apk.sh` (ver [`docs/RUNBOOK.md`](docs/RUNBOOK.md)) |
| Reinstalar la app o borrar sus datos | Nada que estuviera solo en el dispositivo | Room se va con la desinstalación y `allowBackup="false"` impide una copia Android. No hay restauración dentro de la app: la única copia completa es el respaldo `adb run-as` hecho desde la Mac. **Nunca desinstalar la app de la tablet** |
| Compra publicada por error | El asiento y su rastro | No se borra: se **anula** con una compensación auditada que genera movimientos inversos. Ver [`docs/PURCHASE_VOID.md`](docs/PURCHASE_VOID.md) |
| Pérdida de la clave debug de la Mac | — | La tablet solo acepta actualizaciones firmadas con la misma clave que la instaló (`~/.android/debug.keystore`, o la ruta de `FACTURASTOCK_DEBUG_KEYSTORE`). Sin ella, cambiar de firma exige desinstalar y se perderían los datos. Respaldar ese archivo aparte, fuera del repositorio |

Antes de cualquier operación de riesgo (desinstalar, borrar datos de la app, cambiar de equipo)
conviene registrar que **no hay restauración integral dentro de la app**: el coordinador de
restauración de [`FULL_DEVICE_SNAPSHOT`](docs/FULL_DEVICE_SNAPSHOT_FOUNDATION.md) sigue devolviendo
`NOT_READY`. El JSON de **Ajustes → Datos → «Exportar libro contable»** es una copia legible
parcial (no incluye ventas, deudas ni abonos) y no se puede importar. El procedimiento operativo
detallado (respaldo, actualización, incidentes y rollback) está en
[`docs/RUNBOOK.md`](docs/RUNBOOK.md).

## Integración continua y entrega Android

El workflow [`ci.yml`](.github/workflows/ci.yml) se ejecuta en pull requests, pushes a
`main`, despachos manuales y, de lunes a viernes, a las 02:17 de Lima (07:17 UTC). El
wrapper fija la distribución Gradle 8.13 mediante `distributionSha256Sum`; además,
`gradle/actions/setup-gradle@v6` valida el JAR del wrapper y usa el proveedor de caché
básico, de código abierto. Las ramas distintas de `main` solo leen esa caché.

Los chequeos reproducibles que no necesitan un teléfono pueden ejecutarse así:

```bash
bash scripts/run-actionlint.sh
ruby scripts/scan-repository-secrets.rb
ruby scripts/test-prepare-ci-artifacts.rb
ruby scripts/test-release-contracts.rb
bash scripts/test-resolve-spotless-base.sh
ruby scripts/test-summarize-macrobenchmark.rb
SPOTLESS_BASE_SHA="$(git rev-parse HEAD^)" \
  ./gradlew --no-daemon spotlessCheck ciStaticAnalysis
./gradlew --no-daemon :app:kspLocalDebugKotlin
./gradlew --no-daemon \
  :app:testLocalDebugUnitTest :app:lintLocalDebug :app:koverVerifyLocalDebug
```

En el snapshot entregado sin `.git`, el equivalente disponible es
`./gradlew --no-daemon spotlessCheck ciStaticAnalysis`; en ese modo el gate declara y omite
explícitamente la comparación de fuentes Kotlin, pero mantiene KTS, texto y análisis estático.

Con un emulador API 35 iniciado —y la tablet desconectada—, los dos grupos instrumentados y el
benchmark principal usados por CI se reproducen con:

```bash
export ANDROID_SERIAL=emulator-5554   # nunca el serial de la tablet del negocio
./gradlew --no-daemon :app:connectedLocalDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.facturastock.app.data
./gradlew --no-daemon :app:connectedLocalDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notPackage=com.facturastock.app.data
./gradlew --no-daemon --max-workers=1 \
  :benchmark:connectedLocalBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.facturastock.app.benchmark.FacturaStockMacrobenchmark \
  -Pandroid.testInstrumentationRunnerArguments.iterations=10
benchmark_json="$(find benchmark/build/outputs/connected_android_test_additional_output \
  -name '*benchmarkData.json' -print -quit)"
FACTURASTOCK_MIN_BENCHMARK_SAMPLES=10 \
  ruby scripts/summarize-macrobenchmark.rb \
    "$benchmark_json" scripts/macrobenchmark-budgets.json
```

La generación de perfiles usa otro artefacto, `profile`: es no minificado, no debuggable y
profileable por shell. `benchmark` sigue minificado para no falsear las métricas. El wrapper borra
solo las salidas generadas anteriores, exige que
`:benchmark:connectedLocalProfileAndroidTest` termine 2/2 y publica Startup, Baseline, JUnit y sus
hashes juntos en un directorio run-id mediante un rename atómico:

```bash
ruby scripts/run-local-profile-capture.rb 20260829T190000Z-final
```

No se debe copiar a `app/src/main` un perfil generado por `connectedLocalBenchmarkAndroidTest`:
esa salida contiene símbolos residuales dependientes del mapping de una build concreta.
`scripts/prepare-profile-candidates.rb` acepta únicamente el par canónico del mismo run-id
`localProfile`, verifica JUnit/hashes, rechaza mangling ligado a una variante, conserva los cinco CUJ
manuales de normalización/lista y escribe el merge revisable solo bajo
`app/build/reports/profile-candidates`:

```bash
ruby scripts/prepare-profile-candidates.rb \
  app/build/reports/profile-runs/20260829T190000Z-final/startup-prof.txt \
  app/build/reports/profile-runs/20260829T190000Z-final/baseline-prof.txt \
  app/src/main/baselineProfiles/startup-prof.txt \
  app/src/main/baseline-prof.txt \
  app/build/reports/profile-candidates
```

Para diagnosticar estabilidad y omisión de composables sin alterar una build normal ni CI:

```bash
./gradlew :app:generateComposeCompilerReports \
  -Pfacturastock.composeCompilerReports=true \
  -Pfacturastock.composeCompilerReportVariant=localRelease
```

CI añade dos smokes acotados: `DurablePrivateFilePublicationTest` en API 26 y el método de arranque
Hilt/`MainActivity` de `HiltUdfRuntimeTest` en API 36. Las suites exhaustivas permanecen en API 35,
por lo que la cobertura de extremos suma dos arranques de emulador, no tres copias de las 568 pruebas.

La optimización profunda ejecutada el 24 de agosto de 2026, incluidos tamaño de APK/AAB, arranque,
memoria, lista de 100 líneas, 6.613 ejecuciones JVM y 474 pruebas Android, está documentada con sus
límites y resultados mixtos en
[`docs/test-evidence/2026-08-24-optimizacion-profunda.md`](docs/test-evidence/2026-08-24-optimizacion-profunda.md).
Es una regresión en Pixel Tablet AVD; la aceptación de rendimiento continúa reservada al Pixel 6a
físico y 30 iteraciones.

El empaquetado que se ejecuta después de todos los gates equivale a:

```bash
./gradlew --no-daemon \
  :app:assembleLocalDebug :app:assembleLocalRelease :app:assembleLocalProfile \
  :benchmark:assembleLocalProfile \
  :app:bundleLocalRelease :app:verifyLocalReleaseBundleOptimization
```

En CI, [`resolve-spotless-base.sh`](scripts/resolve-spotless-base.sh) resuelve y valida un
`merge-base` por evento: base del PR, `before` de un push, o `HEAD^` para schedule/despacho.
Rechaza SHA cero, commits ausentes y usar el propio `HEAD`; el SHA se entrega a Gradle como
argumento de entorno, nunca se interpola dentro de un comando shell. Spotless aplica
`ktlint_official` completo solo a Kotlin nuevo (incluido un archivo local no versionado), y a
Kotlin existente modificado le exige únicamente whitespace final y newline para no crear un
baseline mecánico de toda la deuda histórica. Los tres KTS se validan globalmente. En el snapshot
distribuido sin `.git`, valida Kotlin Gradle y normaliza whitespace en YAML y los demás archivos
de texto, pero no finge haber comparado fuentes Kotlin con un historial inexistente.
`local.properties` y la evidencia histórica de pruebas quedan fuera deliberadamente. El
análisis estático combina Android Lint con las reglas de arquitectura, UI, privacidad, OCR local,
offline-first y esquemas Room de `ciStaticAnalysis`. Dependabot revisa semanalmente Gradle y
GitHub Actions. En cada PR, Dependency Review bloquea nuevas
vulnerabilidades altas o críticas en los cambios de dependencias, incluidas las Android/Gradle;
no se presenta como un escaneo total del árbol histórico. En `main`, el grafo Gradle se envía sin
guardar un artefacto crudo para habilitar las alertas continuas de Dependabot sobre el grafo
completo.

La relación entre gates y empaquetado es cerrada:

| Gate crítico | Cobertura | Requerido por el empaquetado |
| --- | --- | :---: |
| `quality` | formato, análisis estático, esquemas, unitarias, lint y cobertura | Sí |
| `security` | secretos y Dependency Review | Sí |
| `room-migrations` | migraciones y persistencia instrumentadas | Sí |
| `android-ui-e2e` | Compose, navegación y factura demo de 38 líneas | Sí |
| `android-sdk-smoke` | regresión `minSdk` API 26 y arranque `targetSdk` API 36 | Sí |
| `android-performance` | StrictMode y quince presupuestos p95 de Macrobenchmark en emulador, solo `local` | Sí |

Solo cuando los seis jobs terminan correctamente, `package-validation` compila los APK debug,
release y profile y el AAB `local`, todos sin firma de distribución, y conserva sus salidas
saneadas. No existe un job de release firmado: el repositorio no guarda secretos de firma y la app
no se publica en ninguna tienda.

El identificador definitivo es `com.facturastock.app`. `localRelease` sale sin firma de
distribución y sirve para validar el empaquetado; el único artefacto que se instala en la tablet es
el que prepara el script de la sección siguiente.

### Actualizar la tablet del negocio

La tablet recibe `localRelease` (R8, no depurable, con perfil de arranque) firmado con la clave
debug de la Mac que la instaló, de modo que se actualiza encima sin perder datos. **Antes de
instalar siempre se hace el respaldo `run-as`** descrito en [`docs/RUNBOOK.md`](docs/RUNBOOK.md).
Después:

```bash
bash scripts/build-tablet-optimized-apk.sh
adb -s <serial-de-la-tablet> install -r app/build/outputs/tablet/app-local-release-debugsigned.apk
adb -s <serial-de-la-tablet> shell cmd package compile -m speed -f com.facturastock.app
```

El script compila `localRelease` y `localDebug`, alinea con `zipalign -P 16`, firma con
`~/.android/debug.keystore` (o `FACTURASTOCK_DEBUG_KEYSTORE`) y se detiene si el certificado no
coincide con el de `localDebug`. `adb install` deja la app sin compilar hasta el dexopt nocturno;
`compile -m speed` la compila por completo en el momento. Nunca se desinstala la app de la tablet ni
se ejecuta una prueba instrumentada con la tablet conectada.

### Evidencia sin datos fiscales

Ningún job sube directamente `build/`, logcat, UTP, logs `*-debug.log`, archivos protobuf,
trazas Perfetto ni reportes HTML/XML/JSON/SARIF de las herramientas.
Antes de cada `upload-artifact`, [`prepare-ci-artifacts.rb`](scripts/prepare-ci-artifacts.rb)
copia una allowlist de resúmenes CSV/Markdown, checksums y APK/AAB. Los JUnit se convierten
antes en una tabla cerrada con hashes estables de suite/caso, estado y duración; nunca conserva
nombres, mensajes de aserción, stdout ni stderr. Dependency Review conserva solo estados y conteos,
y Macrobenchmark conserva solo el resumen p50/p95/máximo calculado. En esos resúmenes de texto el
staging elimina rutas privadas, correos, RUC de 11 dígitos, JWT, API keys, tokens y claves
privadas. Los APK/AAB sin firma del job de validación pasan únicamente por ese staging sanitizado.
Las pruebas y capturas deben usar exclusivamente la factura sintética; `evidence/`, keystores y
resultados del escáner están ignorados por Git.

La última auditoría reproducible de los prompts 44–50 (histórica, anterior al retiro de la variante
cloud), incluidos los resultados JVM, Android, Firebase, cobertura, AAB y los p95 que aún están en
rojo, está en
[`docs/test-evidence/2026-08-24-fase-g-auditoria.md`](docs/test-evidence/2026-08-24-fase-g-auditoria.md).
La evidencia complementaria de ventas por nombre o lector HID, precio de venta obligatorio y
ganancias estimadas por producto está en
[`docs/test-evidence/2026-08-24-ventas-ganancias.md`](docs/test-evidence/2026-08-24-ventas-ganancias.md).
La corrida específica de ventas a crédito, deudas y pagos (también histórica en su parte de
sincronización) está en
[`docs/test-evidence/2026-08-31-deudores-credito-sync.md`](docs/test-evidence/2026-08-31-deudores-credito-sync.md).
La decisión de entrega se mantiene en
[`docs/ACEPTACION_V1.md`](docs/ACEPTACION_V1.md); esa matriz no confunde una prueba en AVD con el
piloto humano en el dispositivo real.

Las versiones e inputs del workflow se contrastaron con las fuentes oficiales de
[Gradle Wrapper](https://docs.gradle.org/8.13/userguide/gradle_wrapper.html#sec:verification),
[Gradle Actions](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md),
[Dependency Submission](https://github.com/gradle/actions/blob/main/docs/dependency-submission.md),
[Dependency Review](https://github.com/actions/dependency-review-action),
[upload-artifact](https://github.com/actions/upload-artifact) y
[actionlint 1.7.12](https://github.com/rhysd/actionlint/releases/tag/v1.7.12). La alineación
`zipalign -P 16` del APK de la tablet sigue la guía de
[páginas de 16 KiB](https://developer.android.com/guide/practices/page-sizes).

## Arquitectura y tipos financieros

El código de producto vive en `:app`, separado en los paquetes `core`, `data`, `domain`,
`di`, `navigation`, `ui` y `feature`; `:benchmark` es un módulo de pruebas que instrumenta
el build optimizado sin incorporarse a la aplicación. La dirección obligatoria es
`UI → ViewModel → UseCase → Repository`; sus límites y responsabilidades están definidos
en [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

`Money` guarda unidades menores en `Long` y toma la escala del código ISO 4217.
`Quantity` y `UnitCost` emplean `BigDecimal`, conservan su escala y requieren una regla
explícita cuando hay redondeo. PEN y `es_PE` son valores iniciales inyectables, pero la
configuración admite otras monedas y regiones. La tarea `verifyDomainBoundaries` se
ejecuta antes de compilar y prohíbe dependencias de Android y los tipos `Float`/`Double`
dentro de `domain`.

El tema Material 3, sus componentes móviles y las reglas para TalkBack y fuente al 200 %
se documentan en [`docs/DESIGN_SYSTEM.md`](docs/DESIGN_SYSTEM.md). La tarea
`verifyUiConventions` protege la centralización de textos y colores en cada compilación.
La tarea `verifyNoSensitiveLogging` impide en la misma compuerta cualquier registro en
producción de rutas, RUC o contenido de documentos.

El análisis heurístico de captura, sus umbrales no bloqueantes, la cota de memoria y la receta
JPEG de la versión OCR se documentan en
[`docs/IMAGE_PREPROCESSING.md`](docs/IMAGE_PREPROCESSING.md).
El reconocimiento latino local, el modelo geométrico propio y la barrera contra resultados
tardíos se documentan en [`docs/LOCAL_OCR.md`](docs/LOCAL_OCR.md).
La normalización pura de texto OCR, importes, cantidades, fechas y unidades peruanas se
documenta en [`docs/PERUVIAN_NORMALIZATION.md`](docs/PERUVIAN_NORMALIZATION.md).
La extracción geométrica y conservadora de cabecera se documenta en
[`docs/INVOICE_HEADER_PARSING.md`](docs/INVOICE_HEADER_PARSING.md).
La composición versionada de cabecera, líneas y totales, su confianza explicable y publicación
idempotente se documentan en [`docs/INVOICE_PARSING.md`](docs/INVOICE_PARSING.md).
La entrada principal de Comprobantes abre la cámara y, al tomar una sola foto, ejecuta OCR local e
importa automáticamente únicamente las filas seguras al catálogo. No crea una compra ni modifica
existencias; el lote local/outbox, la deduplicación y la limpieza de la foto se documentan en
[`docs/INVOICE_PRODUCT_SCANNER.md`](docs/INVOICE_PRODUCT_SCANNER.md).
La cabecera publicada se revisa en una pantalla móvil editable con evidencia OCR, confirmación
explícita de lecturas dudosas y autosave Room revisionado. Los textos parciales sobreviven a la
recreación, mientras la proyección tipada conserva el último valor válido. El CAS por revisión
base y el merge por campo evitan que dos instancias se pisen silenciosamente.
La vinculación de cada línea contra el catálogo —cascada de coincidencias exactas, sugerencias
difusas nunca automáticas, creación de productos sin abandonar la revisión, unidad de compra con
factor hacia inventario y alias guardados solo tras confirmación— se documenta en
[`docs/PRODUCT_MATCHING.md`](docs/PRODUCT_MATCHING.md).

La conversión de unidades, el tratamiento explícito de descuento e impuesto, las políticas de
costo neto/bruto y la fórmula de promedio ponderado sin doble redondeo se documentan en
[`docs/INVENTORY_COSTING.md`](docs/INVENTORY_COSTING.md).

Ventas mantiene un carrito Room local: primero se elige **Contado** o **A crédito**; una segunda
pantalla ofrece **Escáner físico** o **Venta manual**. La venta manual muestra los productos
disponibles para agregarlos tocándolos y permite filtrar por nombre, mientras el código queda
reservado a **Escáner físico** y al lector USB/Bluetooth tipo
teclado y exige una asociación explícita cuando es nuevo. El precio de venta se solicita al
crear/vincular el producto recibido, queda en el catálogo y se propone en el carrito sin derivarlo
del costo promedio. El apartado **Ganancias por producto** compara ese precio con el costo promedio
ponderado, sin mezclar monedas, y muestra ganancia unitaria, margen y proyección sobre el stock
actual; es una estimación de inventario, no utilidad contable realizada. El checkout vuelve a
comprobar stock por producto/almacén, impide existencias negativas y aplica descuento, movimientos
`SALE` y auditoría en una transacción Room idempotente, sin necesitar conexión. Una venta puede
marcarse **a crédito** con el nombre de la persona: el mismo commit crea la deuda ligada a sus
productos, y **Deudores** permite consultar saldo/historial y registrar abonos parciales o totales
con control optimista de versión e idempotencia. Las ventas no usan outbox. Los contratos y límites
están en
[`docs/SALES_AND_BARCODE_SCANNER.md`](docs/SALES_AND_BARCODE_SCANNER.md) y
[`docs/DEBTORS_AND_CREDIT_SALES.md`](docs/DEBTORS_AND_CREDIT_SALES.md).

**Inventario → Existencias** también separa explícitamente **Buscar** de **Escáner físico**. El
lector HID consulta el código exacto dentro del negocio activo y, si lo encuentra, abre la
trazabilidad existente `inventory/{productId}`. Este flujo es de solo lectura: no crea ni asocia
productos, no reemplaza códigos y no modifica stock, movimientos o precios. Al pasar a
**Ganancias por producto** se desactiva el receptor. Ni Ventas ni Inventario decodifican códigos con
la cámara o con ML Kit Barcode Scanning.

La detección offline de comprobantes duplicados, sus señales exactas/probables y la excepción
motivada con auditoría se documentan en
[`docs/DUPLICATE_DETECTION.md`](docs/DUPLICATE_DETECTION.md).

El grafo de 29 destinos, la compuerta de primer inicio, la política de Atrás y descarte, los
argumentos basados solo en IDs y el deep link interno de detalle se documentan en
[`docs/NAVIGATION.md`](docs/NAVIGATION.md). Navigation Compose conserva el destino al
recrear la actividad y las pruebas instrumentadas recorren el flujo completo de compra.
**Ajustes** es una ruta secundaria que se abre con el engranaje de la barra superior de Vender,
Inventario y Reportes; solo contiene **Negocio**, **Impuestos y costos** y **Datos** (exportar el
libro contable).

La outbox de compras y el código de sincronización siguen en `src/main`, pero sin transporte: el
flavor `local` enlaza `UnavailablePurchaseBackupTransport` y repositorios remotos no disponibles,
WorkManager no programa ningún drenado y las operaciones permanecen en `PENDING_SYNC`. El modelo
se documenta en [`docs/BACKUP_SYNC.md`](docs/BACKUP_SYNC.md).

La política de datos —todo se procesa y guarda en el dispositivo, retención de imágenes (por
defecto se conservan; la política ya no se configura desde la interfaz), cifrado en reposo de las
imágenes retenidas con clave de AndroidKeyStore, mantenimiento periódico de privacidad y
exportación en JSON— se documenta contra el código en
[`docs/PRIVACY_DATA_LIFECYCLE.md`](docs/PRIVACY_DATA_LIFECYCLE.md).

La aplicación arranca desde `FacturaStockApplication` con Hilt. Los ViewModels de cada flujo
(Inicio, Catálogos, Captura, OCR, Revisión de cabecera, Vinculación, Preparación, Compras,
Ventas, Deudores e Inventario) reciben
casos de uso y utilidades por constructor, recuperan los IDs de ruta mediante
`SavedStateHandle` y exponen un contrato UDF: `StateFlow<UiState>`, `UiAction` y un flujo
de `UiEffect` sin repetición. El trabajo de repositorio se deriva explícitamente al
dispatcher IO inyectado y la cancelación no se transforma en error de interfaz.

## Documentación de la versión 1.0

| Documento | Para quién | Contenido |
| --- | --- | --- |
| [`docs/MANUAL_USUARIO.md`](docs/MANUAL_USUARIO.md) | Persona que usa la app | Manual completo del flujo: negocio, permisos, compras, ventas manuales/HID, deudores, trabajo sin conexión, respaldo, inventario, Ajustes y privacidad |
| [`docs/SALES_AND_BARCODE_SCANNER.md`](docs/SALES_AND_BARCODE_SCANNER.md) | Producto, desarrollo y soporte | Contrato de Ventas e Inventario para el lector USB/Bluetooth tipo teclado, asociación exclusiva de Ventas, consulta de trazabilidad, privacidad y límites v1 |
| [`docs/DEBTORS_AND_CREDIT_SALES.md`](docs/DEBTORS_AND_CREDIT_SALES.md) | Persona usuaria, producto y soporte | Venta a crédito, lista/detalle de deudores, abonos y concurrencia |
| [`docs/RUNBOOK.md`](docs/RUNBOOK.md) | Operación y soporte | Actualización de la tablet, respaldo `run-as`, respuesta a incidentes, rollback y problemas conocidos |
| [`docs/PILOTO_CERRADO.md`](docs/PILOTO_CERRADO.md) | Quien ejecuta el piloto | Protocolo del piloto cerrado con documentos anonimizados y registro de resultados |
| [`docs/ACEPTACION_V1.md`](docs/ACEPTACION_V1.md) | Aceptación de versión | Matriz final CUMPLE / NO CUMPLE / NO APLICA con evidencia y responsable |

El resto de `docs/` describe cada subsistema contra el código: arquitectura, OCR local,
normalización peruana, parsing de cabecera y líneas, emparejamiento de productos, costeo, ventas,
duplicados, navegación, outbox de respaldo, privacidad, sistema de diseño, corpus dorado y el
escenario de demostración.

## Alcance y advertencia

La aplicación integra `com.google.mlkit:text-recognition:16.0.1`; el OCR de facturas usa el modelo
latino incluido y se ejecuta sin conexión. **No se empaqueta ML Kit Barcode Scanning**: los flujos
de Ventas e Inventario no decodifican códigos con la cámara, sino que reciben texto desde un lector
físico USB/Bluetooth reconocido como teclado HID. Sus modos manuales permanecen separados. No se
declaran como dependencias raíz las variantes descargables ni el plugin Google Services. La app no
configura Firebase ni ningún backend, no incorpora una API key y elimina el permiso `INTERNET`. Las
tareas `verifyLocalOcrConfiguration` y `verifyOfflineFirstBoundaries` protegen estas condiciones,
impiden reincorporar el modelo Barcode no usado o una dependencia Firebase y evitan que tipos ML Kit
salgan de `data/ocr`.

Coil se configuró sin módulo de red porque el proyecto carga imágenes locales. El flujo OCR procesa secuencialmente las copias JPEG privadas y devuelve texto, bloques, líneas, elementos y geometría en modelos propios. Una capa pura normaliza valores regionales y extrae cabecera, líneas y totales con evidencia, alternativas, confianza y ambigüedades explícitas. El resultado estructurado se conserva en Room junto con una proyección editable, sin consultar servicios externos. **OCR, normalización y checksum local no equivalen a una validación ante SUNAT**.

Publicar o anular una compra escribe su operación de respaldo en la outbox Room dentro del mismo commit. El protocolo de drenado (worker WorkManager en `data/sync`, claim con token y lease del esquema v15, backoff exponencial durable y `SYNCED` solo ante un acuse con la clave idempotente exacta) sigue en el código compartido, pero la única variante enlaza un transporte no disponible: no se programa ningún trabajo, la cola permanece en `PENDING_SYNC` y nada se presenta como respaldado. El protocolo se documenta en [`docs/BACKUP_SYNC.md`](docs/BACKUP_SYNC.md).

`ACCOUNTING_LEDGER` v4 todavía no exporta cabeceras/líneas de venta, deudas ni abonos, y el JSON no
constituye una restauración integral del dispositivo. La única copia completa de los datos del
negocio es el respaldo `adb run-as` que se hace desde la Mac antes de cada actualización
([`docs/RUNBOOK.md`](docs/RUNBOOK.md)).

Referencias de verificación: [compatibilidad de AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [versiones estables de AndroidX](https://developer.android.com/jetpack/androidx/versions/stable-channel), [Text Recognition para Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) y [Coil sin módulo de red obligatorio](https://coil-kt.github.io/coil/network/).
