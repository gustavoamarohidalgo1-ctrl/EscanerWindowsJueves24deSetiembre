#!/usr/bin/env ruby
# frozen_string_literal: true

require "find"
require "open3"

ROOT = File.expand_path("..", __dir__)
SELF_FILES = %w[
  scripts/scan-repository-secrets.rb
  scripts/prepare-ci-artifacts.rb
].freeze
EXCLUDED_DIRECTORIES = %w[
  .git
  .gradle
  .idea
  .kotlin
  build
  ci-artifacts
  evidence
  node_modules
  security-results
].freeze
EXCLUDED_PATHS = %w[
  gradle/wrapper/dists
].freeze
FALLBACK_ONLY_EXCLUDED_PATHS = %w[local.properties].freeze
EXCLUDED_FILE_PATTERNS = [/-debug\.log\z/, /\.class\z/, /\.jar\z/].freeze
FORBIDDEN_FILE_PATTERNS = [
  /(?:\A|\/)local\.properties\z/,
  /(?:\A|\/)google-services\.json\z/,
  /(?:\A|\/)service-account[^\/]*\.json\z/i,
  /(?:\A|\/)\.env(?:\..+)?\z/,
  /\.(?:jks|keystore|p12|pfx|pem)\z/i,
].freeze
SECRET_PATTERNS = {
  "clave privada PEM" => /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/,
  "credencial de cuenta de servicio" => /"type"\s*:\s*"service_account"/,
  "AWS access key" => /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/,
  "Google API key" => /\bAIza[0-9A-Za-z_-]{35}\b/,
  "GitHub token" => /\b(?:gh[oprsu]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,})\b/,
  "Slack token" => /\bxox[baprs]-[A-Za-z0-9-]{20,}\b/,
  "JWT literal" => /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/,
}.freeze

def excluded_path?(relative_path, fallback: false)
  components = relative_path.split(File::SEPARATOR)
  EXCLUDED_PATHS.any? do |path|
    relative_path == path || relative_path.start_with?("#{path}/")
  end ||
    (fallback && FALLBACK_ONLY_EXCLUDED_PATHS.include?(relative_path)) ||
    EXCLUDED_DIRECTORIES.any? { |name| components.include?(name) } ||
    EXCLUDED_FILE_PATTERNS.any? { |pattern| pattern.match?(relative_path) }
end

def candidate_paths
  git_output, _git_error, git_status = Open3.capture3(
    "git",
    "ls-files",
    "-z",
    "--cached",
    "--others",
    "--exclude-standard",
    chdir: ROOT,
  )
  if git_status.success?
    return [git_output.split("\0").reject(&:empty?), false]
  end

  paths = []
  Find.find(ROOT) do |absolute_path|
    relative_path = absolute_path.delete_prefix("#{ROOT}/")
    if File.directory?(absolute_path) && excluded_path?(relative_path, fallback: true)
      Find.prune
    elsif File.file?(absolute_path) && !excluded_path?(relative_path, fallback: true)
      paths << relative_path
    end
  end
  [paths, true]
end

violations = []
paths, fallback = candidate_paths
paths.uniq.sort.each do |relative_path|
  next if SELF_FILES.include?(relative_path) || excluded_path?(relative_path, fallback: fallback)

  absolute_path = File.join(ROOT, relative_path)
  next unless File.file?(absolute_path)

  FORBIDDEN_FILE_PATTERNS.each do |pattern|
    violations << "#{relative_path}: archivo de credencial prohibido" if pattern.match?(relative_path)
  end

  bytes = File.binread(absolute_path)
  next if bytes.include?("\0")

  text = bytes.force_encoding(Encoding::UTF_8).scrub
  text.each_line.with_index(1) do |line, line_number|
    SECRET_PATTERNS.each do |label, pattern|
      violations << "#{relative_path}:#{line_number}: #{label}" if pattern.match?(line)
    end
  end
end

unless violations.empty?
  warn "Se detectó material que no puede entrar al repositorio:"
  violations.uniq.each { |violation| warn "  - #{violation}" }
  exit 1
end

puts "Secret scan: sin credenciales de alta confianza ni archivos de clave."
