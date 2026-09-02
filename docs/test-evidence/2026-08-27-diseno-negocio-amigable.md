# Evidencia — diseño amigable y confiabilidad para negocio

Fecha: **27 de agosto de 2026** (`America/Lima`). Ronda enfocada en claridad comercial,
adaptación a tablet, accesibilidad, manejo explícito de errores y prevención de acciones
inseguras. La validación instrumental se realizó en un **Pixel Tablet AVD con Android
15/API 35**.

## Resultado funcional

- Inicio reorganizado con bienvenida, acciones principales para escanear factura y crear
  una venta, accesos de gestión y un estado de borradores más claro.
- Navegación adaptable: rail lateral en tablet y barra inferior cuando la altura o el
  tamaño de fuente requieren conservar espacio útil.
- Modo oscuro natural basado en el tema del sistema, con superficies, bordes, estados y
  jerarquía visual coherentes.
- Onboarding agrupado por negocio, impuestos, región e inventario; campos obligatorios,
  límites, explicaciones, navegación IME y estado de guardado visibles.
- Catálogos, compras e inventario distinguen carga, lista vacía, filtros sin resultados,
  datos en caché y fallos recuperables. Los filtros se pueden limpiar desde el propio
  estado vacío.
- Formularios y diálogos limitan su ancho en tablet, respetan teclado y áreas seguras y
  evitan que acciones críticas desaparezcan en ventanas compactas o con fuente al 200 %.
- Ventas separa fallos de catálogo, carrito, búsqueda e historial. Un error de búsqueda
  no invalida un carrito sano, mientras que datos operativos inseguros sí bloquean el
  cobro hasta recuperarse.
- Vinculación de productos ignora respuestas obsoletas, limpia candidatos anteriores y
  evita confirmar una selección que ya no corresponde a la búsqueda activa.
- Preview mantiene la imagen visible, incluso con errores y controles en paisaje
  compacto; los fallos de edición ya no se silencian y procesar durante un recorte queda
  bloqueado.
- Revisión de líneas mejora lectura con TalkBack, contexto de acciones, diferencia de
  importes, grupos seleccionables y distribución compacta sin desbordes.

## Verificaciones ejecutadas

| Verificación | Resultado |
| --- | --- |
| Compilación Kotlin de aplicación y pruebas Android | **BUILD SUCCESSFUL** |
| Pruebas JVM de todas las variantes | **7.558/7.558**, 0 fallos, 0 errores, 0 omitidas |
| Batería UI focal de los flujos modificados | **55/55**, 0 fallos, 0 omitidas |
| Suite Android completa en Pixel Tablet | **588/588**, 0 fallos, 0 omitidas; **BUILD SUCCESSFUL** en 7 min 12 s |
| Spotless aplicable, análisis estático, Lint local/cloud y Kover | **BUILD SUCCESSFUL** en 8 min 39 s |
| Gate productivo `cloudRelease` | **BUILD SUCCESSFUL** en 2 min 2 s |
| Instalación `localDebug` | APK instalado correctamente en `emulator-5554` |
| Apertura final | `com.facturastock.app/.MainActivity` activa en modo oscuro |

Cobertura `localDebug` verificada por Kover:

| Métrica | Cobertura |
| --- | ---: |
| Líneas | **88,54 %** |
| Instrucciones | **87,19 %** |
| Métodos | **82,44 %** |
| Clases | **83,08 %** |
| Ramas | **67,14 %** |

También pasaron los gates de límites de dominio, OCR local, seguridad móvil, ausencia de
logs sensibles, App Check, operación offline-first, esquema Room y convenciones de UI.

## Regresión encontrada durante la validación

La primera ejecución completa encontró una única expectativa antigua en
`InventoryScreensTest`: la lista compacta ya muestra totales y cantidad de almacenes,
mientras que el desglose por almacén vive en el detalle del producto. Se actualizó la
prueba al contrato actual, se validó aisladamente **1/1** y luego se repitió toda la
matriz hasta obtener **588/588** en verde.

## Estado del emulador

La compilación validada quedó instalada y abierta en la Pixel Tablet. El sistema reporta
`mNightMode=2 (yes)` y `MainActivity` como actividad reanudada. Como las pruebas limpian
los datos de aplicación, se completó el onboarding con los datos neutros **Negocio Demo**
y **Almacen Principal** para dejar visible el inicio comercial.

## Alcance de la garantía

No es técnicamente responsable prometer ausencia absoluta de defectos. Esta ronda sí
deja sin fallos conocidos las suites y gates ejecutados, y una revisión final no encontró
incidencias P0, P1 ni P2 en el alcance de interfaz revisado. Siguen siendo controles
externos recomendables una sesión manual con TalkBack real, cámara física, lector HID y
un piloto con datos representativos del negocio.
