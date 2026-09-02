# FacturaStock

FacturaStock es una aplicación Android nativa, en español, destinada a facilitar el registro de
compras y ventas y la gestión de inventario desde un teléfono. La base técnica utiliza Kotlin,
Jetpack Compose y Material 3. El flavor `local` no usa red; el flavor `cloud` añade sincronización
Firebase opcional de compras, catálogos, inventario, ventas publicadas y cuentas por cobrar. Cada
teléfono conserva su base Room, mientras Functions actúa como autoridad transaccional del stock y
de los abonos de un negocio enlazado.

Las versiones fueron verificadas el **7 de agosto de 2026** con Android Studio Quail 1 (`2026.1.1`), en la zona horaria `America/Lima`. No se usan versiones dinámicas, rangos, `SNAPSHOT`, RC, beta ni alpha. Plugins, SDK y librerías están centralizados en [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Requisitos

- Android Studio compatible con Android Gradle Plugin 8.13.2.
- JDK 17 o una versión compatible con Gradle 8.13.
- Android SDK Platform 36 y Android SDK Build-Tools 36.0.0.
- Un dispositivo o emulador con Android 8.0 (API 26) o posterior.

El SDK local se configura en `local.properties`. Ese archivo es específico de cada equipo y no debe versionarse.

## Setup desde cero

Estos pasos son suficientes para compilar y ejecutar el producto sin ninguna cuenta, credencial
ni servicio externo. El flavor `local` es un producto completo por sí mismo.

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

`36.0.0` no es negociable: `verify-release-artifacts.sh` rechaza cualquier otra versión de build
tools porque `apksigner` y `zipalign` deben coincidir con el artefacto entregable.

### 2. Compilar y probar el producto offline

```bash
./gradlew :app:assembleLocalDebug
./gradlew :app:testLocalDebugUnitTest
adb install -r app/build/outputs/apk/local/debug/app-local-debug.apk
adb shell am start -n com.facturastock.app/.MainActivity
```

Si esto funciona, el setup está completo. Todo lo que sigue es opcional.

Las tareas ancla por tipo de compilación también existen y abarcan **los dos flavors** a la vez:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Son más lentas porque construyen `local` y `cloud`. Para el trabajo diario conviene la variante
concreta; las ancla sirven para comprobar que ninguna variante se rompió.

### 3. Claves opcionales de `local.properties`

Solo el flavor `cloud` las lee, y solo para compilar un candidato productivo. Cada clave tiene
una variable de entorno equivalente que tiene prioridad. Ninguna es necesaria para desarrollar.

| Clave en `local.properties` | Variable de entorno | Para qué sirve |
| --- | --- | --- |
| `firebase.projectId` | `FACTURASTOCK_FIREBASE_PROJECT_ID` | Proyecto Firebase del respaldo |
| `firebase.applicationId` | `FACTURASTOCK_FIREBASE_APPLICATION_ID` | App ID Android registrada |
| `firebase.apiKey` | `FACTURASTOCK_FIREBASE_API_KEY` | API key cliente |
| `firebase.storageBucket` | `FACTURASTOCK_FIREBASE_STORAGE_BUCKET` | Bucket de Storage para el respaldo documental |
| `privacy.policyUrl` | `FACTURASTOCK_PRIVACY_POLICY_URL` | URL HTTPS de la política |

No existe ni debe agregarse un `google-services.json`: `verifyLocalOcrConfiguration` falla el
build si aparece uno en cualquier parte del repositorio. Un valor presente pero inválido detiene
la configuración en vez de degradarse en silencio; ausente simplemente deja el respaldo apagado y
la cola de sincronización en `PENDING_SYNC`.

La sincronización segura entre teléfonos usa Cloud Functions y por eso el proyecto productivo debe
estar en el plan Blaze con una cuenta de facturación, aunque conserve las cuotas sin costo incluidas.
Para dos teléfonos y uso pequeño es razonable esperar consumo dentro de esas cuotas, pero no se
promete costo cero: el despliegue puede generar un cargo pequeño de almacenamiento de contenedores.
Antes de publicar se configuran alertas de presupuesto y, cuando estén disponibles para el servicio,
límites de gasto. Las alertas por sí solas no detienen el uso ni los cargos, y los límites no son un
tope duro instantáneo debido al retraso de medición. Referencias oficiales:
[planes de Firebase](https://firebase.google.com/pricing) y
[cuotas de Cloud Functions](https://firebase.google.com/docs/functions/quotas), además de la guía
para [evitar cargos inesperados](https://firebase.google.com/docs/projects/billing/avoid-surprise-bills).

### 4. Emulator Suite de Firebase (opcional, para tocar el backend)

Requiere Node 22. El proyecto de emulación es `demo-facturastock`, declarado en `.firebaserc`; ese
identificador es uno de los cinco literales que `verifyCloudReleaseBundleHygiene` prohíbe dentro
de un AAB de release, así que nunca debe filtrarse a fuentes de producción.

```bash
cd functions
npm ci
npx firebase emulators:start \
  --project demo-facturastock \
  --only auth,firestore,functions,storage
```

La UI queda en `http://localhost:4000` (Auth 9099, Firestore 8080, Functions 5001, Storage 9199).
Para sembrar datos sintéticos de desarrollo, con los emuladores ya arriba:

```bash
node scripts/seed-demo.mjs
```

La suite completa de Functions se ejecuta contra los emuladores en un solo comando:

```bash
cd functions
npx firebase emulators:exec \
  --project demo-facturastock \
  --only auth,firestore,functions,storage \
  'node --test test/*.test.mjs'
```

Un solo test no necesita emuladores y sirve como comprobación de humo instantánea:

```bash
cd functions && node --test test/firestoreIndexes.test.mjs
```

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

`targetSdk 36` corresponde a Android 16 y está preparado para el requisito de nuevos envíos y actualizaciones de Google Play que entra en vigor el 31 de agosto de 2026.

## Compilar y probar

El proyecto tiene dos flavors (`local` y `cloud`, dimensión `backend`). `local` es la app
offline-first sin INTERNET ni Firebase; `cloud` añade el respaldo Firebase opcional
([`docs/CLOUD_BACKUP_FIREBASE.md`](docs/CLOUD_BACKUP_FIREBASE.md)). Desde la raíz del proyecto:

```bash
./gradlew :app:assembleLocalDebug
./gradlew :app:testLocalDebugUnitTest
./gradlew :app:lintLocalDebug
./gradlew :app:assembleCloudDebug        # variante con respaldo opcional
./gradlew :app:testCloudDebugUnitTest
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

Para instalarlo y abrirlo con un dispositivo o emulador conectado:

```bash
adb install -r app/build/outputs/apk/local/debug/app-local-debug.apk
adb shell am start -n com.facturastock.app/.MainActivity
```

La experiencia está diseñada principalmente en vertical para teléfonos. La actividad sigue siendo redimensionable y la aplicación no excluye pantallas grandes.

## Migraciones de base de datos

Room es la fuente de verdad y **nunca** se destruye para migrar: `verifyRoomSchemaPolicy` prohíbe
`fallbackToDestructiveMigration` en cualquier forma dentro de las fuentes de producción. El esquema
vigente es la **versión 27** y su historial completo (`1.json` … `27.json`) vive en
`app/schemas/com.facturastock.app.data.local.FacturaStockDatabase/`.

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
./gradlew --no-daemon :app:kspLocalDebugKotlin :app:kspCloudDebugKotlin
git status --porcelain --untracked-files=all -- app/schemas
```

5. Ejecutar la verificación estática y las pruebas instrumentadas de migración:

```bash
./gradlew --no-daemon :app:verifyRoomSchemaPolicy
bash scripts/verify-room-schema-history.sh "$(git rev-parse HEAD^)"
./gradlew --no-daemon :app:connectedLocalDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.facturastock.app.data
```

`MigrationTest`, `FullPathMigrationTest`, `PurchasePostingMigrationTest` y
`PurchaseDuplicateOverrideMigrationTest` usan `MigrationTestHelper` contra los esquemas exportados,
que se empaquetan como assets de `androidTest`. Una migración que compile pero pierda datos falla
aquí, no en el teléfono de un usuario.

## Recuperación

Compras, ventas, deudas y pagos viven en Room. En `local`, o en un negocio sin enlace cloud, la copia
local es autónoma. En un negocio compartido y enlazado, el checkout de venta exige autorización
online para impedir que dos teléfonos vendan la última unidad; el feed remoto materializa después
la venta completa, la deuda opcional, sus pagos y el saldo autoritativo en los demás dispositivos.

| Situación | Qué se conserva | Procedimiento |
| --- | --- | --- |
| Sin conexión de forma prolongada | Todo lo ya guardado y los borradores | Compras/catálogos quedan en `PENDING_SYNC`. En `local` o sin enlace cloud la venta puede confirmarse offline; en un negocio compartido el borrador continúa disponible, pero el checkout espera conexión para reservar stock en la nube |
| Sesión vencida | Todo lo local | El transporte intenta renovar el token una vez; si no lo logra devuelve `SESSION_EXPIRED` como fallo transitorio, conserva la operación y la reintenta. Volver a iniciar sesión en Ajustes → Cuenta y respaldo reanuda la cola |
| Cerrar sesión | Todos los datos locales, incluidas compras, ventas, deudas y pagos | `signOut` limpia tokens y cancela los trabajos de respaldo; **no** borra datos locales |
| Reinstalar la app | Nada que estuviera solo en el dispositivo | Room se va con la desinstalación y `allowBackup="false"` impide una copia Android. Tras volver a enlazar y activar el respaldo, el feed reconstruye catálogo, inventario compartido, ventas cloud, deudas y pagos, pero **no es una restauración integral** de compras, imágenes, ajustes y borradores |
| Conflicto local/nube | Estado remoto y rastro local | El inventario compartido aplica el saldo autoritativo de forma transaccional; una referencia ambigua o un carrito alterado falla cerrado. Los conflictos documentales de compras siguen requiriendo resolución explícita |
| Compra publicada por error | El asiento y su rastro | No se borra: se **anula** con una compensación auditada que genera movimientos inversos. Ver [`docs/PURCHASE_VOID.md`](docs/PURCHASE_VOID.md) |
| Pérdida del keystore de carga | — | No se recupera el archivo perdido. Si la app está inscrita en Play App Signing, la persona autorizada solicita el restablecimiento de la **clave de carga** y registra una nueva; hasta completarlo no se suben actualizaciones. El keystore vive fuera del repositorio y se respalda aparte |

Antes de cualquier operación de riesgo (desinstalar, borrar datos de la app, cambiar de teléfono)
conviene registrar que **no hay restauración integral del dispositivo**: la nube compartida
reconstruye catálogo, saldos, ventas, deudas y pagos sincronizados, pero no todos los documentos, imágenes,
preferencias ni borradores. El procedimiento operativo detallado (respaldo, incidentes y rollback)
está en [`docs/RUNBOOK.md`](docs/RUNBOOK.md).

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
ruby scripts/test-release-apk-runtime-smoke.rb
ruby scripts/test-release-contracts.rb
ruby scripts/test-release-policies.rb
bash scripts/test-resolve-spotless-base.sh
ruby scripts/test-summarize-macrobenchmark.rb
SPOTLESS_BASE_SHA="$(git rev-parse HEAD^)" \
  ./gradlew --no-daemon spotlessCheck ciStaticAnalysis
./gradlew --no-daemon :app:kspLocalDebugKotlin :app:kspCloudDebugKotlin
./gradlew --no-daemon \
  :app:testLocalDebugUnitTest :app:testCloudDebugUnitTest \
  :app:lintLocalDebug :app:lintCloudDebug :app:koverVerifyLocalDebug

cd functions
npm ci
npm audit --audit-level=high
npx firebase emulators:exec \
  --project demo-facturastock \
  --only auth,firestore,functions,storage \
  'node --test --test-reporter=junit test/*.test.mjs > firebase-junit.xml'
```

En el snapshot entregado sin `.git`, el equivalente disponible es
`./gradlew --no-daemon spotlessCheck ciStaticAnalysis`; en ese modo el gate declara y omite
explícitamente la comparación de fuentes Kotlin, pero mantiene KTS, texto y análisis estático.

Con un emulador API 35 iniciado, los dos grupos instrumentados y el benchmark principal usados por
CI se reproducen con:

```bash
./gradlew --no-daemon :app:connectedLocalDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.facturastock.app.data
./gradlew --no-daemon :app:connectedLocalDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notPackage=com.facturastock.app.data
./gradlew --no-daemon :app:connectedCloudDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.facturastock.app.data.reporting.CloudFirebaseRuntimeSmokeTest
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

La saga Android↔Firebase debe iniciar ambos emuladores dentro de la misma vida útil. Desde la raíz,
con un AVD API 35 ya iniciado y las dependencias `functions/` instaladas:

```bash
cd functions
npx firebase emulators:exec \
  --project demo-facturastock \
  --only auth,firestore,functions,storage \
  'cd .. && ./gradlew --no-daemon :app:connectedCloudDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.facturastock.app.data.sync.CloudPurchaseSagaE2ETest'
```

`cloudBenchmark` se mide en una corrida separada (sus muestras no se mezclan con local):

```bash
./gradlew --no-daemon --max-workers=1 \
  :benchmark:connectedCloudBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.facturastock.app.benchmark.FacturaStockMacrobenchmark \
  -Pandroid.testInstrumentationRunnerArguments.iterations=10
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
  :app:assembleLocalDebug :app:assembleCloudDebug \
  :app:assembleLocalRelease :app:assembleCloudRelease \
  :app:bundleLocalRelease :app:bundleCloudRelease
ruby scripts/verify-play-assets.rb
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
análisis estático combina Android Lint con las reglas de arquitectura, UI, privacidad,
logging sanitizado de Functions, OCR local, offline-first y esquemas Room de
`ciStaticAnalysis`. Dependabot revisa
semanalmente Gradle, npm y GitHub Actions. En cada PR, Dependency Review bloquea nuevas
vulnerabilidades altas o críticas en los cambios de dependencias, incluidas las Android/Gradle;
no se presenta como un escaneo total del árbol histórico. `npm audit` aplica el mismo umbral al
lockfile de Functions. En `main`, el grafo Gradle se envía sin guardar un artefacto crudo para
habilitar las alertas continuas de Dependabot sobre el grafo completo.

La relación entre gates y empaquetado es cerrada:

| Gate crítico | Cobertura | Requerido por el AAB |
| --- | --- | :---: |
| `quality` | formato, análisis estático, esquemas, unitarias, lint y cobertura | Sí |
| `security` | secretos, npm audit y Dependency Review | Sí |
| `firebase-emulator` | Auth, reglas Firestore/Storage y Functions | Sí |
| `room-migrations` | migraciones y persistencia instrumentadas | Sí |
| `android-ui-e2e` | Compose, runtime/consentimiento cloud, navegación y factura demo de 38 líneas | Sí |
| `android-firebase-e2e` | registro, compra, outbox, Function, Firestore, pull, anulación y sesión expirada contra Emulator Suite | Sí |
| `android-sdk-smoke` | regresión `minSdk` API 26 y arranque `targetSdk` API 36 | Sí |
| `android-performance` | StrictMode y quince presupuestos p95 de Macrobenchmark en emulador | Sí |

Solo cuando los ocho jobs terminan correctamente, `package-validation` compila APK debug y
release y AAB `local`/`cloud` sin firma de distribución. Por ello un PR puede validar el
empaquetado sin recibir secretos. El job `signed-release` vuelve a compilar `cloudRelease`
únicamente mediante un despacho manual desde `main` que incluya `release_version_code` y
`release_version_name`; además depende
explícitamente de los mismos gates y del empaquetado previo. Descarga el JAR standalone oficial
de bundletool 1.18.3, exige su SHA-256 fijado, valida el AAB y deriva de él un APK universal
firmado antes de borrar el keystore temporal. Después ancla ambos firmantes a la huella de carga
registrada, inspecciona el artefacto y publica checksums y una atestación de procedencia. Antes de
atestarlo también exige un `versionCode` superior al máximo distribuido, conserva Room hacia
adelante e instala el APK universal derivado en un AVD: compara package, versión, certificado y
bytes instalados, abre `MainActivity`, ejecuta `force-stop`, reabre y rechaza crash o ANR.

El identificador definitivo es `com.facturastock.app`. Debe confirmarse que está disponible en
la cuenta de Play Console **antes del primer upload**, porque después identifica de forma
irreversible la ficha y sus actualizaciones. `localRelease` existe solo para validar el
empaquetado offline y permanece sin firma de distribución; el único candidato es
`cloudRelease`. `:app:packageCloudProductionRelease` exige versión explícita, configuración
Firebase, URL HTTPS de privacidad, keystore externo, Lint `cloudRelease` y sus pruebas unitarias;
construye APK/AAB firmados, pero no sube ni publica nada. El APK de validación que se conserva no es
el ensamblado en paralelo: se
genera desde el AAB mediante bundletool para comprobar el mismo contenido entregable.

### Environment protegido de release

Se debe crear en GitHub el environment `android-production`, restringirlo a `main` y, para
producción, exigir aprobación. Debe contener exactamente estos secretos:

- `FACTURASTOCK_SIGNING_KEYSTORE_BASE64`
- `FACTURASTOCK_SIGNING_STORE_PASSWORD`
- `FACTURASTOCK_SIGNING_KEY_ALIAS`
- `FACTURASTOCK_SIGNING_KEY_PASSWORD`
- `FACTURASTOCK_FIREBASE_PROJECT_ID`
- `FACTURASTOCK_FIREBASE_APPLICATION_ID`
- `FACTURASTOCK_FIREBASE_API_KEY`
- `FACTURASTOCK_FIREBASE_STORAGE_BUCKET`

Además, el environment debe definir cuatro variables públicas:

- `FACTURASTOCK_PRIVACY_POLICY_URL`, con la página HTTPS estable declarada en Play.
- `FACTURASTOCK_UPLOAD_CERT_SHA256`, con la huella SHA-256 del certificado de **carga** ya
  registrado en Play App Signing. No es la huella de firma de aplicación que Play usa para los
  APK distribuidos.
- `FACTURASTOCK_MAX_DISTRIBUTED_VERSION_CODE`, con el máximo ya entregado en cualquier pista de
  Play (`0` antes de la primera distribución).
- `FACTURASTOCK_MAX_DISTRIBUTED_ROOM_SCHEMA`, con el máximo esquema Room alcanzado por esas
  versiones (`0` antes de la primera distribución).

El keystore se entrega codificado en Base64, se decodifica con permisos privados dentro de
`RUNNER_TEMP`, se valida con `keytool` y su ruta se pasa como output solo al step de compilación.
Los ocho secretos existen exclusivamente en los dos steps que preparan o compilan; no quedan
en el entorno global del job. El archivo se elimina inmediatamente después de derivar el APK
universal y un cleanup final `always()` repite la ruta exacta para cubrir fallos. La huella
pública se inyecta solo en la verificación y un certificado diferente bloquea el artefacto. Si falta
cualquiera de los
valores requeridos el job falla antes de invocar Gradle. No se necesita ni debe agregarse
una cuenta de servicio o `google-services.json`. La API key cliente de Firebase acaba, por
diseño, dentro de la aplicación firmada: no sustituye la autorización de reglas/App Check y
debe restringirse en Google Cloud al package, certificados y APIs necesarios.
`FACTURASTOCK_VERSION_CODE` debe ser un entero entre 1 y 2 100 000 000 y
`FACTURASTOCK_VERSION_NAME` un identificador visible de 1 a 100 caracteres seguros; un valor
presente pero inválido detiene la configuración en vez de degradar silenciosamente a
`1/1.0.0`.
El gate también valida la sintaxis de los cuatro valores Firebase (project ID, app ID Android,
API key y bucket de Storage), pero esa forma válida no demuestra que pertenezcan al mismo proyecto
ni que el app ID esté registrado para `com.facturastock.app`: esa correspondencia debe confirmarse
en Firebase Console antes de generar el candidato.
La URL puede configurarse localmente como `privacy.policyUrl`; un valor presente que no sea
HTTPS, no tenga host o incluya credenciales detiene la configuración. El candidato productivo
también falla si la URL está ausente.

### Validar localmente el AAB firmado

La huella esperada de producción se copia una sola vez desde **Integridad de la app** de Play
Console y se contrasta con el certificado del keystore. Derivarla automáticamente del mismo
keystore en cada build solo sirve para una validación efímera: no prueba que sea la clave
registrada. Sin Play Console, etiquetar siempre el resultado como `NO SUBIR`.

Con las variables de firma, versión y Firebase ya cargadas, la secuencia reproducible es:

```bash
./gradlew --no-daemon :app:packageCloudProductionRelease

bundletool_dir="$(mktemp -d "${TMPDIR:-/tmp}/facturastock-bundletool.XXXXXX")"
bash scripts/prepare-bundletool.sh \
  "$bundletool_dir/bundletool-all-1.18.3.jar"
bash scripts/build-aab-validation-apk.sh \
  "$bundletool_dir/bundletool-all-1.18.3.jar" \
  app/build/outputs/bundle/cloudRelease/app-cloud-release.aab \
  app/build/outputs/apk-from-bundle/cloudRelease/app-cloud-release-universal.apk

export FACTURASTOCK_EXPECTED_UPLOAD_CERT_SHA256
bash scripts/verify-release-artifacts.sh \
  app/build/outputs/bundle/cloudRelease/app-cloud-release.aab \
  app/build/outputs/apk-from-bundle/cloudRelease/app-cloud-release-universal.apk \
  app/build/outputs/release-checksums.sha256
```

El tercer argumento es la ruta de salida de los checksums y CI usa exactamente
`app/build/outputs/release-checksums.sha256`. Si se desea un nombre versionado en una validación
local, basta añadir el sufijo en ese argumento; el verificador no lo interpreta.

Con un AVD dedicado ya iniciado, el mismo smoke black-box de CI se puede repetir sin reconstruir:

```bash
bash scripts/run-release-apk-runtime-smoke.sh \
  app/build/outputs/apk-from-bundle/cloudRelease/app-cloud-release-universal.apk \
  ci-summaries/manual-release-smoke
```

Requiere `FACTURASTOCK_VERSION_CODE`, `FACTURASTOCK_VERSION_NAME` y la huella pública
`FACTURASTOCK_EXPECTED_UPLOAD_CERT_SHA256`. No inicia sesión, no publica y no sustituye la
instalación del artefacto firmado por Play desde la pista interna.

El preparador solo acepta `bundletool-all-1.18.3.jar` con SHA-256
`a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`. El verificador resuelve
el SDK desde `ANDROID_SDK_ROOT`, `ANDROID_HOME` o `sdk.dir` de `local.properties`, pero exige
exactamente Build Tools 36.0.0. Ejecuta `apksigner --Werr --verbose --print-certs`, compara los
firmantes, revisa identidad/versión/SDK/launcher/iconos y aplica una allowlist cerrada de permisos.
También inspecciona cada entrada descomprimida del AAB/APK, comprueba alineación ZIP con
`zipalign -P 16` y analiza los `PT_LOAD` ELF de `arm64-v8a` y `x86_64` sin depender del NDK.

El APK universal permite una instalación local representativa, pero no demuestra una instalación
desde Google Play. Ese criterio solo se cierra instalando el AAB procesado por la pista interna.

### Evidencia sin datos fiscales

Ningún job sube directamente `build/`, logcat, UTP, logs `*-debug.log`, archivos protobuf,
trazas Perfetto ni reportes HTML/XML/JSON/SARIF de las herramientas.
Antes de cada `upload-artifact`, [`prepare-ci-artifacts.rb`](scripts/prepare-ci-artifacts.rb)
copia una allowlist de resúmenes CSV/Markdown, checksums y APK/AAB. Los JUnit se convierten
antes en una tabla cerrada con hashes estables de suite/caso, estado y duración; nunca conserva
nombres, mensajes de aserción, stdout ni stderr. npm audit y Dependency Review conservan solo
estados y conteos, y Macrobenchmark conserva solo el resumen p50/p95/máximo calculado. En esos
resúmenes de texto el staging elimina rutas privadas, correos, RUC de 11 dígitos, JWT, API
keys, tokens y claves privadas. Antes del staging, la verificación de release recorre los nombres
y el contenido **descomprimido** de cada entrada APK/AAB y rechaza credenciales, tokens y los
cinco marcadores cerrados del Emulator Suite. La API key cliente Firebase configurada para la
app no se presenta como secreto: su protección depende de restricciones, reglas y App Check.
La subida del candidato firmado se ejecuta solo si esa verificación termina correctamente; el APK
sin firma del job de validación pasa únicamente por el staging sanitizado y nunca es candidato de
Play. Las pruebas y
capturas deben usar exclusivamente la
factura sintética; `evidence/`, keystores, configuraciones Firebase locales y resultados del
escáner están ignorados por Git.

La última auditoría reproducible de los prompts 44–50, incluidos los resultados JVM, Android,
Firebase, cobertura, AAB y los p95 que aún están en rojo, está en
[`docs/test-evidence/2026-08-24-fase-g-auditoria.md`](docs/test-evidence/2026-08-24-fase-g-auditoria.md).
La evidencia complementaria de ventas por nombre o lector HID, precio de venta obligatorio y
ganancias estimadas por producto está en
[`docs/test-evidence/2026-08-24-ventas-ganancias.md`](docs/test-evidence/2026-08-24-ventas-ganancias.md).
La corrida específica de ventas a crédito, deudas, pagos y sincronización está en
[`docs/test-evidence/2026-08-31-deudores-credito-sync.md`](docs/test-evidence/2026-08-31-deudores-credito-sync.md).
La decisión de entrega se mantiene en
[`docs/ACEPTACION_V1.md`](docs/ACEPTACION_V1.md); esa matriz no confunde una prueba en AVD con el
piloto humano o la instalación desde Play.

Las versiones e inputs del workflow se contrastaron con las fuentes oficiales de
[Gradle Wrapper](https://docs.gradle.org/8.13/userguide/gradle_wrapper.html#sec:verification),
[Gradle Actions](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md),
[Dependency Submission](https://github.com/gradle/actions/blob/main/docs/dependency-submission.md),
[Dependency Review](https://github.com/actions/dependency-review-action),
[upload-artifact](https://github.com/actions/upload-artifact),
[attest](https://github.com/actions/attest) y
[actionlint 1.7.12](https://github.com/rhysd/actionlint/releases/tag/v1.7.12). La derivación y
compatibilidad del artefacto siguen la documentación oficial de
[bundletool](https://developer.android.com/tools/bundletool), su
[release 1.18.3](https://github.com/google/bundletool/releases/tag/1.18.3) y la guía de
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

Ventas mantiene un carrito Room local: **Tipo de venta** permite elegir **Contado** o **A crédito**;
**Buscar** consulta solo por nombre y muestra coincidencias similares, mientras el código queda
reservado a **Escáner físico** y al lector USB/Bluetooth tipo
teclado y exige una asociación explícita cuando es nuevo. El precio de venta se solicita al
crear/vincular el producto recibido, queda en el catálogo y se propone en el carrito sin derivarlo
del costo promedio. El apartado **Ganancias por producto** compara ese precio con el costo promedio
ponderado, sin mezclar monedas, y muestra ganancia unitaria, margen y proyección sobre el stock
actual; es una estimación de inventario, no utilidad contable realizada. El checkout vuelve a
comprobar stock por producto/almacén, impide existencias negativas y aplica descuento, movimientos
`SALE` y auditoría en una transacción idempotente. En un negocio cloud enlazado, `postSale` reserva
el stock remoto antes del commit Room y un feed incremental materializa la venta en los otros
teléfonos. Una venta puede marcarse **a crédito** con el nombre de la persona: el mismo commit crea
la deuda ligada a sus productos, y **Deudores** permite consultar saldo/historial y registrar abonos
parciales o totales. En cloud, los abonos usan control de versión remoto y el mismo feed los replica
sin doble cobro; no hay outbox ni anulación de venta en v1. Los contratos y límites están en
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

El grafo de 30 destinos, la compuerta de primer inicio, la política de Atrás y descarte, los
argumentos basados solo en IDs y el deep link interno de detalle se documentan en
[`docs/NAVIGATION.md`](docs/NAVIGATION.md). Navigation Compose conserva el destino al
recrear la actividad y las pruebas instrumentadas recorren el flujo completo de compra.

Con la cuenta enlazada y el respaldo activo, la sincronización empuja la cola de compras/catálogo y
después trae catálogo, ventas, deudas, pagos e inventario remoto con cursores incrementales. El feed
compartido aplica ventas, cuentas por cobrar y saldos autoritativos de forma transaccional; la
comparación histórica de documentos de compra sigue
siendo diagnóstica y sus conflictos nunca se resuelven solos. El detalle
está en [`docs/BACKUP_SYNC.md`](docs/BACKUP_SYNC.md) y
[`docs/CLOUD_BACKUP_FIREBASE.md`](docs/CLOUD_BACKUP_FIREBASE.md).

La política de datos — qué se procesa en el dispositivo y qué llega a la nube, retención de
imágenes configurable (tras OCR, tras confirmar, 30/90 días o conservar), cifrado en reposo
de las imágenes retenidas con clave de AndroidKeyStore, exportación en JSON, interruptor del
respaldo y eliminación verificable de la cuenta — se documenta contra el código en
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
| [`docs/MANUAL_USUARIO.md`](docs/MANUAL_USUARIO.md) | Persona que usa el teléfono | Manual completo del flujo: negocio, permisos, compras, ventas manuales/HID, deudores, offline, sincronización, inventario y privacidad |
| [`docs/SALES_AND_BARCODE_SCANNER.md`](docs/SALES_AND_BARCODE_SCANNER.md) | Producto, desarrollo y soporte | Contrato de Ventas e Inventario para el lector USB/Bluetooth tipo teclado, asociación exclusiva de Ventas, consulta de trazabilidad, privacidad y límites v1 |
| [`docs/DEBTORS_AND_CREDIT_SALES.md`](docs/DEBTORS_AND_CREDIT_SALES.md) | Persona usuaria, producto y soporte | Venta a crédito, lista/detalle de deudores, abonos, concurrencia y sincronización entre teléfonos |
| [`docs/RUNBOOK.md`](docs/RUNBOOK.md) | Operación y soporte | Respaldo, respuesta a incidentes, rollback y problemas conocidos |
| [`docs/PILOTO_CERRADO.md`](docs/PILOTO_CERRADO.md) | Quien ejecuta el piloto | Protocolo del piloto cerrado con documentos anonimizados y registro de resultados |
| [`docs/ACEPTACION_V1.md`](docs/ACEPTACION_V1.md) | Aceptación de versión | Matriz final CUMPLE / NO CUMPLE / NO APLICA con evidencia y responsable |

El resto de `docs/` describe cada subsistema contra el código: arquitectura, OCR local,
normalización peruana, parsing de cabecera y líneas, emparejamiento de productos, costeo, ventas,
duplicados, navegación, respaldo, privacidad, sistema de diseño, corpus dorado y el escenario de
demostración.

## Alcance y advertencia

La aplicación integra `com.google.mlkit:text-recognition:16.0.1`; el OCR de facturas usa el modelo
latino incluido y se ejecuta sin conexión. **No se empaqueta ML Kit Barcode Scanning**: los flujos
de Ventas e Inventario no decodifican códigos con la cámara, sino que reciben texto desde un lector
físico USB/Bluetooth reconocido como teclado HID. Sus modos manuales permanecen separados. No se
declaran como dependencias raíz las variantes descargables ni el plugin Google Services. El flavor
`local` no configura Firebase, no incorpora una API key y elimina el permiso `INTERNET`; `cloud`
recibe su configuración opcional desde propiedades locales o variables del job protegido, sin
`google-services.json`. La tarea `verifyLocalOcrConfiguration` protege estas condiciones, impide
reincorporar el modelo Barcode no usado y evita que tipos ML Kit salgan de `data/ocr`.

Coil se configuró sin módulo de red porque el proyecto carga imágenes locales. El flujo OCR procesa secuencialmente las copias JPEG privadas y devuelve texto, bloques, líneas, elementos y geometría en modelos propios. Una capa pura normaliza valores regionales y extrae cabecera, líneas y totales con evidencia, alternativas, confianza y ambigüedades explícitas. El resultado estructurado se conserva en Room junto con una proyección editable, sin consultar servicios externos. **OCR, normalización y checksum local no equivalen a una validación ante SUNAT**.

Publicar o anular una compra escribe su operación de respaldo en la outbox Room dentro del mismo commit. Un worker WorkManager (`data/sync`) drena la cola con trabajo único, constraint de red y backoff exponencial durable; el claim concurrente usa token y lease (esquema v15) y solo un acuse con la clave idempotente exacta marca `SYNCED`. El flavor `local` enlaza un transporte no disponible; `cloud` incluye el transporte Firebase configurable, pero el repositorio no incorpora credenciales ni demuestra un proyecto productivo desplegado. Sin esa configuración la cola permanece en `PENDING_SYNC` y nada se presenta como respaldado. El protocolo completo se documenta en [`docs/BACKUP_SYNC.md`](docs/BACKUP_SYNC.md).

Las compras y catálogos usan outbox; las ventas y abonos compartidos usan autorización síncrona e
idempotente contra el estado remoto y luego el pull incremental. `ACCOUNTING_LEDGER` v4 todavía no
exporta cabeceras/líneas de venta, deudas ni abonos. La nube puede reconstruir los hechos que
recibió, pero ni ese feed ni el JSON constituyen una restauración integral del dispositivo.

En el flavor `cloud` una cuenta de email y contraseña **verificada** permite enlazar el negocio,
pero no activa envíos por sí sola: «Respaldar registros en la nube» es un opt-in separado,
apagado por defecto, y las fotos requieren además «Respaldar documentos cifrados». El perfil local
puede seguir sin nube. Los roles `OWNER`, `ADMIN`, `OPERATOR` y `READER` los aplica siempre la
función contra la membresía del servidor —nunca un `businessId` o rol enviado por el cliente— y las
invitaciones, el cambio de negocio y la gestión de miembros se administran desde la app. Cerrar
sesión limpia el estado de autenticación gestionado por Firebase y cancela los trabajos de respaldo
sin borrar compras, ventas, deudas, pagos ni borradores locales. El contrato completo está en
[`docs/CLOUD_BACKUP_FIREBASE.md`](docs/CLOUD_BACKUP_FIREBASE.md).

Referencias de verificación: [compatibilidad de AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [versiones estables de AndroidX](https://developer.android.com/jetpack/androidx/versions/stable-channel), [política de API objetivo de Google Play](https://support.google.com/googleplay/android-developer/answer/11926878?hl=es), [Text Recognition para Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) y [Coil sin módulo de red obligatorio](https://coil-kt.github.io/coil/network/).
