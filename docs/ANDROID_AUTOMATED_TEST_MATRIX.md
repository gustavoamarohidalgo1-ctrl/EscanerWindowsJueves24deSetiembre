# Matriz de automatización Android

Esta matriz relaciona cada riesgo del recorrido de compras con una prueba objetiva. Todos los datos
son sintéticos; ninguna suite requiere RUC, correo, comprobante o credencial de una persona real.

| Riesgo/recorrido | Evidencia automatizada |
| --- | --- |
| Onboarding | `OnboardingNavigationTest` completa el formulario por tags, llega a Inventario y conserva ese destino al recrear la Activity. |
| Cámara/importación | `SourceScreenTest` recorre ambas fuentes; `CaptureScreenTest` inyecta el slot de cámara y valida captura, permiso y error; `DemoInvoiceEndToEndTest` inyecta bytes JPEG y el importador. |
| Procesamiento y OCR fallido | `OcrScreenTest` recorre etapas, cancelación, fallo, reintento y entrada manual; `TestOcrModule` sustituye ML Kit en los recorridos Hilt. |
| Imagen borrosa/exposición | `PreviewScreenTest.qualityWarningExplainsBlurAndExposureAndOffersContinueOrRetake`. |
| Cabecera y líneas | `InvoiceHeaderReviewScreenTest` e `InvoiceLineReviewScreenTest`; el E2E persiste y vuelve a leer ambas revisiones. |
| Matching, producto nuevo | `ProductLinkingScreenTest`; el E2E valida 36 enlaces SKU, una ambigüedad y un alta de producto. |
| Redondeo | `PurchaseSummaryScreenTest` y el E2E exigen aceptación y motivo para la diferencia de S/ 0.03. |
| Duplicado | `PreparationScreenTest` cubre bloqueo exacto, override con motivo y advertencia probable. |
| Confirmación y doble toque | El E2E lanza dos confirmaciones concurrentes y exige una `Posted`, una `AlreadyPosted`, una compra, una auditoría y una operación outbox. |
| Historial e inventario | `PurchaseScreensTest`, `InventoryScreensTest` y el E2E contrastan 38 líneas/movimientos y saldo por producto contra el libro de movimientos. |
| Rotación/muerte de proceso | El E2E reconstruye repositorios y después cierra/reabre Room; verifica cabecera, líneas, enlaces e IDs persistidos. `NavigationRecreationTest` recrea la Activity durante la revisión. `DraftFlowViewModelTest` conserva en `SavedStateHandle` el ID pendiente y lo reanuda desde Room tras recrear el proceso. |
| Creación/descarte durable | `NavigationRecreationTest` comprueba con Room real que Compras crea el borrador `CREATED` antes de abrir Source y lo elimina antes de abandonar el flujo. `DraftFlowViewModelTest` verifica además que el doble toque solo crea o descarta una vez. |
| Modo avión/reconexión | La app no usa red, así que el modo avión no cambia ningún recorrido. Para el código de outbox compartido, `FirebaseTestDoublesContractTest` demuestra que el corte de `FakeFirebaseConnectivity` no consume los guiones y que la reconexión los reanuda, y `ProcessPurchaseBackupOutboxUseCaseTest` cubre una caída de transporte a mitad del lote sin incrementar intentos ni consumir la siguiente operación. |
| Conflicto | `ProcessPurchaseBackupOutboxUseCaseTest` clasifica `Conflict` como `CONFLICT` y conserva la operación local. La pantalla Sincronización que lo resolvía se retiró con la variante cloud. |
| Transporte ausente | `PurchaseBackupSyncWorkerTest` comprueba que sin transporte configurado no se encola ningún trabajo; `OfflineRoomRestartRepositoryTest` confirma que el paquete no declara `INTERNET` y que la outbox sobrevive a cerrar y reabrir Room. |
| Factura demo de 38 líneas | `DemoInvoiceEndToEndTest` usa la factura sintética canónica, Room y repositorios productivos. |

La instrumentación de CI está dividida en dos conjuntos disjuntos y exhaustivos sobre API 35:
`room-migrations` incluye el paquete `com.facturastock.app.data`, mientras `android-ui-e2e` lo
excluye y ejecuta todo el resto. Así, cada prueba corre una vez y cualquier clase Compose/navegación
nueva entra automáticamente, sin mantener una lista manual. Los contratos de los fakes de
conectividad corren en el job unitario de `local`, el único flavor. El antiguo carril
`android-firebase-e2e` contra Emulator Suite se retiró junto con la variante cloud.

La matriz pequeña `android-sdk-smoke`, posterior a `quality`, cubre además los extremos declarados
sin duplicar esas suites: en API 26 ejecuta `DurablePrivateFilePublicationTest`, incluida la
regresión que abre y sincroniza un archivo sin depender de `O_CLOEXEC`; en API 36 ejecuta solo
`HiltUdfRuntimeTest.mainActivityProtectsSensitiveScreensFromScreenshots`, que construye el grafo
Hilt y arranca `MainActivity`. Ambos resultados son requisitos de `package-validation`.

La cámara física, rotación del sensor, suspensión real del proceso y radios del teléfono se validan
con [ANDROID_E2E_DEVICE_CHECKLIST.md](ANDROID_E2E_DEVICE_CHECKLIST.md). Esa evidencia nunca debe
reemplazarse por capturas fabricadas o por un emulador.
