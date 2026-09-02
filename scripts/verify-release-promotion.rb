#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "fileutils"
require "optparse"

EXPECTED_STAGES = [
  ["artifact-verified", nil, nil, 0],
  ["internal", "internal", nil, 24],
  ["production-1", "production", 0.01, 24],
  ["production-5", "production", 0.05, 24],
  ["production-20", "production", 0.2, 48],
  ["production-50", "production", 0.5, 48],
  ["production-100", "production", 1.0, 0],
].freeze
EXPECTED_TRANSITIONS = EXPECTED_STAGES.each_cons(2).map { |left, right| [left[0], right[0]] }.freeze
SHA256_PATTERN = /\A[0-9a-f]{64}\z/i.freeze

def abort_policy(message)
  abort "ERROR: política de promoción inválida: #{message}"
end

def load_and_validate_policy(path)
  abort_policy("no existe #{path}") unless File.file?(path) && !File.symlink?(path)
  policy = JSON.parse(File.read(path, encoding: "UTF-8"))
  abort_policy("schemaVersion debe ser 1") unless policy["schemaVersion"] == 1

  stages = policy.fetch("stages", []).map do |stage|
    [stage["id"], stage["track"], stage["userFraction"], stage["minimumObservationHours"]]
  end
  abort_policy("las etapas deben ser exactamente interna → 1 → 5 → 20 → 50 → 100") unless stages == EXPECTED_STAGES
  abort_policy("solo se permiten transiciones adyacentes") unless policy["allowedTransitions"] == EXPECTED_TRANSITIONS

  artifact_policy = policy.fetch("artifactPolicy", {})
  {
    "sameAabSha256AcrossStages" => true,
    "sameVersionCodeAcrossStages" => true,
    "rebuildBetweenStages" => false,
  }.each do |key, expected|
    abort_policy("artifactPolicy.#{key} debe ser #{expected}") unless artifact_policy[key] == expected
  end
  promotion_gates = policy.fetch("promotionGates", {})
  %w[
    manualApprovalRequired
    observationWindowRequired
    versionScopedHealthReviewRequired
    haltBeforeHotfix
  ].each do |key|
    abort_policy("promotionGates.#{key} debe ser true") unless promotion_gates[key] == true
  end
  hotfix_policy = policy.fetch("hotfixPolicy", {})
  %w[
    higherVersionCodeRequired
    roomSchemaForwardOnly
    retainLatestEntitiesAndMigrations
    userDataResetForbidden
  ].each do |key|
    abort_policy("hotfixPolicy.#{key} debe ser true") unless hotfix_policy[key] == true
  end
  policy
rescue JSON::ParserError, KeyError => error
  abort_policy(error.message)
end

begin
options = {}
parser = OptionParser.new do |arguments|
  arguments.banner = "Uso: verify-release-promotion.rb --policy RUTA [--check-policy-only | opciones de transición]"
  arguments.on("--policy PATH") { |value| options[:policy] = value }
  arguments.on("--check-policy-only") { options[:check_only] = true }
  arguments.on("--from STAGE") { |value| options[:from] = value }
  arguments.on("--to STAGE") { |value| options[:to] = value }
  arguments.on("--version-code CODE") { |value| options[:version_code] = value }
  arguments.on("--locked-version-code CODE") { |value| options[:locked_version_code] = value }
  arguments.on("--aab-sha256 SHA") { |value| options[:sha256] = value }
  arguments.on("--locked-aab-sha256 SHA") { |value| options[:locked_sha256] = value }
  arguments.on("--observed-hours HOURS") { |value| options[:observed_hours] = value }
  arguments.on("--health-gate STATUS") { |value| options[:health_gate] = value }
  arguments.on("--approval-reference ID") { |value| options[:approval_reference] = value }
  arguments.on("--evidence PATH") { |value| options[:evidence] = value }
end
parser.parse!
abort_policy("sobran argumentos posicionales") unless ARGV.empty?
abort_policy("--policy es obligatorio") unless options[:policy]
policy = load_and_validate_policy(options[:policy])
if options[:check_only]
  extra = options.keys - %i[policy check_only]
  abort_policy("--check-policy-only no admite una transición") unless extra.empty?
  puts "Política de promoción válida: internal → 1% → 5% → 20% → 50% → 100%."
  exit 0
end

required = %i[
  from
  to
  version_code
  locked_version_code
  sha256
  locked_sha256
  observed_hours
  health_gate
  approval_reference
  evidence
]
missing = required.reject { |key| options[key] && !options[key].empty? }
abort_policy("faltan opciones: #{missing.join(', ')}") unless missing.empty?
transition = [options[:from], options[:to]]
abort_policy("la transición omite o retrocede una etapa: #{transition.join(' → ')}") unless
  policy.fetch("allowedTransitions").include?(transition)

version_code = Integer(options[:version_code], 10)
locked_version_code = Integer(options[:locked_version_code], 10)
abort_policy("versionCode fuera de rango") unless version_code.positive? && version_code <= 2_100_000_000
abort_policy("el versionCode cambió entre etapas") unless version_code == locked_version_code
sha256 = options[:sha256].downcase
locked_sha256 = options[:locked_sha256].downcase
abort_policy("SHA-256 del AAB ilegible") unless SHA256_PATTERN.match?(sha256) && SHA256_PATTERN.match?(locked_sha256)
abort_policy("el AAB cambió entre etapas") unless sha256 == locked_sha256
observed_hours = Integer(options[:observed_hours], 10)
abort_policy("las horas observadas no pueden ser negativas") if observed_hours.negative?
source = policy.fetch("stages").find { |stage| stage.fetch("id") == options[:from] }
required_hours = source.fetch("minimumObservationHours")
abort_policy("ventana insuficiente: #{observed_hours} h observadas, #{required_hours} h requeridas") if
  observed_hours < required_hours
abort_policy("el health gate versionado debe ser PASS") unless options[:health_gate] == "PASS"
abort_policy("approval-reference debe ser un ID no sensible de 3..80 caracteres") unless
  options[:approval_reference].match?(/\A[A-Za-z0-9][A-Za-z0-9._-]{2,79}\z/)

evidence_path = File.expand_path(options[:evidence])
abort_policy("la evidencia ya existe o es enlace simbólico") if File.exist?(evidence_path) || File.symlink?(evidence_path)
parent = File.dirname(evidence_path)
FileUtils.mkdir_p(parent)
target = policy.fetch("stages").find { |stage| stage.fetch("id") == options[:to] }
File.open(evidence_path, "wx", 0o644) do |file|
  file.puts "gate,status,value"
  file.puts "adjacent_transition,PASS,#{options[:from]}_to_#{options[:to]}"
  file.puts "same_version_code,PASS,#{version_code}"
  file.puts "same_aab_sha256,PASS,#{sha256}"
  file.puts "target_track,PASS,#{target.fetch('track')}"
  fraction = target["userFraction"] ? format("%.2f", target.fetch("userFraction")) : "internal"
  file.puts "target_user_fraction,PASS,#{fraction}"
  file.puts "minimum_observation_hours,PASS,#{observed_hours}_of_#{required_hours}"
  file.puts "manual_approval,PASS,#{options[:approval_reference]}"
  file.puts "version_scoped_health_review,PASS,#{options[:health_gate]}"
  file.puts "publication_performed,PASS,false"
end
puts "Transición #{options[:from]} → #{options[:to]} validada; este comando no publica en Play."
rescue ArgumentError => error
  abort_policy(error.message)
end
