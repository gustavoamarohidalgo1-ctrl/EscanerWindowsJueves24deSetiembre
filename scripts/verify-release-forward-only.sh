#!/usr/bin/env bash
set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
project_root="$(cd "$script_dir/.." && pwd -P)"

schema_dir="${1:-}"
evidence_path="${2:-}"
[[ $# -eq 2 ]] ||
    fail "uso: verify-release-forward-only.sh <directorio-schemas-Room> <evidencia-csv-nueva>"
[[ -d "$schema_dir" && ! -L "$schema_dir" ]] || fail "no existe el directorio de schemas Room"
[[ -n "$evidence_path" && ! -e "$evidence_path" && ! -L "$evidence_path" ]] ||
    fail "la evidencia forward-only debe escribirse en una ruta nueva"

candidate_version_code="${FACTURASTOCK_VERSION_CODE:-}"
maximum_distributed_version_code="${FACTURASTOCK_MAX_DISTRIBUTED_VERSION_CODE:-}"
maximum_distributed_room_schema="${FACTURASTOCK_MAX_DISTRIBUTED_ROOM_SCHEMA:-}"
[[ "$candidate_version_code" =~ ^[1-9][0-9]{0,9}$ && "$candidate_version_code" -le 2100000000 ]] ||
    fail "FACTURASTOCK_VERSION_CODE debe ser un entero entre 1 y 2100000000"
[[ "$maximum_distributed_version_code" =~ ^(0|[1-9][0-9]{0,9})$ && "$maximum_distributed_version_code" -le 2100000000 ]] ||
    fail "FACTURASTOCK_MAX_DISTRIBUTED_VERSION_CODE debe ser un entero entre 0 y 2100000000"
[[ "$maximum_distributed_room_schema" =~ ^(0|[1-9][0-9]{0,9})$ ]] ||
    fail "FACTURASTOCK_MAX_DISTRIBUTED_ROOM_SCHEMA debe ser un entero no negativo"
(( candidate_version_code > maximum_distributed_version_code )) ||
    fail "el versionCode candidato debe superar al máximo ya distribuido"

candidate_room_schema=0
schema_count=0
while IFS= read -r schema_path; do
    schema_name="$(basename "$schema_path" .json)"
    [[ "$schema_name" =~ ^[1-9][0-9]*$ ]] || fail "schema Room con nombre no versionado: $schema_path"
    schema_version="$(ruby -rjson -e 'puts JSON.parse(File.read(ARGV.fetch(0))).fetch("database").fetch("version")' "$schema_path")" ||
        fail "schema Room ilegible: $schema_path"
    [[ "$schema_version" == "$schema_name" ]] ||
        fail "el nombre y database.version no coinciden en $schema_path"
    (( schema_count += 1 ))
    if (( schema_version > candidate_room_schema )); then
        candidate_room_schema="$schema_version"
    fi
done < <(find "$schema_dir" -maxdepth 1 -type f -name '*.json' -print | LC_ALL=C sort)
(( schema_count > 0 )) || fail "el directorio no contiene schemas Room exportados"

for (( expected_schema = 1; expected_schema <= candidate_room_schema; expected_schema += 1 )); do
    [[ -f "$schema_dir/$expected_schema.json" ]] ||
        fail "falta el schema Room append-only $expected_schema.json"
done
(( candidate_room_schema >= maximum_distributed_room_schema )) ||
    fail "el candidato intenta bajar el esquema Room ya distribuido"

database_source="$project_root/app/src/main/java/com/facturastock/app/data/local/FacturaStockDatabase.kt"
[[ -f "$database_source" ]] || fail "no existe FacturaStockDatabase.kt"
declared_room_schema="$(ruby -e '
  source = File.read(ARGV.fetch(0))
  annotation = source.match(/@Database\s*\([\s\S]*?\bversion\s*=\s*([0-9]+)/)
  abort "@Database version ausente" unless annotation
  puts annotation[1]
' "$database_source")" || fail "no se pudo leer la versión @Database"
[[ "$declared_room_schema" == "$candidate_room_schema" ]] ||
    fail "@Database y el último schema Room exportado no coinciden"

mkdir -p "$(dirname "$evidence_path")"
{
    printf 'gate,status,candidate,distributed_max\n'
    printf 'version_code_monotonic,PASS,%s,%s\n' \
        "$candidate_version_code" "$maximum_distributed_version_code"
    printf 'room_schema_forward_only,PASS,%s,%s\n' \
        "$candidate_room_schema" "$maximum_distributed_room_schema"
    printf 'room_schema_source_export_match,PASS,%s,%s\n' \
        "$declared_room_schema" "$candidate_room_schema"
} > "$evidence_path"
printf 'Release forward-only: versionCode=%s, Room=%s; no se publicó ningún artefacto.\n' \
    "$candidate_version_code" "$candidate_room_schema"
