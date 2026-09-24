# FacturaStock para Windows

FacturaStock es una aplicación de escritorio para Windows, en español, que registra ventas al
contado y a crédito, deudas y abonos, inventario y reportes (con PDF) de un negocio. Es el
traslado a Windows de la app Android del mismo nombre: la misma lógica de negocio, las mismas
pantallas y la misma base de datos, empaquetadas como un programa instalable (`.msi`).

- **100 % sin conexión.** No usa internet, cuenta ni nube. Todo se guarda en el equipo.
- **Misma base de datos que la tablet.** El esquema Room v29 es idéntico (hash de identidad
  `6814b9206f465ac8e710d5afa59409b2`), así que un archivo `facturastock.db` copiado desde la
  tablet se abre tal cual (ver [Traer los datos de la tablet](#traer-los-datos-de-la-tablet)).
- **Lector de códigos USB.** En Windows el lector funciona como un teclado: se conecta y se usa
  en Vender e Inventario igual que en la tablet.

## Instalar en Windows

1. Descargar el instalador `FacturaStock-<versión>.msi` (lo genera GitHub Actions, ver
   [Generar el instalador](#generar-el-instalador)).
2. Ejecutarlo y seguir el asistente. Crea un acceso en el menú Inicio y en el escritorio.
   Windows 10 u 11 de 64 bits; no hace falta instalar Java (va incluido en el instalador).
3. Abrir **FacturaStock**. La primera vez pide el nombre del negocio, igual que en Android.

Un `.msi` nuevo de una versión mayor actualiza la instalación anterior sin tocar los datos.

## Dónde se guardan los datos

Todo vive en `%LOCALAPPDATA%\FacturaStock` (por ejemplo `C:\Users\<usuario>\AppData\Local\FacturaStock`):

| Carpeta | Contenido | Equivalente Android |
| --- | --- | --- |
| `databases\facturastock.db` (+ `-wal`, `-shm`) | Base Room: productos, ventas, deudas, compras | `/data/data/.../databases` |
| `datastore\app_settings.preferences_pb` | Configuración (negocio activo, IGV, política de costos) | DataStore |
| `files\`, `no_backup\`, `cache\` | Imágenes de facturas y archivos temporales | `filesDir`, `noBackupFilesDir`, `cacheDir` |

Desinstalar el programa **no** borra esta carpeta.

### Respaldo

Con FacturaStock **cerrado**, copiar la carpeta `%LOCALAPPDATA%\FacturaStock` completa a un
disco externo o a otra ubicación. Para restaurar, cerrar FacturaStock y volver a poner la
carpeta en su sitio. La exportación JSON de **Ajustes → Datos** sigue existiendo, igual que en
Android, y es una copia legible parcial que no se puede importar.

### Traer los datos de la tablet

La base de la tablet es compatible directamente:

1. Hacer el respaldo de la tablet descrito en [`docs/RUNBOOK.md`](docs/RUNBOOK.md), que deja
   `facturastock.db`, `facturastock.db-wal` y `facturastock.db-shm`.
2. Instalar FacturaStock en Windows, abrirlo una vez y **cerrarlo**.
3. Copiar esos tres archivos a `%LOCALAPPDATA%\FacturaStock\databases\`, reemplazando los que
   haya. Copiar también `app_settings.preferences_pb` de la tablet (carpeta `files/datastore`)
   a `%LOCALAPPDATA%\FacturaStock\datastore\` para conservar el negocio activo y la
   configuración.
4. Abrir FacturaStock.

Las imágenes de facturas cifradas en la tablet no se pueden descifrar en Windows (la clave vivía
en el Keystore de Android); el resto de los datos sí.

## Diferencias con la versión Android

| Android | Windows |
| --- | --- |
| Botón Atrás del sistema | Tecla **Esc** o la flecha de la barra superior. En la pantalla inicial no cierra la ventana |
| Lector HID distinguido por ser un teclado físico | Se distingue por la velocidad: una ráfaga de caracteres a menos de 50 ms entre sí seguida de Enter/Tab es una lectura; lo que teclea una persona en el buscador sigue siendo búsqueda |
| "Crear documento" (SAF) para el PDF y la exportación | Diálogo "Guardar como" de Windows; el PDF se abre con el visor predeterminado |
| PDF con `android.graphics.pdf` | PDF con Apache PDFBox, con la fuente Arial de Windows |
| Bloqueo biométrico | No existe (la versión Android ya no permitía activarlo) |
| Cámara (CameraX) y OCR ML Kit en el flujo de compra por factura | El flujo sigue oculto, como en Android. Si se usa, la imagen se elige como archivo y el OCR es el integrado en Windows (`Windows.Media.Ocr`) |
| Clave de imágenes en Android Keystore | Clave AES-256 en `no_backup\keys\`, protegida por la carpeta del usuario |
| WorkManager | Mantenimiento de privacidad en segundo plano mientras la app está abierta |

Solo se puede abrir una ventana de FacturaStock a la vez, porque dos procesos sobre la misma base
romperían los cerrojos internos de cobro e inventario.

## Desarrollo

### Requisitos

- JDK 21 (por ejemplo Temurin o el JBR de IntelliJ).
- Nada más: Gradle descarga Compose, Room, SQLite embebido y el resto.

### Tecnología

| Componente | Versión |
| --- | ---: |
| Gradle / Kotlin / KSP | 8.13 / 2.3.21 / 2.3.11 |
| Compose Multiplatform (Desktop) | 1.12.1 |
| Navigation / Lifecycle (JetBrains) | 2.9.2 / 2.10.0 |
| Room / SQLite embebido | 2.8.4 / 2.6.2 |
| Dagger (sin Hilt) | 2.58 |
| DataStore Preferences | 1.2.1 |
| Coil / PDFBox | 3.4.0 / 3.0.8 |

Las versiones están fijadas en [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

### Comandos

```bash
./gradlew :app:run
```

```bash
./gradlew :app:test
```

`:app:run` abre la app con los datos reales del equipo. Para probar sin tocarlos, definir
`FACTURASTOCK_HOME` con una carpeta vacía:

```bash
FACTURASTOCK_HOME=/tmp/facturastock-prueba ./gradlew :app:run
```

### Estructura

El código conserva la arquitectura de la app Android (ver [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)):

- `domain/`: modelos, puertos y casos de uso en Kotlin puro. Sin cambios respecto de Android.
- `data/`: Room, archivos, exportación y OCR. Las piezas propias de Windows son:
  - `data/local/sqlite/`: fachada `SupportSQLiteDatabase` y traducción de errores de SQLite, para
    que las 28 migraciones y los triggers de Android corran sin cambios sobre el driver de escritorio.
  - `data/local/RoomTransactions.kt`: `withTransaction` sobre la conexión de escritura.
  - `data/export/`: PDF con PDFBox y escritores de "Guardar como".
  - `data/ocr/`: OCR de Windows mediante PowerShell/WinRT.
- `feature/`, `ui/`, `navigation/`: las mismas pantallas Compose. `ui/navigation/DesktopBackHandler.kt`
  reemplaza el botón Atrás y `ui/platform/DesktopFileDialogs.kt` los diálogos de archivos.
- `di/`: Dagger. `AppComponent` es el grafo; `appViewModel()` reemplaza a `hiltViewModel()`.
- `Main.kt`: ventana, lector USB a nivel de ventana, tecla Esc e instancia única.

### Migraciones

Igual que en Android: la base **nunca** se destruye para migrar. Para un cambio de esquema,
subir `version` en `FacturaStockDatabase`, escribir `MIGRATION_N_N+1` como `LegacyMigration`,
registrarla en `.addMigrations(...)` y comprobar que KSP exporta exactamente un
`app/schemas/.../N+1.json` nuevo. `scripts/verify-room-schema-history.sh` impide modificar los
esquemas anteriores.

## Generar el instalador

El `.msi` solo se puede generar en Windows (usa `jpackage` y WiX). El workflow
[`ci.yml`](.github/workflows/ci.yml) lo hace en una máquina Windows de GitHub en cada push a
`main` o `windows` y lo publica como artefacto **FacturaStock-Windows-MSI** (pestaña *Actions*
→ la ejecución → *Artifacts*).

En un PC con Windows y JDK 21 también se puede generar localmente:

```bash
gradlew.bat :app:packageReleaseMsi
```

El instalador queda en `app\build\compose\binaries\main-release\msi\`. La versión se toma de
`FACTURASTOCK_VERSION_NAME` (por defecto `1.0.12`); para que un `.msi` actualice al anterior, la
versión debe ser mayor.

## Documentación histórica

`docs/` conserva los informes de la versión Android (análisis, optimizaciones, pruebas en la
tablet). Describen la misma lógica de negocio; lo específico de Android (APK, `adb`, emulador,
CameraX, ML Kit) no aplica a esta versión.
