# Evidencia — optimización profunda Android

Fecha: **24 de agosto de 2026** (`America/Lima`). Esta corrida compara el mismo Pixel Tablet AVD,
el mismo recorrido sintético de 100 líneas y cinco iteraciones antes y después del cambio. Es una
regresión diagnóstica; no sustituye la aceptación de 30 iteraciones en el Pixel 6a físico.

## Cambios aplicados

- El inicio de la aplicación difiere la construcción de dependencias que no son necesarias para el
  primer frame y agenda la configuración de WorkManager fuera de Main, sin cambiar el orden durable
  de recuperación.
- La revisión de líneas carga productos y unidades con dos consultas `IN` acotadas en lugar de hasta
  dos consultas por línea. La réplica de compras remotas pasa de `1 + N` consultas a dos consultas
  transaccionales y ensamble lineal.
- El matcher manual reutiliza buffers de distancia de edición, prepara una sola vez la consulta y
  evita colecciones temporales. La validación OCR recorre los lotes una vez y deja de crear seis
  listas auxiliares.
- Ventas e inventario conservan índices normalizados y opciones derivadas mientras no cambie su
  fuente. Las listas Compose usan claves/tipos estables y la revisión de 100 líneas conserva una
  caché LRU acotada de presentación, sin modificar acciones, semántica ni resultado visual.
- Se retiró el modelo ML Kit Barcode que no era usado: el lector soportado continúa siendo HID, como
  teclado físico. R8, reducción optimizada de recursos, reglas ProGuard acotadas y gates de AAB
  verifican el empaquetado release.

## Resultado medido

### Tamaño de artefactos

| Artefacto | Antes | Después | Variación |
| --- | ---: | ---: | ---: |
| APK `localDebug` | 88.961.109 B | 66.643.845 B | **−25,09 %** |
| AAB `localRelease` | 38.269.832 B | 28.643.325 B | **−25,15 %** |
| AAB `cloudRelease` | 41.516.430 B | 31.879.869 B | **−23,21 %** |

Los dos gates de bundle confirmaron R8, ofuscación, optimización, reducción optimizada de recursos,
perfil base compilado y ausencia de `mlkit_barcode_models`/`libbarhopper`. El metadato de ambos AAB
declara `DEX startup=false`; por eso no se atribuye una mejora a un Startup Profile inexistente.

### Inicio y memoria diagnósticos

`adb shell am start -S -W` midió cinco inicios `COLD` del APK debug. Se usa nearest rank para p95.

| Métrica | Antes | Después | Variación |
| --- | ---: | ---: | ---: |
| Media de `TotalTime` | 3.564,0 ms | 3.109,6 ms | **−12,75 %** |
| p95 de `TotalTime` | 4.398 ms | 3.697 ms | **−15,94 %** |
| `TOTAL PSS`, muestra tras apertura | 106.836 KiB | 105.213 KiB | −1,52 % |
| `TOTAL RSS`, muestra tras apertura | 226.576 KiB | 225.788 KiB | −0,35 % |

Tiempos finales: `2852, 2764, 2900, 3697, 3335 ms`. La memoria es una muestra puntual, no un p95.
El buffer `logcat -b crash` quedó vacío y `MainActivity` terminó como `topResumedActivity`.

### Lista Compose de 100 líneas

El benchmark `hundredLineListScroll` terminó 5/5 iteraciones, sin ANR/OOM. AndroidX informó
`context.compilationMode=run-from-apk`; este valor corresponde al runner self-instrumenting y no se
presenta como prueba del estado dexopt del target.

| Métrica | Antes | Después | Variación | Presupuesto físico | Estado diagnóstico |
| --- | ---: | ---: | ---: | ---: | --- |
| `frameDurationCpuMs` p50 | 5,660 ms | 5,706 ms | +0,81 % | — | — |
| `frameDurationCpuMs` p95 | 20,324 ms | 21,668 ms | **+6,61 %** | ≤ 16,67 ms | NO CUMPLE |
| `frameDurationCpuMs` máximo | 73,548 ms | 32,331 ms | **−56,04 %** | — | mejora |
| `frameOverrunMs` p50 | −8,704 ms | −9,475 ms | −8,86 % | — | mejora |
| `frameOverrunMs` p95 | 7,349 ms | 5,866 ms | **−20,18 %** | ≤ 0 ms | NO CUMPLE |
| `frameOverrunMs` máximo | 58,982 ms | 23,455 ms | **−60,23 %** | — | mejora |
| Heap p95 | 37.040 KiB | 37.051 KiB | +0,03 % | — | estable |
| RSS emparejado p95 | 215.844 KiB | 217.112 KiB | +0,59 % | ≤ 393.216 KiB | dentro del límite AVD |

La optimización redujo los tirones extremos y el sobretiempo p95, pero el costo CPU p95 varió al
alza y ambos presupuestos de frame siguen rojos. Con cinco muestras en AVD no se afirma una mejora
universal ni la aceptación física.

SHA-256 de los JSON crudos usados por el resumidor:

- antes: `b66b8e34dc8562fd7a42a37f83d29b7058b1312d02d5ab4efd24ea6a7e77d4bf`;
- después: `cbfbea540d21a7e3407c42efb985b878630404bb27f97b5b44b3ed9eb695b95b`.

## Validación funcional y de entrega

| Control | Resultado |
| --- | --- |
| Formato, límites de arquitectura, privacidad, logging, OCR, offline-first y esquema Room | CUMPLE |
| Lint `localDebug` y `cloudDebug` | CUMPLE |
| JVM `test` | 6.613/6.613, 0 fallos, 0 errores, 0 omitidas; 1.063 corresponden a `localDebug` |
| Cobertura de dominio crítico | 10.888/12.234 líneas, **89,00 %**; gate mínimo 80 % |
| Android `connectedLocalDebugAndroidTest` | **474/474** en Pixel Tablet AVD, 0 omitidas |
| Macrobenchmark focal | 1/1; cinco iteraciones y cinco trazas Perfetto |
| AAB local/cloud | Generados y aprobados por los gates de optimización |
| Instalación final | APK `localDebug` instalado y abierto en `Pixel_Tablet` (`emulator-5554`) |

Comandos principales:

```bash
./gradlew --offline --no-daemon --max-workers=1 \
  spotlessCheck ciStaticAnalysis :app:testLocalDebugUnitTest \
  :app:lintLocalDebug :app:lintCloudDebug :app:koverVerify
./gradlew --offline --no-daemon --max-workers=1 test
ANDROID_SERIAL=emulator-5554 ./gradlew --offline --no-daemon --max-workers=1 \
  :app:connectedLocalDebugAndroidTest
```

## Pendientes honestos

- Repetir todos los CUJ con 30 iteraciones en el Pixel 6a físico, con estado térmico, batería y
  almacenamiento predeclarados.
- Generar un Startup Profile desde CUJ reales antes de afirmar ordenamiento DEX de arranque.
- Completar TalkBack/fuente 200 %, cámara real y lector HID físico. Ninguno se acredita con el AVD.
- La reconciliación remota todavía materializa el historial completo en memoria; paginarlo exige
  cambiar el contrato de reconciliación y queda como evolución posterior.
