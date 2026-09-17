# Inventario sin Archivados y con eliminación permanente

Los productos agotados permanecen visibles con cantidad **0**. La lista incluye también productos que todavía no tienen posiciones de inventario, sin crear saldos, ubicaciones ni costos ficticios. Se retiraron los filtros Activos/Archivados y las acciones de archivar/restaurar productos tanto de Inventario como del catálogo de productos. Los catálogos de otras entidades conservan su comportamiento.

**Eliminar** abre una confirmación de eliminación permanente. La revisión captura negocio, identidad y versión; cancelar no escribe. La confirmación llama a un borrado físico del producto, no a un cambio de estado. Un cambio concurrente exige cerrar y revisar nuevamente. Una confirmación pendiente no se repite después de reiniciar el proceso.

La eliminación se limita a productos sin existencias ni referencias. Ventas, compras, movimientos, alias, borradores y snapshots restaurables vinculados impiden el borrado para conservar su integridad e historial. También se bloquea una identidad que esté vinculada o pueda haberse sincronizado remotamente, porque esta operación no introduce un protocolo remoto de eliminación.

La transacción comprueba cada cantidad decimal exactamente, sin sumar posiciones opuestas. Sólo elimina saldos cero sin historial y operaciones de producto locales nunca enviadas. Comprueba después la desaparición del producto y sus operaciones pendientes; un fallo revierte toda la limpieza. Se mantiene la excepción estrecha de documentos locales cancelados sin intentos ni identidad remota. Ningún movimiento histórico se elimina ni reescribe.

El esquema sigue en versión 28, sin migraciones ni cambios de triggers. Las implementaciones de ventas y ajuste de existencias permanecen idénticas a la copia inicial. Los 15 archivos centrales del escáner no cambiaron; los cambios de integración de Inventario suspenden la entrada mientras se revisa/confirma un borrado y la liberan al cerrar.

## Validación y evidencias

Las 1.549 pruebas unitarias aprobaron. Compilación de aplicación y pruebas, comprobaciones estáticas y Android Lint aprobaron; Lint conserva 44 advertencias y cero errores.

Las pruebas de Android cubren borrado real, reutilización de códigos, saldos cero, protección del historial y de snapshots, aislamiento por negocio, concurrencia, recuperación tras fallos, edición, registro y ventas con escáner. La primera ejecución incluyó 46 casos: 45 aprobaron y una prueba de recorrido esperaba el rechazo por historial sin haber pulsado Confirmar. Se añadió esa confirmación al test, manteniendo sus comprobaciones de estado e historial; el registro de la repetición del recorrido se conserva junto al original.

Registros y comparación contra la copia inicial: `build/reports/inventario-sin-archivados/2026-09-05/`. Las pruebas de escritura se ejecutaron exclusivamente en un emulador aislado; no se accionó físicamente el lector ni se borraron productos reales de la tablet.

La repetición final aprobó los 5 recorridos de Inventario, incluido el caso corregido. Quedan validados los 46 casos distintos seleccionados; el emulador temporal se retiró al concluir.

## Aplicación en la tablet

Instalada por Wi-Fi el **05/09/2026 a las 12:42:48 (Lima)** usando reemplazo del mismo paquete firmado, sin desinstalar ni limpiar datos. La APK instalada coincide con la probada: `06b3fab829f9ba39570ef39b170a79b524ae84310d544cb7871c30ed56dd69a0`.

Se comprobó la ausencia de filtros Archivados y la confirmación de borrado de Ace 700 G, que se canceló sin confirmar. El editor conserva cantidad `12`, compra `7.5` y venta `8.50`; también se canceló sin guardar.

La comparación entre **12:40:58** y **12:45:08** confirmó las **36 tablas y configuración idénticas**, esquema 28, integridad correcta y cero infracciones de claves foráneas. Se conservan 121 productos, 121 saldos, 4 ventas, 6 líneas y 126 movimientos, incluido el borrador de venta existente.

Respaldo local con APK anterior y nueva, datos privados, base SQLite consolidada, verificaciones y sumas SHA-256: `/Users/gustavo/Desktop/Backup/FacturaStock_2026-09-05_12-07-16_sin_archivados`.
