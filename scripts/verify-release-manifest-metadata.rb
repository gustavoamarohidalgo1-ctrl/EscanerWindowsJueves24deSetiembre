#!/usr/bin/env ruby
# frozen_string_literal: true

# Valida la política de colección directamente sobre `aapt dump xmltree`. Crashlytics forma parte
# del flavor cloud, pero tanto él como Analytics deben permanecer apagados antes del opt-in.
REQUIRED_FALSE_METADATA = %w[
  firebase_analytics_collection_enabled
  firebase_crashlytics_collection_enabled
  google_analytics_default_allow_ad_personalization_signals
  google_analytics_adid_collection_enabled
  google_analytics_automatic_screen_reporting_enabled
].freeze

manifest_tree = $stdin.read
abort "El manifest decodificado está vacío" if manifest_tree.empty?

lines = manifest_tree.lines
REQUIRED_FALSE_METADATA.each do |metadata_name|
  name_lines = lines.each_index.select { |index| lines[index].include?(%Q{"#{metadata_name}"}) }
  abort "#{metadata_name} debe aparecer exactamente una vez en el artefacto" unless name_lines.one?

  value_line = lines[name_lines.first + 1].to_s
  unless value_line.include?("(type 0x12)0x0")
    abort "#{metadata_name} debe iniciar en false en el artefacto"
  end
end

puts "Analytics y Crashlytics inician con colección desactivada."
