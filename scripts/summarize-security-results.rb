#!/usr/bin/env ruby
# frozen_string_literal: true

require "csv"
require "fileutils"
require "json"

ROOT = File.expand_path("..", __dir__)
SEVERITIES = %w[info low moderate high critical total].freeze

abort "Uso: #{File.basename($PROGRAM_NAME)} <resumen.csv> <npm-audit.json> [dependency-review.json]" if ARGV.length < 2

destination = File.expand_path(ARGV.shift, ROOT)
npm_path = File.expand_path(ARGV.shift, ROOT)
dependency_review_path = ARGV.empty? ? nil : File.expand_path(ARGV.shift, ROOT)
abort "El resumen debe quedar dentro del proyecto" unless destination.start_with?("#{ROOT}/")

begin
  rows = []
  if File.file?(npm_path)
    npm_report = JSON.parse(File.read(npm_path))
    vulnerabilities = npm_report.dig("metadata", "vulnerabilities") || {}
    SEVERITIES.each do |severity|
      count = Integer(vulnerabilities.fetch(severity, 0))
      rows << ["npm_audit", severity, count]
    rescue ArgumentError, TypeError
      rows << ["npm_audit", severity, 0]
    end
  else
    rows << ["npm_audit", "report_present", 0]
  end

  if dependency_review_path && File.file?(dependency_review_path)
    dependency_report = JSON.parse(File.read(dependency_review_path))
    changes =
      if dependency_report.is_a?(Array)
        dependency_report
      elsif dependency_report.is_a?(Hash) && dependency_report["changes"].is_a?(Array)
        dependency_report.fetch("changes")
      else
        []
      end
    rows << ["dependency_review", "changes", changes.size]
  else
    rows << ["dependency_review", "report_present", 0]
  end

  FileUtils.mkdir_p(File.dirname(destination))
  CSV.open(destination, "wb") do |csv|
    csv << %w[gate metric count]
    rows.each { |row| csv << row }
  end
  puts "Seguridad cerrada: solo se conservaron conteos; se omitieron paquetes y payloads."
rescue JSON::ParserError => error
  abort "No se pudo resumir un reporte JSON: #{error.class}"
end
