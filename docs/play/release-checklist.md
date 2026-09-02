# Checklist de release, pista interna y publicación

Marcar cada casilla únicamente con evidencia real. Las instrucciones no equivalen a acceso a
Play Console, a una firma válida ni a una instalación desde Google Play.

## 1. Identidad inmutable

- [ ] Confirmar que `com.facturastock.app` es el `applicationId` definitivo, pertenece al
  responsable y no está registrado por otra app. Después de crear la ficha o subir el primer
  artefacto no puede cambiarse.
- [ ] Confirmar que el nombre público es `FacturaStock` y que la cuenta de desarrollador muestra
  la misma entidad indicada en la política.
- [ ] Reservar un `versionCode` positivo, único y mayor que cualquier artefacto cargado; fijar el
  `versionName` que verá el tester. Actualizar en el environment protegido los máximos públicos
  `FACTURASTOCK_MAX_DISTRIBUTED_VERSION_CODE` y `FACTURASTOCK_MAX_DISTRIBUTED_ROOM_SCHEMA` con el
  estado de la pista más adelantada antes de despachar `signed-release`.
- [ ] Elegir un único artefacto de distribución. Para ofrecer cuenta/respaldo, usar
  `cloudRelease`; no cargar simultáneamente un `localRelease` con el mismo código de versión.
  En el código actual, `localRelease` tiene `PRIVACY_POLICY_URL` vacío y no está preparado para
  Play hasta corregir y verificar su enlace dentro de la app.
- [ ] Comprobar en el AAB final `applicationId`, `versionCode`, `versionName`, `minSdk`,
  `targetSdk` y permisos mediante Bundle Explorer, sin deducirlos solo del Gradle fuente.

## 2. Firma y AAB

- [ ] Crear **una sola vez** la clave de carga en una ruta absoluta fuera del repositorio. El
  comando pide de forma interactiva la contraseña del almacén, los datos del certificado y la
  contraseña de la clave; no escribir contraseñas en el comando ni en el historial:

  ```bash
  keytool -genkeypair -v -keystore /RUTA_EXTERNA/facturastock-upload.jks -storetype JKS -alias facturastock-upload -keyalg RSA -keysize 4096 -validity 10000
  ```

  En el último prompt se puede pulsar Intro para reutilizar la contraseña del almacén o definir
  una distinta; las variables de entorno deben coincidir con la elección. No usar la clave debug.
- [ ] Comprobar desde la raíz del checkout que el archivo es legible y que su ruta canónica no
  comienza por la ruta canónica del repositorio. Este control falla si está dentro del checkout:

  ```bash
  release_keystore="$(cd /RUTA_EXTERNA && pwd -P)/facturastock-upload.jks"
  repository_root="$(pwd -P)"
  test -r "$release_keystore"
  case "$release_keystore" in "$repository_root"/*) echo "ERROR: keystore dentro del repositorio" >&2; false;; esac
  ```

- [ ] Respaldar el keystore cifrado y sus credenciales en al menos dos custodias controladas. No
  regenerarlo para una versión posterior: Play reconoce la clave de carga registrada. Si se
  pierde, solicitar el restablecimiento de la clave de carga en **Integridad de la app** de Play
  Console y esperar su aprobación; no empezar a usar otra clave por cuenta propia.
- [ ] Activar Play App Signing y distinguir la clave de firma de aplicación de la clave de
  carga. Guardar ambas huellas SHA-256 para Firebase, Google Cloud y recuperación. Copiar la
  huella de **carga** registrada a la variable pública protegida de CI
  `FACTURASTOCK_UPLOAD_CERT_SHA256`; no calcularla del propio AAB durante el gate porque eso
  auto-confiaría en cualquier clave equivocada.
- [ ] Cargar los valores reales desde el gestor de secretos, nunca desde un archivo versionado,
  y exportar exactamente estas variables en la sesión protegida. Las cuatro variables de firma
  local usan el archivo anterior; CI recibe el mismo keystore como Base64 y lo materializa de
  forma temporal mediante `FACTURASTOCK_SIGNING_KEYSTORE_BASE64`:

  ```bash
  export FACTURASTOCK_SIGNING_STORE_FILE="$release_keystore"
  export FACTURASTOCK_SIGNING_STORE_PASSWORD
  export FACTURASTOCK_SIGNING_KEY_ALIAS="facturastock-upload"
  export FACTURASTOCK_SIGNING_KEY_PASSWORD
  export FACTURASTOCK_EXPECTED_UPLOAD_CERT_SHA256
  export FACTURASTOCK_VERSION_CODE
  export FACTURASTOCK_VERSION_NAME
  export FACTURASTOCK_FIREBASE_PROJECT_ID
  export FACTURASTOCK_FIREBASE_APPLICATION_ID
  export FACTURASTOCK_FIREBASE_API_KEY
  export FACTURASTOCK_FIREBASE_STORAGE_BUCKET
  export FACTURASTOCK_PRIVACY_POLICY_URL
  ```

  `export` no asigna los valores omitidos: antes deben existir en la sesión por el mecanismo
  seguro elegido. La huella es pública, pero en producción debe proceder de Play Console. Si
  todavía no existe ficha ni clave registrada, puede derivarse de una clave efímera solo para
  probar el pipeline; ese resultado debe rotularse `NO SUBIR` y no satisface la firma real:

  ```bash
  export FACTURASTOCK_EXPECTED_UPLOAD_CERT_SHA256="$(
    keytool -exportcert -rfc \
      -keystore "$FACTURASTOCK_SIGNING_STORE_FILE" \
      -storepass:env FACTURASTOCK_SIGNING_STORE_PASSWORD \
      -alias "$FACTURASTOCK_SIGNING_KEY_ALIAS" 2>/dev/null |
      openssl x509 -noout -fingerprint -sha256 |
      sed 's/^[^=]*=//'
  )"
  ```

  No pegar contraseñas ni secretos en este documento, en comandos compartidos ni en logs.
- [ ] Ejecutar los controles locales descritos en el README y comprobar que los seis gates
  críticos preceden al empaquetado.
- [ ] Con todas las variables no vacías y válidas, generar el AAB firmado mediante el flujo
  protegido de CI o ejecutar localmente el gate productivo completo:

  ```bash
  ./gradlew --no-daemon :app:packageCloudProductionRelease
  ```

- [ ] Preparar el JAR **standalone** oficial de bundletool y derivar un APK universal del AAB
  antes de retirar el keystore. No usar el JAR de la caché Gradle: no incluye `Main-Class` ni
  sus dependencias transitivas. El preparador fija bundletool 1.18.3 y exige SHA-256
  `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`:

  ```bash
  bundletool_dir="$(mktemp -d "${TMPDIR:-/tmp}/facturastock-bundletool.XXXXXX")"
  bash scripts/prepare-bundletool.sh \
    "$bundletool_dir/bundletool-all-1.18.3.jar"
  bash scripts/build-aab-validation-apk.sh \
    "$bundletool_dir/bundletool-all-1.18.3.jar" \
    app/build/outputs/bundle/cloudRelease/app-cloud-release.aab \
    app/build/outputs/apk-from-bundle/cloudRelease/app-cloud-release-universal.apk
  ```

  El helper ejecuta `bundletool validate` y firma el APK derivado con la misma clave de carga;
  guarda las contraseñas solo en archivos temporales con permisos privados y los elimina al
  terminar. No instala ni publica nada.
- [ ] Verificar el AAB y el APK derivado y crear un archivo de checksums nuevo; el script rechaza
  un destino ya existente para no sobrescribir evidencia:

  ```bash
  bash scripts/verify-release-artifacts.sh \
    app/build/outputs/bundle/cloudRelease/app-cloud-release.aab \
    app/build/outputs/apk-from-bundle/cloudRelease/app-cloud-release-universal.apk \
    "app/build/outputs/release-checksums-${FACTURASTOCK_VERSION_CODE}.sha256"
  ```

- [ ] Confirmar en la salida cerrada que AAB, APK universal y huella esperada tienen el mismo
  firmante; `apksigner --Werr --verbose --print-certs` no produjo advertencias; y el artefacto
  declara `com.facturastock.app`, la versión reservada, minSdk 26, compile/targetSdk 36,
  `FacturaStock`, iconos y `MainActivity`. El script inspecciona el APK real, no solo el manifest
  fusionado de Gradle.
- [ ] Revisar la allowlist exacta de permisos del artefacto. El candidato actual admite solo
  `CAMERA` como permiso runtime y contiene además los permisos normales cerrados de red,
  WorkManager y Firebase (`INTERNET`, estado de red, wake lock, boot, foreground service,
  Install Referrer, C2DM/GServices y el permiso de receptor dinámico propio). Cualquier alta o
  baja rompe el gate y exige revisar funcionalidad, ficha, Data Safety y política antes de
  actualizar conscientemente la allowlist.
- [ ] Confirmar las dos comprobaciones independientes de 16 KiB: `zipalign -c -P 16` sobre el
  APK universal y `p_align >= 16384` en cada segmento `PT_LOAD` de todas las `.so`
  `arm64-v8a`/`x86_64`. Una sola de ellas no demuestra compatibilidad. Verificar también el
  resultado de Play en App Bundle Explorer después del upload.
- [ ] Rechazar clave privada, keystore, token, configuración de emulador y cualquier dato
  fiscal/identificador real o no allowlisted. El gate recorre nombres y contenido descomprimido
  de todas las entradas AAB/APK y bloquea los cinco marcadores conocidos del Emulator Suite. El
  fixture canónico `[DEMO]`, ficticio y documentado, sí puede quedar compilado. La inspección
  automática no sustituye la revisión visual de capturas y fixture.
- [ ] Confirmar que la evidencia `signed-release/runtime-smoke` demuestra sobre el APK universal
  derivado: package/versión/certificado/bytes instalados, apertura, `force-stop`, reapertura y cero
  crash/ANR. Este smoke automatizado solo comprueba la derivación local: **no** satisface la
  instalación desde pista interna.
- [ ] Guardar el AAB firmado y su SHA-256 como artefactos restringidos; no versionarlos.

### Backend Firebase obligatorio antes de una pista

- [ ] En Firebase Console confirmar que el proyecto de producción y su app Android pertenecen al
  responsable, que el package registrado es exactamente `com.facturastock.app` y que
  `FACTURASTOCK_FIREBASE_PROJECT_ID`, `FACTURASTOCK_FIREBASE_APPLICATION_ID` y
  `FACTURASTOCK_FIREBASE_API_KEY`, además de `FACTURASTOCK_FIREBASE_STORAGE_BUCKET`, corresponden
  al mismo proyecto y app. Los regex del gate solo validan sintaxis, no esta correspondencia.
- [ ] Con credenciales autorizadas y el project ID explícito (nunca el default demo del
  `.firebaserc`), desplegar rules, índices/TTL, Storage y Functions:

  ```bash
  firebase deploy --project "$FACTURASTOCK_FIREBASE_PROJECT_ID" \
    --only firestore:rules,firestore:indexes,storage,functions
  ```

- [ ] Verificar en ese proyecto las políticas TTL de `accountDeletionEmailLocks.expiresAt`,
  `invitations.expiresAt`, `invitationRateLimits.expiresAt`,
  `documentUploadRateLimits.expiresAt` y `documentSyncOperations.expiresAt`; comprobar además que
  las reglas publicadas coinciden con el checkout y que todos los callables esperados están
  desplegados en `us-central1`.
- [ ] Habilitar y probar Firebase Authentication con correo/contraseña, registrar los certificados
  release/Play App Signing requeridos y configurar App Check con Play Integrity **antes** del
  despliegue de Functions. Los callables exigen token en todo runtime desplegado y solo lo omiten
  cuando Emulator Suite fija `FUNCTIONS_EMULATOR="true"`; probar que producción rechaza requests
  sin token o con token inválido. Verificar por separado el enforcement de Firestore y Storage en
  Firebase Console.
- [ ] Antes de cargar el AAB, ejecutar con cuentas y compras ficticias el recorrido completo en
  el backend real: crear/verificar cuenta → crear o aceptar negocio → publicar y recuperar una
  compra → anular/sincronizar → eliminar cuenta. Confirmar autorización por rol, cursor, TTL,
  anonimizado/borrado y cierre de Auth sin guardar UID, correo, token ni contenido comercial en
  la evidencia.

## 3. Bloqueos de privacidad y eliminación

- [x] Añadir en Ajustes una entrada visible que abra la política HTTPS configurada.
- [x] Añadir en Cuenta y respaldo una acción accesible “Eliminar mi cuenta”, con confirmación,
  error/reintento y limpieza local separada del callable remoto.
- [x] Corregir `deleteMyAccount` para borrar invitaciones de cualquier estado dirigidas al correo
  verificado y borrar completas las históricas donde el UID aceptó o rechazó. Anonimizar las
  referencias UID restantes en negocios compartidos, conservando las compras comerciales y el
  actor UUID interno del payload de anulación. Retener solo la guarda UID pseudónima de seguridad;
  el lock de email deja de bloquear en 24 h y tiene TTL asíncrono versionado. Una cuenta no
  verificada se elimina sin usar su correo para tocar invitaciones.
- [x] Rechazar antes de consultar árboles o escribir una eliminación que abarque más de 480
  negocios donde la cuenta figura como OWNER (`ACCOUNT_DELETION_SCOPE_TOO_LARGE`); no hay borrado
  parcial y la UI dirige
  al canal de soporte para reducir/segmentar el alcance.
- [ ] Publicar `privacy-policy-template-es.html` como HTML estático HTTPS, sin login,
  JavaScript obligatorio, geobloqueo, PDF ni permiso de edición pública.
- [ ] Publicar `account-deletion-template-es.html` y conectar un formulario/correo atendido que
  permita iniciar la solicitud fuera de la app y verificar la identidad sin pedir contraseña.
- [ ] Reemplazar todos los marcadores: `rg -n '\{\{' docs/play` debe devolver cero coincidencias
  en los HTML destinados a producción.
- [ ] Quitar de las copias publicadas los avisos `draft`, “Borrador de publicación” y “Plantilla
  no operativa” solo después de comprobar que cada afirmación coincide con el release.
- [ ] Probar desde una red y navegador no autenticados que ambas URLs responden HTTP 200, que el
  formulario recibe una solicitud sintética y que la app abre la política.
- [ ] Ejecutar eliminación end-to-end con cuentas sintéticas: usuario único, miembro de negocio
  ajeno, propietario único, propietario con otros miembros y sesión expirada.
- [ ] Confirmar que la eliminación no borra datos locales sin advertencia y que explica cómo
  borrarlos desde Android.

## 4. Data Safety y contenido de la aplicación

- [ ] Auditar Firebase/Analytics con `data-safety-cloud-es.md`; fijar retención exacta,
  opciones de uso compartido, Signals, Ads, BigQuery, proveedores Auth e integraciones.
- [ ] Completar Data Safety para la suma de todas las variantes/versiones activas, no solo para
  el flujo elegido por un usuario.
- [ ] Declarar cuenta creada dentro de la app y proporcionar la URL externa de eliminación.
- [ ] Declarar cifrado en tránsito solo después de comprobar que no existe endpoint HTTP de
  producción.
- [ ] Partir de **Sí†** para datos visibles a miembros de un negocio compartido. Cambiar la
  respuesta global/filas a “No compartido” solo con evidencia de que Play aplica la excepción de
  transferencia iniciada/esperada por el usuario al flujo completo y de que Google/Firebase
  cumple además la excepción de proveedor sin Signals, Ads ni otra finalidad.
- [ ] Confirmar externamente plazo y control de borrado para Firebase Installation ID y
  tokens/metadatos App Check/Play Integrity. Cerrar cuenta o revocar Analytics no los elimina
  automáticamente; no publicar un plazo inventado.
- [ ] Completar Acceso a la aplicación: indicar que el modo local/demo no requiere login y,
  para funciones cloud, introducir una cuenta de revisión dedicada solo en Play Console.
- [ ] Completar audiencia y contenido. Propuesta: adultos/empresa; no seleccionar infancia si
  no existe un análisis y diseño específico para Families.
- [ ] Completar anuncios = No; clasificación de contenido; categoría Empresa; declaraciones de
  permisos; prácticas gubernamentales/financieras solo según las preguntas reales mostradas.
- [ ] Revisar si Play clasifica el registro contable y la cuenta por cobrar como función financiera
  regulada. La app registra ventas fiadas y abonos manuales, pero no evalúa solvencia, presta dinero
  ni procesa/transfiere pagos electrónicos; la ficha debe explicarlo sin insinuar esas funciones.

## 5. Ficha y recursos sin datos reales

- [ ] Sustituir contacto y URLs en `store-listing-es-PE.md`.
- [ ] Copiar nombre, descripción breve y completa; comprobar de nuevo los contadores de Play.
- [x] Generar icono Play 512 × 512, gráfico 1 024 × 500 y ocho capturas sintéticas verticales;
  el paquete local pasa `ruby scripts/verify-play-assets.rb`.
- [ ] Subir icono Play 512 × 512, gráfico 1 024 × 500 y al menos dos capturas válidas; usar
  cuatro o más capturas verticales para la presentación recomendada.
- [ ] Revisar píxel por píxel que todas las capturas contienen solo el fixture `[DEMO]`, que el
  aviso “SIN VALOR TRIBUTARIO” no fue recortado y que no aparecen correo, notificaciones,
  ubicación, ruta, ID, RUC o comprobante de producción.
- [ ] Confirmar que icono, gráfico y capturas representan la UI real y no prometen consulta
  SUNAT, OCR perfecto, sincronización instantánea, certificación o funcionalidades futuras.
- [ ] Cargar correo de soporte atendido y verificar que recibe respuestas externas.

## 6. Crear la pista interna

Estas acciones requieren acceso autorizado a Play Console:

1. Crear la aplicación con idioma predeterminado español y tipo Aplicación.
2. Configurar **Integridad de la app → Firma de aplicaciones de Play** y registrar en
   Firebase/App Check la huella de **firma de aplicación** que usará Play. Mantenerla separada de
   la huella de **carga** que verifica el AAB antes del upload.
3. Abrir **Pruebas → Pruebas internas**, crear una versión y cargar el AAB firmado
   `cloudRelease`.
4. Resolver cada error/advertencia bloqueante del precheck; no omitir declaraciones pendientes.
5. Añadir una lista o grupo de testers sintéticos/autorizados, guardar y publicar únicamente en
   la pista interna.
6. Abrir el enlace de participación con una cuenta tester, aceptar la prueba e instalar desde
   Google Play. Un `adb install` local no demuestra este criterio.
7. Verificar en un teléfono real: instalador `com.android.vending`, aplicación y versión
   correctas. Extraer solo el `base.apk` instalado, ejecutar `apksigner verify --Werr
   --print-certs` y comparar su SHA-256 con la huella de **firma de aplicación** mostrada por
   Play, no con la clave de carga. Después recorrer inicio, demo, cámara, importación,
   confirmación, inventario, cuenta, respaldo, política y eliminación.
8. Ejecutar el escenario de 38 líneas sin información fiscal real y comprobar la actualización
   de stock y reconexión.
9. Con un segundo teléfono y el mismo negocio cloud, activar **«Respaldar registros en la nube»**
   en ambos, sincronizar catálogo/saldo, vender una unidad en cada dirección con **«Tipo de venta» →
   «Contado»** y comprobar que la venta completa y el saldo final convergen. Desde **Deudores →
   «Registrar deuda»**, comprobar que **«A crédito»** queda preseleccionado y registrar una venta con
   nombre y varios productos; comprobar en el otro teléfono la deuda, las líneas y el saldo, tocar
   allí **«Registrar pago»** para un pago parcial y verificar la convergencia del historial y saldo
   en el primero. Completar el saldo y
   comprobar el estado **Pagada**. Finalmente, partir de la misma versión en ambos e intentar dos
   pagos que compitan: solo uno puede confirmarse y el otro debe pedir sincronizar antes de
   reintentar. En modo avión, el negocio enlazado debe conservar el carrito, pero bloquear tanto el
   checkout como un pago nuevo.
10. Guardar evidencia sanitaria: fecha, modelo/API, versionCode/name, pista, SHA-256 del AAB y
   resultado. No guardar cuenta, correo, token, factura, RUC ni capturas de Play Console que los
   muestren.

## 7. Rollout gradual y hotfix

- [ ] Fijar el `versionCode` y SHA-256 del AAB atestado como identidad inmutable del rollout. No
  reconstruir el artefacto entre etapas.
- [ ] Seguir únicamente interna → 1% → 5% → 20% → 50% → 100%. Antes de cada transición ejecutar
  `scripts/verify-release-promotion.rb` con la ventana observada, health gate `PASS` y una referencia
  no sensible de aprobación; conservar su CSV sanitizado.
- [ ] Revisar por `versionCode` crashes/ANR, 5xx, fallos y antigüedad de outbox y conflictos durante
  la ventana definida en [`release-policy.json`](../../play/release-policy.json). La ausencia de
  acceso a estas métricas bloquea avanzar; no se sustituye por una revisión global de la app.
- [ ] Ante regresión, detener el rollout en Play. Un hotfix es otro AAB con `versionCode` mayor y
  conserva como mínimo el último esquema Room distribuido; nunca reinstala código que solo conozca
  un esquema anterior ni borra datos.
- [ ] Actualizar las dos anclas máximas del environment después de entregar una versión. Aplicar el
  contrato completo de [`rollout-and-hotfix-policy.md`](rollout-and-hotfix-policy.md).

## 8. Acceso condicionado a producción

- [ ] Comprobar en Play Console si la cuenta de desarrollador es **personal** y fue creada
  después del 13 de noviembre de 2023. No inferirlo a partir del repositorio.
- [ ] Si esa condición aplica, crear una **prueba cerrada**, mantener al menos 12 testers
  inscritos de forma continua durante 14 días y luego solicitar en Play Console acceso a
  producción. Una pista interna, aunque instale correctamente, no satisface por sí sola este
  requisito.
- [ ] Verificar que la cifra, duración y procedimiento sigan vigentes en la consola antes de
  planificar el lanzamiento; conservar evidencia no sensible de la elegibilidad y de la
  solicitud. Fuente oficial:
  [Requisitos de prueba para cuentas personales nuevas](https://support.google.com/googleplay/android-developer/answer/14151465?hl=es-419).

## 9. Condición de salida

La versión está “lista para pruebas internas” solo cuando el AAB firmado se haya cargado e
instalado desde la pista interna. Está “lista para publicación” solo después de cerrar además
ficha, política, Data Safety, eliminación, acceso, audiencia, clasificación, permisos y revisión
de producción. Sin acceso a Play Console o firma se entregan estas instrucciones y el artefacto
local verificable, pero nunca se afirma que hubo publicación.
