# Optimización y correcciones — 4 de septiembre de 2026

Esta ronda mantiene el flujo de venta por pasos y conserva la APK entregada para las pruebas del
usuario. La copia de `/Users/gustavo/Desktop/Appsf/FacturaStock.apk` se identifica por SHA-256
`6aecfd4f8b26d971770831b5ff6277dafae998f0e9b146cb8b79553732fd0c54`.

**Catálogo de ventas.** `SalesViewModel` prepara la lista ordenada al recibir cambios de catálogo
o existencias, en el dispatcher de cálculo. Buscar y editar el carrito reutilizan esa lista;
antes reconstruían y ordenaban todas las opciones. La actualización también conserva los códigos
recién asociados cuando la siguiente emisión del repositorio tarda. Cerrar la asociación cancela
su búsqueda pendiente. La regresión utiliza 1.001 productos, 30 cambios de búsqueda y cantidad, y
comprueba la reutilización y la actualización posterior de nombre, precio y stock. Es evidencia
de trabajo eliminado, no una medición de porcentaje de velocidad en un teléfono físico.

**Entrada de productos desde facturas.** Los campos conservan el texto escrito y comparten el
parser decimal del catálogo: `12,50` mantiene un precio de 1.250 céntimos y `1,5` una cantidad de
1,5. Una entrada inválida permanece visible con un error; no se convierte en otro importe ni se
descarta silenciosamente. Se rechazan cantidades o precios no positivos cuando se escriben.
El formulario y la confirmación de almacén toman una instantánea antes del trabajo de IO y
bloquean el doble envío. Las consultas de unidades y almacenes filtran activos antes de limitar
la página, evitando que varias filas archivadas oculten la opción válida.

**Búsqueda de productos de facturas.** Cada búsqueda cancela la anterior, conserva el debounce y
verifica su generación antes de publicar resultados. Un fallo de almacenamiento muestra un
reintento y no termina permanentemente la búsqueda. Cerrar el diálogo cancela incluso la consulta
pendiente; resultados tardíos no reemplazan los de un diálogo reabierto. La pantalla distingue
carga, error y ausencia de resultados.

**Existencias sincronizadas.** La aplicación del feed utiliza el saldo de cada evento en su orden
de secuencia aunque una venta ya esté confirmada localmente. Antes una compra pendiente podía
sobrescribir ese saldo y la venta posterior se omitía por estar `POSTED`. La validación e
idempotencia del grafo de venta siguen separadas y evitan duplicar venta, movimientos y auditoría.
Las regresiones usan Room y cubren una página, páginas separadas y reintento con cursor antiguo.
Durante la descarga de páginas el saldo representa el cursor procesado hasta ese momento.

**Respaldos de documentos.** Un finalizador fallido por revocación de membresía o error transitorio
conserva los bytes que otro intento pudo confirmar. El borrado exige comprobar una purga durable
del documento o el borrado/bloqueo del negocio. Se mantienen los errores de autorización y la
recuperación de reservas vencidas. Se comprobó una carrera real de dos uploads: el segundo
confirma, se revoca el miembro del primero y se libera su finalizador tardío; el documento
confirmado sigue existiendo.

**Compilación y arranque.** Los controles de código de Spark permanecen activos para todas las
compilaciones; las credenciales sólo se exigen al construir la variante Spark. Los contratos de
arranque y el materializador de perfiles esperan ahora el selector de ventas y Contado habilitado
y pulsable. La captura exige el serial explícito de un emulador dedicado y conserva evidencia
JUnit y hashes del par de perfiles. Los perfiles se generan desde ejecuciones reales, sin añadir
reglas observadas a mano. El guion de preparación también se ajustó al formulario actual:
introduce el nombre del negocio y usa el almacén predeterminado, sin buscar un campo retirado.
La captura `sales-20260904-02` pasó sus dos pruebas y produjo 2.250 reglas de Startup y 2.255 de
Baseline. La diferencia conserva los cinco CUJ manuales de normalización y lista. Profgen resolvió
las 2.250 reglas exactas contra el DEX del APK reconstruido; el control R8 también pasó.

**Validación de esta ronda.** Las pruebas focalizadas iniciales de ventas y matching pasaron.
La ejecución completa posterior pasó 1.454 pruebas JVM locales y 1.631 cloud. En el emulador
aislado `emulator-5556` pasaron 34 pruebas Android: pantalla de ventas, pantalla de matching y
aplicación del inventario compartido. Las pruebas documentales pasaron 30/30 en los emuladores
Firebase y otras seis regresiones locales del finalizador. También pasaron los contratos Ruby,
políticas de release y análisis de secretos. No se desplegó el backend.

Lint terminó con cero errores en local y cloud; conserva 66 advertencias en cada variante
(dependencias, recursos sin uso y convenciones, entre otras). En una copia temporal sin claves
Firebase pasaron el prebuild local y los dry runs de ambas variantes; el prebuild Spark rechazó
la configuración ausente como corresponde. El `local.properties` original se conservó intacto.
Los cinco archivos de matching se verificaron con ktlint 1.8.0 offline; el formato no cambió su
lógica ni archivos ajenos. Los logs están en
`build/reports/optimization/2026-09-04/` y `/tmp/facturastock-optimization-*`.

La comprobación final confirmó el mismo hash de la APK de `Appsf`. El emulador aislado se cerró
al terminar las pruebas; el emulador del usuario no se modificó durante esta ronda. La compilación
D8 conserva advertencias sobre métodos sintéticos de Startup, aunque la comprobación posterior
contra el DEX final resolvió todas las reglas exactas.

**Límites pendientes del análisis anterior.** Esta ronda no resuelve todavía la preservación y
resolución de unidades/monedas del OCR en matching, la confirmación atómica completa de ese flujo,
la recuperación del estado de matching tras muerte del proceso, el intento durable del checkout
cloud ante pérdida de ACK, ni las migraciones de inventario remoto heredado. Estos puntos siguen
en el análisis técnico; esta validación no equivale a afirmar que la app carece de errores.
