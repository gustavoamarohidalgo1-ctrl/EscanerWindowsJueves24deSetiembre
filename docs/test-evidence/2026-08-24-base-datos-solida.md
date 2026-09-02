# Evidencia — hardening de base de datos local

Fecha: **24 de agosto de 2026** (`America/Lima`).

Alcance: Room/SQLite v23, migración desde cada esquema soportado, transacciones e idempotencia,
WAL, foreign keys, aislamiento entre negocios, corrupción fail-closed y reapertura. Esta corrida no
declara respaldo/restauración total ni sustituye un ensayo de pérdida física del equipo.

## Entorno

| Elemento | Valor observado |
| --- | --- |
| Proyecto | `/Users/gustavo/Desktop/ProyectoMayda` |
| Dispositivo | Pixel Tablet AVD, Android 15 / API 35 |
| Serial usado | `emulator-5554` |
| Red durante JVM/Gradle | `--offline` |
| Esquema generado | v23, 29 entidades, identity hash `a3fb25dda1524de7615e135992d305e7` |
| APK instalada | `app-local-debug.apk`, 67.237.238 bytes |
| SHA-256 APK | `274be4b1e46eba9ede25ba53f76f288c780cdd26c1c62b5a5c6d0f1838b1d989` |
| SHA-256 `23.json` | `b7d078a74e6a465b69b7fbdf5b8ef76d4945db3e6bf8a0d8819613fe5f250222` |

No se usó ni modificó un emulador de teléfono.

## Cambios acreditados

- `FacturaStockDatabase` sube de v22 a v23, registra `MIGRATION_22_23`, fija
  `WRITE_AHEAD_LOGGING` y recibe una factory endurecida.
- La migración valida relaciones entre negocios antes del primer DDL, sustituye índices
  redundantes/cortos por índices compuestos e instala diez triggers tenant adicionales.
- `FailClosedSQLiteOpenHelperFactory` rechaza `allowDataLossOnRecovery` y no delega la corrupción
  al handler que puede eliminar la base o sus archivos `-wal`/`-shm`.
- El gate `verifyRoomSchemaPolicy` exige el esquema 1…23, cada migración declarada/registrada, WAL
  explícito, factory productiva y ausencia de fallback destructivo.
- La regresión de venta ejecuta checkpoint WAL, cierra/reabre una base nombrada y verifica que el
  reintento no duplica venta, línea, movimiento ni auditoría.

## Resultados finales

| Control | Comando/evidencia | Resultado |
| --- | --- | --- |
| Formato, límites y política Room | `spotlessCheck`, `ciStaticAnalysis`, `:app:verifyRoomSchemaPolicy` | **CUMPLE** |
| Generación/compilación | `:app:kspLocalDebugKotlin`, `:app:compileLocalDebugKotlin`, `:app:compileLocalDebugAndroidTestKotlin` | **CUMPLE**; `23.json` generado |
| JVM completa | `./gradlew --offline --no-daemon --max-workers=1 test` | **6.613/6.613**, 0 fallos, 0 errores, 0 omitidas |
| Persistencia focal | seis clases de migración, factory, política, esquema y ventas | **53/53**, 0 fallos, 0 omitidas |
| Full path | `FullPathMigrationTest` | **22/22** orígenes v1…v22→v23; cada ruta comprueba FK e integridad |
| Hardening v22→v23 | `DatabaseHardeningMigrationTest` | **4/4**: filas/planes, triggers, preflight y rollback DDL |
| Corrupción | `FailClosedSQLiteOpenHelperFactoryTest` | **4/4**: ciclo normal, rechazo destructivo, sidecars y archivo corrupto preservados |
| Venta tras reapertura | `RoomSaleRepositoryTest` | **13/13**, incluida idempotencia después de checkpoint/cierre/reapertura |
| Android completa | `ANDROID_SERIAL=emulator-5554 … :app:connectedLocalDebugAndroidTest` | **485/485**, 0 fallos, 0 errores, 0 omitidas |
| Instalación | `adb -s emulator-5554 install -r …/app-local-debug.apk` | **Success** |
| Arranque | `am start -W -n com.facturastock.app/.MainActivity` | **Status ok**, arranque frío; `MainActivity` quedó reanudada |
| Base instalada | snapshot coherente tras cerrar la app | v23, journal `wal`, `integrity_check=ok`, 0 violaciones FK, 29 tablas, 72 índices y 78 triggers |

La corrida completa encontró una aserción de integración que todavía esperaba literalmente la
versión 22 aunque ya recorría hasta v23. Se actualizó el nombre del helper y la expectativa; sus dos
casos focales pasaron y luego la suite completa terminó 485/485. También se reemplazó una lectura
directa de `openHelper` que no representaba el camino de conexiones de Room 2.8 por una prueba de
comportamiento: una escritura DAO huérfana debe ser rechazada, además de ejecutar
`foreign_key_check`.

## Garantías observadas y límites

| Escenario | Estado |
| --- | --- |
| Crash/cierre normal, reintento y reapertura | Cubierto por Room, WAL, transacciones, CAS e idempotencia |
| Migración 1…22→23 | Cubierta con datos sintéticos máximos por versión; no se borran tablas |
| Referencia cross-tenant nueva | Rechazada por triggers v23 |
| v22 legacy cross-tenant | La migración aborta antes del DDL y conserva v22 para intervención |
| Archivo corrupto | Se conserva y la apertura falla cerrada; **no** existe reparación automática |
| Desinstalación, borrar datos o pérdida de equipo | **No recuperable actualmente** desde la app |
| Ventas en Firebase/exportación v3 | **No incluidas**; continúan solo en Room |
| Cifrado integral del archivo SQLite | No usa SQLCipher; depende del sandbox/cifrado del dispositivo |
| Corte eléctrico justo después de commit | WAL/`synchronous=NORMAL` puede perder el commit más reciente; adoptar `FULL` requiere configuración uniforme y benchmark físico |

La explicación operativa y los comandos reproducibles están en
[`../DATABASE_RELIABILITY.md`](../DATABASE_RELIABILITY.md).
