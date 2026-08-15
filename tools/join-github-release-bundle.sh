#!/bin/sh
set -eu

bundle_dir=${1:-"$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"}
parts_file="$bundle_dir/PARTS.txt"
part_hashes="$bundle_dir/PART_SHA256SUMS.txt"
final_hash_file="$bundle_dir/FINAL_SHA256.txt"

if [ ! -f "$parts_file" ] || [ ! -f "$part_hashes" ] || [ ! -f "$final_hash_file" ]; then
  echo "Missing release manifest files in: $bundle_dir" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$bundle_dir" && sha256sum -c PART_SHA256SUMS.txt)
  hash_file() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null 2>&1; then
  (cd "$bundle_dir" && shasum -a 256 -c PART_SHA256SUMS.txt)
  hash_file() { shasum -a 256 "$1" | awk '{print $1}'; }
else
  echo "Neither sha256sum nor shasum is available." >&2
  exit 1
fi

expected_hash=$(awk 'NR == 1 { print $1 }' "$final_hash_file")
output_name=$(awk 'NR == 1 { $1=""; sub(/^[[:space:]]+/, ""); print }' "$final_hash_file")
case "$output_name" in
  ''|*/*|*\\*|..*) echo "Unsafe output name: $output_name" >&2; exit 1 ;;
esac

output_path="$bundle_dir/$output_name"
partial_path="$output_path.partial"
if [ -e "$output_path" ] || [ -e "$partial_path" ]; then
  echo "Output or partial output already exists; inspect it before retrying: $output_path" >&2
  exit 1
fi

cleanup_partial() {
  rm -f -- "$partial_path"
}
trap cleanup_partial EXIT HUP INT TERM

: > "$partial_path"
while IFS= read -r raw_part || [ -n "$raw_part" ]; do
  part=$(printf '%s' "$raw_part" | tr -d '\r')
  case "$part" in
    ''|*/*|*\\*|..*) echo "Unsafe part name: $part" >&2; exit 1 ;;
  esac
  cat -- "$bundle_dir/$part" >> "$partial_path"
done < "$parts_file"

actual_hash=$(hash_file "$partial_path")
if [ "$actual_hash" != "$expected_hash" ]; then
  echo "Reassembled SHA-256 mismatch." >&2
  exit 1
fi

mv -- "$partial_path" "$output_path"
trap - EXIT HUP INT TERM
echo "ASSEMBLE_OK=$output_path"
echo "SHA256=$actual_hash"
