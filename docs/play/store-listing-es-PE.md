# Ficha principal en español (Perú)

## Configuración propuesta

| Campo | Valor |
| --- | --- |
| Idioma predeterminado | Español (Latinoamérica), con texto revisado para `es-PE` |
| Nombre de la app | `FacturaStock` |
| Categoría | Aplicaciones → Empresa |
| Etiquetas sugeridas | Gestión de inventario; Pequeña empresa; Productividad, solo si Play Console las ofrece |
| Contiene anuncios | No |
| Sitio web | `{{URL_SITIO_PUBLICO_HTTPS}}` |
| Correo de soporte | `{{CORREO_SOPORTE}}` |
| Teléfono | Omitir, salvo que exista un canal atendido |
| Política de privacidad | `{{URL_POLITICA_PRIVACIDAD_HTTPS}}` |
| Eliminación de cuenta | `{{URL_ELIMINACION_CUENTA_HTTPS}}` |

No seleccionar una etiqueta que no aparezca en el catálogo actual de Play Console. El nombre
tiene 12 caracteres, por debajo del máximo de 30.

## Descripción breve

Copiar exactamente este texto, de 67 caracteres:

> Registra facturas de compra y actualiza tu inventario desde Android

## Descripción completa

El siguiente texto está por debajo del límite de 4 000 caracteres:

> FacturaStock ayuda a registrar facturas de compra y mantener actualizado el inventario desde
> un teléfono Android.
>
> Toma una foto del comprobante o importa una imagen. El reconocimiento de texto se ejecuta en
> el dispositivo y prepara una revisión paso a paso de proveedor, fecha, comprobante, líneas y
> totales. Tú confirmas los datos antes de que una compra cambie las existencias.
>
> Funciones principales:
>
> • captura e importación de imágenes de facturas de compra;
>
> • OCR local: el texto OCR nunca se envía y la imagen no sale por defecto;
>
> • revisión y corrección de cabecera, productos, cantidades, costos y redondeos;
>
> • vinculación con productos existentes o creación de productos nuevos;
>
> • historial de compras, anulaciones e inventario por almacén;
>
> • registro de ventas para descontar existencias, con precio final revisado antes de confirmar;
>
> • ventas a crédito con nombre de la persona, productos debidos, saldo e historial de abonos
> parciales o totales;
>
> • trabajo local sin conexión; en un negocio cloud compartido, confirmar una venta requiere red
> y registrar un abono también requiere conexión para evitar que dos teléfonos vendan o cobren el
> mismo saldo;
>
> • exportación de compras, catálogos, saldos, movimientos y auditoría a un archivo JSON; las
> cabeceras/líneas de venta, deudas y abonos no están incluidos en la versión 1.0;
>
> • sincronización opcional de compras, catálogo, inventario, ventas publicadas, deudas y abonos
> mediante una cuenta verificada en la versión con nube;
>
> • respaldo documental opcional y separado: solo con ambos interruptores activos puede enviarse
> una copia JPEG derivada por HTTPS y Firebase Storage aplica cifrado administrado en reposo;
>
> • colaboración por invitaciones y roles en negocios compartidos.
>
> Las fotos permanecen en el almacenamiento privado de la app salvo ese doble opt-in documental.
> Puedes elegir cuándo eliminar las imágenes, borrarlas manualmente y desactivar el respaldo o los
> diagnósticos opcionales desde Ajustes. El OCR continúa siempre en el dispositivo.
>
> Al aceptar o vincular un negocio compartido, sus miembros autorizados pueden ver el respaldo
> comercial y la lista del equipo, incluidos correo e ID, según su rol. Quien administra el
> negocio controla invitaciones, roles y bajas de acceso. Las ventas publicadas, los nombres/saldos
> de deudores y sus abonos también son visibles para esos miembros autorizados.
>
> FacturaStock usa soles peruanos y formato regional de Perú como configuración inicial. No
> consulta SUNAT, no verifica la existencia o estado de un RUC y no sustituye asesoría contable
> o tributaria. El registro de venta no emite comprobantes electrónicos, no lleva una caja general,
> no evalúa solvencia, no presta dinero y no procesa pagos electrónicos; solo anota las ventas fiadas
> y los abonos que el negocio declara haber recibido. Revisa siempre los datos antes de confirmar.

La mención de nube solo corresponde si se distribuye `cloudRelease`. Si se decide publicar
exclusivamente `localRelease`, retirar la última viñeta y cualquier captura de cuenta, pero no
cambiar Data Safety mientras siga activa en el mismo paquete alguna versión `cloud`.

## Capturas sintéticas entregadas

El paquete contiene ocho capturas reales de la app en `play/assets/es-PE/phone-screenshots/`.
Salieron de un Pixel 10a virtual, usan únicamente el modo demo y la factura canónica ficticia de
38 líneas, y fueron recortadas para quitar la barra de estado. El orden de carga y los textos
alternativos están en
[`play/assets/es-PE/captions.md`](../../play/assets/es-PE/captions.md); los PNG originales se
conservan en `play/assets/source/raw-screenshots/` para auditar procedencia.

Las ocho imágenes finales son JPEG sin transparencia de 1 080 × 1 920 px. El verificador local
confirma formato, dimensiones y cantidad:

```bash
ruby scripts/verify-play-assets.rb
```

Antes de subir, aún se requiere una revisión humana píxel por píxel: conservar visibles las
marcas `[DEMO]`, no usar una cuenta, negocio, RUC, correo, factura, foto, código de barras o
inventario de producción, y no añadir premios, precios, estrellas, logos de SUNAT o afirmaciones
de certificación. La captura `01-inicio.jpg` debe regenerarse desde el código actual porque la
pantalla vigente ya incluye **Vender** y **Deudores**.

## Recursos que deben acompañar la ficha

- Icono de Play: `play/assets/play-icon-512.png`, PNG de 32 bits con alfa, 512 × 512 px y menos
  de 1 024 KB. Es independiente del launcher icon empaquetado.
- Gráfico de funciones: `play/assets/feature-graphic-1024x500.png`, PNG sin alfa de
  1 024 × 500 px.
- Capturas: ocho JPEG de teléfono, 1 080 × 1 920 px, en el directorio indicado arriba.
- Video: opcional; no prometerlo ni dejar una URL vacía.

Los recursos existen y pasan `ruby scripts/verify-play-assets.rb`; esto valida propiedades de
archivo, no que ya se hayan cargado o aprobado en Play Console. La procedencia del fondo generado,
del icono derivado y de las capturas está en
[`play/assets/GENERATION.md`](../../play/assets/GENERATION.md).

## Acceso para revisión

La función local y el escenario demo deben poder revisarse sin cuenta. Si Google necesita
validar cuenta, membresías, respaldo o eliminación, crear credenciales de revisión dedicadas en
producción y cargarlas únicamente en **Política → Contenido de la aplicación → Acceso a la
aplicación**. No guardar esas credenciales en este repositorio, la descripción ni las capturas.

## Notas para la primera prueba interna

Texto propuesto, de menos de 500 caracteres:

> Primera versión candidata de FacturaStock para pruebas internas. Incluye captura e importación,
> OCR local, revisión de facturas de compra, confirmación de inventario, historial, anulaciones,
> exportación y respaldo cloud opcional. Todas las verificaciones deben realizarse con el modo
> demo y datos ficticios. Reportar fallos por el canal privado del equipo de pruebas.
