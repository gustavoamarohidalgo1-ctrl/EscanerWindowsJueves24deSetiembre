#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"

def usage!
  warn "Uso: #{File.basename($PROGRAM_NAME)} <benchmarkData.json> <presupuestos.json>"
  exit 2
end

def numeric_runs(value)
  Array(value).flatten.select { |sample| sample.is_a?(Numeric) }.map(&:to_f)
end

def nearest_rank(values, percentile)
  raise ArgumentError, "serie vacía" if values.empty?

  sorted = values.sort
  rank = (percentile * sorted.length).ceil
  sorted[[rank - 1, 0].max]
end

def format_number(value)
  rounded = value.round(3)
  rounded == rounded.to_i ? rounded.to_i.to_s : format("%.3f", rounded).sub(/0+\z/, "").sub(/\.\z/, "")
end

def unit_for(metric_name)
  return "ms" if metric_name.end_with?("Ms")
  return "KiB" if metric_name.end_with?("Kb")
  return "bytes" if metric_name.end_with?("Bytes")

  "—"
end

def load_budgets(path)
  abort "No existe #{path}" unless File.file?(path)

  document = JSON.parse(File.read(path, encoding: "UTF-8"))
  abort "presupuestos.json debe declarar schemaVersion=1" unless document["schemaVersion"] == 1
  profile = document["profile"]
  unless profile.is_a?(String) && profile.match?(/\A[a-z0-9][a-z0-9._-]{0,63}\z/)
    abort "presupuestos.json no declara un profile válido"
  end
  entries = document["budgets"]
  abort "presupuestos.json no contiene budgets" unless entries.is_a?(Array) && !entries.empty?

  budgets = {}
  entries.each_with_index do |entry, index|
    abort "budgets[#{index}] debe ser un objeto" unless entry.is_a?(Hash)

    benchmark = entry["benchmark"]
    metric = entry["metric"]
    maximum = entry["maxP95"]
    unit = entry["unit"]
    unless benchmark.is_a?(String) && benchmark.match?(/\A[A-Za-z0-9][A-Za-z0-9._-]{0,127}\z/)
      abort "budgets[#{index}].benchmark no es válido"
    end
    unless metric.is_a?(String) && metric.match?(/\A[A-Za-z0-9][A-Za-z0-9._-]{0,127}\z/)
      abort "budgets[#{index}].metric no es válida"
    end
    unless maximum.is_a?(Numeric) && maximum.finite? && maximum >= 0
      abort "budgets[#{index}].maxP95 debe ser un número finito no negativo"
    end
    expected_unit = unit_for(metric)
    unless unit == expected_unit
      abort "budgets[#{index}].unit=#{unit.inspect}; se esperaba #{expected_unit.inspect} para #{metric}"
    end

    key = [benchmark, metric]
    abort "presupuesto duplicado para #{benchmark}/#{metric}" if budgets.key?(key)

    budgets[key] = maximum.to_f
  end
  [profile, budgets]
end

minimum_samples = begin
  Integer(ENV.fetch("FACTURASTOCK_MIN_BENCHMARK_SAMPLES", "1"), 10)
rescue ArgumentError
  abort "FACTURASTOCK_MIN_BENCHMARK_SAMPLES debe ser un entero positivo"
end
abort "FACTURASTOCK_MIN_BENCHMARK_SAMPLES debe ser un entero positivo" if minimum_samples < 1

usage! unless ARGV.length == 2
source = ARGV.fetch(0)
abort "No existe #{source}" unless File.file?(source)
budget_profile, budgets = load_budgets(ARGV.fetch(1))

document = JSON.parse(File.read(source, encoding: "UTF-8"))
context = document["context"]
abort "benchmarkData.json no declara contexto de compilación" unless context.is_a?(Hash)
compilation_mode = context["compilationMode"]
unless compilation_mode.is_a?(String) && compilation_mode.match?(/\A[a-z0-9][a-z0-9+._-]{0,63}\z/)
  abort "benchmarkData.json no declara un compilationMode válido"
end
benchmarks = document.fetch("benchmarks")
abort "benchmarkData.json no contiene resultados" if benchmarks.empty?

puts "Modo de compilación reportado por AndroidX: `#{compilation_mode}`."
puts "Perfil de presupuestos: `#{budget_profile}`."
puts
puts "| Benchmark | Métrica | Muestras | p50 | p95 nearest-rank | Máximo | Unidad | Presupuesto p95 | Estado |"
puts "| --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |"

observed_budgets = {}
violations = []

benchmarks.sort_by { |benchmark| [benchmark.fetch("className", ""), benchmark.fetch("name")] }.each do |benchmark|
  name = benchmark.fetch("name")
  metrics = benchmark.fetch("metrics", {})
  sampled_metrics = benchmark.fetch("sampledMetrics", {})
  derived_metrics = {}
  if metrics.key?("memoryRssAnonMaxKb") && metrics.key?("memoryRssFileMaxKb")
    anonymous_runs = numeric_runs(metrics.fetch("memoryRssAnonMaxKb").fetch("runs", []))
    file_runs = numeric_runs(metrics.fetch("memoryRssFileMaxKb").fetch("runs", []))
    abort "#{name}/RSS anon y file tienen distinta cantidad de muestras" unless anonymous_runs.length == file_runs.length

    derived_metrics["memoryRssTotalPairedMaxKb"] = {
      "runs" => anonymous_runs.zip(file_runs).map { |anonymous, file| anonymous + file },
    }
  end
  metric_groups = [metrics, sampled_metrics, derived_metrics]
  rows = metric_groups.flat_map(&:to_a).sort_by(&:first)
  rows.each do |metric_name, metric|
    runs = numeric_runs(metric.fetch("runs", []))
    abort "#{name}/#{metric_name} no conserva muestras crudas" if runs.empty?
    if runs.length < minimum_samples
      abort "#{name}/#{metric_name} conserva #{runs.length} muestras; se requieren al menos #{minimum_samples}"
    end

    p50 = nearest_rank(runs, 0.50)
    p95 = nearest_rank(runs, 0.95)
    key = [name, metric_name]
    maximum = budgets[key]
    status = "diagnóstico"
    budget_cell = "—"
    unless maximum.nil?
      observed_budgets[key] = p95
      budget_cell = "≤ #{format_number(maximum)}"
      if p95 <= maximum
        status = "CUMPLE"
      else
        status = "NO CUMPLE"
        violations <<
          "#{name}/#{metric_name}: p95=#{format_number(p95)} #{unit_for(metric_name)} " \
          "> #{format_number(maximum)} #{unit_for(metric_name)}"
      end
    end

    puts [
      "| `#{name}`",
      "`#{metric_name}`",
      runs.length,
      format_number(p50),
      format_number(p95),
      format_number(runs.max),
      unit_for(metric_name),
      budget_cell,
      status,
    ].join(" | ") + " |"
  end
end

(budgets.keys - observed_budgets.keys).sort.each do |benchmark, metric|
  violations << "falta la métrica presupuestada #{benchmark}/#{metric}"
end

if violations.any?
  warn "Gate de macrobenchmark NO CUMPLE (#{violations.length} incumplimientos):"
  violations.each { |violation| warn "- #{violation}" }
  exit 1
end

puts
puts "Gate de macrobenchmark CUMPLE: #{observed_budgets.length}/#{budgets.length} presupuestos."
