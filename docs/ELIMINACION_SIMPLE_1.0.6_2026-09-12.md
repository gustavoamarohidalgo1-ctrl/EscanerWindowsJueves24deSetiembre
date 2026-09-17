# Eliminación simple de productos — versión 1.0.6

El usuario pidió eliminar sin preguntas adicionales ni opciones de restauración. El flujo queda en **Eliminar → una confirmación**. Desaparecen Ver retirados, Restaurar producto y Quitar del catálogo.

La misma confirmación elimina físicamente el producto cuando no tiene uso ni existencias. Si contiene historial o saldos, lo oculta del catálogo mediante el estado interno ARCHIVED, conservando su identidad, existencias, movimientos, ventas y deudas. No se altera ninguna restricción de integridad ni se crea otra confirmación. Los detalles históricos permanecen de solo lectura.

La actualización del estado usa la misma versión revisada que la comprobación inicial, vuelve a comprobar el negocio y bloquea las pulsaciones duplicadas. Carga, búsqueda, diagnóstico y reinicio mantienen ocultos los productos eliminados. El escáner no los restaura.

Las pruebas cubren eliminación con historial o stock, cancelación sin escrituras, cambios concurrentes de producto/negocio entre las dos operaciones y persistencia de la ocultación tras recrear la pantalla. El repositorio de datos conserva su implementación y sus restricciones anteriores.

Evidencias de esta versión: `build/reports/simple-product-deletion-2026-09-12/`.

Verificación ejecutada: 1.647 pruebas JVM locales, 1.825 cloud y 18 pruebas de pantalla/navegación en emulador aprobadas. El recorrido con historial se completó con una sola confirmación, conservando exactamente saldos y movimientos, sin reaparecer al recrear la actividad o buscar.

Instalación completada y verificada en la tablet: versión 1.0.6 (7), edición local y firma original. Lint local/cloud y ciStaticAnalysis aprobados. Se comprobó igualdad exacta de las 35 tablas de datos antes/después, con 2.868 registros totales contando tablas internas y esquema 29 sin cambios. Respaldo adicional: `/Users/gustavo/Desktop/Backup8/Actualizacion_1.0.6/`.
