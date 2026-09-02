#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "open3"
require "rbconfig"
require "tmpdir"

ROOT = File.expand_path("..", __dir__)
SUMMARIZER = File.join(ROOT, "scripts", "summarize-macrobenchmark.rb")
CANONICAL_BUDGETS = File.join(ROOT, "scripts", "macrobenchmark-budgets.json")
CI_WORKFLOW = File.join(ROOT, ".github", "workflows", "ci.yml")

def assert(condition, message)
  raise "Fallo del gate p95: #{message}" unless condition
end

def benchmark_document(samples)
  {
    "context" => { "compilationMode" => "run-from-apk" },
    "benchmarks" => [
      {
        "className" => "SyntheticBenchmark",
        "name" => "syntheticP95",
        "metrics" => {
          "durationMs" => { "runs" => samples },
          # Los p95 marginales serían 100 + 100 = 200; el RSS correcto empareja cada iteración.
          "memoryRssAnonMaxKb" => { "runs" => [100, 1, 100, 1, 100, 1, 100, 1, 100, 1] },
          "memoryRssFileMaxKb" => { "runs" => [1, 100, 1, 100, 1, 100, 1, 100, 1, 100] },
        },
        "sampledMetrics" => {},
      },
    ],
  }
end

def budget_document(duration_max: 10, include_missing_metric: false)
  budgets = [
    {
      "benchmark" => "syntheticP95",
      "metric" => "durationMs",
      "maxP95" => duration_max,
      "unit" => "ms",
    },
    {
      "benchmark" => "syntheticP95",
      "metric" => "memoryRssTotalPairedMaxKb",
      "maxP95" => 101,
      "unit" => "KiB",
    },
  ]
  if include_missing_metric
    budgets << {
      "benchmark" => "syntheticP95",
      "metric" => "missingMs",
      "maxP95" => 1,
      "unit" => "ms",
    }
  end
  {
    "schemaVersion" => 1,
    "profile" => "synthetic-ci",
    "budgets" => budgets,
  }
end

Dir.mktmpdir("facturastock-p95-") do |temporary_dir|
  source = File.join(temporary_dir, "benchmarkData.json")
  budgets = File.join(temporary_dir, "budgets.json")
  File.write(source, JSON.generate(benchmark_document((1..10).to_a)))
  File.write(budgets, JSON.generate(budget_document))

  stdout, stderr, status = Open3.capture3(
    { "FACTURASTOCK_MIN_BENCHMARK_SAMPLES" => "10" },
    RbConfig.ruby,
    SUMMARIZER,
    source,
    budgets,
    chdir: ROOT,
  )
  assert(status.success?, "rechazó diez muestras: #{stdout}#{stderr}")
  assert(stdout.include?("Modo de compilación reportado por AndroidX: `run-from-apk`."),
         "el resumen oculta el modo de compilación realmente reportado")
  assert(stdout.include?("Perfil de presupuestos: `synthetic-ci`."),
         "el resumen oculta el perfil de presupuestos")
  assert(stdout.include?("| `syntheticP95` | `durationMs` | 10 | 5 | 10 | 10 | ms | ≤ 10 | CUMPLE |"),
         "el resumen no conserva nearest-rank p50/p95")
  assert(stdout.include?("| `syntheticP95` | `memoryRssTotalPairedMaxKb` | 10 | 101 | 101 | 101 | KiB | ≤ 101 | CUMPLE |"),
         "el resumen no empareja RSS anon + file por iteración")
  assert(stdout.include?("Gate de macrobenchmark CUMPLE: 2/2 presupuestos."),
         "el resumen no cierra explícitamente el gate")

  File.write(budgets, JSON.generate(budget_document(duration_max: 9.999)))
  stdout, stderr, status = Open3.capture3(
    { "FACTURASTOCK_MIN_BENCHMARK_SAMPLES" => "10" },
    RbConfig.ruby,
    SUMMARIZER,
    source,
    budgets,
    chdir: ROOT,
  )
  assert(!status.success?, "aceptó un p95 por encima del presupuesto")
  assert(stdout.include?("| ≤ 9.999 | NO CUMPLE |"),
         "la tabla no marca la métrica fuera de presupuesto")
  assert(stderr.include?("syntheticP95/durationMs: p95=10 ms > 9.999 ms"),
         "el fallo no identifica valor y presupuesto")

  File.write(budgets, JSON.generate(budget_document(include_missing_metric: true)))
  _stdout, stderr, status = Open3.capture3(
    { "FACTURASTOCK_MIN_BENCHMARK_SAMPLES" => "10" },
    RbConfig.ruby,
    SUMMARIZER,
    source,
    budgets,
    chdir: ROOT,
  )
  assert(!status.success?, "aceptó la ausencia de una métrica presupuestada")
  assert(stderr.include?("falta la métrica presupuestada syntheticP95/missingMs"),
         "el fallo no identifica la métrica presupuestada ausente")

  File.write(source, JSON.generate(benchmark_document((1..9).to_a)))
  File.write(budgets, JSON.generate(budget_document))
  _stdout, stderr, status = Open3.capture3(
    { "FACTURASTOCK_MIN_BENCHMARK_SAMPLES" => "10" },
    RbConfig.ruby,
    SUMMARIZER,
    source,
    budgets,
    chdir: ROOT,
  )
  assert(!status.success?, "aceptó una serie truncada de nueve muestras")
  assert(stderr.include?("conserva 9 muestras; se requieren al menos 10"),
         "el error no explica la serie truncada")

  document_without_compilation_mode = benchmark_document((1..10).to_a)
  document_without_compilation_mode.delete("context")
  File.write(source, JSON.generate(document_without_compilation_mode))
  _stdout, stderr, status = Open3.capture3(
    { "FACTURASTOCK_MIN_BENCHMARK_SAMPLES" => "10" },
    RbConfig.ruby,
    SUMMARIZER,
    source,
    budgets,
    chdir: ROOT,
  )
  assert(!status.success?, "aceptó métricas sin contexto de compilación")
  assert(stderr.include?("no declara contexto de compilación"),
         "el rechazo no identifica el contexto de compilación ausente")
end

canonical = JSON.parse(File.read(CANONICAL_BUDGETS, encoding: "UTF-8"))
assert(canonical["schemaVersion"] == 1, "los presupuestos canónicos no usan schemaVersion=1")
assert(canonical["profile"] == "pixel-6a-release-v1", "el perfil canónico cambió sin contrato")
canonical_entries = canonical.fetch("budgets")
canonical_keys = canonical_entries.map { |entry| [entry.fetch("benchmark"), entry.fetch("metric")] }
assert(canonical_keys.uniq.length == canonical_keys.length, "hay presupuestos canónicos duplicados")
expected_budgets = {
  ["coldStartup", "timeToInitialDisplayMs"] => 1_200,
  ["cameraFirstFrame", "cameraBindFirstMs"] => 500,
  ["cameraFirstFrame", "cameraFirstFrameFirstMs"] => 1_500,
  ["cameraFirstFrame", "cameraCaptureFirstMs"] => 2_000,
  ["cameraFirstFrame", "cameraJpegCopyFirstMs"] => 150,
  ["bitmapOcrParserAndSyncPipeline", "bitmapRenderEncodeFirstMs"] => 900,
  ["bitmapOcrParserAndSyncPipeline", "bitmapDecodeFirstMs"] => 250,
  ["bitmapOcrParserAndSyncPipeline", "bitmapWriteFirstMs"] => 150,
  ["bitmapOcrParserAndSyncPipeline", "imagePreprocessFirstMs"] => 1_500,
  ["bitmapOcrParserAndSyncPipeline", "ocrFirstMs"] => 3_000,
  ["bitmapOcrParserAndSyncPipeline", "parserFirstMs"] => 250,
  ["bitmapOcrParserAndSyncPipeline", "sync100FirstMs"] => 500,
  ["bitmapOcrParserAndSyncPipeline", "memoryRssTotalPairedMaxKb"] => 393_216,
  ["hundredLineListScroll", "frameDurationCpuMs"] => 16.67,
  ["hundredLineListScroll", "frameOverrunMs"] => 0,
}
actual_budgets = canonical_entries.to_h do |entry|
  [[entry.fetch("benchmark"), entry.fetch("metric")], entry.fetch("maxP95")]
end
assert(actual_budgets == expected_budgets, "los quince presupuestos canónicos cambiaron sin contrato")

ci_workflow = File.read(CI_WORKFLOW, encoding: "UTF-8")
assert(
  ci_workflow.include?("-name '*benchmarkData.json'"),
  "CI no descubre el nombre prefijado que AndroidX genera para benchmarkData.json",
)
assert(
  !ci_workflow.include?("-name benchmarkData.json"),
  "CI volvió a exigir el nombre literal y perdería el JSON prefijado de AndroidX",
)
assert(
  ci_workflow.include?("scripts/macrobenchmark-budgets.json"),
  "CI no aplica el perfil versionado de presupuestos",
)

puts "Macrobenchmark gate test: presupuesto, p95, RSS, métricas ausentes y CI verificados."
