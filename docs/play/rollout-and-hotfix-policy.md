# Política de rollout y hotfix forward-only

Esta política no publica por sí sola. Separa la creación del candidato de cualquier mutación en
Play Console y obliga a que cada promoción use el mismo `versionCode` y el mismo SHA-256 del AAB
atestado.

## Secuencia permitida

La única ruta válida es:

`artifact-verified → internal → 1% → 5% → 20% → 50% → 100%`

Cada transición requiere aprobación humana, revisión de salud limitada al `versionCode` candidato
y la ventana mínima declarada en `play/release-policy.json`. No se reconstruye el AAB entre etapas.
Ante una regresión se detiene el rollout en Play; continuar con un hotfix significa construir otro
AAB con un `versionCode` superior.

El gate local comprueba una transición sin credenciales ni llamadas externas:

```bash
ruby scripts/verify-release-promotion.rb \
  --policy play/release-policy.json \
  --from internal \
  --to production-1 \
  --version-code "$VERSION_CODE" \
  --locked-version-code "$ATTESTED_VERSION_CODE" \
  --aab-sha256 "$AAB_SHA256" \
  --locked-aab-sha256 "$ATTESTED_AAB_SHA256" \
  --observed-hours 24 \
  --health-gate PASS \
  --approval-reference "$CHANGE_ID" \
  --evidence rollout-1.csv
```

El gate rechaza una ventana menor que la exigida para la etapa de origen, un health gate distinto
de `PASS`, saltos, otro AAB, otro `versionCode` o una referencia de aprobación inválida. La
evidencia dice explícitamente `publication_performed=false`. La promoción real requiere una persona
o servicio autorizado por Play y debe ocurrir únicamente después de que el gate pase.

## Anclas operativas

El environment protegido `android-production` mantiene dos variables públicas tomadas de la pista
más adelantada de Play, incluida la interna:

- `FACTURASTOCK_MAX_DISTRIBUTED_VERSION_CODE`
- `FACTURASTOCK_MAX_DISTRIBUTED_ROOM_SCHEMA`

Para una primera distribución sus valores son `0`. Después de entregar una versión, se actualizan
antes de despachar otro candidato. No son secretos, pero una revisión del environment debe confirmar
que coinciden con Play; el repositorio no puede descubrir ese estado sin autorización externa.

`signed-release` ejecuta `scripts/verify-release-forward-only.sh` antes de construir. El candidato
debe tener un `versionCode` estrictamente mayor y un esquema Room mayor o igual que el máximo ya
distribuido. También exige que `@Database`, el JSON más reciente y la historia `1..N` coincidan.

## Contrato de hotfix

Un hotfix parte del código compatible con el esquema más reciente ya distribuido. Se puede
revertir o backportear comportamiento, pero se conservan:

- la versión Room actual o una superior;
- todas las entidades necesarias para abrir esa versión;
- todos los JSON y `Migration` históricos;
- el mismo `applicationId` y las claves de firma correspondientes;
- los datos de usuario, sin `pm clear`, desinstalación ni fallback destructivo.

Si el defecto está en la base, la reparación es otra migración hacia adelante. Una app que ya abrió
Room `N` no recibe código que solo conozca `N-1`. El hotfix usa un `versionCode` nuevo, se vuelve a
validar como artefacto completo y comienza otra vez en pista interna; no se sustituye silenciosamente
el AAB de un rollout en curso.

Antes de 1% debe existir además una prueba de actualización con artefactos reales archivados:
instalar la versión anterior, sembrar datos sintéticos, actualizar al esquema distribuido y después
al hotfix sin borrar datos; comprobar apertura, `user_version`, `integrity_check`, claves foráneas,
compras, anulaciones y outbox. Obtener esos APK/AAB y el máximo realmente distribuido sí requiere
acceso autorizado a Play o al archivo restringido de releases.
