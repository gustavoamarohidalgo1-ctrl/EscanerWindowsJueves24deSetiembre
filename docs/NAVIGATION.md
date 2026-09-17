# Navegación Android

FacturaStock usa un único `NavHost` de Navigation Compose. El destino inicial depende de la
compuerta de primer inicio (ver abajo): `onboarding` si la configuración no se ha completado
y `sales` en caso contrario. El controlador se crea con `rememberNavController`, por lo que
Navigation guarda y restaura automáticamente el destino y sus argumentos al recrear
`MainActivity`.

## Compuerta de primer inicio

`AppGateViewModel` observa `AppConfigurationRepository` y expone `Loading | Incomplete |
Complete`. Mientras `Loading` se muestra una espera a pantalla completa; con `Incomplete` el
`NavHost` arranca en `onboarding`; con `Complete`, en `sales`. El deep link interno solo se
procesa con la compuerta en `Complete`. Atrás no sortea la compuerta: `onboarding` es la raíz
del grafo, así que la flecha superior es un no-op y Atrás del sistema cede al sistema (cierra
la app). Al completar, se navega a `sales` con `popUpTo(onboarding) { inclusive = true }` y la
propia compuerta reacciona al cambio de configuración. Con `useInjectedViewModels = false`
(tests de navegación) la compuerta se fuerza a `Complete`.

## Destinos registrados

El registro canónico contiene exactamente 32 patrones y rechaza patrones duplicados o
metadatos de argumentos que no coincidan con sus placeholders.

| Área | Destino | Patrón | Argumentos |
| --- | --- | --- | --- |
| Compatibilidad | Redirección a Vender | `home` | Ninguno |
| Superior | Ventas | `sales` | Ninguno |
| Compatibilidad | Redirección a Vender | `invoices` | Ninguno |
| Superior | Inventario | `inventory` | Ninguno |
| Inventario | Registro con escáner físico | `inventory/register` | Ninguno |
| Superior | Reportes | `reports` | Ninguno |
| Arranque | Onboarding | `onboarding` | Ninguno |
| Catálogo | Productos | `products?barcode={barcode}` | `barcode` (opcional) |
| Compra | Historial | `purchases` | Ninguno |
| Ajustes | Ajustes | `settings` | Ninguno |
| Deudores | Lista | `debtors` | Ninguno |
| Deudores | Registrar deuda mediante venta a crédito | `debtors/new` | Ninguno |
| Deudores | Detalle y abonos | `debtors/{debtId}` | `debtId` |
| Compra | Nueva compra | `purchase/new` | Ninguno |
| Compra | Origen | `purchase/draft/{draftId}/source?replace={replaceId}` | `draftId`, `replaceId` (opcional) |
| Compra | Cámara | `purchase/draft/{draftId}/camera?replace={replaceId}&scanRetake={scanRetake}` | `draftId`, `replaceId` y `scanRetake` (opcionales y excluyentes) |
| Compra | Vista previa | `purchase/draft/{draftId}/preview/{captureId}` | `draftId`, `captureId` |
| Compra | Procesamiento | `purchase/draft/{draftId}/processing` | `draftId` |
| Compra | Revisar factura | `purchase/draft/{draftId}/header` | `draftId` |
| Compra | Líneas | `purchase/draft/{draftId}/lines` | `draftId` |
| Compra | Coincidencias de productos | `purchase/draft/{draftId}/matching` | `draftId` |
| Compra | Vinculación | `purchase/draft/{draftId}/linking/{lineId}` | `draftId`, `lineId` |
| Compra | Resumen | `purchase/draft/{draftId}/summary` | `draftId` |
| Compra | Confirmación | `purchase/draft/{draftId}/confirmation/{expectedPreparedHash}` | `draftId`, `expectedPreparedHash` |
| Compra | Éxito | `purchase/success/{purchaseId}` | `purchaseId` |
| Compra | Detalle | `purchases/{purchaseId}` | `purchaseId` |
| Compra | Anular compra | `purchases/{purchaseId}/void` | `purchaseId` |
| Inventario | Trazabilidad de producto | `inventory/{productId}` | `productId` |
| Cuenta | Cuenta y respaldo | `account` | Ninguno |
| Cuenta | Miembros del negocio | `account/members` | Ninguno |
| Cuenta | Mis invitaciones | `account/invitations` | Ninguno |
| Cuenta | Sincronización | `sync` | Ninguno |

Ajustes dejó de ser un marcador de posición: muestra el perfil del negocio, impuestos y
costos, la región de solo lectura y el modo demostración. Con la demo activa, su CTA genera e
importa la factura sintética por la frontera normal de captura y abre
`purchase/draft/{draftId}/preview/{captureId}`; si el borrador estable ya terminó, abre
`purchases/{purchaseId}`. Ambos saltos transportan solo ids tipados y reutilizan rutas existentes:
no añaden un deep link de escritura.

La entrada “Sincronización” de esa misma sección abre `sync`: cola de respaldo con sus
intentos y próximo intento, conflictos con comparación local/nube y resolución explícita,
y el pull incremental con reconciliación diagnóstica auditada. Sin sesión enlazada (o en el
flavor local) el destino muestra su estado no disponible sin ofrecer acciones.

Desde Ajustes también se abre `settings/privacy`: política de retención de imágenes (tras
OCR, tras confirmar, 30/90 días o conservar), limpieza y borrado de imágenes con contadores
reales, exportación JSON de los datos propios, interruptor del respaldo y eliminación de la
cuenta y los datos en la nube con resultado verificable. El detalle de qué dato sale del
dispositivo y qué nunca sale está en [`PRIVACY_DATA_LIFECYCLE.md`](PRIVACY_DATA_LIFECYCLE.md).

## Rutas de facturas conservadas

Facturas se retiró de la navegación principal. La ruta `invoices` redirige a `sales`, al igual
que `home`, para recuperar pilas guardadas de versiones anteriores. La navegación superior no
crea borradores ni abre la cámara.

Los destinos internos de captura, procesamiento y revisión de compras permanecen registrados
para compatibilidad y recuperación de estados anteriores. Las pruebas de estas funciones pueden
abrir sus rutas directamente; no deben depender de una pestaña Facturas inexistente.

“Revisar factura” tampoco es un marcador: carga el resultado parseado por `draftId`, permite ampliar la
factura, consultar evidencia OCR y editar proveedor, comprobante y totales. “Revisar productos”
espera el último autosave y solo avanza cuando los campos imprescindibles son válidos y toda
lectura esencial de confianza baja o desconocida fue confirmada por la persona. El checksum RUC
y la diferencia financiera exacta se muestran como advertencias no bloqueantes; ningún importe
se altera para hacerlo cuadrar.

Las rutas se construyen con `DraftId`, `CaptureId`, `LineId`, `PurchaseId`, `ProductId`, `DebtId` e
`ImageId`. Cada
tipo admite únicamente UUID canónicos, en minúsculas, de 36 caracteres y distintos del UUID
nulo. Confirmación añade un token de revisión `expectedPreparedHash`, validado como SHA-256 hex
en minúsculas, para impedir que una pantalla publique una instantánea distinta a la revisada.
No se transportan bitmaps, URI de archivos, JSON, importes ni entidades. La cámara
guardará la imagen fuera del back stack y `captureId` permitirá recuperarla desde el
repositorio correspondiente. El parámetro de consulta opcional `replace` transporta el
`ImageId` de la página a reemplazar ("Repetir" desde la vista previa); ausente, la
importación añade una página nueva.
El parámetro interno `scanRetake=true` solo lo construye `invoiceScanRetakeCamera`: sustituye
atómicamente la única foto del escaneo rápido después de un fallo, invalida sus derivados OCR y
no puede combinarse con `replaceId`. No habilita reemplazos arbitrarios de borradores multipágina.

## Navegación superior y Atrás

La barra inferior y la barra lateral de tablet muestran Vender, Inventario y Reportes.
Inicio y Facturas se retiraron del menú; las rutas `home` e `invoices` redirigen a `sales`
para recuperar pilas guardadas.
La selección
se deriva del destino actual, no de un índice local. Cada cambio superior usa
`launchSingleTop`, `saveState` y `restoreState` con `popUpTo(findStartDestination())` para
no duplicar destinos y conservar el estado disponible de cada sección.

La app abre directamente **Vender** (`sales`). **Tipo de venta** permite elegir **Contado** o **A crédito**;
en **Buscar** la pantalla consulta únicamente por nombre y muestra coincidencias exactas o similares,
mientras los códigos de barras pertenecen a **Escáner físico** y al lector HID. La ruta
`debtors` conserva **Registrar deuda**, que navega a `debtors/new` y reutiliza el carrito de Ventas
preseleccionado a crédito, y cada tarjeta navega con un `DebtId` canónico a su
detalle. Inventario conserva una sola ruta `inventory`: dentro de ella se alterna entre
**Existencias** y **Ganancias por producto**, por lo que cambiar de sección no crea otro destino ni
transporta importes por navegación. Dentro de **Existencias**, **Buscar** y **Escáner físico** son
modos explícitos. Una lectura HID válida consulta el código exacto en el negocio activo y navega a
la trazabilidad ya registrada como `inventory/{productId}`. Solo se transporta el `ProductId`
resuelto: el código no entra a la ruta, y el salto no crea, asocia ni modifica productos o
inventario. **Ganancias por producto** no activa el receptor. Este recorrido no usa cámara ni ML
Kit Barcode Scanning.

- Atrás desde `purchase/new` vuelve normalmente porque todavía no existe un borrador.
- Atrás desde una sección superior secundaria vuelve a Vender; Atrás desde Vender cede
  al sistema para cerrar la actividad.
- Desde Origen hasta Confirmación, tanto Atrás del sistema como la flecha superior abren
  un diálogo. Abrir o cerrar el diálogo no borra nada.
- `Paso anterior` retrocede dentro del flujo y conserva el borrador.
- Solo `Descartar borrador` ejecuta el callback de descarte una vez, elimina el flujo sin
  guardar su back stack y regresa a Vender.
- Al confirmar, Nueva compra y todos los pasos editables se eliminan antes de mostrar
  Éxito. Éxito observa la compra publicada en Room y ofrece Detalle e Inventario. Al abrir
  Detalle, Éxito se reemplaza; Atrás nunca reabre el borrador confirmado.
- Desde el detalle de una compra `POSTED`, Anular abre una ruta dedicada con el impacto de
  inventario, el rol autorizado, el motivo y una confirmación explícita. Al completar (o si
  el mismo comando ya había completado), la ruta sale hacia el detalle observado desde Room,
  que permanece consultable como `VOIDED`. Una compra `VOIDED` no vuelve a ofrecer la acción.

## Deep link interno

La única ruta permitida es de solo lectura:

```text
facturastock://internal/purchases/{purchaseId}
```

`InternalDeepLinks.resolve` aplica una allowlist exacta de esquema, autoridad y ruta;
rechaza user info, puertos, query, fragmentos, barras adicionales, escapes de ruta, UUID
no canónicos y entradas sobredimensionadas. Solo después de validar construye un
`PurchaseId` y navega al detalle. `MainActivity` acepta el URI desde su `Intent` inicial
o `onNewIntent`, lo valida antes de entregarlo al grafo y conserva la solicitud pendiente
si existe un borrador protegido. El manifiesto no declara filtros `VIEW` ni `BROWSABLE`:
el contrato solo se usa con intents explícitos dirigidos al componente, no como enlace
público resoluble por otras aplicaciones.

## Verificación

`NavigationContractTest` fija el registro (31 patrones, 3 destinos superiores), builders,
UUID y deep links. `AppNavigationTest` compara los nodos reales con el contrato, recorre
las tres secciones y comprueba las redirecciones de compatibilidad; los flujos internos de captura
se prueban mediante sus rutas, sin una pestaña Facturas. `NavigationRecreationTest` y
`HiltUdfRuntimeTest` son `@HiltAndroidTest` con `@TestInstallIn` sobre
`AppConfigurationModule` (compuerta forzada a completa): comprueban la recreación en Inventario
y en el catálogo, el acceso manual desde Inventario, la ausencia de Inicio y Facturas en el menú
y que navegar no cree borradores ni movimientos. También prueban Atrás del sistema y un deep link
mediante un `Intent` explícito real. `OnboardingNavigationTest` verifica la compuerta: arranque en
onboarding con configuración incompleta, imposibilidad de ver Vender sin completar y llegada a
`sales` tras guardar el formulario mínimo.

`InventoryViewModelTest` verifica por separado que el lookup HID respete el negocio activo y emita
`OpenProduct` con el `ProductId` exacto, además de mantener recuperables los códigos inválidos,
desconocidos y los fallos de lectura. `InventoryScreensTest` cubre el selector explícito y sus
estados accesibles; ninguno de esos casos añade un patrón al registro de navegación.
