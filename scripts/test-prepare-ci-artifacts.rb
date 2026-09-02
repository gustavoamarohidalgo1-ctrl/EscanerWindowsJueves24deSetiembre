#!/usr/bin/env ruby
# frozen_string_literal: true

require "fileutils"
require "json"
require "open3"
require "pathname"
require "rbconfig"
require "tmpdir"

ROOT = File.expand_path("..", __dir__)
STAGING_ROOT = File.join(ROOT, "ci-artifacts")
PREPARE_SCRIPT = File.join(ROOT, "scripts", "prepare-ci-artifacts.rb")
SUMMARIZE_JUNIT_SCRIPT = File.join(ROOT, "scripts", "summarize-junit.rb")
SUMMARIZE_SECURITY_SCRIPT = File.join(ROOT, "scripts", "summarize-security-results.rb")

def assert(condition, message)
  raise "Fallo de política de artefactos: #{message}" unless condition
end

# Las aserciones comparan contra literales UTF-8. `capture3` etiqueta la salida con el encoding
# externo por defecto, que en el Ruby del sistema de macOS es US-ASCII y rompe `include?` con
# Encoding::CompatibilityError. Se normaliza aquí para que la prueba sea portable.
def utf8(text)
  text.dup.force_encoding(Encoding::UTF_8)
end

def run_prepare(destination, source)
  stdout, stderr, status = Open3.capture3(RbConfig.ruby, PREPARE_SCRIPT, destination, source, chdir: ROOT)
  [utf8(stdout), utf8(stderr), status]
end

staging_root_existed = Dir.exist?(STAGING_ROOT)
FileUtils.mkdir_p(STAGING_ROOT)
fixture_root = Dir.mktmpdir("policy-test-", STAGING_ROOT)

begin
  source = File.join(fixture_root, "source")
  destination = File.join(fixture_root, "staged")
  FileUtils.mkdir_p(source)

  synthetic_email = "nobody@example.invalid"
  synthetic_ruc = "0" * 11
  synthetic_uuid = "12345678-90ab-4cde-8fab-1234567890ab"
  synthetic_jwt = ["eyJ" + ("A" * 12), "B" * 12, "C" * 12].join(".")
  synthetic_api_key = "AI" + "za" + ("D" * 35)
  synthetic_access_token = "ghp_" + ("E" * 36)
  synthetic_private_path = "/Users/example/Desktop/SyntheticProject/report.xml"
  report = File.join(source, "closed-summary.csv")
  File.write(
    report,
    <<~CSV,
      field,value
      workspace,#{ROOT}/private/report.xml
      private_path,#{synthetic_private_path}
      email,#{synthetic_email}
      ruc,#{synthetic_ruc}
      uuid,#{synthetic_uuid}
      jwt,#{synthetic_jwt}
      api_key,#{synthetic_api_key}
      access_token,#{synthetic_access_token}
    CSV
  )
  File.write(File.join(source, "raw-junit.xml"), "<failure>#{synthetic_email}</failure>\n")
  File.write(File.join(source, "raw-report.html"), "<p>#{synthetic_ruc}</p>\n")
  File.write(File.join(source, "raw-report.json"), %({"assertion":"#{synthetic_jwt}"}\n))
  File.write(File.join(source, "raw-report.sarif"), %({"path":"#{synthetic_private_path}"}\n))
  checksum = File.join(source, "release.sha256")
  File.write(checksum, ("f" * 64) + "  synthetic-release.aab\n")
  File.write(File.join(source, "firestore-debug.log"), "synthetic raw log\n")
  File.binwrite(File.join(source, "receipt.png"), "\x89PNG\r\n".b)
  File.symlink(report, File.join(source, "linked-report.xml"))

  junit_fixture = File.join(source, "synthetic-junit.xml")
  File.write(
    junit_fixture,
    <<~XML,
      <testsuite name="suite-#{synthetic_ruc}">
        <testcase classname="Synthetic" name="case-#{synthetic_email}" time="1.25">
          <failure message="#{synthetic_jwt}">#{synthetic_private_path}</failure>
        </testcase>
      </testsuite>
    XML
  )
  junit_summary = File.join(source, "junit-summary.csv")
  junit_stdout, junit_stderr, junit_status = Open3.capture3(
    RbConfig.ruby,
    SUMMARIZE_JUNIT_SCRIPT,
    junit_summary,
    junit_fixture,
    chdir: ROOT,
  )
  assert(junit_status.success?, "el resumen JUnit falló: #{junit_stdout}#{junit_stderr}")
  closed_junit = File.read(junit_summary)
  [synthetic_email, synthetic_ruc, synthetic_jwt, synthetic_private_path].each do |raw_value|
    assert(!closed_junit.include?(raw_value), "el resumen JUnit conservó contenido crudo")
  end
  assert(closed_junit.include?("failed,1.250"), "el resumen JUnit omitió estado/duración")

  npm_audit_fixture = File.join(source, "npm-audit.json")
  dependency_fixture = File.join(source, "dependency-review.json")
  File.write(
    npm_audit_fixture,
    JSON.generate(
      "metadata" => {
        "vulnerabilities" => {
          "info" => 0,
          "low" => 0,
          "moderate" => 2,
          "high" => 1,
          "critical" => 0,
          "total" => 3,
        },
      },
      "vulnerabilities" => { "private-package-#{synthetic_email}" => {} },
    ),
  )
  File.write(
    dependency_fixture,
    JSON.generate([{ "package_url" => "pkg:synthetic/#{synthetic_ruc}" }]),
  )
  security_summary = File.join(source, "security-summary.csv")
  security_stdout, security_stderr, security_status = Open3.capture3(
    RbConfig.ruby,
    SUMMARIZE_SECURITY_SCRIPT,
    security_summary,
    npm_audit_fixture,
    dependency_fixture,
    chdir: ROOT,
  )
  assert(security_status.success?, "el resumen de seguridad falló: #{security_stdout}#{security_stderr}")
  closed_security = File.read(security_summary)
  assert(closed_security.include?("npm_audit,high,1"), "el resumen omitió el conteo npm")
  assert(closed_security.include?("dependency_review,changes,1"),
         "el resumen omitió el conteo de cambios")
  [synthetic_email, synthetic_ruc].each do |raw_value|
    assert(!closed_security.include?(raw_value), "el resumen de seguridad conservó payload crudo")
  end

  stdout, stderr, status = run_prepare(destination, source)
  assert(status.success?, "el staging válido falló: #{stdout}#{stderr}")

  relative_report = Pathname.new(report).relative_path_from(Pathname.new(ROOT)).to_s
  staged_report = File.join(destination, relative_report)
  assert(File.file?(staged_report), "no copió el resumen CSV permitido")
  sanitized = File.read(staged_report)
  [
    ROOT,
    synthetic_private_path,
    synthetic_email,
    synthetic_ruc,
    synthetic_uuid,
    synthetic_jwt,
    synthetic_api_key,
    synthetic_access_token,
  ].each do |sensitive_value|
    assert(!sanitized.include?(sensitive_value), "conservó un valor sintético sensible")
  end
  %w[
    <WORKSPACE>
    <REDACTED_WORKSPACE_PATH>
    <REDACTED_EMAIL>
    <REDACTED_FISCAL_ID>
    <REDACTED_UUID>
    <REDACTED_JWT>
    <REDACTED_API_KEY>
    <REDACTED_ACCESS_TOKEN>
  ].each do |placeholder|
    assert(sanitized.include?(placeholder), "falta la redacción #{placeholder}")
  end

  manifest = JSON.parse(File.read(File.join(destination, "artifact-manifest.json")))
  relative_checksum = Pathname.new(checksum).relative_path_from(Pathname.new(ROOT)).to_s
  relative_junit_summary = Pathname.new(junit_summary).relative_path_from(Pathname.new(ROOT)).to_s
  relative_security_summary =
    Pathname.new(security_summary).relative_path_from(Pathname.new(ROOT)).to_s
  expected_paths =
    [relative_checksum, relative_junit_summary, relative_report, relative_security_summary].sort
  actual_paths = manifest.fetch("files").map { |entry| entry.fetch("path") }
  assert(actual_paths == expected_paths, "la allowlist omitió SHA-256 o incluyó un prohibido")
  assert(!Dir.glob(File.join(destination, "**", "*debug.log")).any?, "copió un log crudo")
  assert(!Dir.glob(File.join(destination, "**", "*.png")).any?, "copió un PNG")
  %w[*.xml *.html *.json *.sarif].each do |glob|
    files = Dir.glob(File.join(destination, "**", glob)).reject do |path|
      File.basename(path) == "artifact-manifest.json"
    end
    assert(files.empty?, "copió un reporte crudo #{glob}")
  end
  assert(!Dir.glob(File.join(destination, "**", "linked-report.xml")).any?,
         "siguió un enlace simbólico")

  binary_source = File.join(fixture_root, "binary-source")
  FileUtils.mkdir_p(binary_source)
  File.binwrite(
    File.join(binary_source, "sensitive.perfetto-trace"),
    "trace:".b + synthetic_access_token.b,
  )
  binary_destination = File.join(fixture_root, "binary-staged")
  binary_stdout, binary_stderr, binary_status = run_prepare(
    binary_destination,
    binary_source,
  )
  assert(binary_status.success?, "falló al excluir una traza: #{binary_stdout}#{binary_stderr}")
  binary_manifest = JSON.parse(File.read(File.join(binary_destination, "artifact-manifest.json")))
  assert(binary_manifest.fetch("files").empty?, "copió una traza Perfetto cruda")

  linked_source = File.join(fixture_root, "source-link")
  File.symlink(source, linked_source)
  link_destination = File.join(fixture_root, "link-staged")
  _link_stdout, link_stderr, link_status = run_prepare(link_destination, linked_source)
  assert(!link_status.success?, "aceptó una fuente que era symlink")
  assert(link_stderr.include?("enlace simbólico"), "el rechazo no identificó el symlink")
  assert(!File.exist?(link_destination), "creó staging antes de rechazar el symlink")

  escaped_source = File.join(File.dirname(ROOT), "facturastock-outside-fixture")
  escape_destination = File.join(fixture_root, "escape-staged")
  _escape_stdout, escape_stderr, escape_status = run_prepare(
    escape_destination,
    escaped_source,
  )
  assert(!escape_status.success?, "aceptó una fuente fuera del proyecto")
  assert(escape_stderr.include?("dentro del proyecto"), "el rechazo no identificó el escape")
  assert(!File.exist?(escape_destination), "creó staging antes de rechazar el escape")

  puts "Artifact policy test: resumen cerrado, redacción, reportes/trazas crudos, symlinks y escape verificados."
ensure
  FileUtils.remove_entry_secure(fixture_root) if File.exist?(fixture_root)
  if !staging_root_existed && Dir.exist?(STAGING_ROOT) && Dir.empty?(STAGING_ROOT)
    Dir.rmdir(STAGING_ROOT)
  end
end
