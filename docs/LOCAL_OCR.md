# OCR latino completamente local

## Límite de arquitectura

`InvoiceTextRecognizer` es el único puerto que conoce el flujo de reconocimiento. Recibe las
copias privadas `OcrImageFile` en el orden del manifiesto y devuelve `InvoiceTextDocument` con
páginas, bloques, líneas y elementos. `domain` no importa Android, Tasks ni ML Kit.

`MlKitInvoiceTextRecognizer`, dentro de `data/ocr`, usa el artefacto integrado
`com.google.mlkit:text-recognition:16.0.1`. El modelo latino forma parte del APK; no requiere
descarga, API key, Firebase ni conexión. El manifiesto elimina explícitamente el permiso
`INTERNET` añadido por dependencias transitivas.

## Geometría

Las coordenadas pertenecen al JPEG preprocesado, con origen arriba a la izquierda. Cada página
incluye sus dimensiones. Las cajas se intersectan con `0..width × 0..height` y se descartan si
quedan vacías; los cuatro puntos de perspectiva se acotan al último píxel válido. Por ello ningún
modelo de dominio puede construirse con geometría fuera de página.

La confianza se convierte a milésimas enteras `0..1000` y el ángulo horario a décimas de grado
`-1800..1800`. Los bloques no inventan confianza ni ángulo: ML Kit solo los ofrece para líneas y
elementos. Texto e idioma se conservan sin normalización semántica; `und` se representa como
idioma ausente.

## Ejecución y cancelación

Un cliente ML Kit se crea por lote, se reutiliza secuencialmente para todas sus páginas y se
cierra en `finally`. La Task se adapta mediante una suspensión cancelable; una respuesta tardía
no puede reanudar una coroutine cancelada.

`RunInvoiceOcrUseCase` valida la sesión y sus páginas, reclama atómicamente el borrador con un
`OcrRunId` nuevo y estado `OCR_PROCESSING`, y recién entonces preprocesa y reconoce. Informa solo
tres etapas respaldadas por trabajo real: preparar imágenes, leer páginas y unir/publicar el
resultado. Éxito, fallo y cancelación actualizan Room únicamente si siguen coincidiendo estado y
token y el borrador no fue confirmado. Esto protege frente a:

- cierre o avance del borrador durante OCR;
- cancelación tardía de un intento anterior;
- resultado tardío después de comenzar un reintento;
- borrado del borrador mientras el motor termina en segundo plano.

Reanudar o repetir un OCR interrumpido también compara el `OcrRunId` que vio la UI. Una acción
restaurada u obsoleta no puede limpiar el token de otro intento, ni devolver a `CAPTURED` un
borrador que ya avanzó o fue confirmado. Dos acciones `Start` consecutivas no reemplazan una
ejecución activa.

El documento completo se persiste por página con un codec binario versionado y hash. La cabecera,
todas las páginas y la transición a `OCR_READY` comparten una transacción Room; un reintento
reemplaza el snapshot por `draftId` y nunca agrega páginas a un resultado anterior. Tras muerte de
proceso, Inicio detecta `OCR_PROCESSING`, limpia solo el token observado mediante CAS y vuelve a
ejecutar el pipeline sobre los originales guardados.

No se persiste texto desde listeners ni se publican páginas parciales. Los errores almacenados
son códigos controlados; el texto OCR, rutas e identificadores no se registran en logs. La app
desactiva backup y no declara permiso de red, de modo que el snapshot permanece local.

## Verificación

- Pruebas JVM del mapper, geometría, fake determinista, orden y carreras de tokens.
- Prueba del puente Task que completa tarde después de cancelar.
- Prueba instrumentada multipágina con una factura sintética y sin permiso de red.
- Migraciones Room `3 → 4` (token) y `4 → 5` (snapshot recuperable), más pruebas del
  compare-and-set y del codec.
- `verifyLocalOcrConfiguration` comprueba que la dependencia bundled esté declarada, ausencia de
  API keys y aislamiento de imports ML Kit dentro de infraestructura.
