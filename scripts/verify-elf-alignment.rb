#!/usr/bin/env ruby
# frozen_string_literal: true

# Verifica directamente los program headers ELF de bibliotecas Android de 64 bits. No depende
# de un NDK instalado ni de que el objdump del host entienda AArch64.

MINIMUM_ALIGNMENT = 16 * 1024
EXPECTED_MACHINES = {
  "arm64-v8a" => 183, # EM_AARCH64
  "x86_64" => 62,    # EM_X86_64
}.freeze

abort "Uso: #{File.basename($PROGRAM_NAME)} <directorio-con-lib/>" unless ARGV.length == 1

root = File.expand_path(ARGV.fetch(0))
abort "No existe el directorio ELF: #{root}" unless Dir.exist?(root)

def unsigned(bytes, offset, size, little_endian)
  slice = bytes.byteslice(offset, size)
  abort "ELF truncado al leer offset #{offset}" unless slice&.bytesize == size

  format =
    case [size, little_endian]
    when [2, true] then "v"
    when [2, false] then "n"
    when [4, true] then "V"
    when [4, false] then "N"
    when [8, true] then "Q<"
    when [8, false] then "Q>"
    else abort "Tamaño entero ELF no soportado: #{size}"
    end
  slice.unpack1(format)
end

libraries = Dir.glob(File.join(root, "lib", "{arm64-v8a,x86_64}", "**", "*.so")).sort
abort "No se encontraron bibliotecas ELF Android de 64 bits" if libraries.empty?

errors = []
load_segments = 0

libraries.each do |library|
  relative = library.delete_prefix("#{root}/")
  abi = relative.split(File::SEPARATOR).fetch(1, "")
  bytes = File.binread(library)

  unless bytes.start_with?("\x7FELF".b)
    errors << "#{relative}: cabecera ELF ausente"
    next
  end

  elf_class = bytes.getbyte(4)
  endian = bytes.getbyte(5)
  unless elf_class == 2 && endian == 1
    errors << "#{relative}: se esperaba ELF64 little-endian"
    next
  end

  machine = unsigned(bytes, 18, 2, true)
  expected_machine = EXPECTED_MACHINES.fetch(abi)
  if machine != expected_machine
    errors << "#{relative}: e_machine=#{machine}, esperado=#{expected_machine}"
    next
  end

  program_header_offset = unsigned(bytes, 32, 8, true)
  program_header_entry_size = unsigned(bytes, 54, 2, true)
  program_header_count = unsigned(bytes, 56, 2, true)
  if program_header_entry_size < 56 || program_header_count.zero?
    errors << "#{relative}: tabla de program headers inválida"
    next
  end

  table_end = program_header_offset + (program_header_entry_size * program_header_count)
  if table_end > bytes.bytesize
    errors << "#{relative}: tabla de program headers truncada"
    next
  end

  library_load_segments = 0
  program_header_count.times do |index|
    header_offset = program_header_offset + (index * program_header_entry_size)
    next unless unsigned(bytes, header_offset, 4, true) == 1 # PT_LOAD

    library_load_segments += 1
    load_segments += 1
    file_offset = unsigned(bytes, header_offset + 8, 8, true)
    virtual_address = unsigned(bytes, header_offset + 16, 8, true)
    alignment = unsigned(bytes, header_offset + 48, 8, true)
    valid_power_of_two = alignment.positive? && (alignment & (alignment - 1)).zero?

    unless valid_power_of_two && alignment >= MINIMUM_ALIGNMENT
      errors << "#{relative}: PT_LOAD #{index} usa p_align=#{alignment}, mínimo=#{MINIMUM_ALIGNMENT}"
      next
    end
    unless (file_offset % alignment) == (virtual_address % alignment)
      errors << "#{relative}: PT_LOAD #{index} no conserva congruencia offset/vaddr"
    end
  end
  errors << "#{relative}: no contiene segmentos PT_LOAD" if library_load_segments.zero?
end

unless errors.empty?
  warn "Bibliotecas incompatibles con páginas de 16 KiB:"
  errors.each { |error| warn "  - #{error}" }
  exit 1
end

puts "ELF 16 KiB: #{libraries.size} bibliotecas de 64 bits, #{load_segments} segmentos PT_LOAD."
