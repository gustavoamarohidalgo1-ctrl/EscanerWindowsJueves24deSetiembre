#!/usr/bin/env ruby
# frozen_string_literal: true

require "csv"
require "digest"
require "fileutils"
require "find"
require "pathname"
require "rexml/parsers/pullparser"

ROOT = File.expand_path("..", __dir__)

def usage!
  warn "Uso: #{File.basename($PROGRAM_NAME)} <resumen.csv> <XML JUnit o directorio> [...]"
  exit 2
end

def stable_id(value)
  Digest::SHA256.hexdigest(value.to_s.encode(Encoding::UTF_8, invalid: :replace, undef: :replace))[0, 16]
end

def safe_duration(raw_value)
  value = Float(raw_value || 0)
  return "0.000" unless value.finite? && value >= 0

  format("%.3f", value)
rescue ArgumentError, TypeError
  "0.000"
end

def junit_files(path)
  return [path] if File.file?(path) && File.extname(path).casecmp?(".xml")
  return [] unless File.directory?(path)

  files = []
  Find.find(path) do |candidate|
    if File.symlink?(candidate)
      Find.prune if File.directory?(candidate)
      next
    end
    files << candidate if File.file?(candidate) && File.extname(candidate).casecmp?(".xml")
  end
  files
end

def summarize_file(path)
  rows = []
  suite_stack = []
  current_case = nil
  File.open(path, "rb") do |input|
    parser = REXML::Parsers::PullParser.new(input)
    while parser.has_next?
      event = parser.pull
      if event.start_element?
        attributes = event[1]
        case event[0]
        when "testsuite"
          suite_stack << stable_id(attributes["name"] || "unnamed-suite")
        when "testcase"
          identity = [attributes["classname"], attributes["name"]].compact.join("\u0000")
          current_case = {
            "suite_id" => suite_stack.last || stable_id("unnamed-suite"),
            "case_id" => stable_id(identity),
            "status" => "passed",
            "duration_seconds" => safe_duration(attributes["time"]),
          }
        when "failure"
          current_case["status"] = "failed" if current_case
        when "error"
          current_case["status"] = "error" if current_case
        when "skipped"
          current_case["status"] = "skipped" if current_case
        end
      elsif event.end_element?
        case event[0]
        when "testcase"
          rows << current_case if current_case
          current_case = nil
        when "testsuite"
          suite_stack.pop
        end
      end
    end
  end
  rows
rescue REXML::ParseException => error
  warn "JUnit inválido omitido (#{stable_id(path)}): #{error.class}"
  []
end

usage! if ARGV.length < 2
destination = File.expand_path(ARGV.shift, ROOT)
sources = ARGV.map { |path| File.expand_path(path, ROOT) }
abort "El resumen debe quedar dentro del proyecto" unless destination.start_with?("#{ROOT}/")

rows = []
sources.flat_map { |source| junit_files(source) }.uniq.sort.each do |source|
  source_id = stable_id(Pathname.new(source).relative_path_from(Pathname.new(ROOT)).to_s)
  summarize_file(source).each do |test_case|
    rows << [
      source_id,
      test_case.fetch("suite_id"),
      test_case.fetch("case_id"),
      test_case.fetch("status"),
      test_case.fetch("duration_seconds"),
    ]
  end
end

FileUtils.mkdir_p(File.dirname(destination))
CSV.open(destination, "wb") do |csv|
  csv << %w[source_id suite_id case_id status duration_seconds]
  rows.each { |row| csv << row }
end
puts "JUnit cerrado: #{rows.size} casos resumidos sin nombres, mensajes ni salida de prueba."
