# Base de `FULL_DEVICE_SNAPSHOT`

## Estado real

Esta fase **no implementa todavía un backup/import completo utilizable por el usuario**. Existe un
productor ZIP puro para una imagen SQLite que el llamador ya haya cerrado y checkpointado, un
preflight SQLite Android aislado, un journal durable y primitivas internas de rename atómico. Aún
no hay compuerta global que detenga writers/workers, cierre el singleton Room antes del swap y
reinicie el proceso, ni selector SAF de exportación/importación.
`FullDeviceSnapshotRestoreCoordinator.requestActivation()` sigue deliberadamente read-only y
siempre devuelve `NOT_READY`; incluso un snapshot y un recibo válidos no reemplazan
`facturastock.db`.

La compuerta solo podrá habilitarse cuando el adaptador Android pueda demostrar, en este orden:

1. Room está cerrado y el swap ocurre antes de construir el singleton de base de datos.
2. La candidata se migra en un nombre Room aislado y completa los digests canónicos, invariantes
   contables y correspondencia filas↔archivos que el preflight todavía no acredita.
3. El coordinador conecta el journal durable y los renames ya implementados con la compuerta del
   punto 1 y con una recuperación anterior a la construcción de Room.
4. Tras activarla se repiten las validaciones antes de marcar `COMPLETE`; un fallo recorre el camino
   de rollback y conserva el original en quarantine.

## Alcance del contrato v1

El formato es independiente de `ACCOUNTING_LEDGER` v4. El manifiesto declara
`COMPLETE_ROOM_DATABASE_INCLUDING_SALES`, exige una entrada `database/facturastock.db` y digests de
las tablas exigidas por el contrato, incluidas `sales`, `sale_lines`, `debts` y `debt_payments` en
el esquema vigente. El lector reconoce fuentes v24–v27: v25 agrega el índice de salud de outbox,
v26 los cursores del stream de inventario y v27 el ledger de cuentas por cobrar. Un gate de build
liga el techo del contrato con la versión y el identity hash Room actuales; aceptar el contenedor no
habilita por sí solo la restauración, que continúa en estado `NOT_READY` como se explica arriba.
El manifiesto también liga cada payload mediante ruta,
tamaño y SHA-256 y enumera los checks obligatorios de restauración.

El validador ZIP:

- exige un manifiesto JSON canónico como primera entrada;
- rechaza rutas absolutas, `..`, backslashes, directorios, aliases por mayúsculas/minúsculas,
  entradas duplicadas, desconocidas o ausentes;
- limita tamaño del archivo, expansión, entradas y rutas;
- comprueba tamaño y SHA-256 durante el streaming hacia un directorio nuevo;
- elimina el staging completo al fallar y nunca toca la base activa.

El productor ZIP puro:

- recibe una base ya checkpointada, payloads explícitos y digests de tablas ya calculados; no abre,
  cierra, checkpointa ni interpreta SQLite;
- calcula tamaños, CRC y SHA-256, crea el manifiesto canónico y lo escribe como primera entrada;
- ordena las entradas y usa ZIP STORED con metadata fija, de modo que la misma solicitud y los
  mismos bytes producen exactamente el mismo archivo, independientemente del orden de entrada o
  la zona horaria del host;
- rechaza fuentes symlink, rutas no canónicas, aliases por case-folding y destinos existentes;
- fuerza el temporal, lo autovalida en un staging nuevo y publica con hard link `create-new`, que
  falla cerrado si el filesystem no ofrece publicación atómica sin reemplazo;
- fuerza el directorio y limpia los temporales; cualquier fallo previo a publicación deja el
  destino intacto o lo reporta explícitamente como no intacto si no pudo comprobar el rollback.

Este productor trabaja con `Path` en un filesystem local que soporte hard links y `fsync`; no es un
writer SAF y no convierte por sí solo el flujo en una función Android disponible al usuario.

Los SHA-256 detectan corrupción accidental, pero no autentican un archivo hostil cuyo manifiesto
también pueda modificarse. Antes de ofrecer transferencia entre dispositivos falta un contenedor
AEAD portable y el recifrado de fotos retenidas, cuya clave AndroidKeyStore no es exportable.

## Preflight, journal y archivos de swap

`FullDeviceSnapshotSQLitePreflight` abre exclusivamente la copia extraída en modo read-only y:

- vuelve a verificar su fingerprint y exige ausencia de `-wal`, `-shm` y `-journal`;
- compara `user_version` y `room_master_table.identity_hash` con el schema Room exportado;
- ejecuta `PRAGMA integrity_check`, `PRAGMA foreign_key_check` y todos los conteos declarados;
- vuelve a verificar fingerprint/sidecars al terminar para detectar cambios durante la lectura.

No emite el recibo final: faltan digests canónicos de filas, invariantes semánticas completas,
migración aislada y la correspondencia entre filas de imagen y payloads privados.

El journal durable usa lock entre procesos, compare-and-set por operación/revisión, temporal con
`fsync`, rename atómico y `fsync` del directorio. Un journal existente corrupto nunca se pisa.

La primitiva de archivos serializa con otro lock, exige que no existan sidecars, verifica el
fingerprint antes y después, no acepta un destino existente y solo ejecuta los cinco renames que
puede ordenar la máquina de recuperación. Todavía no está expuesta por DI ni por Settings.

La máquina de estados modela avance y rollback, incluida la muerte entre un rename y el registro de
su estado posterior. Solo devuelve la siguiente acción segura al comparar fingerprints del activo,
candidato y quarantines; un layout ambiguo devuelve `Refuse`. El coordinador puro no ejecuta esas
acciones por sí solo; las primitivas se mantienen desconectadas hasta que exista la compuerta Room
de proceso completo.
