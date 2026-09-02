# Borrador Data Safety — `cloudRelease`

Este documento es una hoja de respuestas para Play Console, no una exportación directa del
formulario. Se aplica al AAB `cloudRelease` y adopta el criterio conservador de declarar toda
transmisión posible del artefacto. Google define “recopilar” como transmitir datos fuera del
dispositivo; el tratamiento que permanece íntegramente local no se declara como recopilación.

**Estado: no enviar todavía.** La ruta de eliminación y el enlace de política ya existen en la
UI, pero faltan las dos URLs públicas funcionales, la identidad/contacto definitivos y la
auditoría de las opciones del proyecto Firebase/Analytics.

## Respuestas generales

| Pregunta de Play Console | Respuesta preparada | Condición/evidencia |
| --- | --- | --- |
| ¿La app recopila o comparte datos? | Sí | Firebase recibe datos en el flavor `cloud` |
| ¿Todos los datos recopilados se cifran en tránsito? | Sí | Los clientes Firebase usan TLS/HTTPS; no existe transporte propio en claro |
| ¿Se comparten datos con terceros? | **Sí† (clasificación conservadora)** | Los miembros autorizados reciben datos del negocio compartido. Cambiar a “No” solo si el formulario vigente confirma que toda esa colaboración califica como transferencia iniciada/esperada por el usuario **y** Google/Firebase conserva la excepción de proveedor de servicios |
| ¿El usuario puede solicitar eliminación? | Sí dentro de la app; no enviar el formulario hasta publicar también el recurso web obligatorio | Cuenta y respaldo → Eliminar mi cuenta; callable `deleteMyAccount` |
| ¿La app permite crear cuentas? | Sí | Alta con correo y contraseña desde la app cloud |
| ¿La recopilación es opcional? | Por tipo, según la tabla | La app funciona localmente sin cuenta ni respaldo; algunos SDK se inicializan sin opt-in |

La excepción de “proveedor de servicios” permite no marcar una transferencia como “compartida”,
pero no permite omitirla como “recopilada”. Si la organización habilita en las consolas una
finalidad propia de Google, exportación publicitaria o destinatario distinto, hay que volver a
evaluar “se comparte” antes de publicar.

## Tipos que se deben seleccionar

| Categoría → tipo | Recopilado | Compartido | Obligatorio u opcional | Finalidades que seleccionar | Qué lo origina |
| --- | :---: | :---: | --- | --- | --- |
| Información personal → Nombre | Sí | Sí† | Opcional | Funcionalidad de la app | Razón social/nombre comercial de proveedor o negocio, y nombre escrito para una cuenta por cobrar; puede identificar a una persona natural y es visible a miembros autorizados |
| Información personal → Dirección de correo electrónico | Sí | Sí† | Opcional | Funcionalidad de la app; Gestión de cuentas | Registro/acceso, verificación, miembros e invitaciones; los miembros ven el correo del equipo y OWNER/ADMIN gestionan invitaciones |
| Información personal → IDs de usuario | Sí | Sí† | Opcional | Funcionalidad de la app; Gestión de cuentas; Prevención del fraude, seguridad y cumplimiento | UID de Firebase, membresías y autorización de sync; el listado del equipo expone IDs a sus miembros |
| Información personal → Otra información | Sí | Sí† | Opcional | Funcionalidad de la app | RUC del proveedor cuando corresponde a una persona natural y se comparte dentro del negocio cloud |
| Información financiera → Historial de compras | Sí | Sí† | Opcional | Funcionalidad de la app | Facturas de compra confirmadas respaldadas en Firestore y accesibles a miembros autorizados |
| Información financiera → Otra información financiera | Sí | Sí† | Opcional | Funcionalidad de la app | Compras y ventas publicadas, líneas, precios, subtotales, impuestos, costos, cargos, redondeos, cuentas por cobrar, saldo, abonos/método y movimientos de inventario visibles en el negocio compartido |
| Archivos y documentos → Archivos y documentos | Sí | Sí† | Opcional | Funcionalidad de la app | Identidad y contenido estructurado del comprobante accesible al equipo |
| Fotos y videos → Fotos | Sí | Sí† | Opcional | Funcionalidad de la app | Solo con respaldo comercial y documental activos: copia JPEG derivada del comprobante, por HTTPS y cifrada de forma administrada en Firebase Storage; accesible a miembros autorizados |
| Actividad en la aplicación → Otro contenido generado por el usuario | Sí | Sí† | Opcional | Funcionalidad de la app; Gestión de cuentas | Negocio, proveedores, productos, almacenes, descripciones, motivos, nombre del deudor, notas/referencias de abono, roles e invitaciones compartidos con miembros según rol |
| Actividad en la aplicación → Interacciones con la app | Sí | No* | Opcional | Analíticas | Eventos de ciclo de app y acciones operacionales, solo tras opt-in de diagnósticos |
| Información y rendimiento de la app → Diagnósticos | Sí | No* | Opcional | Analíticas | Resultado/código cerrado de operaciones y metadatos técnicos del SDK tras opt-in |
| Ubicación → Ubicación aproximada | Sí | No* | Opcional | Analíticas | Analytics deriva ubicación general de la IP enmascarada cuando está habilitado |
| Dispositivo u otros IDs → Dispositivo u otros IDs | Sí | No* | Obligatorio en `cloudRelease` | Funcionalidad de la app; Prevención del fraude, seguridad y cumplimiento | Firebase Installation ID y material/token de App Check; Analytics añade un app-instance ID tras opt-in |

`Sí†` es la clasificación conservadora para colaboración entre usuarios: un OWNER/ADMIN invita a
otras personas y los miembros autorizados acceden a datos del negocio. Play puede ofrecer la
excepción de transferencia iniciada/esperada por el usuario; no se debe cambiar estas filas a
“No compartido” sin confirmar en el formulario vigente que esa excepción cubre el flujo completo y
guardar evidencia de la decisión. Para cada fila, marcar “no se procesa de forma efímera” salvo que
la consola presente una pregunta específica y exista evidencia contractual/técnica de que ese dato
solo vive durante la solicitud. Los registros de cuenta, compras, miembros y Analytics persisten,
por lo que no son efímeros. Los `No*` restantes siguen condicionados a que Google/Firebase actúe
solo como proveedor y a las verificaciones de consola descritas arriba.

### Por qué “Dispositivo u otros IDs” es obligatorio

En el release cloud configurado, el arranque aplica el consentimiento de diagnósticos incluso
cuando está apagado; ese adaptador inicializa perezosamente Firebase y App Check. Firebase
Installations genera y transmite un FID, y Play Integrity puede transmitir material de
atestación. No existe hoy un control del usuario que evite esa inicialización. Por eso no sería
veraz marcar este tipo como opcional aunque la cuenta y el respaldo sí lo sean.

Esta clasificación es provisional hasta observar el tráfico del AAB release en dos arranques
limpios: sin entrar a cuenta y después de usar voluntariamente nube. Si la prueba demuestra que
ningún FID, token o atestación sale antes de esa acción voluntaria, puede reclasificarse como
“opcional”. No hacer ese cambio basándose solo en que Analytics esté apagado: Installations y
App Check son canales distintos.

## Tipos que no se deben seleccionar con el código actual

| Tipo | Motivo |
| --- | --- |
| Videos o audio | La app no los solicita ni procesa |
| Ubicación precisa | No hay permiso ni API de ubicación |
| Contactos, SMS, llamadas o calendario | No hay permisos ni APIs correspondientes |
| Información de pago | La app permite anotar manualmente un método cerrado y referencia libre de un abono, pero no recibe credenciales, números de tarjeta/cuenta, tokens de billetera ni pagos de Google Play. Confirmar en el formulario vigente que esta anotación queda cubierta por «Otra información financiera» antes de excluir esta fila |
| Registros de fallos | El SDK de Crashlytics está incluido solo en `cloud`, pero su colección automática está forzada a `false`, no recibe excepciones manuales y no se sube el mapping; Analytics recibe únicamente eventos cerrados tras opt-in |
| Historial de búsqueda web o apps instaladas | No se accede a estos datos |
| ID de publicidad | Los permisos AD_ID/AdServices se eliminan y los consentimientos publicitarios se deniegan |

El correo introducido para invitar a un miembro sigue siendo “Dirección de correo electrónico”;
no se declara como acceso a la agenda porque el usuario lo escribe y la app no lee Contactos.
Aunque muchas razones sociales y RUC pertenecen a personas jurídicas, el modelo acepta un
proveedor persona natural; por eso el borrador conservador selecciona “Nombre” y “Otra
información”. La contraseña se entrega directamente a Firebase Authentication y no se guarda en
Room ni en DataStore. Play no ofrece una fila específica “contraseña”; la política pública sí
explica su tratamiento.

## Retención y eliminación que debe reflejar el formulario

- Datos locales: permanecen hasta que el usuario borra un borrador, aplica un control de
  imágenes, borra los datos de FacturaStock en Android o desinstala la app. Anular una compra
  conserva su historia.
- Fotos: la opción “tras OCR” borra el original solo cuando existe un snapshot OCR publicado que
  cubre las páginas activas; una versión de trabajo reducida puede permanecer hasta confirmar o
  hasta el mantenimiento. Las demás opciones son “tras confirmar”, 30 días, 90 días o conservar.
  Al elegir una política potencialmente destructiva la app confirma y ejecuta una pasada inmediata;
  el worker periódico cubre reinicios y vencimientos posteriores.
- Copias documentales: no se crean por defecto y exigen los dos opt-ins. Antes de borrar una imagen
  local que pudo respaldarse, Room conserva una intención `SYNC_DOCUMENT_PURGE`; solo el ACK remoto
  permite mostrar que la copia desapareció de Storage. Apagar el respaldo comercial no equivale a
  borrar nube, pero las purgas ya solicitadas pueden continuar por el canal mínimo de privacidad.
- Nube: compras, ventas, deudas, abonos y miembros no se eliminan automáticamente. Al solicitar la eliminación, el
  callable borra Auth y el árbol de un negocio si el solicitante es su propietario y único
  miembro; elimina también su membresía ajena y todas las invitaciones dirigidas a su correo
  verificado. Los datos locales no se borran.
- Negocios compartidos: las compras, ventas, deudas y abonos comerciales sobreviven para los demás miembros, pero las
  referencias UID de autoría en negocio, invitaciones, miembros, compras y `voidRecord` se
  sustituyen por `deleted-account`. Las invitaciones dirigidas al correo se borran solo cuando el
  token confirma que ese correo está verificado; cualquier invitación histórica que identifique al
  UID como quien aceptó o rechazó se borra completa. Una cuenta no verificada se puede borrar,
  pero su correo no se usa para eliminar invitaciones.
- Guardas de seguridad: se conserva indefinidamente un hash namespaced del UID, sin UID/email en
  claro, y los UUID aleatorios de los negocios borrados. Impiden que un token o una purga ya emitidos
  recreen datos o afecten una generación posterior con la misma identidad técnica. El hash temporal
  de email solo serializa invitaciones durante el barrido; se intenta eliminar al concluir, deja de
  bloquear a las 24 h y Firestore lo purga después mediante una política TTL asíncrona si ese
  borrado best-effort falla.
- Propiedad compartida: si el solicitante es propietario de un negocio con más miembros, la
  operación completa se rechaza antes de borrar y exige transferir la propiedad.
- Analytics: al revocar se detiene la colección y se restablece el estado local; los eventos ya
  recibidos siguen la retención configurada en Analytics. Registrar el plazo real en la política.
- Identificadores técnicos: cerrar la cuenta o revocar Analytics no borra automáticamente el
  Firebase Installation ID ni los tokens/metadatos de App Check o Play Integrity ya tratados.
  El plazo y el control de eliminación aplicables siguen pendientes de verificación externa en el
  proyecto/contratos de Google; no completar el formulario ni la política con un plazo supuesto.

## Verificación obligatoria en consolas

Guardar evidencia fechada, sin IDs de usuario ni contenido comercial, de:

1. Google Analytics → Administrar → Configuración de datos → Retención: plazo exacto.
2. Google Analytics → Configuración de la cuenta/propiedad → Uso compartido de datos: opciones
   activas y justificación.
3. Google Signals, Google Ads, BigQuery y cualquier integración/exportación: confirmar estado.
4. Firebase Authentication: solo proveedores realmente habilitados; el código usa
   correo/contraseña.
5. Firebase App Check: Play Integrity está instalado en release y todo callable declara
   `enforceAppCheck = true` fuera de Emulator Suite. Confirmar en el proyecto real, con evidencia
   sintética, que acepta un token legítimo y rechaza solicitudes sin token o con token inválido;
   verificar aparte el enforcement de Firestore y Storage en Firebase Console.
6. Firestore/Functions: región, operadores con acceso y política real de copias de seguridad.
7. Eliminación: prueba end-to-end desde la UI y desde la URL pública, incluida cuenta única,
   miembro no propietario y propietario bloqueado por otros miembros.
8. Firebase Installations y App Check/Play Integrity: plazo real y control disponible para FID,
   tokens y metadatos técnicos tras cerrar cuenta, borrar la app o revocar Analytics.

Si se cambia un SDK, payload, permiso, finalidad, configuración de Analytics o variante activa,
hay que volver a auditar y actualizar la declaración antes de subir el nuevo AAB.
