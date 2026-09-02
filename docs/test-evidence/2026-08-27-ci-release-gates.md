# Evidencia — gates CI y empaquetado release

Fecha: **27 de agosto de 2026** (`America/Lima`). Esta evidencia cubre únicamente los contratos de
CI/release modificados en esta revisión. No afirma haber ejecutado los nuevos emuladores API 26/36,
un benchmark nuevo ni un candidato firmado.

## Gate de presupuestos Macrobenchmark

`scripts/macrobenchmark-budgets.json` versiona los quince límites de
`ANDROID_PERFORMANCE_ACCESSIBILITY.md`. El resumidor exige el archivo, calcula p95 nearest-rank,
falla si falta una métrica presupuestada y devuelve código distinto de cero ante cualquier exceso.

Comprobaciones ejecutadas:

```text
ruby scripts/test-summarize-macrobenchmark.rb
Macrobenchmark gate test: presupuesto, p95, RSS, métricas ausentes y CI verificados.
```

Como regresión, el gate se ejecutó sobre el JSON histórico
`benchmark/build/prompt47-evidence/benchmarkData-api37.json`; terminó con código 1 y rechazó cuatro
valores ya rojos. Esto demuestra el bloqueo, pero no constituye una medición actual:

```text
bitmapOcrParserAndSyncPipeline/parserFirstMs: 380,482 ms > 250 ms
cameraFirstFrame/cameraCaptureFirstMs: 3.354,971 ms > 2.000 ms
hundredLineListScroll/frameDurationCpuMs: 59,938 ms > 16,67 ms
hundredLineListScroll/frameOverrunMs: 65,496 ms > 0 ms
```

## Calidad del candidato productivo

`verifyCloudProductionReleaseQuality` depende de `lintCloudRelease` y
`testCloudReleaseUnitTest`; `packageCloudProductionRelease` depende a su vez de ese gate. La
ejecución real fue:

```text
./gradlew --no-daemon spotlessCheck ciStaticAnalysis \
  :app:verifyCloudProductionReleaseQuality
BUILD SUCCESSFUL in 4m 21s
```

El grafo se verificó además sin materializar firma ni candidato:

```text
./gradlew --no-daemon --dry-run :app:packageCloudProductionRelease
:app:lintCloudRelease SKIPPED
:app:testCloudReleaseUnitTest SKIPPED
:app:verifyCloudProductionReleaseQuality SKIPPED
:app:packageCloudProductionRelease SKIPPED
```

## Extremos de SDK y contratos de CI

El job matricial `android-sdk-smoke` declara:

- API 26: `DurablePrivateFilePublicationTest`;
- API 36: `HiltUdfRuntimeTest#mainActivityProtectsSensitiveScreensFromScreenshots`.

La suite exhaustiva permanece en API 35. La configuración y el bytecode de instrumentación se
validaron, sin presentar eso como ejecución en ambos emuladores:

```text
bash scripts/run-actionlint.sh
# exit 0; actionlint 1.7.12 verificado por SHA-256

./gradlew --no-daemon :app:assembleLocalDebugAndroidTest
BUILD SUCCESSFUL in 1m 17s
```

`ruby scripts/test-release-contracts.rb` también terminó correctamente y exige que el workflow
conserve ambos API, las dos pruebas seleccionadas y el gate de calidad productiva.
