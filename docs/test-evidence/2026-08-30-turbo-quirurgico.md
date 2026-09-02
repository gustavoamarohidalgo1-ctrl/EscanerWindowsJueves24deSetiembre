# Evidencia — Turbo quirúrgico

Fecha de cierre: **30 de agosto de 2026** (`America/Lima`). La campaña de perfiles y
Macrobenchmark comenzó el 29 de agosto y cruzó medianoche. Esta evidencia corresponde a la opción
de optimización de bajo/medio riesgo: arranque, configuración compartida y revisión de cien líneas,
sin modificar el modelo contable.

## Resultado

La implementación y sus gates funcionales están verdes. El inicio frío diagnóstico queda muy por
debajo del presupuesto de 1.200 ms y el costo CPU p95 de la lista cumple 16,67 ms en `local` y
`cloud`. El objetivo de overrun p95 `<= 0 ms` todavía no se cumple en el AVD: quedan 1,132 ms local
y 1,432 ms cloud. Por eso esta ronda no afirma cero errores ni aceptación física “ultra fast”.

## Cambios aplicados

### Arranque

- [`FacturaStockApp.kt`](../../app/src/main/java/com/facturastock/app/navigation/FacturaStockApp.kt)
  no crea `DraftFlowViewModel` mientras la compuerta de acceso/onboarding no permite montar la app.
- [`FacturaStockApplication.kt`](../../app/src/main/java/com/facturastock/app/FacturaStockApplication.kt)
  conserva las dependencias pesadas como `Lazy` y difiere limpieza, recuperación de outbox,
  schedulers y observabilidad hasta el primer destino real y dos frames. Los harness de perfil y
  benchmark pueden suprimir ese trabajo sin cambiar producción.
- [`DataStoreAppConfigurationRepository.kt`](../../app/src/main/java/com/facturastock/app/data/settings/DataStoreAppConfigurationRepository.kt)
  comparte una sola observación caliente, versionada y reintentable entre Application, bloqueo y
  onboarding. Un retry invalida también el replay antiguo para colectores nuevos.
- [`MainActivity.kt`](../../app/src/main/java/com/facturastock/app/MainActivity.kt) coordina el retry,
  no expone deep links antes de desbloquear y no monta la navegación detrás de la pantalla de lock.
- [`HomeRoute.kt`](../../app/src/main/java/com/facturastock/app/feature/home/HomeRoute.kt) marca Home
  listo únicamente después de recibir dashboard o fallo y renderizar dos frames.

### Revisión de cien líneas

- [`InvoiceLineReviewViewModel.kt`](../../app/src/main/java/com/facturastock/app/feature/linereview/InvoiceLineReviewViewModel.kt)
  proyecta textos, búsqueda normalizada y formatos monetarios fuera de Main. Conserva identidad para
  99/100 filas cuando cambia una sola línea y acumula IDs sucios sin perder cambios durante renders
  cancelados.
- Cada autoguardado usa linaje local y revisión CAS esperada. Una observación remota nunca se adopta
  como base implícita de escritura; resultados, rebases, navegación y errores obsoletos no pueden
  publicar sobre una operación más nueva.
- [`InvoiceLineReviewScreen.kt`](../../app/src/main/java/com/facturastock/app/feature/linereview/InvoiceLineReviewScreen.kt)
  prepara presentaciones y contenido TalkBack en `Dispatchers.Default`, reutiliza la proyección que
  no cambió y muestra un placeholder estable mientras llega una tarjeta.
- Los saltos largos usan `scrollToItem` hasta una cola corta y animan solo los últimos cuatro items.
  Las tarjetas redujeron jerarquía y movieron acciones secundarias a un menú accesible.
- [`InvoiceLinesEdit.kt`](../../app/src/main/java/com/facturastock/app/domain/model/InvoiceLinesEdit.kt)
  calcula `activeLines` una vez por snapshot.
- Los reportes del compilador Compose se habilitaron desde
  [`app/build.gradle.kts`](../../app/build.gradle.kts). En `localRelease`, los 19 composables de
  `feature.linereview` son `skippable`; los dos símbolos no omitibles son helpers puros
  (`summaryStatusMessage` y `confidenceLabel`), no composables.

### Perfiles y variantes

- La captura atómica `turbo-20260829-final2-hwgpu` recorrió arranque hasta Home listo y terminó 2/2,
  0 fallos y 0 omitidas en 5 min 37 s.
- [`startup-prof.txt`](../../app/src/main/baselineProfiles/startup-prof.txt): 1.397 líneas,
  SHA-256 `43ea0082ce1ebbdd87e04ef436e801e3ffff93f5a06b189bd5e24b54b582bfc1`.
- [`baseline-prof.txt`](../../app/src/main/baseline-prof.txt): 1.402 líneas,
  SHA-256 `34bc46be8fcf4a31664ee40707bb407778d03b4182f288081216d482d9bf2160`.
- Profgen resolvió **1.395/1.395 reglas exactas** contra el DEX no minificado de `localProfile`.
- La matriz de ensamblado descubrió que `cloudProfile` tenía el SDK Play Integrity pero no los dos
  bindings Hilt propios. Se añadió
  [`BuildTypeAppCheckInstaller.kt`](../../app/src/cloudProfile/java/com/facturastock/app/data/sync/BuildTypeAppCheckInstaller.kt),
  se amplió el gate anti-emulador y se agregó regresión. La repetición completa ensambló app y
  benchmark `localProfile`/`cloudProfile` y app `localRelease`/`cloudRelease` correctamente.

## Validación funcional

| Control | Resultado final |
| --- | --- |
| Gate Spotless, estático, unitarias, Lint local/cloud, Kover, release y Profgen | **BUILD SUCCESSFUL**, 180 tareas |
| Unitarias `localDebug` | **1.286/1.286**, 0 fallos, errores u omitidas |
| Unitarias `cloudDebug` | **1.391/1.391**, 0 fallos, errores u omitidas |
| Instrumentadas `localDebug` en Pixel Tablet AVD | **613/613**, 0 fallos, errores u omitidas |
| Smoke real `cloudDebug` Firebase/App Check/consentimiento | **1/1**, 0 fallos |
| Matriz app/benchmark profile + app release | **BUILD SUCCESSFUL**, 307 tareas |
| Contratos release, resumidor Macrobenchmark, artefactos CI y Spotless base | **4/4 PASS** |
| Auditorías finales independientes de carreras | Sin hallazgos P0, P1 o P2 |

La primera corrida instrumentada detectó una expectativa sensible al viewport: con el IME abierto,
el AVD dejó unos 214 px y `LazyColumn` descompuso la tarjeta buscada. El filtro y la normalización
seguían correctos. La regresión ahora localiza el item mediante el contenedor lazy, el caso aislado
pasó y luego pasaron las 613 pruebas completas.

## Macrobenchmark final separado por flavor

Entorno: Pixel Tablet AVD, Android 15/API 35 ARM64, cuatro cores, renderer Apple M2/Metal,
`cpuLocked=false`, `sustainedPerformanceMode=false` y `compilationMode=run-from-apk`. Los percentiles
son nearest-rank sobre las muestras crudas. El resumen reproducible está en
[`summary.md`](../../app/build/reports/turbo-benchmarks/2026-08-29/summary.md), SHA-256
`0f30ba8575ea21394b95b2ea1423980c84bc7857569af8aaf63d31d4c6b139c7`.

### Inicio frío hasta Home listo

| Variante | Iteraciones/trazas | TtID válidos | p50 | p90 | p95 | Máximo | Presupuesto p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| local | 18/18 | 13 | 140,457 ms | 166,146 ms | **170,923 ms** | 170,923 ms | CUMPLE `<= 1.200 ms` |
| cloud | 18/18 | 14 | 129,879 ms | 169,228 ms | **203,529 ms** | 203,529 ms | CUMPLE `<= 1.200 ms` |

AndroidX no emitió `timeToFullDisplayMs`; TtFD no se infiere desde TtID. El journey sí espera Home
listo, pero la única métrica publicada por el recolector fue TtID.

### Lista de cien líneas

| Variante | Iteraciones/trazas | Frames | CPU p50/p90/p95 | Overrun p50/p90/p95 | Jank overrun > 0 | Estado |
| --- | ---: | ---: | --- | --- | ---: | --- |
| local | 11/11 | 435 | 2,435 / 3,956 / **6,721 ms** | -12,182 / -10,087 / **1,132 ms** | 22/435 (**5,06 %**) | CPU cumple; overrun no |
| cloud | 11/11 | 439 | 2,481 / 5,501 / **15,176 ms** | -12,159 / -9,038 / **1,432 ms** | 26/439 (**5,92 %**) | CPU cumple; overrun no |

- Frames CPU > 16,67 ms: local 18/435 (4,14 %), cloud 19/439 (4,33 %).
- Heap p95: local 22.912 KiB, cloud 23.096 KiB.
- RSS total emparejado p95: local 167.084 KiB, cloud 169.588 KiB.
- FrameCount p50/p90/p95: local 40/40/40; cloud 40/41/41.
- Las cuatro series canónicas terminaron JUnit 1/1 y sus logcat contienen 0 FATAL, ANR, OOM o
  violaciones StrictMode del target.

Dos intentos local cold quedaron aislados bajo `superseded/`: uno por un flake de infraestructura
`StartupTimingQuery` después de siete trazas y otro porque AndroidX publicó solo 9 TtID de 15. No se
mezcló ninguna muestra descartada con las cuatro series canónicas.

## Límite honesto y siguiente aceptación

El AVD demuestra que no hay una regresión gruesa, que el arranque cumple holgadamente su cota y que
el CPU p95 de la lista entra en un frame. No reemplaza el control físico: el overrun p95 continúa
rojo y el AVD no bloqueó CPU ni temperatura. La aceptación recomendada es repetir 30 iteraciones por
serie en un Pixel 6a físico, con temperatura, batería, almacenamiento y frecuencia controlados, y
exigir nuevamente TtID, CPU/frame, overrun, jank y ausencia de crashes/ANR/OOM.
