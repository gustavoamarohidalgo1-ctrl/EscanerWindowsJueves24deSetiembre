# Evidencia — venta manual por nombre, precio de venta y ganancias

Fecha: **24 de agosto de 2026** (`America/Lima`). Esta corrida valida la ampliación solicitada
después del primer módulo de ventas: búsqueda manual exclusivamente por nombre, precio de venta
obligatorio al incorporar productos y una vista de ganancia estimada por producto. Todos los datos
de las pruebas son sintéticos.

## Alcance implementado

- **Venta manual por nombre:** el campo manual consulta nombres de productos activos y presenta
  coincidencias exactas o similares. No busca SKU, código de barras ni alias, incluso si la consulta
  solo contiene números. Muestra como máximo cinco resultados y acota el cálculo difuso a 200
  candidatos. El lector HID conserva, por separado, la búsqueda exacta del código asociado.
- **Precio al recibir:** crear un producto durante la vinculación de una compra exige un precio de
  venta positivo por unidad de inventario y en la moneda configurada. Un producto antiguo sin
  precio exige una actualización CAS antes de poder vincularse; un producto staged antiguo sin
  precio no puede omitir la revisión ni llegar a preparación/posting.
- **Ganancias por producto:** Inventario incluye las secciones `Existencias` y
  `Ganancias por producto`. La estimación usa precio de venta menos costo promedio ponderado del
  stock positivo, muestra ganancia unitaria, margen y proyección sobre la existencia. Usa
  `BigDecimal`; no convierte monedas ni presenta `S/ 0,00` cuando falta precio, stock o una moneda
  comparable.
- **Persistencia:** Room v22 añade `salePriceMinorUnits` y `salePriceCurrencyCode` como un par
  nullable para compatibilidad. Las rutas nuevas exigen el par válido y los payloads cloud PRODUCT
  v2 lo sincronizan sin romper PRODUCT v1.

La ganancia mostrada es **estimada**, no utilidad contable realizada: no incorpora gastos
operativos, devoluciones ni conversión de moneda.

## Resultados ejecutados

| Control | Comando o evidencia | Resultado |
| --- | --- | --- |
| Formato y fronteras | `spotlessCheck`, `ciStaticAnalysis`, dominio, logging privado, seguridad móvil, offline, UI y política Room | **CUMPLE** |
| Esquema Room | KSP + `app/schemas/.../22.json` + `verifyRoomSchemaPolicy` | v22 generado; cadena v1…v21→v22 disponible, sin fallback destructivo |
| JVM completo | `./gradlew --offline --no-daemon --max-workers=1 test` | **6.571/6.571**, 0 fallos, 0 errores y 0 omitidas entre local/cloud × benchmark/debug/release |
| JVM local | `:app:testLocalDebugUnitTest` | **1.056/1.056**, 0 fallos, 0 errores y 0 omitidas |
| Cobertura crítica | `:app:koverXmlReportLocalDebug :app:koverVerifyLocalDebug` | 10.888/12.234 líneas = **89,00 %**; umbral 80 % superado |
| Lint | `:app:lintLocalDebug :app:lintCloudDebug` | **CUMPLE**, 0 errores en ambos flavors |
| Firebase local | Emulator Suite Auth, Firestore, Functions y Storage | **111/111**, 0 fallos y 0 omitidas; incluye PRODUCT v1/v2, precio, downgrade e aislamiento por tenant |
| Android completo | `ANDROID_SERIAL=emulator-5554 :app:connectedLocalDebugAndroidTest` | **473/473**, 0 fallos, 0 errores y 0 omitidas en `Pixel_Tablet(AVD)`, Android 15 |
| Venta/Room dentro del total Android | XML conectado | `RoomSaleRepositoryTest` 12/12 e invariantes SQL 1/1 |
| Migraciones dentro del total Android | XML conectado | Full path 21/21 para orígenes v1…v21→v22; caso específico v21→v22 y sus triggers verde |
| UI nueva dentro del total Android | XML conectado | `SalesScreenTest` 11/11, `InventoryScreensTest` 3/3 y `ProductLinkingScreenTest` 5/5 |
| E2E | XML conectado | Factura sintética de 38 líneas, producto staged con precio, doble toque idempotente y flujo Hilt completo verdes |
| APK | `:app:assembleLocalDebug` | **BUILD SUCCESSFUL**; SHA-256 `6d94663e72ee6762e9ce8adbd55aceb29c53d94ee36798d548af6fa555cc01d2` |

Los resultados JVM provienen de los XML bajo `app/build/test-results/`; Android, del XML conectado
`app/build/outputs/androidTest-results/connected/debug/flavors/local/`. Kover filtró
`com.facturastock.app.domain.*`.

## Instalación en la tablet solicitada

- Único dispositivo conectado: `emulator-5554`, modelo **Pixel Tablet**, Android 15.
- `adb install -r -d app/build/outputs/apk/local/debug/app-local-debug.apk`: `Success`.
- Arranque frío de `com.facturastock.app/.MainActivity`: `Status: ok`.
- Después del arranque, `MainActivity` quedó como `topResumedActivity`, el proceso permaneció activo
  y Logcat no registró un `AndroidRuntime` fatal.
- No se inició ni instaló nada en el emulador de celular.

La tarea instrumentada desinstala su aplicación objetivo al finalizar; por eso la instalación final
en esta corrida fue limpia. No se atribuye a este AVD una preservación de datos de una instalación
anterior. La preservación de estructuras y datos se acredita mediante las migraciones v1…v21→v22.

## Límites que permanecen abiertos

1. No se conectó un lector USB/Bluetooth físico; queda acreditado el protocolo HID/*keyboard
   wedge*, no una marca o modelo comercial.
2. No se ejecutó el piloto humano en teléfono físico con cámara, TalkBack y fuente 200 %.
3. La ganancia es una proyección de inventario; ventas anuladas/devoluciones, caja, comprobante
   fiscal, gastos, exportación restaurable y sync de ventas siguen fuera del alcance v1.
4. No se publicó ni desplegó Functions a Firebase productivo y no se instaló un AAB desde Play.

Por estos límites, esta evidencia acredita la implementación y automatización de la ampliación, no
la publicación universal ni la certificación de hardware.
