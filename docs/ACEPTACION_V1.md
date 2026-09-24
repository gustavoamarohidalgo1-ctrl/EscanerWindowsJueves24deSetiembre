# Matriz de aceptación — FacturaStock 1.0

Fecha de corte: **24 de agosto de 2026** (`America/Lima`). Los únicos estados usados son
**CUMPLE**, **NO CUMPLE** y **NO APLICA**. La evidencia reproducible de esta corrida está en
[`test-evidence/2026-08-24-fase-g-auditoria.md`](test-evidence/2026-08-24-fase-g-auditoria.md).
La ampliación posterior de ventas y lector HID tiene evidencia separada en
[`test-evidence/2026-08-24-ventas-lector-hid.md`](test-evidence/2026-08-24-ventas-lector-hid.md),
y la ampliación de búsqueda manual, precio y ganancias en
[`test-evidence/2026-08-24-ventas-ganancias.md`](test-evidence/2026-08-24-ventas-ganancias.md),
para no atribuir esos resultados a la corrida original de Fase G. La optimización posterior y su
comparación antes/después están en
[`test-evidence/2026-08-24-optimizacion-profunda.md`](test-evidence/2026-08-24-optimizacion-profunda.md).
El hardening posterior de Room v23, corrupción, WAL y reapertura está registrado en
[`test-evidence/2026-08-24-base-datos-solida.md`](test-evidence/2026-08-24-base-datos-solida.md).

> **Nota del 24 de septiembre de 2026.** La variante cloud (Firebase, Functions, Emulator Suite),
> la firma de release y la publicación en Google Play se retiraron: la app tiene un único flavor,
> `local`, y se instala directamente en la tablet del negocio con el procedimiento de
> [`RUNBOOK.md`](RUNBOOK.md). Las filas de esta matriz sobre sincronización, Firebase, AAB firmado,
> Play Console o política pública quedan como registro histórico de la corrida de agosto y ya no son
> criterios pendientes.

Un control automatizado puede acreditar código, datos y recorridos en AVD; no sustituye un teléfono
real, una persona siguiendo el manual, secretos productivos ni una instalación desde Play. Por eso
esta matriz no declara lista la versión aunque gran parte de Fase G ya esté implementada.

Responsables:

- **Desarrollo:** código, pruebas, CI y documentación técnica.
- **Piloto:** recorrido humano y dispositivo físico.
- **Publicación:** identidad legal, política pública, firma, Firebase productivo y Play Console.

## Resultado por prompt

| Prompt | Estado | Resultado verificable | Pendiente que impide cerrarlo |
| --- | --- | --- | --- |
| 44 — Unitarias y corpus dorado | CUMPLE | 7 fixtures separados en imagen sintética, OCR esperado y JSON esperado; reporte 7/7, 144/144 comprobaciones; 6.613/6.613 ejecuciones JVM y doble confirmación; 89,00 % de líneas en dominio crítico | El corpus JVM empieza en OCR esperado y no afirma medir universalmente ML Kit sobre los PNG; el smoke OCR Android es complementario |
| 45 — Room, repositorios y migraciones | CUMPLE | DAOs, Flow, claves, rollback, compra, movimientos, anulación y outbox cubiertos; 22/22 full paths v1…v22→v23 preservan el grafo operativo y ejecutan `integrity_check`; v23 añade WAL explícito, apertura fail-closed, preflight tenant e índices medidos | No existe restauración total tras desinstalación/pérdida; está declarada como límite, no como cumplimiento |
| 46 — UI y extremo a extremo | NO CUMPLE | 485/485 pruebas `local` en Pixel Tablet AVD; flujo feliz, doble toque, muerte de proceso, 38 líneas, borrosa, nuevo, duplicado, avión, reconexión, conflicto y sesión vencida están automatizados | Falta ejecutar el checklist manual y la factura de 38 líneas en un **teléfono real con cámara** |
| 47 — Rendimiento y accesibilidad | NO CUMPLE | La corrida postoptimización focal terminó 5/5 iteraciones sin ANR/OOM; sobretiempo p95 −20,18 % y tirón CPU máximo −56,04 %, con evidencia antes/después; accesibilidad/reflujo tienen cobertura Compose | El p95 CPU/overrun de la lista aún excede 16,67/0 ms; falta la serie física de 30 muestras y TalkBack/fuente 200 % en Pixel 6a |
| 48 — Observabilidad privada y CI | CUMPLE | Errores/auditoría tipados sin contenido fiscal, telemetría opt-in y colección inicial desactivada; gates de formato, análisis, Lint, pruebas, migraciones, reglas, secretos, npm/deltas de dependencias y release; Actions fijadas a SHA; README reproduce controles | `npm audit` conserva 5 avisos moderados transitivos (2 de runtime y 3 de tooling), declarados en problemas conocidos; hay 0 altos/críticos. El grafo Gradle alimenta Dependabot, pero no se presenta como escaneo histórico completo |
| 49 — AAB y Google Play | NO CUMPLE | Ambos AAB release y APK se construyen; manifiesto, assets, permisos, metadatos de telemetría y contratos de firma/configuración pasan con entradas de validación sintéticas | Faltan clave de carga y configuración Firebase reales, identidad/URLs legales, Play Console e instalación desde pista interna |
| 50 — Documentar, pilotear y aceptar | NO CUMPLE | README, manual, runbook, privacidad, Play, corpus y esta matriz están actualizados; los diez escenarios exigidos pasan por automatización | Falta que un desarrollador nuevo compile solo con README y que una persona complete el piloto solo con el manual |

## Ampliación — ventas, inventario y lector externo HID

Esta ampliación no cambia la decisión 1.0 ni convierte un emulador en certificación de hardware.
El alcance implementado es una venta con precio explícito, salida de inventario y entrada manual o
por lector que Android reconoce como teclado físico. El mismo contrato HID permite además consultar
un producto desde **Inventario → Existencias** sin mutarlo. La venta es local sin binding; hasta el
retiro de la variante cloud usaba autoridad cloud e inventario compartido cuando el negocio estaba
enlazado.

| Criterio | Estado | Evidencia | Responsable |
| --- | --- | --- | --- |
| Venta manual desde Inicio | CUMPLE | Ruta **Nueva venta**, búsqueda estrictamente por nombre con coincidencias exactas/similares de productos activos con existencia positiva, selección del almacén, precio sugerido del catálogo y segunda confirmación; una consulta numérica no activa código, SKU ni alias | Desarrollo |
| Precio de venta al recibir inventario | CUMPLE | Crear un producto desde la vinculación exige un precio positivo por unidad de inventario en la moneda configurada; un producto existente sin precio abre una edición CAS antes de vincular y un borrador legacy no lo omite | Desarrollo |
| Ganancias por producto | CUMPLE | La sección de Inventario compara precio de venta con costo promedio ponderado, muestra ganancia unitaria, margen y proyección sobre stock; estados sin precio, sin stock o con monedas no comparables no inventan S/ 0,00 | Desarrollo |
| Consulta de inventario por lector | CUMPLE | **Inventario → Existencias** exige elegir **Buscar** o **Escáner físico**; el lector hace lookup exacto en el negocio activo y abre `inventory/{productId}`. Las pruebas de ViewModel y Compose cubren éxito, exclusión por estado, código inválido/desconocido y fallo recuperable; el flujo no crea, asocia ni muta productos | Desarrollo |
| Código ya asociado agrega el producto recibido | CUMPLE | Búsqueda exacta por negocio, selección obligatoria de la ubicación con stock y una sola línea por producto–almacén | Desarrollo |
| Código desconocido se asocia explícitamente | CUMPLE | No se crea ni adivina un producto: se elige uno con existencia positiva; reemplazar otro código exige confirmación separada y, tras persistir, la UI no ofrece una cancelación falsa aunque falle el alta al carrito | Desarrollo |
| Checkout descuenta una sola vez | CUMPLE | `RoomSaleRepositoryTest` 13/13 e invariantes SQL ejecutados dentro de 485/485 Android: transacción atómica, CAS, stock insuficiente sin escrituras parciales, movimiento `SALE`, costo promedio intacto, rollback, idempotencia y reapertura tras checkpoint WAL | Desarrollo |
| Datos sobreviven a la actualización | CUMPLE | Room v22 conserva el par nullable de precio de venta y v23 no reinterpreta filas; 22/22 full paths v1…v22→v23 conservan compras, saldos, movimientos, ventas y operaciones pendientes. El caso v22→v23 verifica además rollback e índices | Desarrollo |
| Protocolo HID tipo teclado | CUMPLE | 17/17 pruebas JVM de Enter/Tab, timeout, cambio de dispositivo, repetición, límite 128, reset y propagación de navegación con buffer vacío | Desarrollo |
| Modelo físico de lector USB/Bluetooth certificado | NO CUMPLE | No se conectó un lector real en esta ampliación; debe ejecutar el checklist de modelo, adaptador OTG, distribución y sufijo | Piloto |
| Códigos por cámara/ML Kit | NO APLICA | Fuera del alcance elegido: Ventas e Inventario usan HID/*keyboard wedge* y conservan un modo manual separado | Desarrollo |
| Sync de ventas e inventario entre dispositivos | CUMPLE | `postSale` transaccional, feed contiguo, materialización Room, bootstrap legacy, idempotencia y recuperación post-ACK; Emulator Suite focal 10/10 y mappers cloud JVM cubiertos | Desarrollo |
| Exportación integral y anulación de ventas | NO APLICA | El JSON contable v4 no exporta cabeceras/líneas, deudas ni abonos y una venta publicada no se revierte desde esta versión; Firebase compartido no equivale a restauración integral | Desarrollo |
| Comprobante fiscal, clientes, pagos y caja | NO APLICA | La ampliación registra precio final, total y salida de inventario; no emite a SUNAT ni implementa un POS fiscal/financiero | Desarrollo |

El contrato y el checklist físico están en
[`SALES_AND_BARCODE_SCANNER.md`](SALES_AND_BARCODE_SCANNER.md). Hasta completar la fila roja, solo
se acredita compatibilidad con el protocolo HID, no con una marca o modelo comercial concreto.

## Prompt 44 — calidad del parser y dominio crítico

| Criterio | Estado | Evidencia | Responsable |
| --- | --- | --- | --- |
| `./gradlew test` no depende de internet, emulador ni hora real | CUMPLE | `./gradlew --offline --no-daemon --max-workers=1 test`: 6.613 ejecuciones JUnit en seis variantes, 0 fallos, 0 errores, 0 omitidas | Desarrollo |
| Fixtures con imagen, OCR esperado y JSON esperado | CUMPLE | `app/src/test/resources/golden-corpus/{cases,expected-ocr,expected-results}`; siete PNG distintos y contratos separados | Desarrollo |
| Limpia, inclinada, borrosa, multilínea, sin IGV y decimales regionales | CUMPLE | [`GOLDEN_CORPUS.md`](GOLDEN_CORPUS.md) y reporte generado `app/build/reports/golden-corpus/golden-corpus-report.md` | Desarrollo |
| Positivos, negativos y límites de reglas críticas | CUMPLE | Money, Quantity, normalización, RUC, cabecera, líneas, totales, confianza, matching, unidades, costo promedio, duplicados, idempotencia y anulación están en la suite JVM | Desarrollo |
| Regresión de doble confirmación | CUMPLE | Pruebas de use case/ViewModel y recorrido Android exigen un solo commit ante doble toque | Desarrollo |
| Cobertura acordada ≥80 % | CUMPLE | Kover: 10.888/12.234 líneas, **89,00 %**, en `com.facturastock.app.domain.*` | Desarrollo |

El corpus reporta exactitud cerrada contra siete contratos sintéticos. No presenta esos siete casos
como una tasa universal del OCR ni de facturas peruanas reales.

## Prompt 45 — persistencia y actualización

| Criterio | Estado | Evidencia | Responsable |
| --- | --- | --- | --- |
| Migraciones preservan datos | CUMPLE | `MigrationTestHelper` recorre los 22 orígenes v1…v22→v23; cada fixture ejecuta `foreign_key_check` e `integrity_check`. El caso v22→v23 conserva filas, aborta antes del DDL ante un grafo tenant inválido y revierte el DDL completo ante un fallo tardío | Desarrollo |
| IDs, centavos, escalas, saldos y estados sobreviven | CUMPLE | La instrumentación compara identidad, cuatro importes monetarios, estado `POSTED`, payload y saldos recalculados | Desarrollo |
| Operación pendiente continúa | CUMPLE | Tras migrar, los repositorios reales enlazan, reclaman y completan la outbox conservando su idempotencia | Desarrollo |
| Fallo transaccional revierte todo | CUMPLE | Pruebas de compra, anulación, CAS y rollback en Room; una segunda finalización es rechazada | Desarrollo |
| Cambio de esquema sin migración bloquea CI | CUMPLE | Esquemas append-only y gates `verifyRoomSchemaPolicy`/historial; no existe `fallbackToDestructiveMigration` | Desarrollo |

## Prompt 46 — recorridos Android

| Criterio | Estado | Evidencia | Responsable |
| --- | --- | --- | --- |
| Flujo feliz termina en stock correcto | CUMPLE | Suite conectada `local` en Pixel Tablet/Android 15: 485/485; incluye E2E de factura demo, precio staged y movimientos | Desarrollo |
| Doble toque registra una vez | CUMPLE | Pruebas de confirmación y anulación con doble envío | Desarrollo |
| Rotación/muerte del proceso conserva revisión | CUMPLE | Pruebas instrumentadas de restauración de revisión y estado guardado | Desarrollo |
| Factura demo de 38 líneas en emulador/fake | CUMPLE | Parser, E2E Compose y detalle verifican 38 líneas y ajuste exacto de S/0,03 | Desarrollo |
| Factura demo y checklist en teléfono real | NO CUMPLE | No se ejecutó un teléfono físico en esta auditoría | Piloto |

## Prompt 47 — rendimiento y accesibilidad

| Criterio | Estado | Evidencia | Responsable |
| --- | --- | --- | --- |
| Sin ANR ni OOM en la serie diagnóstica | CUMPLE | El benchmark focal postoptimización completó 5/5 iteraciones y 743 frames sin ANR/OOM; la auditoría integral previa conserva los otros CUJ y su política StrictMode | Desarrollo |
| Lista de 100 líneas fluye dentro del presupuesto | NO CUMPLE | Pixel Tablet AVD postoptimización: p95 `frameDurationCpuMs` 21,668 ms (límite 16,67) y `frameOverrunMs` 5,866 ms (límite 0); el máximo CPU bajó 56,04 % | Desarrollo |
| Presupuesto p95 medido y documentado | CUMPLE | La evidencia de optimización conserva método, muestras, hashes y tabla antes/después; no convierte las cinco muestras AVD en aceptación física | Desarrollo |
| Aceptación física en dispositivo de referencia | NO CUMPLE | Falta Pixel 6a físico, 30 iteraciones, batería/almacenamiento y estado térmico predefinidos | Piloto |
| Flujo principal con TalkBack y fuente 200 % | NO CUMPLE | Las pruebas Compose de semántica/reflujo pasan, pero falta el checklist humano físico | Piloto |

Un rojo de AVD es una señal de regresión que debe investigarse; un verde de AVD tampoco aprobaría la
serie física. Ambos límites están definidos en
[`ANDROID_PERFORMANCE_ACCESSIBILITY.md`](ANDROID_PERFORMANCE_ACCESSIBILITY.md).

## Prompt 48 — privacidad y bloqueo de builds

| Criterio | Estado | Evidencia | Responsable |
| --- | --- | --- | --- |
| Artefactos publicables no contienen datos sensibles | CUMPLE | Verificadores de logging, redacción, staging allowlist y escaneo descomprimido de APK/AAB pasan. UTP/logcat crudos pueden incluir rutas privadas del runner y no se publican | Desarrollo |
| Desactivar telemetría no rompe funciones | CUMPLE | Analytics/Crashlytics comienzan desactivados por manifiesto; las pruebas cubren opt-out y la app conserva sus funciones | Desarrollo |
| Lint/prueba/migración/regla bloquea AAB | CUMPLE | El workflow encadena los gates antes del job firmado; `actionlint` pasa | Desarrollo |
| README reproduce localmente los controles | CUMPLE | Comandos de análisis, test, Emulator Suite, benchmark, firma y verificación documentados | Desarrollo |
| Reglas y Functions pasan en Emulator Suite | CUMPLE | 111/111, 0 fallos y 0 omitidas con Auth, Firestore, Functions y Storage; incluye PRODUCT v1/v2 y aislamiento del precio | Desarrollo |
| Escaneo de dependencias dentro del alcance declarado | CUMPLE | `npm audit` bloquea alto/crítico; Dependency Review bloquea deltas de PR y el grafo Gradle habilita alertas continuas de Dependabot. No se afirma un escaneo local completo del árbol Android histórico | Desarrollo |

## Prompt 49 — candidato de publicación

| Criterio | Estado | Evidencia | Responsable |
| --- | --- | --- | --- |
| `bundleRelease` genera AAB de validación | CUMPLE | `app-local-release.aab` y `app-cloud-release.aab` se generaron junto a sus APK release | Desarrollo |
| AAB productivo firmado con secretos válidos | NO CUMPLE | Los contratos se validaron con keystore/configuración sintéticos externos al repo; no se usó la clave de carga real | Publicación |
| AAB instalado desde pista interna | NO CUMPLE | No hubo acceso a Play Console | Publicación |
| Ficha y capturas no contienen datos reales | CUMPLE | El verificador acredita formato/dimensiones y textos; la inspección visual separada de las 8 capturas confirma contenido `[DEMO]` sintético. El script no inspecciona píxeles | Publicación |
| Data Safety/política coinciden con la app desplegada | NO CUMPLE | Los borradores coinciden con el código, pero faltan URLs públicas, identidad/plazos legales y verificar Firebase productivo | Publicación |
| SDK y permisos | CUMPLE | `targetSdk 36`, `minSdk 26`, cámara opcional y allowlist cerrada de permisos en artefacto release | Desarrollo |

## Prompt 50 — entrega y aceptación de extremo a extremo

| Criterio | Estado | Evidencia | Responsable |
| --- | --- | --- | --- |
| README cubre setup, pruebas, migraciones, AAB y recuperación | CUMPLE | [`../README.md`](../README.md) | Desarrollo |
| Manual cubre todo el flujo de negocio y privacidad | CUMPLE | [`MANUAL_USUARIO.md`](MANUAL_USUARIO.md) | Desarrollo |
| Runbook cubre respaldo, incidentes, rollback y conocidos | CUMPLE | [`RUNBOOK.md`](RUNBOOK.md) | Desarrollo |
| Desarrollador nuevo compila solo con README | NO CUMPLE | Esta auditoría compiló el proyecto, pero no fue ejecutada por un desarrollador nuevo siguiendo solo el README | Piloto |
| Usuario completa el flujo solo con manual | NO CUMPLE | No se realizó sesión humana en teléfono físico | Piloto |
| 38 líneas, S/0,03, borrosa, nuevo, duplicado, avión, reconexión, conflicto, anulación y sesión vencida | CUMPLE | Los diez escenarios tienen pruebas JVM/Android actuales; la ruta Firebase `UNAUTHENTICATED` además tiene siete pruebas directas | Desarrollo |
| Matriz declara todo fallo o control pendiente | CUMPLE | Este documento conserva los rojos de rendimiento y todos los gates humanos/productivos | Desarrollo |

## Criterios que no aplican

| Criterio | Estado | Motivo |
| --- | --- | --- |
| Restaurar el libro local completo desde cloud | NO APLICA | Fuera del alcance 1.0: el espejo compacto de compras es diagnóstico y no reconstruye su grafo; el feed autoritativo sí materializa en Room catálogo, inventario, ventas, deudas y pagos compartidos, pero no restaura todo el dispositivo |
| Nube/cuenta en flavor `local` | NO APLICA | La variante elimina INTERNET por diseño |
| Subida de fotos sin consentimiento | NO APLICA | La app no tiene permiso de internet; el respaldo documental opcional se retiró con la variante cloud |
| Copia automática Android | NO APLICA | `allowBackup="false"` |
| Rollback descendente de Room | NO APLICA | Las migraciones son solo hacia adelante; una corrección crea una versión superior |
| Publicidad | NO APLICA | La app no contiene publicidad |

## Decisión 1.0

**NO CUMPLE la aceptación final; la versión 1.0 no se declara aceptada.**

Para cambiar esa decisión hacen falta tres evidencias nuevas, no más código supuesto:

1. Continuar la optimización del p95 de la lista y ejecutar 30 muestras en el Pixel 6a físico; los
   tirones extremos ya mejoraron, pero los dos límites de frame siguen rojos en AVD.
2. Completar en ese teléfono el checklist TalkBack/fuente 200 %, cámara real y el guion humano de
   [`PILOTO_CERRADO.md`](PILOTO_CERRADO.md).
3. Instalar el APK optimizado en la tablet del negocio con el procedimiento de
   [`RUNBOOK.md`](RUNBOOK.md) —respaldo `run-as` previo incluido— y registrar versión, dispositivo
   y resultado. Los antiguos puntos de firma, Firebase, URLs legales y pista interna de Play dejaron
   de aplicar al retirarse la publicación.
