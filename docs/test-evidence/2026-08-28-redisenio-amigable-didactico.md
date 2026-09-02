# Evidencia — rediseño amigable, didáctico y profesional

Fecha: **28 de agosto de 2026** (`America/Lima`). Ronda centrada en convertir la
interfaz de FacturaStock en un producto más fácil de aprender, más claro para operar
diariamente y visualmente consistente en teléfono y tablet. La validación instrumental
se realizó en un **Pixel Tablet AVD con Android 15/API 35**.

## Resultado de diseño

- Inicio convertido en un dashboard por tareas: resumen del día, elección explícita de
  compra o venta, explicación breve y tres pasos visibles antes de confirmar.
- Atajos de negocio renombrados y acompañados por descripciones concretas para productos,
  compras e inventario.
- Borradores reformulados como continuidad de trabajo; cada estado indica el siguiente
  paso. Cuando no hay pendientes, el estado vacío enseña cómo registrar la próxima factura.
- Onboarding organizado en tres pasos con progreso, estados `Listo/Pendiente`, contexto
  para cada dato y un resumen final antes de crear el negocio y su primer almacén.
- Navegación tablet con marca compacta, rail de 112 dp, títulos contextuales e indicador
  seleccionado de alto contraste. Los cinco iconos usan ahora una familia de contorno
  coherente y ya no se repite el icono de camión para conceptos distintos.
- Chrome compacto para ventanas de poca altura: con fuente al 200 % conserva como mínimo
  un objetivo táctil completo de 48 dp dentro del área útil.
- Paletas clara y oscura refinadas con fondos naturales, superficies cálidas, acentos menos
  saturados y roles semánticos de éxito, advertencia e información con contraste medido.
- Tipografía, formas, espaciado, botones, mensajes de estado, errores recuperables y estados
  vacíos comparten ahora una jerarquía visual consistente.

## Verificaciones ejecutadas

| Verificación | Resultado |
| --- | --- |
| Compilación Kotlin de aplicación y pruebas Android | **BUILD SUCCESSFUL** |
| Pruebas JVM de todas las variantes | **7.564/7.564**, 0 fallos, 0 errores, 0 omitidas |
| Suite Android completa en Pixel Tablet | **596/596**, 0 fallos, 0 omitidas; **BUILD SUCCESSFUL** en 6 min 56 s |
| Spotless, análisis estático, Lint local y Kover | **BUILD SUCCESSFUL** en 4 min 25 s |
| Gate de contraste del tema y del indicador de navegación | **BUILD SUCCESSFUL** |
| Instalación `localDebug` segura | APK instalado correctamente en `emulator-5554` |
| Apertura final | `com.facturastock.app/.MainActivity` activa en modo oscuro y en Inicio |

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

## Regresiones detectadas y cerradas

- Al unificar el CTA del estado vacío con `Registrar una compra`, una prueba encontraba dos
  textos iguales. Se añadió un selector estable del estado vacío y el escenario quedó verde.
- La suite completa reveló otra ambigüedad legítima: `Inventario` aparece en la navegación y
  como atajo del dashboard. La prueba se corrigió para elegir la pestaña mediante el rol
  accesible `Tab`; el reintento aislado pasó y luego se repitió toda la matriz hasta obtener
  **596/596** en una sola ejecución.
- La revisión crítica detectó poco contraste entre el fondo del rail y su selección. Se
  sustituyó por contenedor primario y borde de 2 dp; una prueba nueva exige al menos **3:1**
  entre el indicador y la superficie tanto en claro como en oscuro.

## Seguridad y estado del emulador

La inspección visual utilizó de forma controlada una compilación local temporal sin bloqueo
de capturas. Después se restauró `FLAG_SECURE`, se ejecutaron nuevamente los gates y se
instaló una compilación limpia. Android reporta la ventana final con el flag `SECURE`,
`mNightMode=2 (yes)` y `MainActivity` como actividad reanudada.

Las pruebas limpian los datos de la aplicación. Por ello se completó otra vez el onboarding
con **Negocio Demo** y **Almacen Principal**, dejando el dashboard de Inicio abierto en la
Pixel Tablet.

## Alcance de la garantía

No es técnicamente responsable prometer ausencia absoluta de defectos. Esta ronda deja sin
fallos conocidos todas las suites y puertas ejecutadas, además de revisión visual en claro y
oscuro. Como validación externa siguen siendo recomendables una sesión con TalkBack real,
cámara física, lector HID y un piloto breve con datos representativos del negocio.
