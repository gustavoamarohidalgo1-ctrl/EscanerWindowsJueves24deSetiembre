# Evidencia — Inicio adaptativo y guiado

Fecha: **28 de agosto de 2026** (`America/Lima`). Esta ronda reemplaza la portada de
tarjetas grandes por un espacio de trabajo para tablet que cambia según el momento real del
negocio. La validación instrumental se realizó en una **Pixel Tablet AVD, Android 15/API 35**,
con modo oscuro activo.

## Resultado funcional y visual

- El encabezado es compacto: muestra la vista, el negocio, la fecha y el estado de respaldo
  sin ocupar el primer pantallazo con un bloque promocional.
- En tablet, Inicio usa una composición aproximada de **dos tercios para operar** y **un tercio
  para contexto**. En ventanas estrechas o con fuente grande se apila en una sola columna.
- Un negocio nuevo recibe una pantalla propia: un siguiente paso recomendado, una ruta táctil
  de tres pasos y accesos directos. No se muestran métricas en cero, actividad vacía, alertas
  vacías ni un segundo estado vacío de borradores.
- Un negocio activo recibe acciones rápidas, prioridades reales, salud del inventario, una
  franja compacta de métricas exactas y actividad reciente unificada.
- La acción destacada es contextual: **Registrar una compra** mientras no haya existencias y
  **Vender** cuando ya exista stock disponible.
- Se eliminaron el hero, la repetición de navegación y la apariencia de “tarjetas por todo”.
  Las superficies grafito organizan el contenido y el azul queda reservado para selección y
  acción primaria; ámbar, rojo y teal conservan significado semántico.
- Los controles mantienen rol, nombre accesible, objetivos táctiles mínimos y compatibilidad
  comprobada con fuente al 200 % y ventanas compactas.

## Evidencia visual

- [`2026-08-28-home-guided-dark.png`](2026-08-28-home-guided-dark.png): negocio nuevo, modo
  oscuro y composición final guiada.
- [`2026-08-28-home-active-dark.png`](2026-08-28-home-active-dark.png): rama activa con un
  producto sin existencias; demuestra el cambio contextual de prioridad hacia compras.

Las capturas se tomaron con una compilación local temporal sin bloqueo de pantalla. Después se
restauró `FLAG_SECURE`, se ensambló de nuevo y se reinstaló exclusivamente la APK protegida.

## Verificaciones finales

| Verificación | Resultado |
| --- | --- |
| JVM `localDebug` | **1.219/1.219**, 0 fallos, 0 errores, 0 omitidas |
| JVM `cloudDebug` | **1.319/1.319**, 0 fallos, 0 errores, 0 omitidas |
| Suite Android completa en Pixel Tablet | **598/598**, 0 fallos, 0 errores, 0 omitidas; `BUILD SUCCESSFUL` |
| Reintento dirigido de onboarding, navegación y recreación | **7/7**, 0 fallos |
| Spotless y análisis estático | `BUILD SUCCESSFUL` |
| Android Lint local/nube | **0 errores**; 83 advertencias informativas por variante |
| Kover `localDebug` | **11.558/13.028 líneas = 88,72 %**, sobre el mínimo exigido |
| Gates de seguridad, dominio, OCR, logs, App Check, offline, Room y UI | `BUILD SUCCESSFUL` |
| APK final protegida | SHA-256 `39555009837c626fdaaede4fd6bcd4b1da968bcaa5370aaa0dd441cc3ec129a7` |

## Regresión detectada y cerrada

La primera ejecución Android completa encontró una colisión semántica: el rótulo de Inicio
**“Tu negocio”** también aparecía en onboarding, por lo que una aserción no podía distinguir las
dos pantallas. Se cambió por **“Vista general”**, se ejecutaron los siete escenarios afectados
y después se repitieron las **598** pruebas Android en una sola ejecución completamente verde.

## Estado final de la tablet

- `MainActivity` está reanudada y muestra Inicio para **Negocio Demo**.
- Android reporta `mNightMode=2 (yes)`.
- `dumpsys window` confirma la bandera `SECURE` en la ventana instalada.
- La fuente conserva `window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)` y el gate de
  seguridad pasó durante el ensamblado final.

## Alcance

No es responsable prometer ausencia absoluta de defectos. Esta ronda termina sin fallos
conocidos en compilación, pruebas, análisis, seguridad o inspección visual. Para riesgo físico
siguen siendo valiosas pruebas breves con cámara real, lector HID, TalkBack y datos reales del
negocio.
