#!/usr/bin/env ruby
# frozen_string_literal: true

require "digest"
require "fileutils"
require "find"
require "json"
require "pathname"

ROOT = File.expand_path("..", __dir__)
# Solo se publican resúmenes cerrados generados por CI. XML/HTML/JSON/SARIF de herramientas
# se leen localmente para producir esos resúmenes, pero nunca se copian al staging porque
# pueden contener mensajes de aserción, OCR o rutas no anticipadas.
TEXT_EXTENSIONS = %w[.csv .md .sha256].freeze
BINARY_EXTENSIONS = %w[.aab .apk].freeze
DENIED_NAMES = [
  /\A(?:cpuinfo|meminfo)\z/,
  /\Aaapt\..*/,
  /\Adevice-info\.pb\z/,
  /\Atest-result(?:\.pb|\.textproto)?\z/,
  /\Alogcat-.*/,
  /\Autp(?:\..*)?\.log\z/,
  /-debug\.log\z/,
  /\.log\z/,
].freeze
REDACTIONS = {
  "workspace_path" => [
    %r{/Users/[^/\s"'<>]+/(?:Desktop/)?[^\s"'<>]+},
    %r{/home/runner/work/[^/\s"'<>]+/[^\s"'<>]+},
    %r{[A-Za-z]:\\(?:a|Users)\\[^\s"'<>]+},
  ],
  "email" => [/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/i],
  "fiscal_id" => [/\b\d{11}\b/],
  "uuid" => [
    /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/i,
  ],
  "jwt" => [/\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/],
  "api_key" => [/\bAIza[0-9A-Za-z_-]{35}\b/],
  "access_token" => [
    /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/,
    /\b(?:gh[oprsu]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,})\b/,
    /\bxox[baprs]-[A-Za-z0-9-]{20,}\b/,
  ],
  "private_key" => [/-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/],
}.freeze

def usage!
  warn "Uso: #{File.basename($PROGRAM_NAME)} <directorio-staging-nuevo> <ruta> [ruta ...]"
  exit 2
end

def allowed?(path)
  return false if DENIED_NAMES.any? { |pattern| pattern.match?(File.basename(path)) }

  extension = File.extname(path)
  TEXT_EXTENSIONS.include?(extension) || BINARY_EXTENSIONS.include?(extension)
end

def files_under(path)
  return [path] if File.file?(path)
  return [] unless File.directory?(path)

  files = []
  Find.find(path) do |candidate|
    if File.symlink?(candidate)
      Find.prune if File.directory?(candidate)
      next
    end
    files << candidate if File.file?(candidate)
  end
  files
end

def inside_root?(path)
  path == ROOT || path.start_with?("#{ROOT}/")
end

def assert_no_symlink_components!(path, label)
  relative_path = Pathname.new(path).relative_path_from(Pathname.new(ROOT))
  current = ROOT
  relative_path.each_filename do |component|
    current = File.join(current, component)
    next unless File.exist?(current) || File.symlink?(current)

    abort "#{label} contiene un enlace simbólico: #{current}" if File.symlink?(current)
    abort "#{label} resuelve fuera del proyecto: #{current}" unless inside_root?(File.realpath(current))
  end
end

def redact_text(text, root)
  counts = Hash.new(0)
  sanitized = text.gsub(root, "<WORKSPACE>")
  REDACTIONS.each do |label, patterns|
    patterns.each do |pattern|
      sanitized = sanitized.gsub(pattern) do
        counts[label] += 1
        "<REDACTED_#{label.upcase}>"
      end
    end
  end
  [sanitized, counts]
end

def assert_clean_text!(text, relative_path)
  REDACTIONS.each do |label, patterns|
    next unless patterns.any? { |pattern| pattern.match?(text) }

    abort "El staging conserva #{label} en #{relative_path}"
  end
end

def assert_clean_path!(relative_path)
  REDACTIONS.each do |label, patterns|
    next unless patterns.any? { |pattern| pattern.match?(relative_path) }

    abort "La ruta del artefacto contiene #{label}: #{relative_path}"
  end
end

def assert_clean_binary!(bytes, relative_path)
  binary_patterns = REDACTIONS.slice("jwt", "access_token", "private_key")
  binary_patterns.each do |label, patterns|
    patterns.each do |pattern|
      binary_pattern = Regexp.new(pattern.source, pattern.options, "n")
      abort "El binario #{relative_path} contiene #{label}" if binary_pattern.match?(bytes)
    end
  end
end

usage! if ARGV.length < 2
destination = File.expand_path(ARGV.shift, ROOT)
unless destination.start_with?("#{ROOT}/") && destination != ROOT
  abort "El staging debe ser un subdirectorio específico del proyecto"
end
assert_no_symlink_components!(destination, "El staging")
if File.exist?(destination) && !Dir.empty?(destination)
  abort "El staging ya contiene archivos: #{destination}"
end

sources = ARGV.map { |path| File.expand_path(path, ROOT) }
sources.each do |source|
  abort "La fuente debe estar dentro del proyecto: #{source}" unless inside_root?(source)
  assert_no_symlink_components!(source, "La fuente")
  next unless File.exist?(source)

  real_source = File.realpath(source)
  abort "La fuente resuelve fuera del proyecto: #{source}" unless inside_root?(real_source)
end
FileUtils.mkdir_p(destination)
source_files = sources.flat_map { |source| files_under(source) }
  .uniq
  .select { |source| allowed?(source) }
  .reject { |source| source.start_with?("#{destination}/") }

manifest_entries = []
total_redactions = Hash.new(0)
source_files.sort.each do |source|
  relative_path = Pathname.new(source).relative_path_from(Pathname.new(ROOT)).to_s
  assert_clean_path!(relative_path)
  target = File.join(destination, relative_path)
  FileUtils.mkdir_p(File.dirname(target))

  extension = File.extname(source)
  if TEXT_EXTENSIONS.include?(extension)
    original = File.binread(source).force_encoding(Encoding::UTF_8).scrub
    sanitized, counts = redact_text(original, ROOT)
    assert_clean_text!(sanitized, relative_path)
    File.write(target, sanitized, mode: "wb")
    counts.each { |label, count| total_redactions[label] += count }
  else
    bytes = File.binread(source)
    assert_clean_binary!(bytes, relative_path)
    File.binwrite(target, bytes)
  end

  manifest_entries << {
    "path" => relative_path,
    "bytes" => File.size(target),
    "sha256" => Digest::SHA256.file(target).hexdigest,
  }
end

manifest = {
  "schema" => 1,
  "policy" => "allowlisted-and-redacted",
  "files" => manifest_entries,
  "redactions" => total_redactions.sort.to_h,
}
File.write(
  File.join(destination, "artifact-manifest.json"),
  JSON.pretty_generate(manifest) + "\n",
)
puts "CI artifacts: #{manifest_entries.size} archivos; redacciones=#{total_redactions.values.sum}."
