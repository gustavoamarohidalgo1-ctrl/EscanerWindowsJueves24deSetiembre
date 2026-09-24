# Piloto cerrado — FacturaStock 1.0

Protocolo y registro honesto del piloto. Fecha de corte: **24 de agosto de 2026**
(`America/Lima`). Solo se permiten datos sintéticos; una factura real tachada sigue siendo real y no
se usa.

> **Nota del 24 de septiembre de 2026.** Desde esta corrida se retiraron la variante cloud y la
> pista interna de Play (preparación de instalación y pasos 1 y 16–18), y Ajustes ya no ofrece el
> modo demostración que usan los pasos 2, 3 y 21. Antes de repetir el piloto hay que adaptar el
> guion a la variante `local`, instalada por `adb` en un dispositivo de pruebas —nunca en la tablet
> del negocio, que contiene datos reales—. El registro de resultados de abajo es histórico.

## Reglas y preparación

- Usar el negocio `[DEMO] Bodega de demostración`, proveedor `[DEMO] Distribuidora Lima`, RUC
  sintético `20111111112` y documento `F035-00000038`.
- Anteponer `[DEMO]` a cada entidad creada.
- Registrar el texto literal de errores y advertencias, sin correos, RUC reales, imágenes fiscales,
  rutas privadas, tokens ni claves.
- No borrar datos, vaciar la outbox ni alterar el esquema para hacer pasar un paso.
- Usar un teléfono Android físico con cámara; para aceptación de rendimiento, el dispositivo de
  referencia es Pixel 6a, Android 15/API 35, según
  [`ANDROID_PERFORMANCE_ACCESSIBILITY.md`](ANDROID_PERFORMANCE_ACCESSIBILITY.md).
- Instalar la variante `local` por `adb` en un teléfono de pruebas, nunca en la tablet del negocio.
  El AVD sirve para diagnóstico, pero no cierra este piloto.

## Guion de 21 pasos

| # | Acción | Resultado esperado |
| --- | --- | --- |
| 1 | Instalar el APK `local` en el teléfono de pruebas y abrir | Onboarding visible; versión y firma corresponden al APK |
| 2 | Activar modo demostración (sin acceso en la app desde 2026-09-24) | Aviso permanente de modo demo |
| 3 | Abrir la factura demo de 38 líneas (requiere el modo demostración) | Captura/importación y OCR local inician sin datos reales |
| 4 | Probar una toma borrosa y otra limpia | Advierte calidad; permite repetir o continuar explícitamente |
| 5 | Revisar cabecera | Proveedor demo, `F035-00000038`, 14/08/2026 y PEN |
| 6 | Revisar las 38 líneas | Suma S/97,80, total S/97,83 y diferencia exacta +S/0,03 |
| 7 | Resolver `PROV-AMB-037` | El matching ambiguo exige elección manual |
| 8 | Crear `PROV-NEW-038` | Crea `[DEMO] Quinua tricolor 500 g` con conversión y precio de venta explícitos |
| 9 | Aceptar redondeo | Exige motivo válido; no continúa vacío |
| 10 | Confirmar con doble toque rápido | Registra una sola compra y 38 movimientos |
| 11 | Abrir historial e inventario | Compra inmutable, stock correcto y ganancia estimada coherente con costo/precio demo |
| 12 | Importar de nuevo el mismo documento | Duplicado exacto bloqueado y enlace al existente |
| 13 | Probar correlativo con ceros distintos | Duplicado probable advierte sin fingir exactitud |
| 14 | Autorizar excepción | Requiere OWNER/ADMIN y motivo válido |
| 15 | Activar modo avión y registrar otra demo | Operación local completa y outbox pendiente |
| 16 | Reconectar (no aplica: sin transporte remoto desde 2026-09-24) | Drena una vez y conserva idempotencia |
| 17 | Provocar conflicto controlado (no aplica: sin transporte remoto) | Muestra `CONFLICT` y permite resolver sin borrar el libro local |
| 18 | Vencer sesión durante drenado (no aplica: sin cuenta) | Refresca/reintenta una vez; si no recupera, conserva cola y pide reingreso |
| 19 | Anular compra | Exige autorización, impacto, motivo y confirmación; historial sigue visible |
| 20 | Activar TalkBack, fuente 200 % y horizontal | Flujo principal conserva foco, anuncios, controles y contenido accesibles |
| 21 | Salir del modo demostración (sin acceso en la app desde 2026-09-24) | Elimina solo los datos demo y deja intacta cualquier información ajena al piloto |

## Registro actual

| Bloque | Estado | Evidencia |
| --- | --- | --- |
| JVM/offline | CUMPLE | 6.571 ejecuciones, 0 fallos; corpus 7/7 y 144/144 comprobaciones |
| Android `local` en `Pixel_Tablet(AVD) - 15` | CUMPLE | 473/473 pruebas, incluidos los diez escenarios de aceptación, ventas/precio/ganancias y 100 líneas con fuente 200 % |
| Runtime `cloud` en AVD | CUMPLE | 1/1 smoke Firebase |
| Firebase Emulator Suite | CUMPLE | 111/111 con Auth, Firestore, Functions y Storage |
| Macrobenchmark diagnóstico en AVD | NO CUMPLE | 4/4 pruebas terminaron; parser ya cumple, pero captura virtual y lista de 100 líneas excedieron presupuestos |
| Guion humano de 21 pasos | NO CUMPLE | No se ejecutó con una persona |
| Teléfono físico/cámara real | NO CUMPLE | No se usó un teléfono físico |
| TalkBack y fuente 200 % en teléfono | NO CUMPLE | Solo hay cobertura automatizada de semántica y reflujo |
| Instalación desde pista interna | NO APLICA | La publicación en Play se retiró el 24 de septiembre de 2026 |

Los resultados automatizados detallados están en
[`test-evidence/2026-08-24-fase-g-auditoria.md`](test-evidence/2026-08-24-fase-g-auditoria.md) y
[`test-evidence/2026-08-24-ventas-ganancias.md`](test-evidence/2026-08-24-ventas-ganancias.md).
No se capturó ni procesó documentación fiscal real durante esa auditoría.

## Hoja de ejecución humana

Al realizar el piloto, crear un archivo fechado en `docs/test-evidence/` con:

| Campo | Valor requerido |
| --- | --- |
| Fecha/zona | ISO 8601 y `America/Lima` |
| Probador | Alias, nunca nombre/correo real |
| Artefacto | `versionName`, `versionCode` y SHA-256 del APK |
| Dispositivo | Modelo, API, RAM y parche; sin número de serie |
| Condiciones | Batería, almacenamiento, estado térmico y red |
| Pasos 1–21 | `CUMPLE` o `NO CUMPLE`, texto literal sanitizado y evidencia sintética |
| Incidentes | ID interno, sin contenido fiscal |

El piloto se aborta si una confirmación duplica movimientos, los importes cambian, el stock se
mueve sin asiento, un duplicado exacto evade autorización, una anulación borra historia o aparece
cualquier dato real.

Hasta que los 21 pasos estén registrados en un teléfono físico, FacturaStock está **verificada por
automatización**, no **pilotada con personas**.
