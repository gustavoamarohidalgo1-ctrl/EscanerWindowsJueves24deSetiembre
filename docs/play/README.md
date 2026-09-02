# Paquete de publicación de Google Play

Este directorio reúne la ficha española y los borradores de cumplimiento de FacturaStock.
Se preparó contra el comportamiento del código del 21 de agosto de 2026 y toma como alcance el
AAB `cloudRelease`, porque es el artefacto con el tratamiento de datos más amplio. El flavor
`local` no tiene permiso de Internet ni Firebase, pero una declaración de Data Safety es global
para todas las versiones activas de un mismo `applicationId`; por eso no se puede usar el perfil
`local` para omitir lo que hace `cloud`. Este paquete presupone que se distribuirá
`cloudRelease`: en el estado actual, `localRelease` deja vacío `PRIVACY_POLICY_URL` y no debe
subirse a Play hasta ofrecer también un enlace de privacidad funcional dentro de esa variante.

## Estado real

| Entregable | Estado | Archivo |
| --- | --- | --- |
| Nombre, descripciones, categoría, contacto y guion visual | Listo con marcadores de contacto | [`store-listing-es-PE.md`](store-listing-es-PE.md) |
| Data Safety para `cloudRelease` | Borrador conservador; falta comprobar opciones de Firebase Console | [`data-safety-cloud-es.md`](data-safety-cloud-es.md) |
| Política pública | HTML estático listo para personalizar y alojar | [`privacy-policy-template-es.html`](privacy-policy-template-es.html) |
| Recurso web de eliminación | HTML estático listo para conectar a un canal de solicitud real | [`account-deletion-template-es.html`](account-deletion-template-es.html) |
| Icono, gráfico y ocho capturas sintéticas | Generados y validados localmente | [`../../play/assets/README.md`](../../play/assets/README.md) |
| Pasos de Play Console y pista interna | Checklist reproducible, no ejecutado | [`release-checklist.md`](release-checklist.md) |
| Rollout gradual y hotfix forward-only | Política local validable; publicación no ejecutada | [`rollout-and-hotfix-policy.md`](rollout-and-hotfix-policy.md) |

## Bloqueos antes de subir a una pista

No se debe marcar este paquete como publicado ni enviar la declaración de Data Safety mientras
quede cualquiera de estos puntos:

1. **Eliminación fuera de la app.** No existe una URL pública donde una persona que ya
   desinstaló FacturaStock pueda iniciar una solicitud. El HTML de este directorio es solo una
   plantilla hasta conectarlo a un formulario o correo atendido y verificable.
2. **Política pública.** La UI ya contiene “Abrir política de privacidad” y `cloudRelease`
   productivo exige una URL HTTPS válida, pero falta personalizar, alojar y proporcionar esa URL.
   Hay que comprobar el enlace desde el AAB distribuido.
3. **Identidad y contacto.** Faltan el nombre legal del responsable, el correo de privacidad,
   el correo de soporte y las dos URLs HTTPS definitivas. Ningún marcador de plantilla puede
   llegar a Play Console.
4. **Configuración externa.** El repositorio no permite verificar la retención de Google
   Analytics, sus opciones de uso compartido, Google Signals ni enlaces con productos
   publicitarios. Deben auditarse en la consola del proyecto Firebase/Analytics. La respuesta
   conservadora es “Sí† se comparten” por la colaboración entre miembros; solo puede cambiarse
   con evidencia simultánea de la excepción de colaboración de Play y de proveedor Firebase.
5. **Consolas y firma.** No se recibió acceso a Play Console, Firebase de producción ni a la
   clave de carga. Por tanto, no se ha creado la app, subido el AAB ni demostrado su instalación
   desde una pista interna.
6. **Variante local.** Si se pretende distribuir `localRelease`, hay que configurar y probar su
   enlace de privacidad dentro de la app. Este expediente solo propone subir `cloudRelease`.

## Controles ya implementados en el candidato

- Ruta in-app: Ajustes → Cuenta y respaldo → Eliminar mi cuenta, con confirmación irreversible,
  error/reintento y limpieza local separada para no repetir el callable después del éxito remoto.
- Un propietario con otros miembros debe transferir propiedad; el backend aborta antes de mutar.
- Un alcance superior a 480 negocios donde la cuenta figura como OWNER se rechaza antes de
  consultar sus árboles o escribir y dirige
  a soporte; no existe limpieza parcial.
- Se borra el negocio de propietario único, la membresía de un no propietario y toda invitación
  dirigida al correo verificado, sin importar su estado.
- La acción también permite borrar una cuenta cuyo correo aún no fue verificado; en ese caso no
  usa el correo sin acreditar para eliminar invitaciones.
- En negocios que sobreviven, cualquier invitación histórica con `acceptedBy` o `declinedBy`
  igual al UID se borra completa. Las referencias de autoría `createdBy`, `invitedBy`,
  `roleUpdatedBy`, `syncedBy`, `voidedBy` y `voidRecord.cloudActorUid` se sustituyen por
  `deleted-account`; el `actorId` del payload de anulación es un UUID interno y se conserva con
  sus hashes. Las compras comerciales del negocio también se conservan.
- La cuenta Auth se elimina al final de un proceso por lotes reintentable. Permanece una guarda
  pseudónima derivada del UID, sin UID ni correo en claro, para bloquear JWT antiguos. El lock
  pseudónimo de correo usado contra invitaciones concurrentes se intenta eliminar al terminar,
  deja de bloquear a las 24 h y queda bajo una política TTL de purga física asíncrona si ese
  delete best-effort falla. Los datos locales del teléfono no se borran silenciosamente.

## Fuentes de la declaración

- Permisos y separación `local`/`cloud`: `app/src/*/AndroidManifest.xml`.
- Cámara, importación y OCR local: `LocalDraftImageImporter`,
  `MlKitInvoiceTextRecognizer` y `docs/LOCAL_OCR.md`.
- Respaldo de compras y catálogo: `FirebasePurchaseBackupTransport`, `functions/index.js` y los
  adaptadores de catálogo cloud.
- Ventas, deudas, abonos e inventario compartido: `FirebaseRemoteSaleSyncRepository`,
  `FirebaseRemoteDebtSyncRepository`, `RoomSharedInventoryApplicationRepository` y
  `functions/saleSync.js`.
- Cuenta, miembros e invitaciones: `FirebaseAccountRepository` y
  `functions/membership.js`.
- Eliminación remota: `functions/accountDeletion.js`.
- Diagnóstico opcional: `FirebaseObservabilitySink`, `ObservabilityAllowlist` y el manifiesto
  `cloud`.
- Retención y exportación: `docs/PRIVACY_DATA_LIFECYCLE.md`,
  `PrivacyUseCases.kt` y `UserDataExportJson.kt`.

## Referencias oficiales verificadas

- [Formulario Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Eliminación de cuentas](https://support.google.com/googleplay/android-developer/answer/13327111)
- [Política de datos de usuario](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Campos de la ficha](https://support.google.com/googleplay/android-developer/answer/9859152)
- [Requisitos de recursos gráficos](https://support.google.com/googleplay/android-developer/answer/9866151)
- [Divulgación de los SDK Firebase para Android](https://firebase.google.com/docs/android/play-data-disclosure)
