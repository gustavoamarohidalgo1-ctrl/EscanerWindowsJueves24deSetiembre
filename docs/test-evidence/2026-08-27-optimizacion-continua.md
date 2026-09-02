# Evidencia — optimización continua Android

Fecha: **27 de agosto de 2026** (`America/Lima`). Esta evidencia reúne la ronda de
optimización de UI, estado, persistencia y empaquetado y sus mediciones focales en un
**Pixel Tablet AVD, Android 15/API 35**. Es evidencia diagnóstica: no aprueba los
presupuestos del Pixel 6a físico, no acredita publicación en Play y no acredita
Configuration Cache.

## Cambios aplicados

### UI, estado y accesibilidad

- El tema Compose publica paletas Material y semánticas completas para claro y oscuro,
  sigue el modo del sistema por defecto y alinea fondos de lanzamiento y apariencia de
  las barras del sistema con los recursos `day`/`night`. Las parejas de texto/fondo se
  verifican con contraste WCAG AA de 4,5:1.
- Las listas de compras, inventario, detalle y ventas conservan claves y `contentType`
  estables; ordenamientos, formatos y proyecciones derivados se recuerdan cuando su
  entrada no cambia. Esto reduce asignaciones y recomposición sin cambiar el orden ni
  la semántica visible.
- Los efectos UDF se recogen únicamente mientras el destino está activo, de modo que
  una entrada de back stack en segundo plano no consuma navegación o snackbars.
- `SalesViewModel` reinicia las observaciones Room solo cuando cambia negocio o moneda,
  prepara la proyección catálogo/inventario fuera de Main y conserva una búsqueda
  mientras su catálogo siga vigente. Los guardados que ya cruzaron el debounce no se
  cancelan al volver/reintentar; cambio de negocio, edición pendiente y doble mutación
  mantienen exclusión explícita.
- CameraX usa `FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER`. En el intento instrumentado
  posterior negoció Preview+JPEG a **1280×960** y completó la captura; ambas dimensiones
  satisfacen el umbral de calidad 900×1200. El E2E de navegación, OCR y recreación quedó
  verde después de corregir la publicación privada descrita abajo.

### Consultas, memoria y archivos privados

- Inventario y catálogo suprimen emisiones Room idénticas; el ensamblaje de inventario
  agrupa en una sola pasada en vez de retener `groupBy` y listas intermedias. Ventas busca
  el borrador activo mediante `draftSlot`, único e indexado, en vez de recorrer el
  historial del negocio.
- El bootstrap documental pasó de `1 + 2N` lecturas a un anti-join Room que devuelve
  únicamente compras sin `upload v1` o `purge v2`. Las páginas de sync de compras y
  catálogo usan inserciones por lote, verifican solo duplicados y mantienen página y
  cursor atómicos, con rollback ante constraint o ENOSPC.
- El ensamblaje remoto reemplazó `groupBy`, `Pair` y listas auxiliares por un merge
  lineal. El manifest OCR se consume en streaming.
- Un artefacto documental ya retenido se autentica y descifra una sola vez y repite
  `fsync` bajo el mismo lock. El formato **FSE1/AES-GCM** no cambió. Cada intento JPEG
  queda limitado a **2 MiB**, limpia el buffer temporal y propaga cancelación.
- La publicación privada de una captura conserva el hard-link como primera opción y
  ahora reconoce tanto `EACCES` como `EPERM` cuando SELinux lo prohíbe. En ese caso usa
  `Files.move` sin reemplazo dentro del mismo `filesDir`; siguen vigentes el rechazo de
  symlinks, la comparación de hash y las barreras `fsync`. Esto corrigió el flujo real
  cámara → importación en Android 15.

### Build, perfiles y CI

- `FacturaStockBaselineProfile` separa dos recorridos del mismo inicio frío: uno genera
  el Baseline Profile instalable y otro el Startup Profile. El filtro se ancla al owner
  `^[HSP]*Lcom/facturastock/app/`, por lo que no incorpora reglas de dependencias.
- Diez reglas de arranque observadas para `FacturaStockApplication`, `MainActivity` y el
  bootstrap Room viven en `src/main/baselineProfiles/startup-prof.txt` y también en
  `baseline-prof.txt`. El primer archivo dirige el layout DEX; el segundo permite
  compilarlas al instalar el perfil.
- Release conserva R8, ofuscación, optimización, `shrinkResources` y el shrinker integrado
  de AGP. Los gates de bundle exigen `.prof/.profm`, metadata R8 y al menos un DEX
  `startup=true`; ambos AAB finales declaran `startup=true, false` e
  `isDexLayoutOptimizationEnabled=true`.
- Los presupuestos Macrobenchmark están versionados y el resumidor falla por métricas
  ausentes o p95 excedido. CI declara smokes acotados en API 26 y API 36, mientras el
  candidato productivo depende de Lint y unitarias `cloudRelease`. La configuración de
  esos jobs está validada, pero esta evidencia no afirma haber ejecutado ambos AVD.

No se actualizaron versiones de dependencias en esta ronda.

## Gates y artefactos verificados

| Verificación | Resultado |
| --- | --- |
| Contrato release, perfiles y CI (`ruby scripts/test-release-contracts.rb`) | **PASS** |
| Spotless, análisis estático, todas las unitarias, lint local/cloud, Kover y AAB local/cloud | **BUILD SUCCESSFUL** en 9 min 42 s; 323 tareas |
| AAB local/cloud y gates de optimización | baseline compilado, R8/recursos optimizados y DEX `startup=true, false` en ambos |
| Generadores Baseline + Startup Profile | **2/2**, 0 fallos; **BUILD SUCCESSFUL** en 6 min 8 s |
| Unitarias `localDebug` | **1.203/1.203**, 0 fallos, 0 errores, 0 omitidas |
| Unitarias `cloudDebug` | **1.303/1.303**, 0 fallos, 0 errores, 0 omitidas |
| Android instrumentado completo | **571/571**, 0 fallos, 0 errores, 0 omitidas; **BUILD SUCCESSFUL** en 7 min 53 s |
| Regresión importador + cámara/OCR/recreación | **11/11**, 0 fallos; **BUILD SUCCESSFUL** en 1 min 41 s |
| `ThemeContrastTest` dentro de `localDebug` | **5/5**, incluye roles Material/semánticos claro y oscuro |
| `SalesViewModelTest` dentro de `localDebug` | **28/28**, incluye cambio de tenant, debounce, búsqueda y mutación única |
| `CappedCancellationByteArrayOutputStreamTest` | **2/2**, límite exacto, overflow y cancelación |
| Macrobenchmark focal `coldStartup` | **1/1**, 0 fallos; **BUILD SUCCESSFUL** en 1 min 31 s |
| Macrobenchmark focal `hundredLineListScroll` | **1/1**, 10 iteraciones, 0 fallos; **BUILD SUCCESSFUL** en 3 min 15 s |

Los AAB son artefactos de validación, no candidatos firmados ni publicados:

| AAB | Tamaño | SHA-256 |
| --- | ---: | --- |
| `app-local-release.aab` | **28.812.415 B** | `4605fc78a7cea196fe2fba07d6f185bacc74fe04f644857d86a887751e7951c6` |
| `app-cloud-release.aab` | **32.124.135 B** | `da46e9f3e2e0792f2c58a745a8107b60d7ff0ff5ddc46203f14dc4f5187dcd40` |

Frente a los artefactos del 25 de agosto, el local creció 120.637 B (+0,42 %) y el
cloud 124.491 B (+0,39 %). La ronda mezcla perfiles y cambios funcionales concurrentes;
por ello ese delta no se atribuye exclusivamente al Startup Profile.

## Lista Compose de 100 líneas

La referencia «antes» es la serie diagnóstica del 24 de agosto; «ahora» es la corrida
focal de diez iteraciones del 27 de agosto en el mismo tipo de Pixel Tablet AVD. Para
frames, p50/p95 se calculan sobre las muestras aplanadas; memoria usa nearest-rank sobre
las iteraciones. La diferencia de cantidad de iteraciones impide tratarla como un A/B
controlado, pero sirve para detectar la dirección de la regresión.

| Métrica | Antes | Ahora | Variación | Presupuesto | Lectura |
| --- | ---: | ---: | ---: | ---: | --- |
| `frameDurationCpuMs` p50 | 5.706 ms | **3.8485 ms** | −32,55 % | — | mejora diagnóstica |
| `frameDurationCpuMs` p95 | 21.668 ms | **19.438417 ms** | −10,29 % | ≤ 16,67 ms | mejora, aún excede |
| `frameOverrunMs` p95 | 5.866 ms | **3.447614 ms** | −41,23 % | ≤ 0 ms | mejora, aún excede |
| Heap máximo por iteración, p95 | 37.051 KiB | **31.206 KiB** | −15,78 % | — | menor pico |
| RSS anon+file emparejado, p95 | 217.112 KiB | **206.620 KiB** | −4,83 % | — | menor pico |

La corrida actual produjo 1.496 muestras de frame y diez trazas Perfetto. El JSON crudo
conservado mide 123.281 B y tiene SHA-256
`14e9e3b29e521c018e804665bf6558d4337a331e673c30aa3fbbab8546fd5ad8`.
La caída de las cinco métricas es favorable, pero los dos límites de fluidez permanecen
rojos; no se declara aprobado el presupuesto físico.

## Inicio frío: cola ruidosa

La corrida focal informó mediana `timeToInitialDisplayMs` de **527.328 ms** y
p95/máximo de **1802.722 ms**. Que p95 coincida con el máximo y sea más de tres veces la
mediana revela una cola larga en el AVD. Además, **1802.722 ms** supera la cota física
predeclarada de 1.200 ms. La serie prueba que el perfil es exigible y que el recorrido
termina, pero no demuestra por sí sola una mejora de startup ni permite aprobar el
presupuesto.

## Riesgos residuales y próximos controles

- Falta la serie de aceptación de **30 iteraciones en Pixel 6a físico**, con estado
  térmico, batería y almacenamiento controlados. Los p95 de frame y overrun y la cola de
  arranque requieren investigación adicional.
- El timeout inicial de `NavigationRecreationTest` reveló que SELinux devolvía `EACCES`
  al intentar el hard-link de la captura. Tras cubrir ese errno, el recorrido cámara →
  Preview → OCR → revisión → recreación pasó tanto focalmente como dentro de la matriz
  completa. La validación sigue siendo de cámara virtual; una cámara física conserva su
  control pendiente.
- Los smokes API 26/API 36 están cubiertos por contratos de workflow y bytecode de test,
  no por una ejecución local documentada en esta ronda. TalkBack, fuente al 200 %, cámara
  real y lector HID físico también siguen pendientes.
- Configuration Cache **no está acreditada ni habilitada**. Los comandos focales de
  benchmark emitieron que el argumento dinámico `iterations` no es compatible con ella;
  no existen dos ejecuciones limpias consecutivas que permitan afirmar soporte.
- La suite descubrió dos métodos de seguridad de symlinks que Kotlin exponía con retorno
  `Boolean`, inválido para JUnit 4, y un test de cancelación que bloqueaba su propio event
  loop. Ahora retornan `Unit`, arrancan la coroutine sin despacho inicial y verifican las
  tres aperturas SAF (escritura, truncado y lectura de comprobación); forman parte de las
  571 pruebas verdes.
