# Corrección al eliminar productos — 12 de septiembre de 2026

El aviso observado en la tablet para **Coca 1L** procedía de la protección del borrado definitivo: un producto referenciado por movimientos o ventas no puede eliminarse físicamente. Los 368 productos de la copia tenían movimientos; por eso ninguno podía usar esa acción. No se detectó corrupción de la base de datos.

El usuario confirmó que desea quitar los productos de las listas conservando su historial. La versión **1.0.5 (6), edición local**, añade este recorrido:

1. Al rechazar el borrado por historial o existencias, ofrece **Quitar del catálogo**.
2. Una confirmación explica que las existencias, ventas, deudas y movimientos se conservan.
3. La operación archiva el mismo producto, comprobando negocio y versión. El inventario habitual y el catálogo de productos muestran activos; las búsquedas de nuevas ventas ya excluyen archivados.
4. **Ver retirados** permite consultar los datos y **Restaurar producto**, con otra confirmación explícita.

El borrado físico permanece limitado a productos sin uso y sin existencias. No se cambian las restricciones de Room, las claves foráneas ni las reglas de protección del historial. El lector no restaura productos automáticamente. Los datos completos siguen disponibles en las proyecciones históricas y exportaciones.

## Verificación

- JVM: 1.647 pruebas locales y 1.825 cloud aprobadas.
- Android: 46 pruebas de repositorio, pantallas y navegación aprobadas, incluyendo retirar/restaurar después de recrear la actividad. El selector de una prueba se acotó al diálogo porque el botón de la lista comparte el texto Restaurar producto; no requirió cambios adicionales en la aplicación.
- Lint local/cloud: sin errores; análisis estático y formato aprobados. Una ejecución simultánea de Lint falló internamente en FIR; al repetir con un trabajador finalizó correctamente.
- La actualización de una copia real pasó de esquema 28 a 29 conservando exactamente las 34 tablas de datos existentes. La migración ya incluida en el proyecto agregó `sale_voids` vacía. Integridad SQLite correcta y ninguna violación de clave foránea.
- En el emulador, Coca 1L pasó de ACTIVE a ARCHIVED y de vuelta a ACTIVE mediante la interfaz. Los otros 367 productos quedaron idénticos. Se conservaron exactamente saldos, movimientos, ventas y deudas; solamente cambiaron el estado/versión/fecha del producto y se añadieron las operaciones correspondientes a la cola local.
- Se verificó la coincidencia de edición local y certificado del APK original con el candidato.

Evidencias locales: `build/reports/product-removal-2026-09-12/`. Las pruebas sobre la copia se ejecutaron exclusivamente en `emulator-5582`; ninguna prueba instrumentada se ejecutó en la tablet física. El respaldo original de `Backup8` se mantuvo intacto.

## Instalación verificada

Actualizada la tablet a versión 1.0.5 (6), conservando la edición local y la firma. Se guardaron capturas completas antes y después en `/Users/gustavo/Desktop/Backup8/Actualizacion_1.0.5/`. La comparación posterior confirmó las 34 tablas existentes idénticas y los 2.868 registros conservados, sin errores SQLite ni referencias rotas. La instalación no retiró productos automáticamente.
