# Evidencia — identidad navy sin verde dominante

Fecha: **28 de agosto de 2026** (`America/Lima`). Esta ronda sustituye la
identidad verde de FacturaStock por una paleta más sobria para un producto de
negocio. La validación instrumental se realizó en un **Pixel Tablet AVD con
Android 15/API 35**.

## Resultado visual

- La marca usa ahora azul cobalto/navy sobre superficies grafito, con cobre
  reservado para la acción de venta.
- Las acciones principales de Inicio dejaron de ser bloques saturados: son
  tarjetas neutras, con el color limitado al icono y a la guía de tres pasos.
- Compras y navegación conservan el azul; ventas usan cobre; éxito usa cian e
  información usa índigo. Ningún estado depende solo del color.
- El tema claro emplea fondos gris frío y el oscuro superficies grafito, sin
  grandes superficies verdes.
- El fondo del total del comprobante de demostración cambió de verde pálido a
  azul tenue.
- Launcher, splash e icono de Play quedaron alineados con el azul de marca.
- Los bordes de tarjetas interactivas usan el rol `outline`; alcanzan al menos
  **3:1** frente a su superficie en ambos temas.

## Protecciones contra regresiones

`ThemeContrastTest` verifica ahora, además de todos los pares de contraste:

- que azul sea el canal cromático dominante de la marca;
- que el cobre sea distinguible del amarillo de advertencia;
- que indicadores de navegación y bordes de tarjetas alcancen contraste no
  textual AA;
- que las paletas clara y oscura mantengan su polaridad de luminancia.

## Verificaciones ejecutadas

| Verificación | Resultado |
| --- | --- |
| Pruebas dirigidas de tema, comprobante demo y OCR | **8/8**, 0 fallos |
| Pruebas dirigidas de Home, onboarding y accesibilidad | **36/36**, 0 fallos |
| Pruebas JVM de todas las variantes | **7.582/7.582**, 0 fallos, 0 errores, 0 omitidas |
| Suite Android completa en Pixel Tablet | **596/596**, 0 fallos, 0 omitidas; **BUILD SUCCESSFUL** en 10 min 53 s |
| Spotless, análisis estático, Lint local, Kover y JVM | **BUILD SUCCESSFUL** en 3 min 47 s |
| Gate final de tema, compilación y pruebas Android | **BUILD SUCCESSFUL** |
| Verificador estructural de Google Play | **OK**: ficha, privacidad, dimensiones, icono, gráfico y 8 capturas válidos; no evalúa coherencia cromática |

Cobertura `localDebug` verificada por Kover:

| Métrica | Cobertura |
| --- | ---: |
| Líneas | **88,54 %** |
| Instrucciones | **87,19 %** |
| Métodos | **82,44 %** |
| Clases | **83,08 %** |
| Ramas | **67,14 %** |

También pasaron los gates de límites de dominio, OCR local, seguridad móvil,
ausencia de registros sensibles, App Check, operación offline-first, esquema
Room y convenciones de UI. El único aviso de construcción es que
`android.r8.optimizedResourceShrinking=true` continúa marcado como experimental
por Android Gradle Plugin.

## Inspección visual y seguridad

Se revisaron onboarding e Inicio en modo oscuro y formato tablet. Una
compilación local temporal permitió obtener evidencia visual; inmediatamente
después se restauró `FLAG_SECURE`. La entrega final se volvió a compilar e
instalar con el gate `verifyMobileSecurityBoundaries` activo.

Las pruebas instrumentadas limpian los datos de la aplicación. Por ello, tras
la instalación final se completó nuevamente el onboarding con **Negocio Demo**
y **Almacen Principal**, y se dejó Inicio abierto en modo oscuro.

## Alcance

No es posible garantizar ausencia absoluta de defectos. En esta ronda no
quedaron fallos conocidos en las suites, gates o inspecciones ejecutadas. Como
validación externa siguen siendo recomendables una sesión con TalkBack real,
cámara física, lector HID y datos representativos del negocio.
