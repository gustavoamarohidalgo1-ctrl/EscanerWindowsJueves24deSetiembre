#!/usr/bin/env ruby
# frozen_string_literal: true

require "csv"
require "fileutils"
require "json"

ROOT = File.expand_path("..", __dir__)
abort "Uso: #{File.basename($PROGRAM_NAME)} <resumen.csv> [dependency-review.json]" if ARGV.empty?

destination = File.expand_path(ARGV.shift, ROOT)
dependency_review_path = ARGV.empty? ? nil : File.expand_path(ARGV.shift, ROOT)
abort "El resumen debe quedar dentro del proyecto" unless destination.start_with?("#{ROOT}/")

begin
  rows = []
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
