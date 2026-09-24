# Runbook técnico — FacturaStock 1.0

Guía de operación para quien mantiene, actualiza y soporta la app. No es un manual de usuario: la
persona que usa la app tiene el suyo en [`MANUAL_USUARIO.md`](MANUAL_USUARIO.md).

Desde el 24 de septiembre de 2026 existe una sola variante, `local`: Room es la única fuente de
verdad, no hay nube, cuenta ni sincronización, y la app se instala directamente en la tablet Huawei
del negocio desde la Mac de desarrollo, sin tienda de aplicaciones. La única copia completa de los
datos del negocio es el respaldo `adb run-as` de la sección 4.

| Documento | Para qué |
| --- | --- |
| [`../README.md`](../README.md) | Setup, compilación, pruebas, migraciones y entrega a la tablet |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Capas, límites y tipos financieros |
| [`BACKUP_SYNC.md`](BACKUP_SYNC.md) | Contrato de la outbox, hoy sin transporte remoto |
| [`DATABASE_RELIABILITY.md`](DATABASE_RELIABILITY.md) | WAL, transacciones, corrupción y límites de restauración |
| [`SALES_AND_BARCODE_SCANNER.md`](SALES_AND_BARCODE_SCANNER.md) | Ventas, consulta de Inventario, lectores HID, asociación y límites |
| [`DEBTORS_AND_CREDIT_SALES.md`](DEBTORS_AND_CREDIT_SALES.md) | Ventas a crédito, deudores, abonos, concurrencia y soporte |
| [`PRIVACY_DATA_LIFECYCLE.md`](PRIVACY_DATA_LIFECYCLE.md) | Retención y ciclo de vida de datos |
| [`ACEPTACION_V1.md`](ACEPTACION_V1.md) | Matriz de aceptación de la versión 1.0 |
| [`PILOTO_CERRADO.md`](PILOTO_CERRADO.md) | Protocolo del piloto con documentos anonimizados |

## 1. Piezas y responsabilidades

| Pieza | Dónde vive | Responsable |
| --- | --- | --- |
| App Android (`:app`) | Este repositorio | Desarrollo |
| Macrobenchmark (`:benchmark`) | Este repositorio | Desarrollo |
| Esquemas Room `1.json`–`29.json` | `app/schemas/` (versionados, append-only) | Desarrollo |
| Clave que firma la app de la tablet | `~/.android/debug.keystore` de la Mac que la instaló, **fuera del repositorio** | Desarrollo |
| Respaldos completos de la tablet | Carpetas fechadas en la Mac, fuera del repositorio | Desarrollo |
| Tablet del negocio con los datos reales | Tablet Huawei; app instalada por `adb` | Negocio y desarrollo |

Reglas no negociables:

- La app de la tablet **nunca se desinstala** y nunca se borran sus datos desde Android: Room se va
  con ella y no existe restauración dentro de la app.
- Antes de instalar cualquier versión se hace y se verifica el respaldo `run-as` (sección 4).
- **Nunca** se ejecuta `connectedAndroidTest` ni otra tarea instrumentada de Gradle con la tablet
  conectada. Al terminar, el Android Gradle Plugin desinstala la app probada —el proyecto no fija
  `android.injected.androidTest.leaveApksInstalledAfterRun`— y eso borraría los datos del negocio.
  Las pruebas instrumentadas se fijan al emulador con `ANDROID_SERIAL=emulator-…`;
  `scripts/run-resilience-matrix.sh android` rechaza cualquier serial que no sea de emulador.
- `~/.android/debug.keystore` no entra al repositorio, pero se respalda aparte: es la única clave
  con la que la tablet acepta una actualización sin desinstalar.
- No existe ningún `google-services.json` ni configuración de red; `local.properties` no se versiona.
- Ningún fuente de producción registra rutas, RUC ni contenido de documentos
  (`verifyNoSensitiveLogging`).

## 2. Turno normal: qué vigila la integración continua

`.github/workflows/ci.yml` corre en cada *pull request*, en cada *push* a `main`, a demanda
(`workflow_dispatch`) y de lunes a viernes a las 07:17 UTC (02:17 en `America/Lima`).

| Job | Qué demuestra |
| --- | --- |
| Format, static analysis, lint, unit, and Room policy | Spotless, los verificadores Gradle de `ciStaticAnalysis`, Lint, pruebas unitarias y política de esquema |
| Secret and dependency review | `scan-repository-secrets.rb` y revisión de dependencias |
| Submit Gradle dependency graph | Grafo de dependencias (no corre en *pull request*) |
| Room migration and persistence instrumentation | Migraciones reales en dispositivo virtual |
| Compose, navigation, and 38-line E2E | Recorrido completo, incluido el escenario demo de 38 líneas |
| SDK 26/36 smoke | Regresión durable en `minSdk` y arranque Hilt de `MainActivity` en `targetSdk`, sin duplicar las suites API 35 |
| Macrobenchmark and StrictMode regression (local) | Rendimiento; cámara, pipeline y lista rechazan IO/Main, fugas y p95 fuera de los quince presupuestos versionados. Inicio frío solo mide arranque |
| Debug, release, profile tooling, and unsigned AAB validation | Empaquetado de la variante `local` y AAB sin firmar |

No hay job de release firmado ni publicación en tiendas. Si un job falla, la regla es leer su
evidencia antes de tocar código: cada job publica artefactos y `prepare-ci-artifacts.rb` los
normaliza. `summarize-junit.rb`, `summarize-macrobenchmark.rb` y `summarize-security-results.rb`
producen los resúmenes legibles.

Comprobación local equivalente al primer job:

```bash
./gradlew --no-daemon ciStaticAnalysis
```

## 3. Actualizar la tablet del negocio

Una versión nueva llega a la tablet siempre desde la Mac que tiene la clave debug con la que se
instaló la app, y siempre en este orden:

1. **Identificar el destino.** `adb devices -l` debe mostrar la tablet; usar siempre
   `adb -s <serial>`, sobre todo si también hay un emulador conectado. La depuración por Wi-Fi se
   apaga cada vez que la tablet se reinicia: para reactivarla hace falta el cable USB.
2. **Respaldar** siguiendo la sección 4. Sin un respaldo verificado no se instala nada.
3. **Compilar** el APK optimizado:

   ```bash
   bash scripts/build-tablet-optimized-apk.sh
   ```

   Genera `app/build/outputs/tablet/app-local-release-debugsigned.apk`: `localRelease` (R8, no
   depurable, con perfil de arranque) alineado con `zipalign -P 16` y firmado con
   `~/.android/debug.keystore` (o la ruta de `FACTURASTOCK_DEBUG_KEYSTORE`). El script se detiene
   si faltan las build tools 36.0.0 o la clave, o si el certificado no coincide con el de
   `localDebug`: un APK con otra firma solo se podría instalar desinstalando.
4. **Instalar encima**, conservando los datos:

   ```bash
   adb -s <serial> install -r app/build/outputs/tablet/app-local-release-debugsigned.apk
   ```

5. **Compilar en el dispositivo.** `adb install` deja la app sin compilar hasta el dexopt
   nocturno; en el emulador con una copia de los datos del negocio, el recorrido diario bajó de
   22,3 s de CPU sin compilar a 12,0 s con `speed` (medido el 2026-09-24):

   ```bash
   adb -s <serial> shell cmd package compile -m speed -f com.facturastock.app
   ```

6. **Comprobar.** Abrir la app y confirmar que Vender, Inventario, Deudores y Reportes muestran
   los datos de siempre. La versión instalada se lee con
   `adb -s <serial> shell dumpsys package com.facturastock.app | grep -E 'versionCode|versionName'`.
   La app aplica `FLAG_SECURE`, así que `adb exec-out screencap` devuelve una imagen negra: la
   pantalla se revisa en la propia tablet.

Si `adb install -r` responde `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (firma distinta) o
`INSTALL_FAILED_VERSION_DOWNGRADE` (`versionCode` menor que el instalado), **no se desinstala para
"arreglarlo"**: se corrige la firma o la versión y se repite la instalación.

**Versión.** `versionCode` y `versionName` provienen de `FACTURASTOCK_VERSION_CODE` y
`FACTURASTOCK_VERSION_NAME`; sin ellas se usan los valores por defecto de `app/build.gradle.kts`.
El script compila `localRelease` y `localDebug` con la misma configuración, por lo que ambos
comparten paquete, firma y `versionCode`. Subir el `versionCode` es seguro; bajarlo impide instalar
encima.

## 4. Respaldo completo de la tablet

Es la única copia completa de los datos: no hay nube, `allowBackup="false"` impide la copia
automática de Android, el JSON de **Ajustes → Datos** excluye ventas, deudas y abonos, y la
restauración dentro de la app no está terminada (el coordinador de
[`FULL_DEVICE_SNAPSHOT_FOUNDATION.md`](FULL_DEVICE_SNAPSHOT_FOUNDATION.md) devuelve `NOT_READY`).

`run-as` solo funciona con un build depurable. Como la tablet ejecuta el build optimizado, el
respaldo instala temporalmente encima el APK debug —misma firma y mismo paquete, así que conserva los
datos— y al final vuelve al optimizado:

1. Guardar el APK instalado ahora, para poder volver a él:

   ```bash
   apk_path="$(adb -s <serial> shell pm path com.facturastock.app | sed 's/^package://' | tr -d '\r')"
   adb -s <serial> pull "$apk_path" app_optimizada_anterior.apk
   ```

2. Instalar encima el APK debug. Conviene compilarlo desde el mismo código que la versión
   instalada: si trae una migración Room nueva y la app llega a abrirse antes de copiar, la copia
   saldría ya migrada.

   ```bash
   ./gradlew :app:assembleLocalDebug
   adb -s <serial> install -r app/build/outputs/apk/local/debug/app-local-debug.apk
   ```

3. Detener la app y copiar su directorio privado completo (base Room con sus archivos WAL,
   preferencias e imágenes):

   ```bash
   adb -s <serial> shell am force-stop com.facturastock.app
   adb -s <serial> exec-out run-as com.facturastock.app tar -cf - . > datos_app.tar
   ```

4. Verificar la copia antes de continuar. `quick_check` debe responder `ok` y los conteos deben
   coincidir con lo que muestra la app:

   ```bash
   mkdir verificacion && tar -xf datos_app.tar -C verificacion
   sqlite3 verificacion/databases/facturastock.db 'PRAGMA quick_check;'
   sqlite3 verificacion/databases/facturastock.db \
     'SELECT COUNT(*) FROM sales; SELECT COUNT(*) FROM products;
      SELECT COUNT(*) FROM debts; SELECT COUNT(*) FROM debt_payments;'
   shasum -a 256 datos_app.tar app_optimizada_anterior.apk > SHA256SUMS.txt
   ```

5. Guardar `datos_app.tar`, `app_optimizada_anterior.apk`, `SHA256SUMS.txt` y una nota `LEEME.txt`
   en una carpeta fechada fuera del repositorio (por ejemplo
   `Respaldo_tablet_completo_<AAAA-MM-DD_HH-MM-SS>/`). Contiene datos reales del negocio (RUC,
   nombres de deudores, importes): no se versiona ni se sube a servicios externos. Conviene tener
   una segunda copia fuera de la Mac, junto con `~/.android/debug.keystore`.
6. Volver al build optimizado: instalar la versión nueva según la sección 3 o, si solo se quería
   respaldar, reinstalar encima `app_optimizada_anterior.apk` y compilarla con `speed`.

Restaurar esa copia en un dispositivo es un procedimiento manual de desarrollo que este repositorio
no automatiza ni prueba. Ante una pérdida de datos se escala antes de tocar la tablet.

## 5. Qué protege los datos y qué no

Lo que sí hay:

- Room con WAL y `synchronous=FULL`. Cada compra, venta, abono o anulación se confirma en una
  transacción completa o no se aplica (ver [`DATABASE_RELIABILITY.md`](DATABASE_RELIABILITY.md)).
- Al publicar o anular una compra, la **misma transacción** Room escribe su operación en la outbox.
  Sin transporte, `WorkManagerPurchaseBackupScheduler` no programa nada y la operación queda en
  `PENDING_SYNC` indefinidamente: es el estado esperado, no una falla.
- El respaldo `run-as` de la sección 4, hecho antes de cada actualización.

Lo que **no** hay, y hay que decirlo en cada conversación de soporte:

- **No hay nube, cuenta ni sincronización.** Ningún dato sale del dispositivo.
- **No restaura desde una exportación.** **Ajustes → Datos → «Exportar libro contable»** crea un
  JSON mediante SAF y confirma conteos reales, pero no existe un importador y el archivo excluye
  ventas, deudas y abonos. Exportar es una copia legible, no restauración.
- **No hay restauración integral dentro de la app.**
- `allowBackup="false"` en el manifiesto: Android tampoco hace copia automática.

Una compra publicada muestra su operación de respaldo en `PENDING_SYNC`. Los demás estados del
modelo (`SYNCING`, `SYNCED`, `ERROR`, `CONFLICT` y resuelto) solo se alcanzaban con el transporte
cloud retirado.

## 6. Incidentes

Regla general de triage: **primero confirmar si el libro local está intacto**. Si lo está —y lo está
salvo daño del dispositivo— el incidente es de interfaz o de operación, no de contabilidad, y no
justifica ninguna acción destructiva.

### 6.1 Compras en `PENDING_SYNC`

Es el comportamiento correcto: la outbox no tiene destino remoto. No hay nada que reintentar y
**vaciar la outbox no es un procedimiento de soporte**.

### 6.2 Deriva de esquema Room

Síntoma: la CI falla con esquema no confirmado, o `verifyRoomSchemaPolicy` /
`verify-room-schema-history.sh` rechazan el cambio.

```bash
./gradlew --no-daemon :app:kspLocalDebugKotlin
git status --porcelain --untracked-files=all -- app/schemas
```

Si aparecen cambios, el esquema exportado no estaba confirmado: hay que versionarlo junto al código
que lo produce. La historia de `app/schemas` es **append-only** desde `git merge-base`: un `N.json`
existente no se edita jamás; una corrección exige una versión nueva con su `Migration`. Está
prohibido `fallbackToDestructiveMigration` en cualquiera de sus formas.

```bash
./gradlew --no-daemon :app:verifyRoomSchemaPolicy
bash scripts/verify-room-schema-history.sh "$(git rev-parse HEAD^)"
```

### 6.3 El script de la tablet falla o `adb` rechaza el APK

- Faltan las build tools 36.0.0 o `~/.android/debug.keystore`: instalarlas o recuperar la clave
  desde su copia; sin la clave original la tablet no acepta la actualización.
- El certificado no coincide con el de `localDebug`: se está usando otra clave. No se instala.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` o `INSTALL_FAILED_VERSION_DOWNGRADE`: ver sección 3.

En ningún caso se desinstala la app para forzar la instalación.

### 6.4 Sospecha de secreto expuesto

1. Ejecutar el escáner del repositorio:

   ```bash
   ruby scripts/scan-repository-secrets.rb
   ```

2. El repositorio no usa secretos de firma ni claves de servicios. Si aparece uno, se retira y se
   rota en su origen. Si lo expuesto es `debug.keystore`, se evalúa con quien opera la tablet: esa
   clave permite firmar actualizaciones que la tablet aceptaría por `adb`.
3. Registrar el incidente con fecha, alcance y acción tomada. Nunca copiar el valor del secreto al
   registro.

### 6.5 Tablet perdida, robada, reinstalada o con datos borrados

Este es el incidente más grave del producto. Room desaparece con la desinstalación y
`allowBackup="false"` impide la copia automática de Android. No hay nube de la cual recuperar: lo
único disponible es el último respaldo `run-as` (sección 4), y todo lo registrado después se pierde.
El JSON exportado sirve para custodia y lectura, pero la versión 1.0 no lo importa.

- Las fotos, borradores y preferencias solo existen en la tablet y en sus respaldos `run-as`.
- Acción preventiva: respaldar antes de cada actualización y con regularidad, y avisar a
  desarrollo **antes** de reinstalar, borrar datos o cambiar de equipo.

### 6.6 El lector físico no abre el producto en Inventario

El receptor solo está habilitado en **Inventario → Existencias → Escáner físico**. No recibe
códigos en **Buscar**, en **Ganancias por producto**, dentro de la trazabilidad ni mientras hay una
operación bloqueante. Cambiar de modo, de pantalla o pausar la app descarta cualquier prefijo
incompleto.

1. Confirmar que Android reconoce el lector USB/Bluetooth como teclado físico HID y que envía
   **Enter**, **Enter de teclado numérico** o **Tab** al terminar. FacturaStock no pide permisos USB
   o Bluetooth ni administra el emparejamiento.
2. Probar un código ya asociado al producto dentro del negocio activo. La comparación es exacta:
   conserva ceros iniciales y mayúsculas/minúsculas.
3. Si aparece «no está asociado», no intentar asociarlo desde Inventario: esa pantalla es de solo
   lectura. Revisar el catálogo del negocio; una asociación nueva sigue siendo una decisión
   explícita del flujo de Ventas.
4. Si el código conocido abre `inventory/{productId}`, verificar allí la trazabilidad. El lookup no
   cambia stock, movimientos, precio ni el producto.
5. No habilitar CAMERA ni agregar ML Kit Barcode Scanning como mitigación: este recorrido usa
   exclusivamente el perfil HID. Para escalar, registrar modelo, Android, adaptador OTG,
   distribución y terminador, sin copiar el código comercial crudo a logs o tickets.

## 7. Rollback

| Qué revertir | Cómo | Advertencia |
| --- | --- | --- |
| Versión en la tablet | Reinstalar encima (`adb install -r`) el `app_optimizada_anterior.apk` del último respaldo y compilarlo con `speed` | Solo si el esquema Room no subió entre ambas versiones y el `versionCode` no bajó. Si subió el esquema, se corrige hacia adelante con una versión nueva |
| Código | Backportear el comportamiento conocido como bueno sobre el código compatible con el esquema más reciente e instalarlo con el procedimiento de la sección 3 | No se recompila sin más un commit antiguo: debe conservar entidades/migraciones ya instaladas y un `versionCode` igual o mayor |
| Esquema Room | **No hay rollback de esquema.** Una base migrada no vuelve a una versión anterior | Cualquier corrección es una versión nueva hacia adelante con su `Migration` |
| Datos | Restaurar un respaldo `run-as` es un procedimiento manual no automatizado | Se escala antes de tocar la tablet; nunca se desinstala para "empezar de cero" |

Regla: **un rollback nunca toca datos de usuario**. Si la única forma de «arreglar» algo fuera borrar
o reescribir asientos, se detiene y se escala.

## 8. Problemas conocidos de la versión 1.0

Se listan sin adornos: son limitaciones reales verificadas en el código, no riesgos hipotéticos.

| # | Problema | Impacto | Mitigación mientras exista |
| --- | --- | --- | --- |
| 1 | **No hay restauración integral.** El único respaldo completo es el `run-as` manual desde la Mac, que exige instalar temporalmente el build debug; la restauración dentro de la app devuelve `NOT_READY` y el JSON no tiene importador | Una tablet perdida o borrada solo recupera lo guardado en el último respaldo | Respaldar antes de cada actualización y con regularidad; guardar una copia fuera de la Mac |
| 2 | **La tablet solo acepta actualizaciones firmadas con la clave debug de una Mac concreta** | Si esa clave se pierde, actualizar exige desinstalar y se pierden los datos | Respaldar `~/.android/debug.keystore` junto con los respaldos de la tablet |
| 3 | **El umbral de cobertura Kover (80 %) solo aplica a `com.facturastock.app.domain.*`** | `feature`, `data/local` y `data/repository` no tienen piso de cobertura | Mantener las pruebas UI/instrumentadas y revisar sus conteos; ampliar Kover si se acuerda un nuevo ámbito |
| 4 | **No hay análisis CodeQL/SARIF en la CI** | Algunas clases de vulnerabilidad de código no tienen un analizador dedicado | Se mantienen análisis Kotlin/Android, Dependency Review y escaneo de secretos |
| 5 | **Los presupuestos físicos de Prompt 47 no están aceptados.** Tras optimizar y remedir, el parser del AVD ya cumple, pero la captura virtual y los frames de la lista de 100 líneas aún exceden sus límites | Riesgo de captura lenta y scroll poco fluido en gama media | Continuar la lista y ejecutar 30 muestras más cámara real, TalkBack y fuente 200 % en Pixel 6a físico; no convertir el AVD en aprobación |
| 6 | **El lector de Ventas e Inventario admite solo el perfil de teclado físico HID y no está certificado con un modelo real concreto** | Un lector serial/SPP, una distribución de teclado o un sufijo distintos pueden no entregar el código esperado | Configurar USB/Bluetooth como *keyboard wedge* con Enter/Tab y ejecutar el checklist de Ventas y de `Inventario → Existencias` con el modelo y adaptador OTG que usará el negocio |
| 7 | **El JSON contable v4 no exporta ventas, deudas ni abonos** | La exportación no sirve como copia de ventas ni de cuentas por cobrar | Usar el respaldo `run-as` como única copia completa |

## 9. Registro de incidente

Se anota en el canal de soporte del proyecto, con este contenido mínimo y **sin datos personales,
RUC, números de documento reales ni valores de secretos**:

- Fecha y hora con zona (`America/Lima`).
- Versión instalada (`versionName` / `versionCode`) y tipo de build (optimizado o debug).
- Síntoma literal que vio la persona, incluido el texto del aviso.
- Qué se verificó y qué no se pudo verificar.
- Estado del libro local: intacto o comprometido.
- Fecha del último respaldo `run-as` verificado.
- Acción tomada y si quedó pendiente algo.

Si el incidente terminó sin explicación, se registra así. Un incidente cerrado sin causa es
información útil; un incidente cerrado con una causa inventada no lo es.
