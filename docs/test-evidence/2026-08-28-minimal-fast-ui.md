# Interfaz minimalista y optimización final — 2026-08-28

## Resultado

La aplicación quedó orientada a las tareas diarias del negocio: vender, registrar facturas, consultar inventario y revisar ganancias. Se retiraron tutoriales persistentes, encabezados repetidos, actividad secundaria, estados vacíos duplicados y mensajes de confirmación que la propia interfaz ya hacía evidentes.

## Cambios funcionales y visuales

- **Inicio:** estado esencial del negocio, dos acciones principales (`Vender` y `Registrar factura`) y dos accesos secundarios. No carga actividad ni ventas recientes.
- **Ventas:** entrada por escáner o búsqueda, carrito vacío de una sola línea, total y cobro solo cuando existen productos. El autoguardado de cantidad/precio ya no bloquea la edición; el cobro espera de forma segura a que termine.
- **Facturas:** apartado directo con un único CTA y una nota breve sobre la actualización del inventario. Se eliminó el tutorial permanente de tres pasos.
- **Inventario:** abre directamente en selector y búsqueda. El diagnóstico técnico se oculta cuando todo está correcto y solo aparece ante ejecución, error, inconsistencia o alertas operativas relevantes.
- **Reportes:** selector Hoy/Semana/Mes, ganancia bruta como dato principal, métricas compactas y detalle de ventas. El estado vacío ya no repite el conteo cero.
- **Primer inicio:** formulario plano de negocio, impuestos/costo y almacén; sin hero, progreso, tarjetas numeradas, valores regionales fijos ni resumen duplicado.

## Optimización

- Eliminada la consulta de actividad reciente y subconsultas de sincronización que Inicio ya no muestra.
- Eliminada la carga del historial de ventas desde la pantalla de venta.
- La búsqueda manual vacía no materializa el catálogo completo.
- Se redujeron ordenamientos y asignaciones del catálogo; las ubicaciones se ordenan solo por producto cuando se necesitan.
- La agregación de reportes corre fuera de Main y se eliminó un ordenamiento duplicado entre Room y el caso de uso.
- El guardado debounced de líneas conserva mutex + CAS sin congelar los campos.
- Eliminados recursos y ramas obsoletas detectadas durante la revisión.

## Evidencia ejecutada

- JVM local: **1,230/1,230**, 0 fallos.
- JVM cloud: **1,330/1,330**, 0 fallos.
- Android focalizado sobre las pantallas modificadas: **66/66**, 0 fallos.
- Android completo en Pixel Tablet API 35: **607/607**, 0 fallos, 0 omitidas.
- Compilación Kotlin principal, unit test y AndroidTest: local y cloud correctas.
- Lint local/cloud: **0 errores y 0 recursos sin uso**. Quedan 60 avisos no bloqueantes por variante (principalmente actualizaciones de dependencias, candidatos a plural y convenciones de API/Modifier).
- APK release local/cloud: R8, reducción de recursos, manifiestos y lint vital validados.

## Artefactos

Los APK finales se generan en:

- `app/build/outputs/apk/local/release/app-local-release-unsigned.apk`
  - 47,574,258 bytes
  - SHA-256 `3641f9d3ed99df6283358345fed495ea773cac23deaafc5be48bd76ca13d9a66`
- `app/build/outputs/apk/cloud/release/app-cloud-release-unsigned.apk`
  - 49,047,200 bytes
  - SHA-256 `0a5bed93537a591c580acd5536aa1595141eff4973f8b5c0e4e22d39307937c0`
