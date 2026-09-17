#!/usr/bin/env ruby
# frozen_string_literal: true

require "fileutils"
require "digest"
require "rexml/document"
require "set"

ROOT = File.expand_path("..", __dir__)
PROFILE_RUNS_ROOT = File.join(ROOT, "app", "build", "reports", "profile-runs")
APP_OWNER = /\A[HSP]*Lcom\/facturastock\/app\//
APP_CLASS = /\A[HSP]*Lcom\/facturastock\/app\/[^;]+;\z/
RESIDUAL_METHOD = /;->[a-z]\(/
RESIDUAL_TYPE = /L[a-z]{1,2}\/[A-Za-z0-9_$-]{1,4};/
VARIANT_MANGLED_METHOD = /\$[^;()]*_(?:local|cloud)(?:Debug|Release|Benchmark|Profile)\b/
REQUIRED_MANUAL_CUJS = [
  "HPLcom/facturastock/app/domain/normalization/**->**(**)**",
  "Lcom/facturastock/app/domain/normalization/**;",
  "HPLcom/facturastock/app/feature/linereview/InvoiceLineReviewScreenKt**->**(**)**",
  "HPLcom/facturastock/app/feature/linereview/InvoiceLineReviewContract**->**(**)**",
  "HPLcom/facturastock/app/ui/format/FormattersKt**->**(**)**",
].freeze
REQUIRED_SALES_OWNERS = [
  "com/facturastock/app/feature/sales/SalesRouteKt",
  "com/facturastock/app/feature/sales/SalesScreenKt",
].freeze

def fail_candidate(message)
  warn "Profile candidate rejected: #{message}"
  exit 1
end

def read_rules(path)
  fail_candidate("missing profile #{path}") unless File.file?(path)

  File.readlines(path, chomp: true, encoding: "UTF-8")
      .map(&:strip)
      .reject { |line| line.empty? || line.start_with?("#") }
end

def flags(rule)
  rule.split("L", 2).first
end

def normalized(rule)
  rule.sub(/\A[HSP]+/, "")
end

def profile_owner(rule)
  rule[/\A[HSP]*L([^;]+);/, 1]
end

def merge_flags(rules)
  rules.group_by { |rule| normalized(rule) }.map do |body, variants|
    merged_flags = %w[H S P].select { |flag| variants.any? { |rule| flags(rule).include?(flag) } }
    "#{merged_flags.join}#{body}"
  end.sort
end

def validate_owners(label, rules)
  invalid = rules.reject { |rule| APP_OWNER.match?(rule) }
  fail_candidate("#{label} contains non-app owners: #{invalid.first(3).join(", ")}") unless invalid.empty?
end

def validate_no_residual_symbols(label, rules)
  invalid = rules.select do |rule|
    RESIDUAL_METHOD.match?(rule) ||
      RESIDUAL_TYPE.match?(rule)
  end
  return if invalid.empty?

  fail_candidate("#{label} appears obfuscated: #{invalid.first(3).join(", ")}")
end

if ARGV.length != 5
  warn <<~USAGE
    Usage: ruby scripts/prepare-profile-candidates.rb \
      GENERATED_STARTUP GENERATED_BASELINE MAINTAINED_STARTUP MAINTAINED_BASELINE OUTPUT_DIR
  USAGE
  exit 2
end

generated_startup_path,
  generated_baseline_path,
  maintained_startup_path,
  maintained_baseline_path,
  output_dir = ARGV.map { |path| File.expand_path(path, ROOT) }

profile_run_dir = File.dirname(generated_startup_path)
unless profile_run_dir == File.dirname(generated_baseline_path) &&
       File.dirname(profile_run_dir) == PROFILE_RUNS_ROOT &&
       File.basename(generated_startup_path) == "startup-prof.txt" &&
       File.basename(generated_baseline_path) == "baseline-prof.txt"
  fail_candidate("generated inputs must be the canonical pair from one atomic localProfile run id")
end

manifest_path = File.join(profile_run_dir, "run-manifest.txt")
junit_path = File.join(profile_run_dir, "junit.xml")
fail_candidate("missing atomic run manifest #{manifest_path}") unless File.file?(manifest_path)
fail_candidate("missing JUnit evidence #{junit_path}") unless File.file?(junit_path)
manifest = File.readlines(manifest_path, chomp: true).each_with_object({}) do |line, values|
  key, value = line.split("=", 2)
  values[key] = value if key && value
end
expected_manifest = {
  "format" => "1",
  "run_id" => File.basename(profile_run_dir),
  "variant" => "localProfile",
  "test_class" => "com.facturastock.app.benchmark.FacturaStockBaselineProfile",
  "tests" => "2",
  "failures" => "0",
  "errors" => "0",
  "skipped" => "0",
}
expected_manifest.each do |key, expected|
  actual = manifest[key]
  fail_candidate("run manifest #{key}=#{actual.inspect}, expected #{expected}") unless actual == expected
end
{
  "startup_sha256" => generated_startup_path,
  "baseline_sha256" => generated_baseline_path,
  "junit_sha256" => junit_path,
}.each do |manifest_key, path|
  actual_hash = Digest::SHA256.file(path).hexdigest
  fail_candidate("#{manifest_key} does not match atomic run") unless manifest[manifest_key] == actual_hash
end
junit = REXML::Document.new(File.read(junit_path, encoding: "UTF-8")).root
unless junit&.name == "testsuite" &&
       junit.attributes["tests"] == "2" &&
       junit.attributes["failures"] == "0" &&
       junit.attributes["errors"] == "0" &&
       junit.attributes["skipped"] == "0"
  fail_candidate("JUnit evidence is not a successful 2/2 profile run")
end

app_source_root = File.join(ROOT, "app", "src")
if output_dir == app_source_root || output_dir.start_with?("#{app_source_root}/")
  fail_candidate("output must stay outside app/src until human review")
end

generated_startup = read_rules(generated_startup_path)
generated_baseline = read_rules(generated_baseline_path)
maintained_startup = read_rules(maintained_startup_path)
maintained_baseline = read_rules(maintained_baseline_path)

generated_variant_mangling = (generated_startup + generated_baseline).select do |rule|
  VARIANT_MANGLED_METHOD.match?(rule)
end
unless generated_variant_mangling.empty?
  fail_candidate(
    "generated profiles contain variant-mangled methods: " \
      "#{generated_variant_mangling.first(3).join(", ")}",
  )
end

{
  "generated startup" => generated_startup,
  "generated baseline" => generated_baseline,
  "maintained startup" => maintained_startup,
  "maintained baseline" => maintained_baseline,
}.each do |label, rules|
  fail_candidate("#{label} is empty") if rules.empty?
  validate_owners(label, rules)
end
validate_no_residual_symbols("generated startup", generated_startup)
validate_no_residual_symbols("generated baseline", generated_baseline)
maintained_variant_mangling = (maintained_startup + maintained_baseline).select do |rule|
  VARIANT_MANGLED_METHOD.match?(rule)
end
unless maintained_variant_mangling.empty?
  fail_candidate(
    "maintained profiles contain variant-mangled methods: " \
      "#{maintained_variant_mangling.first(3).join(", ")}",
  )
end

missing_sales_owners = REQUIRED_SALES_OWNERS.reject do |owner|
  generated_startup.any? { |rule| profile_owner(rule) == owner }
end
unless missing_sales_owners.empty?
  fail_candidate("startup did not reach ready Sales owners: #{missing_sales_owners.join(", ")}")
end

missing_manual_cujs = REQUIRED_MANUAL_CUJS - maintained_baseline
unless missing_manual_cujs.empty?
  fail_candidate("maintained baseline misses manual CUJs: #{missing_manual_cujs.join(", ")}")
end

invalid_startup = generated_startup.reject do |rule|
  if rule.include?("->")
    flags(rule).include?("S")
  else
    APP_CLASS.match?(rule)
  end
end
unless invalid_startup.empty?
  fail_candidate("startup has invalid flags or descriptors: #{invalid_startup.first(3).join(", ")}")
end

unless generated_startup.any? { |rule| rule.include?("->") }
  fail_candidate("startup must include observed methods through ready Sales")
end

startup_candidate = merge_flags(generated_startup)
# Startup class descriptors also belong in Baseline so its compiled profile contains every startup
# owner. Maintained parser/list wildcards survive because this startup-only journey cannot observe
# those post-capture CUJs.
maintained_startup_bodies = maintained_startup.map { |rule| normalized(rule) }.to_set
maintained_post_startup = maintained_baseline.reject do |rule|
  maintained_startup_bodies.include?(normalized(rule))
end
baseline_candidate = merge_flags(generated_startup + generated_baseline + maintained_post_startup)

missing_candidate_cujs = REQUIRED_MANUAL_CUJS - baseline_candidate
unless missing_candidate_cujs.empty?
  fail_candidate("baseline candidate lost manual CUJs: #{missing_candidate_cujs.join(", ")}")
end

normalized_baseline = baseline_candidate.map { |rule| normalized(rule) }.to_set
missing_from_baseline = startup_candidate.reject do |rule|
  normalized_baseline.include?(normalized(rule))
end
unless missing_from_baseline.empty?
  fail_candidate("baseline misses startup rules: #{missing_from_baseline.first(3).join(", ")}")
end

FileUtils.mkdir_p(output_dir)
startup_output = File.join(output_dir, "startup-prof.txt")
baseline_output = File.join(output_dir, "baseline-prof.txt")
summary_output = File.join(output_dir, "summary.txt")

File.write(
  startup_output,
  [
    "# Candidate generated from the non-minified Profile journey through ready Sales.",
    "# Review this diff before copying it into app/src/main/baselineProfiles.",
    *startup_candidate,
    "",
  ].join("\n"),
)
File.write(
  baseline_output,
  [
    "# Candidate generated from the non-minified Profile journey through ready Sales.",
    "# Includes maintained parser/list CUJs that startup cannot observe.",
    *baseline_candidate,
    "",
  ].join("\n"),
)

maintained_startup_set = maintained_startup.to_set
maintained_baseline_set = maintained_baseline.to_set
summary = [
  "startup_rules=#{startup_candidate.length}",
  "startup_added=#{(startup_candidate.to_set - maintained_startup_set).length}",
  "startup_removed=#{(maintained_startup_set - startup_candidate.to_set).length}",
  "baseline_rules=#{baseline_candidate.length}",
  "baseline_added=#{(baseline_candidate.to_set - maintained_baseline_set).length}",
  "baseline_removed=#{(maintained_baseline_set - baseline_candidate.to_set).length}",
  "startup_missing_from_baseline=0",
  "non_app_rules=0",
  "residual_symbols=0",
  "variant_mangled_rules=0",
].join("\n") + "\n"
File.write(summary_output, summary)
puts summary
puts "startup_candidate=#{startup_output}"
puts "baseline_candidate=#{baseline_output}"
