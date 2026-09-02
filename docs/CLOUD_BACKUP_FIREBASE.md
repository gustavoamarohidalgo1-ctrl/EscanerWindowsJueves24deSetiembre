# Respaldo opcional en la nube (Firebase)

## Contrato

La publicación/anulación de compras conserva el flujo local y usa el puerto
`PurchaseBackupTransport` (ver [`BACKUP_SYNC.md`](BACKUP_SYNC.md)) dentro de un **flavor**
separado. Cuando un negocio tiene enlace cloud durable, el checkout de venta sí usa Functions como
autoridad previa: debe confirmar el descuento del inventario compartido antes del commit Room para
evitar sobreventa entre dispositivos. Si la venta es a crédito, esa misma transacción crea la cuenta
por cobrar; cada abono posterior también usa Functions como autoridad previa para evitar doble cobro
entre dispositivos.

| Flavor | Contenido | Resultado |
| --- | --- | --- |
| `local` | Sin INTERNET ni Firebase (guardas lo exigen) | Cola honestamente `PENDING_SYNC` |
| `cloud` | Firebase BoM 34.17.0: Authentication, Firestore, Functions, Storage, App Check, Analytics y Crashlytics | Respaldo configurable e inventario compartido; documentos y diagnóstico usan compuertas separadas y opt-in |

`verifyOfflineFirstBoundaries` inspecciona el modelo de configuraciones de Gradle y falla la
compilación si cualquier módulo `com.google.firebase` aparece en una configuración compartida
(`implementation`, `debugImplementation`, …): Firebase solo entra al APK `cloud` vía
`cloudImplementation`. El APK `local` no contiene SDK de red.

Los providers de App Check se aíslan además por build type: `cloudDebugImplementation`
incluye solo el provider debug para emuladores y `cloudReleaseImplementation` incluye solo Play
Integrity. `cloudBenchmark` y `cloudProfile` también usan Play Integrity para conservar una
ejecución release-like. `FirebaseBackupRuntime` depende de un installer y de
`FirebaseRuntimeEnvironment`; sus implementaciones viven en
`src/cloudDebug`/`src/cloudRelease`/`src/cloudBenchmark`/`src/cloudProfile`. Solo `cloudDebug`
contiene el proyecto, host, puertos y callable auxiliar del Emulator Suite.
`verifyCloudAppCheckBoundaries` resuelve los runtime classpaths y revisa todas las fuentes no
debug; `verifyCloudReleaseBundleHygiene` inspecciona los DEX del AAB y rechaza esos literales en
el candidato productivo.

**Sin Firebase configurado, el modo local sigue funcionando**: en `cloud` release sin
`local.properties`, `CloudBackupConfig` es `null`, el transporte declara `configured = false`
y el programador no encola trabajo.

## Plan y costo operativo

Cloud Functions exige que el proyecto productivo use **Blaze** y tenga una cuenta de facturación.
Blaze conserva cuotas sin costo para Functions y Firestore; dos teléfonos con un negocio pequeño
normalmente deberían quedar dentro de ellas, pero no se garantiza una factura de S/ 0: cada
despliegue de Functions puede consumir almacenamiento facturable de sus contenedores y cualquier
exceso se cobra por uso. Se deben configurar alertas de presupuesto y, cuando estén disponibles para
el servicio subyacente, límites de gasto antes del despliegue. Las alertas no detienen el uso ni los
cargos; los límites tampoco son un tope duro instantáneo porque su aplicación depende de la latencia
de medición. Fuentes vigentes: [precios de Firebase](https://firebase.google.com/pricing),
[cuotas de Functions](https://firebase.google.com/docs/functions/quotas) y
[facturación de Firestore](https://firebase.google.com/docs/firestore/pricing), además de la guía
para [evitar cargos inesperados](https://firebase.google.com/docs/projects/billing/avoid-surprise-bills).

Una variante sin tarjeta ni cuenta de facturación requeriría sustituir Functions y rediseñar la
autoridad transaccional; no es el backend de producción descrito en este documento.

## Diagnóstico operacional y consentimiento

El respaldo y la telemetría son compuertas independientes. `diagnostics_enabled` empieza en
`false` y solo cambia desde el control explícito de Ajustes. `ProductionObservability` acepta
eventos tipados de captura, edición de cabecera/líneas, ajuste, confirmación, anulación y sync;
`ConsentAwareProductionObservability` comprueba la preferencia, transforma cualquier fallo a
un código cerrado y aplica de nuevo `ObservabilityAllowlist` antes del sink.

Para cada evento definido por la app, los únicos atributos admitidos son `outcome`,
`error_code` y `age_bucket`. No se envían `business_id`, `draft_id`, `image_id`, `purchase_id`,
`operation_id`, timestamps ni valores fiscales. Claves desconocidas y valores fuera de los
catálogos se descartan. Un `Throwable` nunca cruza el puerto: ni mensaje, causa, stacktrace,
clase o ruta llega al SDK. Incluso tras el opt-in, `ANALYTICS_STORAGE` permanece `DENIED`; el
SDK no dispone de app-instance ID para correlacionar eventos. Activar diagnósticos tampoco
habilita publicidad: los tres consentimientos publicitarios permanecen `DENIED`, la colección
de Advertising ID y pantallas automáticas está desactivada y el manifiesto elimina AD_ID y
AdServices. Los cuatro estados de consentimiento también nacen en `false` desde el manifiesto,
antes de instanciar Firebase; el gate solo permite señales operacionales no identificables.

El SDK de Crashlytics está incluido solo en el flavor `cloud`, pero su colección automática se
fuerza a `false` en el manifiesto y el mapping de R8 no se sube. No existe una llamada productiva
que le entregue `Throwable`, mensajes o excepciones manuales: los eventos cerrados pasan
exclusivamente por Analytics. Al revocar, primero se deniega/desactiva Analytics, se reinicia su
estado local y, si Firebase ya estaba inicializado, se solicitan descartar reportes Crashlytics no
enviados. La bitácora Room append-only y la outbox funcional no cambian. Los eventos ya agregados
en el proyecto siguen su política de retención/borrado y no son borrables selectivamente desde el
cliente, que nunca fija un `userId`.

## Credenciales: ninguna en el repositorio

- `google-services.json` sigue vetado por `verifyLocalOcrConfiguration`. La app se inicializa
  con `FirebaseOptions` manuales.
- **cloud-debug** usa el proyecto demo `demo-facturastock` contra el Emulator Suite
  (`10.0.2.2`, puertos 9099/8080/5001). Son marcadores públicos, no credenciales.
- **cloud-release** lee `firebase.projectId`, `firebase.applicationId`, `firebase.apiKey` y
  `firebase.storageBucket` de `local.properties` (no versionado) hacia BuildConfig. La API key y
  el nombre del bucket son identificadores públicos por diseño (la seguridad la dan las reglas),
  pero por política no se versionan.

## Desarrollo con emuladores

```bash
cd functions && npm install          # una vez
npx firebase emulators:start         # raíz del repo: auth 9099, firestore 8080, functions 5001, storage 9199, ui 4000
./gradlew :app:assembleCloudDebug    # APK apuntando a los emuladores
```

La membresía demo se siembra con el callable `devEnsureMembership` (la app cloud-debug lo
llama antes del primer envío; fuera del emulador responde `not-found`) o con
`node scripts/seed-demo.mjs <uid> [businessId]`.

## Colecciones

```text
businesses/{businessId}
  members/{uid}                    # membresía con rol (callables de membresía / dev callable)
  invitations/{sha256(email)}      # PENDING/ACCEPTED/DECLINED/CANCELLED (callables)
  suppliers/… products/…           # reservadas; el documento de compra ya es autocontenido
  purchases/{purchaseId}           # documento v1/v2 legacy o v3 vigente + receipt/idempotencia
                                   # + seq de respaldo, inventorySeq y movementSummary
    lines/{purchaseLineId}
    voidRecord/record              # actor cloud + hash local opaco + hash semántico server-side
  sales/{saleId}                   # venta POSTED completa, crédito opcional, acuse y balances finales
    lines/{saleLineId}
  debts/{debtId}                   # cuenta por cobrar versionada ligada uno-a-uno a una venta
    payments/{paymentId}           # abonos append-only; sin UID del actor
  stockMovements/{movementId}
  inventoryBalances/{hash}         # saldo por producto remoto + nombre canónico de almacén
  inventorySyncChanges/{receiptId} # feed contiguo SALE/PURCHASE/PURCHASE_VOID/DEBT_PAYMENT
  auditEvents/{auditEventId}
  documentIndex/{hash}             # unicidad proveedor|tipo|serie|número
  syncKeys/{sha256(key)}           # unicidad de la clave idempotente
  saleSyncKeys/{sha256(key)}       # idempotencia de postSale
  debtPaymentSyncKeys/{sha256(key)}# idempotencia/replay exacto de recordDebtPayment
  sync/metadata                    # seq (contador monotónico), lastSyncedAt, lastPurchaseId, lastReceiptId
  sync/inventoryMetadata           # inventorySeq contigua y estado de bootstrap
  sync/inventoryBootstrap          # recibo/auditoría one-shot de la migración legacy
  syncChanges/{purchaseId}         # proyección compacta cerrada del pull incremental, sin actor
  documentBackups/{imageId}        # metadatos cerrados del archivo remoto y estado terminal
  documentSyncOperations/{hash}    # idempotencia, lease pre-Sharp y fallo JPEG cerrado
invitationRateLimits/{sha256(...)} # cuotas fixed-window pseudónimas; TTL asíncrono
documentUploadRateLimits/{sha256(...)} # 60/h actor y 500/día negocio; TTL asíncrono
syncMutationRateLimits/{sha256(...)}   # ventas/abonos: cuotas por actor+negocio y negocio; TTL
membershipQuotaCounters/{sha256(uid+namespace)} # contador durable; solo Functions
```

Los bytes del documento no se escriben directamente desde Android. La app prepara un JPEG
derivado, sin OCR ni rutas privadas, y guarda su artefacto de salida temporal cifrado con AES-GCM
en el sandbox. El callable valida membresía, negocio, compra publicada, identidad de imagen, MIME,
tamaño y JPEG real antes de que Admin SDK escriba en Storage. La descarga de un miembro autorizado
usa el puerto de solo lectura `RemoteDocumentArchive`; el cliente no obtiene permiso de escritura.
Antes de consultar metadata, antes de descargar y antes de entregar bytes se releen sesión,
binding, ambos opt-ins y el ACK Room sin tombstone (`isBackedUp`). Una revocación durante cualquiera
de esas suspensiones devuelve `NotEligible` y sanea cualquier buffer ya recibido.

## Callables de membresía y roles

`functions/membership.js` (re-exportados desde `index.js`). Todos exigen cuenta
email/contraseña **verificada** y toman la autorización del documento de membresía del
servidor (nunca del cliente). Roles cerrados: `OWNER`, `ADMIN`, `OPERATOR`, `READER`.
Android captura el UID que inició cada lectura o mutación y lo envía como `expectedUid`;
Functions exige que coincida con el UID del token antes de hacer I/O. El campo solo es opcional
para clientes ya publicados, por compatibilidad durante la migración, y nunca sustituye las
comprobaciones de membresía y rol del servidor.

- `createBusiness` crea el negocio y registra al caller como `OWNER`.
- `inviteMember` / `listBusinessInvitations` (OWNER/ADMIN): invitación por email con rol,
  vigencia de 7 días; ADMIN no puede invitar con rol OWNER.
- `listMyInvitations` / `acceptInvitation` / `declineInvitation`: el invitado lista,
  acepta (crea la membresía con el rol invitado; idempotente si ya es miembro) o declina.
- `listMembers` (cualquier miembro), `listMyMemberships` (negocios del caller).
- `changeMemberRole` / `removeMember` (OWNER/ADMIN, o el propio usuario para abandonar):
  un OWNER solo lo toca otro OWNER, ADMIN no puede asignar OWNER ni eliminar a un OWNER,
  y el último OWNER no puede ser degradado ni salir (`LAST_OWNER_REQUIRED`).

Cada listado acepta opcionalmente `{ limit, cursor }` (además de `businessId` cuando aplica y
el `expectedUid` interno de la app), con defecto y máximo de 100, y devuelve los campos
históricos más `nextCursor`/`hasMore`.
La cuota de pertenencia es 20 negocios tanto al crear como al aceptar. Un documento pseudónimo
por UID serializa altas y bajas dentro de la misma transacción que `members/{uid}`; si aún no
existe, se reconstruye con un aggregate `collectionGroup` para migrar datos legacy sin una ventana
de carrera. Se elimina al quedar en cero y al cerrar la cuenta, no contiene UID/email y sus campos
no se indexan. `inviteMember` mantiene contadores transaccionales durables de 30/h por actor,
100/día por negocio y 10/día por destinatario. Un email que ya pertenece al negocio nunca recibe
una nueva `PENDING`: cualquier residuo redundante se marca `CANCELLED` sin consumir cuota, también
al revocar esa membresía. Un retry idéntico dentro de 60 s confirma el ACK sin renovar la
invitación ni consumir otra cuota; una re-invitación posterior conserva la semántica existente. Tanto
`invitations.expiresAt` (7 días) como `invitationRateLimits.expiresAt` tienen TTL físico
versionado; la caducidad lógica se comprueba siempre porque la purga TTL es asíncrona.

Las reglas Firestore dejan leer invitaciones directamente solo a OWNER/ADMIN. El invitado usa
`listMyInvitations`, que valida dentro de una transacción el tombstone de su cuenta; un JWT viejo
no puede seguir consultándolas por el claim de email. Toda escritura pasa por los callables
(Admin SDK).

## Cuentas, sesión y negocios en la app

- La sincronización se activa con **email y contraseña verificada** (Ajustes → Cuenta y
  respaldo). El perfil local nunca la exige: sin sesión la cola queda en `PENDING_SYNC`.
- Estados de sesión (`AccountSession`): `SignedOut`, `AwaitingVerification`, `Active`,
  `Expired` (token revocado o irrecuperable → reingreso) y `Unavailable` (flavor local o
  sin configuración). El transporte solo envía con sesión `Active`; un `UNAUTHENTICATED`
  dispara un único intento de renovación de token antes de marcar la sesión como expirada.
- Cada operación autenticada de membresía, libro, catálogo, documento y borrado lleva el UID
  capturado al iniciarse. Functions lo compara con Auth antes de I/O, de modo que un cambio
  A → B durante un `await` no puede ejecutar ni revelar la operación con la cuenta nueva.
- **Enlace local ↔ nube** (`CloudBusinessLink`): la instalación respalda al negocio de la
  nube elegido solo si el enlace corresponde a la empresa local del envelope; una
  discrepancia es `BUSINESS_LINK_MISMATCH` (fallo visible, nunca reencaminado). El cambio de
  negocio y la creación se hacen desde la app; las invitaciones se aceptan o rechazan desde
  la app y la membresía solo se crea al aceptar.
- **Cerrar sesión** limpia los tokens, olvida el enlace y cancela los trabajos WorkManager
  del usuario (`cancelAll`); las compras, ventas, deudas, abonos y borradores locales se conservan siempre (el
  diálogo de confirmación lo declara).
- En el Emulator Suite el correo de verificación no sale: la verificación se marca desde la
  UI del emulador (Auth, puerto 4000) antes de activar la sincronización.
- Los tokens jamás aparecen en logs: `verifyNoSensitiveLogging` prohíbe cualquier registro
  en las fuentes productivas y los errores de cuenta viajan como códigos cerrados
  (`AccountError`), sin mensajes del backend.

## Catálogo y precio de venta

`syncCatalogEntity` mantiene compatibilidad cerrada por tipo. `SYNC_SUPPLIER` conserva el contrato
v1. `SYNC_PRODUCT` acepta el documento/payload v1 histórico sin campos comerciales y el v2 vigente,
que exige las dos claves `salePriceMinorUnits` y `salePriceCurrencyCode`: ambas son nulas o ambas
forman un precio positivo, entero seguro para JSON y con moneda ISO reconocida. Claves adicionales,
versiones cruzadas o pares incompletos se rechazan.

Las claves idempotentes también expresan el contrato (`sync-product:v1:…` o
`sync-product:v2:…`). Una entidad remota que ya recibió forma v2 no admite una mutación v1 posterior,
aunque el precio v2 sea nulo: así un cliente antiguo no puede borrar silenciosamente el campo. El
pull conserva snapshots v1/v2; Android preserva el precio local al aplicar un snapshot v1 y aplica
el par únicamente cuando el cambio remoto declara la forma v2. La pertenencia del negocio sigue
derivándose de Auth/membresía y las pruebas rechazan lectura, escritura y pull entre tenants.

## Cloud Function `postPurchase`

`functions/index.js` (Node ESM, sin build step). En orden:

1. **Auth**: exige `request.auth` con **email verificado** (cuentas email/contraseña; los
   anónimos del emulador ya no pasan), exige que el `expectedUid` capturado por la outbox
   coincida con el token que finalmente recibe y comprueba la membresía
   `businesses/{bid}/members/{uid}`. Esto cierra cambios de cuenta entre la guarda local y el
   envío. Tombstone de cuenta, lock de negocio, membresía y rol se leen dentro de la transacción que
   escribe, de modo que una baja/degradación concurrente invalida el commit. El
   **rol** de la membresía autoriza la operación: `SYNC_PURCHASE` normal →
   OWNER/ADMIN/OPERATOR; un `SYNC_PURCHASE` con excepción de duplicado y
   `SYNC_PURCHASE_VOID` → solo OWNER/ADMIN. Si no: `unauthenticated` /
   `permission-denied` (`EMAIL_NOT_VERIFIED`, `NOT_A_MEMBER`, `ROLE_FORBIDDEN`).
   `payload.role` sigue siendo obligatorio en el contrato v1 pero ya no autoriza nada.
2. **Versiones y validación**: el envelope `SYNC_PURCHASE` reconoce
   `payloadVersion=2 → document.version=1` y `payloadVersion=3 → document.version=2` solo para
   recuperar un ACK de un hecho legacy ya existente. Una alta nueva usa
   `payloadVersion=4 → document.version=3`; intentar crear v2/v3 falla con
   `INVENTORY_WIRE_MIGRATION_REQUIRED`. Los tres contratos tienen catálogo exacto y proyección
   canónica recursiva; claves extra se rechazan. Los documentos v2/v3 exigen serie
   `[A-Z0-9]{1,4}`, correlativo `[0-9]{1,12}` conservando ceros y, por línea,
   `taxTreatment ∈ {INCLUDED, EXCLUDED, EXEMPT}`,
   `taxEvidence {type,value}` con
   `type ∈ {NONE, EXPLICIT_AMOUNT, EXPLICIT_RATE}` y
   `productProvenance ∈ {EXISTING, CREATED_IN_DRAFT}`. `UNKNOWN` y `UNKNOWN_LEGACY` no son
   publicables como alta v4. El documento v3 añade exactamente `movement.locationName` y
   `movement.appliedCostTotal`: el nombre se canoniza para identificar el almacén y el total debe
   ser no negativo y producir el `unitCost` de la línea mediante división decimal `HALF_EVEN` a
   escala 18. Las claves
   idempotentes son exactamente `sync-purchase:v1:<purchaseId>` o
   `sync-purchase-void:v1:<purchaseId>`; IDs controlados por el cliente son UUID canónicos.
   Los topes individuales (≤ 100 líneas, ≤ 500 movimientos, ≤ 100 auditorías y ≤ 1 MB de
   entrada) no eluden los límites finales: la proyección exacta que se persistirá se vuelve a
   serializar y debe quedar ≤ 900 000 bytes, y antes de la transacción se rechaza cualquier
   grafo cuya estimación supere 480 unidades de escritura. Ambos umbrales dejan margen bajo los
   límites de documento/transacción de Firestore, incluidos campos y transforms del servidor.
3. **Excepción documental**: `document.version=2/3` admite
   `{ existingPurchaseId, sourceDraftId, auditEventId, reason }`. El callable deriva el rol
   efectivo de la membresía —no recibe actor ni rol afirmados por Android— y revalida que el
   target remoto pertenezca al negocio, tenga la misma identidad y esté `POSTED` o `VOIDED`.
   Un target aún no sincronizado devuelve `unavailable/DUPLICATE_TARGET_NOT_SYNCED` para
   conservar la causalidad. La transacción mantiene el índice `PRIMARY` y crea un slot de
   excepción distinto, derivado de identidad + `sourceDraftId`; no debilita la unicidad normal.
4. **Recomputo exacto**: montos individuales son enteros seguros ≤ 10^15; las sumas de
   cabecera/líneas usan `BigInt`, por lo que tampoco desbordan `MAX_SAFE_INTEGER` antes de
   comprobar `suma de líneas + ajuste = total` (`TOTAL_MISMATCH` si no cuadra).
5. **Escritura atómica** en una `runTransaction`: compra + líneas + movimientos + auditoría +
   índices de unicidad + `sync/metadata`. Para una alta v4, la misma transacción también actualiza
   `inventoryBalances`, `sync/inventoryMetadata` y una entrada `PURCHASE` de
   `inventorySyncChanges`; el ACK incluye `inventorySeq` y los balances finales. Cada escritura
   (alta o anulación) toma el
   siguiente `seq` monotónico del negocio (`sync/metadata.seq`) y la compra guarda además
   `movementSummary` (`[{ productId, productName, type, quantityDelta }]`, con el nombre de
   la línea enlazada por `purchaseLineId`) para el pull incremental.
   En v2/v3, una excepción persiste únicamente referencias (`existingPurchaseId`, `sourceDraftId`,
   `auditEventId`) y el rol efectivo autorizado; su evento remoto es tipado y conserva target +
   rol. El motivo se exige recortado y con 10–500 caracteres durante la llamada, pero no se
   guarda —ni como texto, hash o longitud— y tampoco se incorpora a `syncPayloadHash`. El motivo
   y actor exactos permanecen en la compra local Room y su detalle; el evento append-only local
   conserva solo referencias y valores cerrados saneados.
6. **Idempotencia**: misma compra, clave y proyección canónica → mismo `receiptId`
   (`ALREADY_RECORDED`, sin reescribir ni avanzar el `seq`); el replay se reconoce después de
   comprobar cuenta/negocio/membresía, pero antes de exigir el rol de una escritura nueva. Así un
   ACK perdido sigue siendo recuperable aunque el rol se haya degradado. Misma compra con otra
   clave → `failed-precondition`; clave reutilizada en otra compra o identidad primaria repetida
   sin excepción → `already-exists`. Los
   conflictos de compra/identidad/anulación llevan `details` con
   `{ existingPurchaseId, receiptId }` del documento ya respaldado (solo IDs). El
   `receiptId` es determinista (`rcpt_` + SHA-256 truncado), así que una carrera de
   reintentos converge al mismo valor. Para v4, el replay también devuelve el mismo
   `inventorySeq` y los mismos balances, sin mutar el saldo.
7. **Anulación** (`SYNC_PURCHASE_VOID`): valida y serializa canónicamente el payload v1, exige
   compra previamente respaldada y marca `VOIDED` con motivo y acuse propios; repetición
   idéntica → mismo acuse. El `actorId` del payload es un UUID interno y permanece dentro de su
   hash; la identidad Firebase confiable se deriva del token y se guarda separada como
   `voidRecord.cloudActorUid`/`purchase.voidedBy`, para poder anonimizarla sin romper el sello.
   El `impactHash` de Android se conserva sin reinterpretarlo: incluye estado local que la nube
   no posee y cambiar su fórmula rompería el wire v1. El servidor sí verifica que exista un
   impacto por producto/almacén y que `reversalQuantity` sea el opuesto decimal exacto de los
   movimientos `PURCHASE` respaldados; guarda esa relación como `semanticImpactHash` separado.
   La anulación también avanza el `seq` compacto y el `inventorySeq`; revierte los balances en la
   misma transacción y emite `PURCHASE_VOID` con los saldos finales. Una compra legacy solo puede
   anularse contra inventario después del bootstrap one-shot.

El transporte reconstruye el alta desde Room según la versión durable de la outbox: v2/v1 y v3/v2
se conservan solo para replays exactos, mientras las compras nuevas producen v4/documento v3 con
decisión/evidencia tributaria, procedencia, excepción opcional, nombre de almacén y costo total
aplicado exacto. En todos los casos proyecta siempre `status=POSTED` para la
operación durable `SYNC_PURCHASE`, aunque la vista local ya sea `VOIDED`; filtra los movimientos
`VOID` y el evento `PURCHASE_VOIDED`, y usa el nombre/código de unidad congelados en
`purchase_lines` al confirmar (esquema Room vigente v27). La operación posterior
`SYNC_PURCHASE_VOID` comunica la compensación. La outbox no reclama un void hasta que el alta de
esa misma compra esté `COMPLETED`; un alta en backoff no convierte el void dependiente en conflicto
manual y tampoco bloquea compras independientes.

### Compatibilidad y orden de despliegue

El rollout es deliberadamente asimétrico:

1. desplegar primero Functions con v4/documento v3, `postSale`, `recordDebtPayment`, bootstrap y el
   feed unificado con `DEBT_PAYMENT`;
2. verificar replays v2/v1 y v3/v2, altas v4/v3, venta a crédito, pagos y carrera CAS en Emulator
   Suite;
3. recién entonces distribuir Android, que crea operaciones nuevas con las versiones vigentes y
   consume el feed.

Una fila de outbox v2/v3 **no se rellena ni se reescribe a ciegas**. Pudo haber alcanzado al backend y
perder su ACK; cambiarla a v4 produciría otro wire/hash y rompería el replay. Tampoco se atribuye a
compras históricas una decisión tributaria o procedencia que Room no conoce: el legado permanece
legible como v1/`UNKNOWN_LEGACY`, mientras las altas nuevas fallan cerrado si esos datos no están
resueltos. Si el negocio ya contiene compras legacy, se inicializa el ledger cloud con el bootstrap
auditado descrito abajo; no se intenta deducir el saldo actual solo desde las compras.

La app traduce el resultado al ciclo de la outbox: acuse válido → `SYNCED`; 5xx/timeout/red →
reintento con backoff; 4xx → acción manual; conflicto → conciliación
(`FunctionsErrorMapper`, probado en JVM).

## Cloud Function `postSale`

`functions/saleSync.js` confirma una venta compartida antes del commit local. Exige email
verificado, `expectedUid`, membresía y rol OWNER/ADMIN/OPERATOR. El request cerrado es
`{ businessId, expectedUid?, idempotencyKey, operationType: "SYNC_SALE", payloadVersion,
document }`; la clave debe ser `sync-sale:v1:<saleId>`. El documento v1 representa una venta al
contado y conserva el wire anterior exacto. El documento v2 representa una venta a crédito y añade
`credit: { version: 1, debtId, debtorNameSnapshot, dueAt }`; `debtId` se deriva de `saleId`, el
nombre debe estar en forma canónica y el total debe ser positivo. Ambos contienen cabecera y líneas
completas, sin imagen/OCR. Valida productos activos sincronizados, unidad, moneda, importes/hash,
orden `createdAt <= updatedAt <= postedAt` y rechaza dos líneas con la misma pareja
`productId + canonicalLocationName` (`SALE_PRODUCT_LOCATION_DUPLICATED`). El UUID `locationId` se
conserva como origen, pero no define la identidad del saldo entre teléfonos.

Dentro de una transacción lee productos y todos los balances, aplica cuotas fixed-window de 300
ventas/h por actor+negocio y 2.000/h por negocio, descuenta el stock y crea venta, líneas,
movimientos, auditoría, clave idempotente, balances, feed y `sync/inventoryMetadata`. Ningún saldo
se modifica si una línea falla. En v2 también crea `debts/{debtId}` con versión 1, saldo igual al
total y estado `OPEN`; nunca crea deuda sin su venta exacta. La sobreventa responde
`INSUFFICIENT_STOCK` con
`details { productId, locationName, requested, available }`; un saldo histórico ausente falla con
`INVENTORY_BALANCE_MIGRATION_REQUIRED` en vez de asumir cero.

El primer commit fija un `effectivePostedAt = max(reloj de Functions, document.postedAt,
updatedAtMillis de los balances de apertura)`. Ese instante se persiste como `updatedAt` y
`postedAt` de la venta y como timestamp de movimientos/balances, de modo que el segundo teléfono
acepta `createdAt <= updatedAt <= postedAt`. No forma parte del hash del request: un retry con el
mismo wire devuelve el valor ya almacenado. El ACK al contado conserva exactamente
`{ receiptId: "sale_<32hex>", idempotencyKey, status: "RECORDED"|"ALREADY_RECORDED", seq,
postedAtMillis, balances }`. El ACK a crédito añade `debt`, la instantánea autoritativa inicial.
Cada balance contiene `productId`, `locationName`,
`quantityOnHand`, `averageUnitCost`, `currency`, `version`, `updatedAtMillis` y `seq`.

## Cloud Function `recordDebtPayment`

`functions/saleSync.js` registra un abono contra una cuenta abierta. Exige email verificado,
`expectedUid`, membresía y rol OWNER/ADMIN/OPERATOR. El request cerrado usa
`operationType: "SYNC_DEBT_PAYMENT"`, `payloadVersion: 1` y la clave
`debt-payment:v1:<debtId>:<paymentId>`. El documento contiene deuda/negocio/moneda, importe positivo,
método cerrado (`CASH|YAPE|PLIN|BANK_TRANSFER|OTHER`), nota/referencia opcionales, versión esperada y
fechas. La nota se limita a 500 caracteres y la referencia a 120; ambas se normalizan y rechazan
controles.

Dentro de una transacción comprueba que la deuda pertenezca al negocio, siga `OPEN`, conserve la
moneda y versión esperadas, y que el abono no supere el saldo. Inserta el pago append-only, reduce el
saldo, avanza la versión y marca `PAID` cuando llega a cero. También crea el recibo idempotente y una
entrada `DEBT_PAYMENT` en el feed, sin modificar inventario. Dos teléfonos que parten de la misma
versión no pueden cobrar ambos: uno gana el CAS y el otro recibe conflicto para sincronizar.

El ACK es `{ receiptId: "debt_payment_<32hex>", idempotencyKey,
status: "RECORDED"|"ALREADY_RECORDED", seq, debt, payment }`. Un replay exacto devuelve el hecho
original sin otro descuento, incluso si después hubo más pagos; reutilizar la identidad con otro
contenido se rechaza. `payment.createdAt` coincide con `debt.updatedAt` y conserva por separado el
`occurredAt` solicitado.

## Cloud Function `bootstrapInventoryBalances`

La migración one-shot inicializa negocios cuyas compras v1/v2 no llevaban nombre canónico de
almacén ni `appliedCostTotal`. El primer commit exige email verificado, OWNER/ADMIN y
`auth_time` reciente; un replay idéntico después de un ACK perdido no vuelve a exigir la ventana de
cinco minutos. Request:

```text
{
  businessId, expectedUid?,
  idempotencyKey: "inventory-bootstrap:v1:<businessId>", payloadVersion: 1,
  locations: [{ sourceLocationId, locationName }],
  balances: [{ productId, locationName, quantityOnHand, averageUnitCost, currency }]
}
```

Functions acepta 1..200 balances, hasta 200 ubicaciones y hasta 200 compras legacy. Exige el set
exacto producto+almacén canónico del ledger activo, productos ya sincronizados, moneda coherente,
cantidades/costos no negativos y `quantityOnHand <= compras activas acumuladas`. El snapshot Room
del administrador es la base inicial —incluido el promedio— porque las ventas locales anteriores y
el costo total exacto no existen en el wire viejo; la diferencia
`compras acumuladas - saldo sembrado` se audita como `legacyUnreplicatedSales`. Una cantidad mayor
que las compras, un negativo o un costo inválido falla cerrado. Si no hay compras o todas están
anuladas responde `INVENTORY_BOOTSTRAP_NOT_REQUIRED` sin crear metadata ni un cambio vacío.

El commit crea los balances, la auditoría, `sync/inventoryBootstrap`,
`sync/inventoryMetadata.seq=1` y un cambio inicial `PURCHASE` con balances no vacíos. El ACK es
`{ receiptId: "inventory_bootstrap_<32hex>", idempotencyKey,
status: "BOOTSTRAPPED"|"ALREADY_BOOTSTRAPPED", seq: 1, balances }`. Ante
`INVENTORY_*_MIGRATION_REQUIRED`, el repositorio de venta Android construye este snapshot, intenta
el bootstrap una vez y repite `postSale` solo si el ACK coincide; errores de rol, autenticación
reciente, tamaño o validación quedan visibles y no inicializan parcialmente el ledger.

## Cloud Function `listSalesInventoryChanges`

`functions/saleSync.js` expone el feed autoritativo de inventario. Exige email verificado,
`expectedUid` y cualquier membresía, y acepta
`{ businessId, expectedUid?, sinceSeq?: 0, limit?: 100 }`, con `limit` 1..200 y ventana interna de
20. Devuelve `{ changes, nextCursor, hasMore, latestSeq }`; valida que `sinceSeq` no esté por delante
de `latestSeq` y que no exista ningún hueco. Las entradas históricas conservan la forma
`{ kind, seq, receiptId, sale, balances, syncedAtMillis }`; `DEBT_PAYMENT` añade exactamente
`debt` y `payment`. `kind ∈ { SALE, PURCHASE, PURCHASE_VOID, DEBT_PAYMENT }` y `seq` es
estrictamente contigua. En mutaciones de inventario, `balances` contiene los saldos finales tocados;
en un abono es vacío. `sale` es `null` para compra/anulación/abono; para `SALE` lleva la
cabecera canónica completa, todas las líneas y movimientos, además de su `receiptId` y `seq`.
`syncedAtMillis` puede ser `null` mientras se materializa el server timestamp. La repetición
idempotente devuelve su ACK original y no agrega otra entrada.

`PullRemoteChangesUseCase` drena primero catálogo, luego este feed y por último el espejo compacto
de compras. Cada bucle tiene un máximo fail-closed de **500 páginas por invocación**. Para cada
página de inventario, `RoomSharedInventoryApplicationRepository` resuelve el producto mediante el
enlace de catálogo y el almacén por nombre canónico, materializa una venta ausente con cabecera,
líneas, movimientos, auditoría y deuda opcional, o inserta el abono antes de avanzar la cuenta por
cobrar; aplica los balances finales y mueve `inventorySeq` dentro de una sola transacción Room. Una
referencia ambigua, un grafo incompatible o un hueco revierte toda la página y conserva el cursor
anterior; aplicar un cambio remoto no crea una outbox de eco.

El feed permite recuperar saldos compartidos, ventas completas, deudas y pagos que continúen en su
secuencia, pero **no constituye una restauración integral**: `listChanges` solo conserva una proyección compacta de
compras y no recrea sus líneas/auditoría completa; tampoco cubre borradores, preferencias, todo el
historial local ni imágenes sin respaldo documental.

## Cloud Function `listChanges` (pull incremental)

`functions/syncPull.js` (re-exportado desde `index.js`). Exige auth con email verificado y
membresía del negocio (cualquier rol, incluido `READER`). Payload:
`{ businessId, sinceSeq?, limit?, expectedUid? }` — `expectedUid` lo envía Android y se
contrasta con Auth antes de leer; `sinceSeq` entero ≥ 0 (defecto 0), `limit` entero
1..200 (defecto 100); errores `invalid-argument` con códigos cerrados (`BUSINESS_ID`,
`SINCE_SEQ`, `LIMIT`).

Dentro de una sola transacción lee tombstone, negocio, membresía y la colección compacta
`syncChanges` con `seq > sinceSeq`, ordenada por `seq` (un solo campo: no requiere índice
compuesto). `postPurchase` mantiene esa proyección sin líneas, payloads ni identidad de cuenta,
la limita a 64 KB y el pull usa una ventana interna máxima de 20 elementos más uno para decidir
`hasMore` (el `limit` público 1..200 sigue siendo el máximo solicitado). Devuelve
`{ changes, nextCursor, hasMore }`. Cada cambio es una proyección cerrada:
`{ seq, purchaseId, status, documentType, documentSeries, documentNumber, issueDate,
currency, supplierRuc, supplierLegalName, totalMinorUnits, movementSummary, receiptId,
syncedAtMillis, syncedBy }` (`syncedAtMillis` puede ser `null` recién escrito y `syncedBy` es
siempre `null` por compatibilidad, nunca se almacena en `syncChanges`). `nextCursor`
es exclusivamente el `seq` del último cambio efectivamente entregado o, si la página está
vacía, el mismo `sinceSeq`; `hasMore` decide si pedir otra página. Android persiste solo ese
cursor consumido junto con la página del espejo —nunca adelanta al `latestSeq` de otro flujo—. Un
commit entre páginas aparece en la consulta siguiente y nunca puede quedar saltado por un contador
global leído desde otro snapshot.

## Cloud Function `deleteMyAccount` (eliminación de cuenta)

`functions/accountDeletion.js` (re-exportado desde `index.js`). Exige auth pero **no** email
verificado (una cuenta sin verificar también debe poder borrarse; la limpieza de
invitaciones solo corre con email verificado, porque uno sin verificar podría ser ajeno).
La validación y el cierre se hacen en una transacción: si el caller es OWNER de un negocio con
otros miembros → `failed-precondition` (`OWNED_BUSINESS_HAS_MEMBERS`, `details.businesses` con
`{businessId, legalName}`) y no deja tombstone ni lock. Si pasa, crea un tombstone pseudónimo
permanente (hash namespaced, sin UID/email) que todos los callables mutantes consultan dentro de
su transacción, y bloquea cada negocio de propietario único en el mismo snapshot que vuelve a
contar miembros. `acceptInvitation` y los demás writers rechazan el lock; las reglas impiden que
un cliente lo quite o modifique.

El lock inicial admite como máximo **480 negocios donde la cuenta figura como OWNER** (incluidos
los pendientes de un reintento), dejando margen bajo el límite de 500 escrituras de Firestore.
Un alcance mayor falla antes de consultar los árboles o escribir con
`ACCOUNT_DELETION_SCOPE_TOO_LARGE`: no se borra nada y la persona debe contactar el canal de
soporte configurado para reducir/segmentar el alcance de forma controlada.

Antes de empezar, Android reautentica al usuario con Firebase mediante la contraseña introducida en
el diálogo, fuerza un ID token nuevo, persiste un checkpoint local previo al paso ambiguo y
descarta la contraseña. El callable acepta exclusivamente `{}` legacy o `{ expectedUid }` y
rechaza un token de otra cuenta antes de crear tombstones. `deleteMyAccount` aplica además
`requireRecentAuth`: exige un `auth_time` entero con antigüedad máxima de cinco minutos (y tolera
solo 30 segundos de desfase futuro). Un refresh de token no cambia `auth_time`; por eso un token
viejo o sin ese claim falla con `RECENT_AUTH_REQUIRED` antes de leer o mutar datos.

El checkpoint elige el fallo seguro ante una respuesta perdida: una vez escrito, el cliente no
reenvía automáticamente el callable y termina la limpieza local. Queda una ventana estrecha entre
esa escritura y el inicio real de `.call()`: una muerte de proceso exactamente allí puede limpiar
la sesión sin haber enviado la eliminación remota. Cerrar esa garantía de extremo a extremo exige
un journal/idempotency key reconocido por servidor; no se afirma que esta versión lo resuelva.

Cuando existe email verificado, la misma transacción crea además un lock temporal cuyo ID es un
hash namespaced del email y cuyo documento no contiene el correo ni el UID. `inviteMember` lo lee
en su propia transacción, por lo que no puede reinsertar ese correo durante el barrido. El lock se
se intenta eliminar después de limpiar Firestore y borrar Auth, sin propagar un falso fallo si ese
delete final no responde. A las 24 h deja de bloquear: una invitación posterior limpia el lock
vencido dentro de su transacción y la política TTL versionada en `firestore.indexes.json` programa
su purga física asíncrona. Firestore no garantiza que el delete TTL ocurra en el instante exacto
del vencimiento. Una invitación creada después es un dato nuevo y el mismo correo puede volver a
registrarse.

Después borra recursivamente esos negocios, elimina sus membresías en negocios sobrevivientes
y, solo si el token acredita el email, elimina todas las invitaciones dirigidas a ese correo sin
importar su estado. También elimina por completo cualquier invitación histórica cuyo
`acceptedBy` o `declinedBy` sea el UID, incluso si contiene un correo anterior. En documentos que
deben sobrevivir sustituye por `deleted-account` `business.createdBy`,
`invitation.invitedBy/cancelledBy`, `member.roleUpdatedBy`, `purchase.syncedBy/voidedBy` y
`voidRecord.cloudActorUid`; no toca el
`actorId` UUID interno ni los hashes/payload canónicos del void.

La eliminación purga cada prefijo documental de Storage antes y después del borrado Firestore. El
tombstone conserva además esos UUID como limpieza pendiente durante una gracia de 10 minutos; el
reconciliador programado vuelve a borrar los prefijos para cubrir un `file.save` que hubiera
aterrizado tarde y recién entonces cierra el pendiente. Los UUID quedan retirados permanentemente
dentro del tombstone pseudónimo: `createBusiness` rechaza reutilizarlos, evitando que una outbox o
purga antigua de la generación A alcance documentos de un negocio nuevo con la misma identidad.

Las mutaciones se deduplican y fraccionan en lotes de hasta 400 escrituras con precondición de
versión; una consulta o lote fallido conserva Authentication para permitir un reintento
idempotente. El tombstone permanece incluso después del éxito para que un idToken previamente
emitido no pueda crear negocio, aceptar/invitar/cambiar miembros ni publicar/anular compras. Solo
al terminar se ejecuta `admin.auth().deleteUser(uid)`; el refresh token ya no se renueva. Las
membresías y referencias directas se eliminan antes de Auth; la supresión de Functions no depende
de esperar a que expire ese token.
Devuelve `{ businessesDeleted, membershipsRemoved }` (las membresías de OWNER caen con el
árbol y se representan en `businessesDeleted`). La política completa está en
[`PRIVACY_DATA_LIFECYCLE.md`](PRIVACY_DATA_LIFECYCLE.md).

## Reglas de seguridad y App Check

**Firestore** (`firestore.rules`, versionada en el repo): default-deny total; toda lectura
de negocio exige autenticación y membresía (`isMember`), y la lectura directa de invitaciones
exige rol OWNER/ADMIN (el destinatario usa el callable con tombstone); compras, líneas,
movimientos de stock, auditoría,
ventas, deudas, abonos, balances/feed de inventario, índices y membresías **no se escriben desde el cliente** (solo
Functions con Admin SDK, que
derivan el UID del token y validan membresía y rol). El perfil del negocio también es
control-plane y ningún cliente, ni siquiera OWNER, lo crea, modifica o elimina directamente.
Tombstones, locks y contadores pseudónimos de cuota son inaccesibles al cliente.

**Storage** (`storage.rules`): ruta privada por negocio
`businesses/{bid}/invoices/{purchaseId}/{imageId}.jpg`; la membresía se verifica contra
Firestore. Android tiene únicamente lectura para miembros del negocio. Toda escritura, reemplazo
y eliminación directa del cliente se deniega; las altas y purgas pasan por Functions/Admin SDK,
que vuelve a validar rol, tenant, compra terminal, JPEG y tamaño. El lock de eliminación del
negocio bloquea nuevas altas. Todo lo demás queda denegado.

**App Check** (punto único versionado en `functions/common.js`): todos los callables declaran
`enforceAppCheck: true` en cualquier runtime desplegado. El bypass solo existe cuando Emulator
Suite fija `FUNCTIONS_EMULATOR` exactamente a `"true"`; project IDs demo, build flags y variables
propias no lo desactivan. Antes de desplegar Functions debe estar operativo Play Integrity para la
app release, porque una instalación sin token válido será rechazada. El enforcement de acceso
directo a Firestore y Storage se gestiona por separado en Firebase Console y también debe
verificarse allí.

Los callables generales usan `maxInstances=20`, `concurrency=20`, timeout de 60 s y 256 MiB.
Subida/purga documental reducen el fan-out a 5 instancias × 2 requests, con 90 s y 512 MiB;
`bootstrapInventoryBalances` usa 3 × 2, 120 s y 512 MiB; `deleteMyAccount` usa 3 × 1, 300 s y
512 MiB. Estos techos contienen coste/memoria y evitan
que operaciones pesadas monopolicen cada instancia; no sustituyen las cuotas transaccionales.
Antes de abrir Sharp, la subida adquiere transaccionalmente un lease por clave idempotente y
consume una sola unidad de cuotas pseudónimas (60/h por actor y 500/día por negocio). Ese preflight
también corta un `imageId` ocupado o purgado y cuotas de páginas/bytes evidentemente llenas. Un
retry de una reserva ya validada reutiliza sus dimensiones; un JPEG inválido queda en estado
`VALIDATION_FAILED` y el mismo cuerpo recibe el mismo error sin otro decode. La transacción que
reserva bytes vuelve a leer negocio, cuenta, compra, `imageId` y cuotas documentales, de modo que
el atajo previo nunca decide una carrera. Leases abandonados y fallos de validación tienen TTL de
48 h; operaciones completas no llevan ese vencimiento y conservan la idempotencia durable.
El trigger de finalización documental usa `retry:true`: es la última defensa para borrar un save
tardío incluso si el borrado de cuenta ya eliminó su reserva Firestore. Queda limitado a 3
instancias × 2 eventos, 90 s y 256 MiB; rutas malformadas terminan sin error y las reservas
corruptas pasan transaccionalmente a `INVALID`, por lo que no crean reintentos venenosos ni ocupan
para siempre la ventana. Todo upload legítimo reserva primero en Firestore; el reconciliador
programado recoge además las reservas stale. Ese barrido usa una sola instancia y una sola
ejecución concurrente, 300 s/256 MiB, con 3 reintentos y backoff acotado a 15 minutos.

Functions fija `firebase-admin` 14.3.0 y aplica un override acotado de su dependencia opcional
`@google-cloud/storage` a 8.0.1. El único breaking change declarado por Storage 8 es exigir Node
≥22, que coincide con `functions/package.json`; la suite ejercita subida, evento de finalización,
descarga y purga con esa resolución. No se fuerza `gaxios` a otro major: el audit residual proviene
de `gaxios@6.7.1 → uuid@9` y debe retirarse cuando Firebase/Google publique una cadena compatible.

## Límites del envío documental

- **Sin doble opt-in no sale ninguna imagen**: iniciar sesión o activar solo el respaldo comercial
  no crea una subida. Con ambos opt-ins y una compra publicada, la app puede enviar por HTTPS una
  copia JPEG derivada. Firebase Storage la cifra de forma administrada en reposo; no se presenta
  como cifrado E2E.
- **La nube nunca recibe el texto OCR, rutas de archivo, `lastError` crudo, mensajes, causas ni
  stacktraces**. Los atributos operacionales definidos por la app usan nombres/valores cerrados y
  UUID internos. Crashlytics está presente pero su colección permanece siempre desactivada y no
  recibe excepciones manuales; Analytics puede sumar la telemetría técnica automática descrita
  arriba tras consentimiento, nunca contenido fiscal aportado por FacturaStock.
- **Retención y purga tienen estados verificables distintos**: antes de borrar localmente un archivo
  que pudo llegar a Storage, Room conserva `SYNC_DOCUMENT_PURGE`. “Durable” significa solicitud
  local pendiente; solo el ACK del callable permite mostrar “eliminada de la nube”. Las purgas
  explícitas pueden drenarse aun con el respaldo comercial apagado. Al quedar durable se retira y
  verifica de inmediato el derivado `.fse`; un fallo permanece como retry local y el barrido elimina
  derivados huérfanos en reinicios posteriores. Una fila legacy intentada sin tenant fijado bloquea
  el borrado con `LEGACY_DESTINATION_UNKNOWN`; nunca se reasigna por el enlace actual.

## Despliegue real

No se despliega nada desde este repositorio: no hay proyecto Firebase real ni permisos. Para
habilitarlo hay que crear/verificar la app Android `com.facturastock.app`, cargar su configuración
por el mecanismo protegido y usar siempre el proyecto explícito (el `.firebaserc` versionado
apunta al demo). Desde una sesión autorizada:

```bash
firebase deploy --project "$FACTURASTOCK_FIREBASE_PROJECT_ID" \
  --only firestore:rules,firestore:indexes,storage,functions
```

Ese despliegue incluye los índices `COLLECTION_GROUP`, las políticas TTL del email-lock, las
invitaciones y sus contadores antiabuso, la exclusión de índices del counter durable, reglas de
Storage y Functions; omitir una parte rompe garantías fail-closed. Después deben configurarse el
proveedor Auth, certificados release/App Check y sus controles de consola antes de Functions, y
probarse el recorrido
cuenta→respaldo→pull→eliminación con datos sintéticos. La validación sintáctica de Gradle no
demuestra que projectId/appId/API key/bucket pertenezcan entre sí. Hasta completar y evidenciar estos
pasos no existe backend de producción desplegado ni se afirma que el candidato pueda usar nube.

Para introducir compra v4/documento v3, inventario compartido, ventas a crédito y pagos, este
despliegue completo de Functions, reglas e índices debe preceder al APK que los usa. Durante la transición el servidor
conserva v2/v1 y v3/v2 solo para replays exactos y acepta altas nuevas v4/v3; retirar compatibilidad
legacy exige demostrar antes que no quedan outboxes v2/v3 pendientes en ninguna instalación, no
realizar un backfill automático. Los negocios existentes inicializan su saldo mediante el bootstrap
one-shot auditado, no reconstruyéndolo únicamente a partir de compras legacy.

## Evidencia automatizada

- `functions/test/postPurchase.test.mjs` contra Emulator Suite: auth/rol, escritura atómica,
  idempotencia, conflictos, esquema recursivo cerrado, replays v2/v1 y v3/v2, alta v4/v3,
  tax/evidencia/procedencia, `locationName`/`appliedCostTotal`, promedio decimal, balances y feed de
  compra/anulación. `functions/test/saleInventorySync.test.mjs` cubre `postSale` al contado/crédito,
  deuda inicial, abonos parciales/totales, sobrepago, CAS concurrente, idempotencia/replay, roles,
  sobreventa, duplicados producto+almacén, timestamp efectivo, cuotas, bootstrap seguro y secuencia
  contigua `SALE|PURCHASE|PURCHASE_VOID|DEBT_PAYMENT`.
- `functions/test/membership.test.mjs`: invitaciones con aceptación/caducidad, respuesta
  `NOT_FOUND` que no revela emails ajenos,
  re-invitación de una `ACCEPTED` rechazada, protección del último OWNER,
  cuotas durables/idempotencia, serialización concurrente del límite 20 al crear y aceptar,
  reconciliación legacy, cancelación de invitaciones redundantes, paginación acotada,
  cuota al aceptar, revocación inmediata, rechazo de `expectedUid` cruzado antes de I/O,
  multi-negocio con cambio y reglas (lectura de cliente sin membresía
  o de invitación ajena, denegadas).
- `functions/test/syncPull.test.mjs`: secuencia monotónica (alta y anulación avanzan `seq`,
  repetición exacta no), pull incremental con cursor `sinceSeq`, reaparición de anuladas
  como `VOIDED`, `nextCursor`/`hasMore` sin salto ante un commit entre páginas, página vacía
  que conserva cursor, ventana compacta, entero seguro, ausencia de actor/`latestSeq`,
  validaciones cerradas, `READER` sí lista, y el
  criterio multi-teléfono: dos usuarios del mismo negocio con la misma identidad documental
  → `already-exists` con `details.existingPurchaseId`, y reenvío del mismo sobre sin
  duplicar.
- `functions/test/securityRules.test.mjs` (Emulator Suite con Storage): matriz allow/deny
  por rol y tenant — cross-tenant negado en lectura y escritura, compras, ventas, deudas, abonos,
  `stockMovements`, balances y feed de inventario inmutables para el cliente (ni OWNER),
  membresías solo por callable, perfil de negocio con
  campos permitidos/tamaños, lock de borrado inmutable y lectura directa de invitado denegada;
  en Storage: todos los `create`/`update`/`delete` del SDK cliente se rechazan, la descarga se
  limita a miembros verificados del mismo negocio y las rutas fuera de
  `businesses/{bid}/invoices/` quedan denegadas. `functions/test/documentBackup.test.mjs` cubre por
  separado validación, lease/idempotencia pre-Sharp, cuotas actor/negocio y escritura/purga
  autorizadas mediante callable/Admin SDK.
- `functions/test/accountDeletion.test.mjs`: `deleteMyAccount` — sin auth, OWNER con
  miembros rechazado sin tocar nada, único OWNER borra todo el árbol y su usuario Auth,
  miembro no-OWNER pierde solo su membresía, invitaciones de todos los estados eliminadas
  para email verificado, email no verificado aislado, referencias UID anonimizadas sin borrar
  compras ajenas (incluido `cancelledBy`), `voidRecord.cloudActorUid` anonimizado con
  payload/hash intactos, tombstone y
  lock contra JWT viejo, lock temporal de email contra invitación concurrente, target en borrado
  no promovible a OWNER, más de 500 mutaciones con fallo/reintento por lotes y estado
  post-borrado verificable.
- `functions/test/firestoreIndexes.test.mjs`: exige los índices de alcance
  `COLLECTION_GROUP` usados por cada consulta de limpieza, las seis clases de TTL vigentes y que
  los campos internos del counter durable no creen índices innecesarios antes del despliegue.
- Todo se ejecuta con:
  `cd functions && npx firebase emulators:exec --only auth,firestore,functions,storage "npm test"`.
  Los archivos se serializan porque comparten una única instancia de Emulator Suite; las pruebas
  de carrera conservan concurrencia explícita dentro de su caso.
- `CloudBackupConfigTest`, `FunctionsErrorMapperTest`, `AccountErrorMapperTest` y
  `AccountMappersTest` (JVM): parsing estricto de configuración y mapeo cerrado de errores
  (sin mensajes del backend).
- `FirebasePurchaseDocumentMapperTest`, `FirebaseSaleWireMapperTest` y
  `FunctionsErrorMapperTest`: forma canónica de replays v2/v1 y v3/v2, alta v4/v3, ACK de venta,
  bootstrap/feed, rechazo del legado desconocido en altas nuevas, minimización de la excepción y
  mapeo cerrado. Además,
  `CloudAccountSettingsStoreTest` cubre la persistencia fail-closed del rol. Se mantiene la prueba
  de 100 líneas sin imagen/OCR, igualdad exacta del wire antes/después del void y rechazo
  de cursores fuera del entero seguro. `PurchasePostingDaoTest` prueba que el snapshot de catálogo
  es obligatorio y no cambia tras renombrar el producto; las pruebas de outbox cubren que un alta
  transitoria bloquea solo su void dependiente y que el reintento manual prioriza el alta.
- `PullRemoteChangesUseCaseTest` y `RoomSharedInventoryApplicationRepositoryTest`: máximo de 500
  páginas, continuidad del cursor, aplicación de saldos y materialización idempotente del grafo de
  venta dentro de la misma transacción Room.
