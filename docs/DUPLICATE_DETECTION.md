# Detección local de comprobantes duplicados

La comprobación se ejecuta antes de confirmar una compra y usa únicamente Room y los archivos
privados del dispositivo. No consulta SUNAT ni necesita conectividad.

## Identidad principal

Una coincidencia exacta exige el mismo negocio, proveedor (`supplierId`) o RUC normalizado, tipo
de documento, serie y correlativo. La normalización aplica NFKC, mayúsculas y elimina separadores,
por lo que espacios, guiones o diferencias de caja no crean una identidad distinta. Los ceros del
correlativo sí se conservan: una variación solo de padding se clasifica como probable.

La barrera SQL por negocio, proveedor, tipo, serie y número sigue siendo la última protección para
la identidad primaria frente a carreras. En una excepción autorizada, el trigger también acepta
la identidad del proveedor por el RUC congelado en ambos borradores; así una edición posterior del
catálogo no cambia la identidad histórica del comprobante.

## Señales secundarias

Fecha, moneda/total y SHA-256 de las imágenes aportan razones explicables a una coincidencia
probable. Ninguna imagen se compara fuera del dispositivo. El hash de imagen nunca decide por sí
solo: dos fotografías del mismo comprobante producen bytes distintos y una misma imagen aislada
no demuestra identidad fiscal.

Fecha y total tampoco bastan cuando serie y correlativo son ajenos: solo elevan a `PROBABLE` una
coincidencia de la misma serie, o complementan una imagen coincidente. Así se evitan alertas por
dos comprobantes legítimos del mismo proveedor, día e importe.

El `PreparedPurchase.logicalHash` tampoco se usa para esta búsqueda porque incluye IDs internos
del borrador y de sus líneas. Se conserva exclusivamente para integridad e idempotencia de la
instantánea preparada.

## Resultado y excepción

- `EXACT`: se muestra la compra existente y se bloquea la confirmación normal.
- `PROBABLE`: se muestran la compra y las señales secundarias; el usuario puede abrirla o
  continuar con la confirmación normal.
- `DISTINCT`: no se encontró una coincidencia local; no equivale a validación externa.

Para continuar ante una coincidencia `EXACT` se requiere un principal entregado por
`PurchaseOverrideAuthorizationRepository`, rol `OWNER` o `MANAGER` y un motivo de 10 a 500
caracteres. La UI no puede suministrar ni modificar el rol. El flavor `local`, único desde el 24 de
septiembre de 2026, representa al propietario del negocio activo como `OWNER`
(`LocalOwnerPurchaseOverrideAuthorizationRepository`); ya no existen roles de membresía cloud.

La autorización vuelve a ejecutar la búsqueda para evitar usar una coincidencia obsoleta y
entrega una decisión tipada al posting. La transacción la revalida y escribe
`PURCHASE_DUPLICATE_OVERRIDE` junto con la nueva compra, sus líneas, stock y outbox. El evento
append-only registra borrador, compra coincidente, clasificación, señales y rol, sin copiar UID ni
texto libre. La compra conserva de forma inmutable el target, actor, rol y motivo; el detalle los
muestra como evidencia operativa. Ambos registros comparten el commit y no pueden quedar huérfanos.

La variante cloud añadía una segunda barrera independiente en Functions al respaldar la compra.
Se retiró el 24 de septiembre de 2026: la operación de outbox que transporta la excepción se sigue
escribiendo en el mismo commit, pero ya no sale del dispositivo (ver [`BACKUP_SYNC.md`](BACKUP_SYNC.md)).

## Pruebas

La suite cubre coincidencia exacta tolerante a formato, probable por padding y señales
secundarias, distinto, prohibición de usar el hash como única clave, rechazo de rol no autorizado,
motivo obligatorio, navegación a la compra existente y persistencia append-only del evento.

## Integración con la publicación

La pantalla de preparación delega la confirmación a `ConfirmPurchaseUseCase`. Su adaptador Room
repite la búsqueda exacta dentro de la misma transacción que inserta la compra, las líneas, el
stock, la auditoría y el outbox. Una carrera con otra confirmación se resuelve contra el estado ya
persistido: el mismo borrador devuelve la misma compra y otro borrador abre la coincidencia
existente sin mover inventario.

Desde el esquema v14 —y también en el esquema vigente v27— Room mantiene una identidad `PRIMARY`
única y permite un slot excepcional por borrador
solo cuando el commit conserva target exacto, actor autorizado y motivo. Triggers independientes
rechazan campos incompletos, objetivos ajenos o no publicados e impiden modificar la autorización
después de publicar. Sin esa decisión completa, el comportamiento sigue siendo fail-closed y se
abre la compra existente.
