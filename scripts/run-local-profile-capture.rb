#!/usr/bin/env ruby
# frozen_string_literal: true

require "digest"
require "fileutils"
require "rexml/document"
require "tmpdir"
require "time"

ROOT = File.expand_path("..", __dir__)
CONNECTED_OUTPUT_ROOT = File.join(
  ROOT,
  "benchmark",
  "build",
  "outputs",
  "connected_android_test_additional_output",
  "localProfile",
)
JUNIT_OUTPUT_ROOT = File.join(
  ROOT,
  "benchmark",
  "build",
  "outputs",
  "androidTest-results",
  "connected",
  "profile",
  "flavors",
  "local",
)
PROFILE_RUNS_ROOT = File.join(ROOT, "app", "build", "reports", "profile-runs")
TEST_CLASS = "com.facturastock.app.benchmark.FacturaStockBaselineProfile"
STARTUP_SOURCE_GLOB = "**/FacturaStockBaselineProfile_coldStartup-startup-prof.txt"
BASELINE_SOURCE_GLOB = "**/FacturaStockBaselineProfile_baselineColdStartup-baseline-prof.txt"
JUNIT_GLOB = "TEST-*.xml"

def fail_capture(message)
  warn "Profile capture rejected: #{message}"
  exit 1
end

def single_fresh_file(root, glob, started_at, label)
  matches = Dir.glob(File.join(root, glob)).select { |path| File.file?(path) }
  fail_capture("expected one #{label}, found #{matches.length}") unless matches.length == 1

  path = matches.first
  fail_capture("#{label} predates this Gradle run") if File.mtime(path) < started_at - 1
  fail_capture("#{label} is empty") if File.zero?(path)
  path
end

run_id = ARGV.shift || "#{Time.now.utc.strftime("%Y%m%dT%H%M%SZ")}-#{Process.pid}"
fail_capture("usage: ruby scripts/run-local-profile-capture.rb [RUN_ID]") unless ARGV.empty?
fail_capture("invalid run id #{run_id.inspect}") unless run_id.match?(/\A[A-Za-z0-9][A-Za-z0-9._-]*\z/)

FileUtils.mkdir_p(PROFILE_RUNS_ROOT)
final_run_dir = File.join(PROFILE_RUNS_ROOT, run_id)
fail_capture("run id already exists: #{final_run_dir}") if File.exist?(final_run_dir)

# Estas salidas pertenecen exclusivamente a build/. Borrarlas antes de Gradle impide que un HRF o
# XML de una corrida anterior pueda confundirse con el par producido por este JUnit 2/2.
stale_outputs = [
  *Dir.glob(File.join(CONNECTED_OUTPUT_ROOT, STARTUP_SOURCE_GLOB)),
  *Dir.glob(File.join(CONNECTED_OUTPUT_ROOT, BASELINE_SOURCE_GLOB)),
  *Dir.glob(File.join(JUNIT_OUTPUT_ROOT, JUNIT_GLOB)),
]
stale_outputs.each { |path| FileUtils.rm_f(path) }
puts "cleared_previous_generated_outputs=#{stale_outputs.length}"

started_at = Time.now
gradle_command = [
  File.join(ROOT, "gradlew"),
  "--no-daemon",
  "--max-workers=1",
  ":benchmark:connectedLocalProfileAndroidTest",
  "-Pandroid.testInstrumentationRunnerArguments.class=#{TEST_CLASS}",
]
success = system(*gradle_command, chdir: ROOT)
fail_capture("Gradle/JUnit did not complete successfully") unless success

startup_source = single_fresh_file(
  CONNECTED_OUTPUT_ROOT,
  STARTUP_SOURCE_GLOB,
  started_at,
  "Startup HRF",
)
baseline_source = single_fresh_file(
  CONNECTED_OUTPUT_ROOT,
  BASELINE_SOURCE_GLOB,
  started_at,
  "Baseline HRF",
)
unless File.dirname(startup_source) == File.dirname(baseline_source)
  fail_capture("Startup and Baseline HRF came from different device directories")
end

junit_source = single_fresh_file(JUNIT_OUTPUT_ROOT, JUNIT_GLOB, started_at, "JUnit XML")
junit = REXML::Document.new(File.read(junit_source, encoding: "UTF-8")).root
fail_capture("JUnit XML has no testsuite root") unless junit&.name == "testsuite"
expected_results = { "tests" => "2", "failures" => "0", "errors" => "0", "skipped" => "0" }
expected_results.each do |attribute, expected|
  actual = junit.attributes[attribute]
  fail_capture("JUnit #{attribute}=#{actual.inspect}, expected #{expected}") unless actual == expected
end
test_methods = junit.elements.to_a("testcase").map { |test| test.attributes["name"] }.sort
unless test_methods == %w[baselineColdStartup coldStartup]
  fail_capture("unexpected JUnit methods: #{test_methods.join(", ")}")
end

temporary_run_dir = Dir.mktmpdir(".#{run_id}-", PROFILE_RUNS_ROOT)
begin
  startup_target = File.join(temporary_run_dir, "startup-prof.txt")
  baseline_target = File.join(temporary_run_dir, "baseline-prof.txt")
  junit_target = File.join(temporary_run_dir, "junit.xml")
  FileUtils.cp(startup_source, startup_target)
  FileUtils.cp(baseline_source, baseline_target)
  FileUtils.cp(junit_source, junit_target)
  manifest = [
    "format=1",
    "run_id=#{run_id}",
    "variant=localProfile",
    "test_class=#{TEST_CLASS}",
    "tests=2",
    "failures=0",
    "errors=0",
    "skipped=0",
    "started_at=#{started_at.utc.iso8601(6)}",
    "completed_at=#{Time.now.utc.iso8601(6)}",
    "startup_sha256=#{Digest::SHA256.file(startup_target).hexdigest}",
    "baseline_sha256=#{Digest::SHA256.file(baseline_target).hexdigest}",
    "junit_sha256=#{Digest::SHA256.file(junit_target).hexdigest}",
    "source_directory=#{File.dirname(startup_source)}",
    "",
  ].join("\n")
  File.write(File.join(temporary_run_dir, "run-manifest.txt"), manifest)
  File.rename(temporary_run_dir, final_run_dir)
ensure
  FileUtils.remove_entry(temporary_run_dir) if File.exist?(temporary_run_dir)
end

puts "profile_run=#{final_run_dir}"
puts "startup_profile=#{File.join(final_run_dir, "startup-prof.txt")}"
puts "baseline_profile=#{File.join(final_run_dir, "baseline-prof.txt")}"
