#!/usr/bin/env ruby

# Verificador sin gemas para los formatos y límites cerrados de los recursos de Google Play.

ROOT = File.expand_path("..", __dir__)
ASSETS = File.join(ROOT, "play", "assets")
PLAY_DOCS = File.join(ROOT, "docs", "play")

Image = Struct.new(:format, :width, :height, :alpha, keyword_init: true)

def png_info(path)
  data = File.binread(path)
  raise "PNG inválido: #{path}" unless data.start_with?("\x89PNG\r\n\x1A\n".b)

  width, height, bit_depth, color_type = data.byteslice(16, 10).unpack("NNC2")
  alpha = [4, 6].include?(color_type) || data.include?("tRNS".b)
  Image.new(format: :png, width: width, height: height, alpha: alpha)
end

def jpeg_info(path)
  data = File.binread(path)
  raise "JPEG inválido: #{path}" unless data.start_with?("\xFF\xD8".b)

  offset = 2
  while offset < data.bytesize
    offset += 1 while offset < data.bytesize && data.getbyte(offset) != 0xFF
    offset += 1 while offset < data.bytesize && data.getbyte(offset) == 0xFF
    marker = data.getbyte(offset)
    offset += 1
    next if marker == 0xD8 || marker == 0xD9
    break if marker == 0xDA

    length = data.byteslice(offset, 2)&.unpack1("n")
    raise "Segmento JPEG inválido: #{path}" unless length && length >= 2

    if [0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF].include?(marker)
      height, width = data.byteslice(offset + 3, 4).unpack("n2")
      return Image.new(format: :jpeg, width: width, height: height, alpha: false)
    end
    offset += length
  end
  raise "JPEG sin dimensiones: #{path}"
end

def image_info(path)
  case File.extname(path).downcase
  when ".png" then png_info(path)
  when ".jpg", ".jpeg" then jpeg_info(path)
  else raise "Formato no admitido: #{path}"
  end
end

def require_asset(condition, message)
  raise message unless condition
end

def quoted_section(text, header, next_header = nil)
  section = text.split(header, 2).fetch(1)
  section = section.split(next_header, 2).first if next_header
  section.lines
    .select { |line| line.start_with?(">") }
    .map { |line| line.sub(/^> ?/, "").rstrip }
    .join("\n")
    .strip
end

icon_path = File.join(ASSETS, "play-icon-512.png")
icon = image_info(icon_path)
require_asset(icon.format == :png && icon.width == 512 && icon.height == 512, "Icono Play inválido")
require_asset(icon.alpha, "El icono Play debe conservar canal alfa")
require_asset(File.size(icon_path) <= 1_024 * 1_024, "El icono Play supera 1 MiB")

feature_path = File.join(ASSETS, "feature-graphic-1024x500.png")
feature = image_info(feature_path)
require_asset(feature.format == :png && feature.width == 1024 && feature.height == 500, "Feature graphic inválido")
require_asset(!feature.alpha, "El feature graphic no debe tener alfa")

screenshots = Dir[File.join(ASSETS, "es-PE", "phone-screenshots", "*.{jpg,jpeg,png}")].sort
require_asset(screenshots.size == 8, "El paquete es-PE debe contener exactamente 8 capturas")
screenshots.each do |path|
  image = image_info(path)
  min, max = [image.width, image.height].minmax
  require_asset(image.format == :jpeg, "Las capturas finales deben ser JPEG: #{path}")
  require_asset(image.width == 1080 && image.height == 1920, "La captura debe medir 1080x1920: #{path}")
  require_asset(!image.alpha, "La captura no debe tener alfa: #{path}")
  require_asset(min >= 320 && max <= 3840 && max <= min * 2, "Dimensiones inválidas: #{path}")
end

listing_path = File.join(PLAY_DOCS, "store-listing-es-PE.md")
listing = File.read(listing_path, encoding: "UTF-8")
name = listing[/\| Nombre de la app \| `([^`]+)` \|/, 1]
short_description = quoted_section(listing, "## Descripción breve", "## Descripción completa")
full_description = quoted_section(listing, "## Descripción completa", "## Capturas sintéticas entregadas")
release_notes = quoted_section(listing, "## Notas para la primera prueba interna")
require_asset(name && name.length.between?(1, 30), "El nombre debe tener 1..30 caracteres")
require_asset(short_description.length.between?(1, 80), "La descripción breve supera 80 caracteres")
require_asset(full_description.length.between?(1, 4_000), "La descripción completa supera 4 000 caracteres")
require_asset(release_notes.length.between?(1, 500), "Las notas de versión superan 500 caracteres")

public_text_paths =
  Dir[File.join(PLAY_DOCS, "*.{md,html}")] +
  Dir[File.join(ASSETS, "**", "*.md")]
public_text = public_text_paths.sort.map { |path| File.read(path, encoding: "UTF-8") }.join("\n")
sensitive_patterns = {
  "correo literal" => /\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/i,
  "JWT" => /\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/,
  "API key Firebase" => /\bAIza[A-Za-z0-9_-]{35}\b/,
}
sensitive_patterns.each do |label, pattern|
  require_asset(!public_text.match?(pattern), "El paquete público contiene #{label}")
end
ruc_literals = public_text.scan(/\b(?:10|15|16|17|20)\d{9}\b/).uniq
require_asset(
  (ruc_literals - ["20111111112"]).empty?,
  "El paquete público contiene un RUC no incluido en la allowlist sintética",
)

puts "OK: ficha #{name.length}/#{short_description.length}/#{full_description.length}/#{release_notes.length}, " \
  "sin identificadores no autorizados; icono, feature graphic y #{screenshots.size} capturas válidos."
