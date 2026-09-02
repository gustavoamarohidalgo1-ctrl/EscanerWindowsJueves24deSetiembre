# Evidencia de validación — ventas, facturas e informes

Fecha: 2026-08-28  
Dispositivo: Pixel Tablet, Android 15 (API 35), 2560 × 1600, 320 dpi  
Zona funcional de los informes: `America/Lima`

## Alcance entregado

- Navegación comercial principal: Inicio, Vender, Facturas, Inventario y Reportes.
- Facturas es un centro dedicado al flujo Captura → Revisa y vincula → Confirma.
- Revisar una factura no cambia el inventario. La compra, sus líneas, los movimientos de
  inventario y el costo promedio se confirman de forma atómica al finalizar.
- Cada venta confirmada se persiste inmediatamente, descuenta existencias y conserva el costo
  histórico usado en ese momento.
- Reportes por día, semana y mes calendario, con cambio automático al siguiente límite temporal.
- Desglose por venta de total cobrado, ingreso sin impuestos, costo histórico y ganancia bruta.
- Los costos ausentes o las monedas incompatibles se muestran como datos no disponibles; nunca
  se convierten silenciosamente en costo o ganancia cero.

Los reportes no dependen de una copia frágil ejecutada una vez cada 24 horas. Se calculan desde el
libro de ventas persistido usando rangos calendario semiabiertos `[inicio, fin)`, por lo que una
venta aparece de inmediato y queda agrupada correctamente en el día, semana y mes de Lima.

## Pruebas ejecutadas

| Validación | Resultado |
|---|---:|
| Pruebas JVM, variante local | 1,226 / 1,226 |
| Pruebas JVM, variante nube | 1,326 / 1,326 |
| Pruebas instrumentadas en Pixel Tablet | 605 / 605 |
| Fallos, errores u omitidas en esas suites | 0 |
| Cobertura de líneas JVM | 88.79 % |
| Cobertura de instrucciones JVM | 87.20 % |
| Cobertura de ramas JVM | 67.02 % |
| Lint fatal/error | 0 / 0 |
| Compilación R8 local release | Correcta |
| Compilación R8 cloud release | Correcta |

Comandos principales:

```text
./gradlew :app:testLocalDebugUnitTest :app:testCloudDebugUnitTest :app:lintLocalDebug
./gradlew :app:connectedLocalDebugAndroidTest
./gradlew :app:koverXmlReportLocalDebug :app:assembleLocalRelease
./gradlew :app:assembleCloudRelease
```

Lint conserva 108 advertencias no bloqueantes del proyecto (principalmente recursos sin uso,
sugerencias de plurales y versiones disponibles). No hay advertencias en las nuevas rutas de
Facturas o Reportes ni errores de Lint.

## Revisión funcional en la tablet

- Se instaló el APK debug sin desactivar `FLAG_SECURE`.
- Se creó una configuración sintética local: `Mi Negocio` / `Almacen Principal`.
- Se comprobó el acceso independiente a Vender, Facturas e Informes.
- Facturas mostró el recorrido didáctico y la garantía de inventario protegido.
- Reportes mostró correctamente:
  - Hoy: `28 ago. 2026`.
  - Semana: `24 ago. 2026 – 30 ago. 2026`.
  - Mes: `1 ago. 2026 – 31 ago. 2026`.
- La aplicación quedó abierta en Reportes → Hoy.
- El proceso permaneció activo y el log de la aplicación no mostró excepciones fatales, ANR ni
  errores de memoria durante la revisión.

La ganancia expuesta es ganancia bruta: ingreso sin impuestos menos costo histórico. No pretende
ser utilidad neta contable y, por diseño, no descuenta alquiler, nómina, comisiones ni otros gastos
operativos.

## Artefactos

| APK | Tamaño | SHA-256 |
|---|---:|---|
| `app-local-debug.apk` | 68,786,953 bytes | `f237fa2d75c6c899c147f866b56c59b8eca7a9507eb476dbaae87548b843247a` |
| `app-local-release-unsigned.apk` | 47,691,834 bytes | `c8fa2458d5665ba8f4cd513c7fb56cd3592be3ea107f244eb29237ac872b543a` |
| `app-cloud-release-unsigned.apk` | 49,181,156 bytes | `6a9eadd868016809a22ef6a305fa4b387b88088d51b154e594c51a6b46a5c602` |

Los APK release están optimizados pero sin firma de distribución.
