#!/usr/bin/env ruby
# frozen_string_literal: true

require "digest"
require "fileutils"
require "open3"
require "tmpdir"

ROOT = File.expand_path("..", __dir__)
PROMOTION = File.join(ROOT, "scripts", "verify-release-promotion.rb")
FORWARD_ONLY = File.join(ROOT, "scripts", "verify-release-forward-only.sh")
POLICY = File.join(ROOT, "play", "release-policy.json")
SCHEMAS = File.join(ROOT, "app", "schemas", "com.facturastock.app.data.local.FacturaStockDatabase")
AAB_SHA256 = Digest::SHA256.hexdigest("immutable synthetic AAB")
CURRENT_ROOM_SCHEMA = Dir.glob(File.join(SCHEMAS, "*.json")).map { |path| File.basename(path, ".json").to_i }.max

def assert(condition, message)
  raise "Fallo del contrato rollout/hotfix: #{message}" unless condition
end

stdout, stderr, status = Open3.capture3(
  "ruby",
  PROMOTION,
  "--policy",
  POLICY,
  "--check-policy-only",
  chdir: ROOT,
)
assert(status.success?, "la política canónica es inválida: #{stdout}#{stderr}")

Dir.mktmpdir("facturastock-release-policy-") do |temporary_dir|
  valid_evidence = File.join(temporary_dir, "valid-transition.csv")
  base_transition = [
    "ruby", PROMOTION,
    "--policy", POLICY,
    "--from", "internal",
    "--to", "production-1",
    "--version-code", "42",
    "--locked-version-code", "42",
    "--aab-sha256", AAB_SHA256,
    "--locked-aab-sha256", AAB_SHA256,
    "--observed-hours", "24",
    "--health-gate", "PASS",
    "--approval-reference", "change-42",
    "--evidence", valid_evidence,
  ]
  stdout, stderr, status = Open3.capture3(*base_transition, chdir: ROOT)
  assert(status.success?, "se rechazó internal → 1%: #{stdout}#{stderr}")
  evidence = File.read(valid_evidence)
  assert(evidence.include?("target_user_fraction,PASS,0.01"), "la evidencia perdió el 1%")
  assert(evidence.include?("publication_performed,PASS,false"), "el gate aparenta publicar")

  skipped_evidence = File.join(temporary_dir, "skipped-transition.csv")
  skipped = base_transition.dup
  skipped[skipped.index("production-1")] = "production-20"
  skipped[skipped.index(valid_evidence)] = skipped_evidence
  _stdout, stderr, status = Open3.capture3(*skipped, chdir: ROOT)
  assert(!status.success?, "la política permitió saltar de internal a 20%")
  assert(stderr.include?("omite o retrocede"), "el rechazo de salto no es explícito")

  changed_evidence = File.join(temporary_dir, "changed-artifact.csv")
  changed = base_transition.dup
  changed[changed.rindex(AAB_SHA256)] = "b" * 64
  changed[changed.index(valid_evidence)] = changed_evidence
  _stdout, stderr, status = Open3.capture3(*changed, chdir: ROOT)
  assert(!status.success?, "la política permitió cambiar el AAB durante el rollout")
  assert(stderr.include?("AAB cambió"), "el rechazo de otro AAB no es explícito")

  short_window_evidence = File.join(temporary_dir, "short-window.csv")
  short_window = base_transition.dup
  short_window[short_window.index("internal")] = "production-20"
  short_window[short_window.index("production-1")] = "production-50"
  short_window[short_window.index("24")] = "12"
  short_window[short_window.index(valid_evidence)] = short_window_evidence
  _stdout, stderr, status = Open3.capture3(*short_window, chdir: ROOT)
  assert(!status.success?, "la política aceptó salir de 20% antes de 48 horas")
  assert(stderr.include?("ventana insuficiente"), "el rechazo de ventana no es explícito")

  forward_evidence = File.join(temporary_dir, "forward-only.csv")
  forward_environment = {
    "FACTURASTOCK_VERSION_CODE" => "42",
    "FACTURASTOCK_MAX_DISTRIBUTED_VERSION_CODE" => "41",
    "FACTURASTOCK_MAX_DISTRIBUTED_ROOM_SCHEMA" => CURRENT_ROOM_SCHEMA.to_s,
  }
  stdout, stderr, status = Open3.capture3(
    forward_environment,
    "bash",
    FORWARD_ONLY,
    SCHEMAS,
    forward_evidence,
    chdir: ROOT,
  )
  assert(status.success?, "se rechazó un hotfix forward-only válido: #{stdout}#{stderr}")
  assert(File.read(forward_evidence).include?(
           "room_schema_forward_only,PASS,#{CURRENT_ROOM_SCHEMA},#{CURRENT_ROOM_SCHEMA}"
         ), "la evidencia no ancla el Room actual")

  _stdout, stderr, status = Open3.capture3(
    forward_environment.merge("FACTURASTOCK_VERSION_CODE" => "41"),
    "bash",
    FORWARD_ONLY,
    SCHEMAS,
    File.join(temporary_dir, "reused-version.csv"),
    chdir: ROOT,
  )
  assert(!status.success?, "forward-only aceptó reutilizar versionCode")
  assert(stderr.include?("debe superar"), "el rechazo de versionCode no es explícito")

  _stdout, stderr, status = Open3.capture3(
    forward_environment.merge(
      "FACTURASTOCK_MAX_DISTRIBUTED_ROOM_SCHEMA" => (CURRENT_ROOM_SCHEMA + 1).to_s,
    ),
    "bash",
    FORWARD_ONLY,
    SCHEMAS,
    File.join(temporary_dir, "schema-downgrade.csv"),
    chdir: ROOT,
  )
  assert(!status.success?, "forward-only aceptó bajar el Room ya distribuido")
  assert(stderr.include?("bajar el esquema"), "el rechazo de downgrade no es explícito")
end

puts "Release policy test: rollout adyacente, AAB inmutable y hotfix forward-only verificados."
