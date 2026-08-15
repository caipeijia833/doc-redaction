#!/bin/sh
set -u

PACKAGE_ROOT=${1:-}
if [ -z "$PACKAGE_ROOT" ]; then
  printf '%s\n' 'ERROR: package root is required.' >&2
  exit 20
fi

ROOT=$(CDPATH= cd -- "$PACKAGE_ROOT" 2>/dev/null && pwd) || {
  printf '%s\n' 'ERROR: package root cannot be resolved.' >&2
  exit 20
}
DIAGNOSTICS="$ROOT/data/diagnostics"
REPORT="$DIAGNOSTICS/environment-report.json"
OPTIONS="$DIAGNOSTICS/runtime-options.sh"
ISSUES=''
WARNINGS=''
REPAIRED=false

add_issue() {
  if [ -n "$ISSUES" ]; then ISSUES="$ISSUES
$1"; else ISSUES=$1; fi
}

add_warning() {
  if [ -n "$WARNINGS" ]; then WARNINGS="$WARNINGS
$1"; else WARNINGS=$1; fi
}

json_escape() {
  printf '%s' "$1" | tr -d '\015' | sed 's/\\/\\\\/g; s/"/\\"/g'
}

json_array() {
  values=$1
  first=true
  printf '['
  old_ifs=$IFS
  IFS='
'
  for value in $values; do
    [ -n "$value" ] || continue
    if [ "$first" = true ]; then first=false; else printf ','; fi
    printf '"%s"' "$(json_escape "$value")"
  done
  IFS=$old_ifs
  printf ']'
}

mkdir -p "$DIAGNOSTICS" 2>/dev/null || {
  printf '%s\n' 'ERROR: package data directory is not writable.' >&2
  exit 20
}
WRITE_PROBE="$DIAGNOSTICS/write-$$.tmp"
if ! (umask 077 && printf '%s' ok > "$WRITE_PROBE") 2>/dev/null; then
  printf '%s\n' 'ERROR: package data directory is not writable.' >&2
  exit 20
fi
rm -f -- "$WRITE_PROBE"

verify_components() {
  [ -f "$ROOT/repair/COMPONENTS_SHA256SUMS.txt" ] || return 1
  expected_list="$DIAGNOSTICS/expected-$$.txt"
  actual_list="$DIAGNOSTICS/actual-$$.txt"
  sed 's/^[0-9a-fA-F][0-9a-fA-F]*  //' "$ROOT/repair/COMPONENTS_SHA256SUMS.txt" | LC_ALL=C sort > "$expected_list" || return 1
  (cd "$ROOT" && find app runtime ocr media vlm update -type f -print | LC_ALL=C sort > "$actual_list") || return 1
  if ! cmp -s "$expected_list" "$actual_list"; then
    rm -f -- "$expected_list" "$actual_list"
    return 1
  fi
  rm -f -- "$expected_list" "$actual_list"
  (cd "$ROOT" && shasum -a 256 -c repair/COMPONENTS_SHA256SUMS.txt >/dev/null 2>&1)
}

restore_components() {
  archive="$ROOT/repair/components.tar.gz"
  hash_file="$ROOT/repair/REPAIR_SHA256.txt"
  [ -f "$archive" ] && [ -f "$hash_file" ] || return 1
  expected=$(tr -d '[:space:]' < "$hash_file")
  actual=$(shasum -a 256 "$archive" | awk '{print $1}')
  [ "${#expected}" -eq 64 ] && [ "$expected" = "$actual" ] || return 1
  temporary=$(mktemp -d "$ROOT/repair-work.XXXXXX") || return 1
  case "$temporary" in "$ROOT"/repair-work.*) ;; *) return 1 ;; esac
  if ! tar -xzf "$archive" -C "$temporary"; then
    rm -rf -- "$temporary"
    return 1
  fi
  for name in app runtime ocr media vlm update; do
    if [ ! -d "$temporary/$name" ]; then
      rm -rf -- "$temporary"
      return 1
    fi
    case "$ROOT/$name" in "$ROOT"/app|"$ROOT"/runtime|"$ROOT"/ocr|"$ROOT"/media|"$ROOT"/vlm|"$ROOT"/update) ;; *) rm -rf -- "$temporary"; return 1 ;; esac
    rm -rf -- "$ROOT/$name"
    cp -R "$temporary/$name" "$ROOT/$name" || { rm -rf -- "$temporary"; return 1; }
  done
  rm -rf -- "$temporary"
}

if ! verify_components; then
  if restore_components && verify_components; then
    REPAIRED=true
  else
    add_issue 'Critical application components are missing or modified and offline repair failed.'
  fi
fi

OS_NAME=$(uname -s 2>/dev/null || printf unknown)
ARCH=$(uname -m 2>/dev/null || printf unknown)
OS_VERSION=$(sw_vers -productVersion 2>/dev/null || printf unknown)
OS_MAJOR=${OS_VERSION%%.*}
[ "$OS_NAME" = Darwin ] || add_issue 'This package requires macOS.'
case "$OS_MAJOR" in ''|*[!0-9]*) add_issue 'The macOS version could not be detected.' ;; *) [ "$OS_MAJOR" -ge 13 ] || add_issue 'macOS 13 or later is required.' ;; esac
case "$ARCH" in x86_64|arm64) ;; *) add_issue "Unsupported processor architecture: $ARCH" ;; esac

check_native_arch() {
  native_file=$1
  native_name=$2
  if [ ! -f "$native_file" ]; then add_issue "$native_name is missing."; return; fi
  native_details=$(file -b "$native_file" 2>/dev/null || printf unknown)
  case "$ARCH" in
    arm64) printf '%s' "$native_details" | grep -q 'arm64' || add_issue "$native_name does not contain arm64 code." ;;
    x86_64) printf '%s' "$native_details" | grep -Eq 'x86_64|x86-64' || add_issue "$native_name does not contain x86_64 code." ;;
  esac
}
check_native_arch "$ROOT/runtime/bin/java" 'Bundled Java'
check_native_arch "$ROOT/ocr/bin/tesseract" 'Bundled Tesseract'
check_native_arch "$ROOT/media/ffmpeg/bin/ffmpeg" 'Bundled FFmpeg'
check_native_arch "$ROOT/media/whisper/whisper-cli" 'Bundled whisper.cpp'
check_native_arch "$ROOT/media/opencv/lib/libopencv_java4130.dylib" 'Bundled OpenCV JNI'
check_native_arch "$ROOT/vlm/bin/llama-server" 'Bundled llama.cpp'

MEMORY_BYTES=$(sysctl -n hw.memsize 2>/dev/null || printf 0)
PROCESSORS=$(sysctl -n hw.logicalcpu 2>/dev/null || printf 1)
DISK_KIB=$(df -Pk "$ROOT" 2>/dev/null | awk 'NR==2 {print $4}')
case "$MEMORY_BYTES" in ''|*[!0-9]*) MEMORY_BYTES=0; add_issue 'Physical memory could not be detected.' ;; esac
case "$PROCESSORS" in ''|*[!0-9]*) PROCESSORS=1 ;; esac
case "$DISK_KIB" in ''|*[!0-9]*) DISK_KIB=0; add_issue 'Free disk space could not be detected.' ;; esac
DISK_BYTES=$((DISK_KIB * 1024))
[ "$MEMORY_BYTES" -ge 4294967296 ] || add_issue 'At least 4 GiB of physical memory is required.'
[ "$DISK_BYTES" -ge 4294967296 ] || add_issue 'At least 4 GiB of free disk space is required to start safely.'

if [ "$MEMORY_BYTES" -ge 17179869184 ]; then
  PROFILE=standard; APP_HEAP=4096m; WORKER_HEAP=2048m; WORKER_COMMIT_LIMIT=4096m; SAMPLE_FRAMES=900
  if [ "$PROCESSORS" -ge 8 ]; then WORKER_CONCURRENCY=2; else WORKER_CONCURRENCY=1; fi
  if [ "$PROCESSORS" -gt 8 ]; then ASR_THREADS=8; elif [ "$PROCESSORS" -gt 2 ]; then ASR_THREADS=$((PROCESSORS - 1)); else ASR_THREADS=2; fi
elif [ "$MEMORY_BYTES" -ge 8589934592 ]; then
  PROFILE=balanced; APP_HEAP=2048m; WORKER_HEAP=1536m; WORKER_COMMIT_LIMIT=3584m; WORKER_CONCURRENCY=1; SAMPLE_FRAMES=600
  if [ "$PROCESSORS" -gt 4 ]; then ASR_THREADS=4; elif [ "$PROCESSORS" -gt 2 ]; then ASR_THREADS=$((PROCESSORS - 1)); else ASR_THREADS=2; fi
else
  PROFILE=constrained; APP_HEAP=1024m; WORKER_HEAP=768m; WORKER_COMMIT_LIMIT=2048m; WORKER_CONCURRENCY=1; SAMPLE_FRAMES=300
  if [ "$PROCESSORS" -gt 2 ]; then ASR_THREADS=2; else ASR_THREADS=$PROCESSORS; fi
  add_warning 'Constrained mode reduces worker memory and sampled video frames. Large files may be refused safely.'
fi
if [ "$MEMORY_BYTES" -ge 8589934592 ]; then
  VLM_MODE=auto
  if [ "$WORKER_CONCURRENCY" -gt 1 ]; then WORKER_CONCURRENCY=1; add_warning 'Worker concurrency was limited to 1 to prevent duplicate Qwen3-VL model instances from exhausting memory.'; fi
else
  VLM_MODE=disabled
  add_warning 'Qwen3-VL was disabled because less than 8 GiB of physical memory was detected.'
fi

if [ -z "$ISSUES" ]; then
  "$ROOT/runtime/bin/java" -version >/dev/null 2>&1 || add_issue 'Bundled Java cannot run on this machine.'
  "$ROOT/ocr/bin/tesseract" --version >/dev/null 2>&1 || add_issue 'Bundled Tesseract cannot run on this machine.'
  "$ROOT/media/ffmpeg/bin/ffmpeg" -version >/dev/null 2>&1 || add_issue 'Bundled FFmpeg cannot run on this machine.'
  "$ROOT/media/ffmpeg/bin/ffprobe" -version >/dev/null 2>&1 || add_issue 'Bundled FFprobe cannot run on this machine.'
  "$ROOT/media/whisper/whisper-cli" --version >/dev/null 2>&1 || add_issue 'Bundled whisper.cpp cannot run on this machine.'
  "$ROOT/runtime/bin/java" "-Ddocredaction.media.opencvLibrary=$ROOT/media/opencv/lib/libopencv_java4130.dylib" -cp "$ROOT/app/doc-redaction-poc.jar" io.github.caipeijia833.docredaction.Main --opencv-probe >/dev/null 2>&1 || add_issue 'Bundled OpenCV JNI cannot run on this machine.'
  "$ROOT/vlm/bin/llama-server" --version >/dev/null 2>&1 || add_issue 'Bundled llama.cpp cannot run on this machine.'
fi

if [ -z "$ISSUES" ]; then STATUS=ready; else STATUS=blocked; fi
cat > "$REPORT" <<EOF
{
  "generatedAt": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')",
  "status": "$STATUS",
  "offline": true,
  "repairAttempted": $REPAIRED,
  "packageRoot": "$(json_escape "$ROOT")",
  "os": {"caption": "macOS", "version": "$(json_escape "$OS_VERSION")", "architecture": "$(json_escape "$ARCH")"},
  "resources": {"physicalMemoryBytes": $MEMORY_BYTES, "freeDiskBytes": $DISK_BYTES, "logicalProcessors": $PROCESSORS},
  "profile": {"name": "$PROFILE", "appHeap": "$APP_HEAP", "workerHeap": "$WORKER_HEAP", "workerCommitLimit": "$WORKER_COMMIT_LIMIT", "workerConcurrency": $WORKER_CONCURRENCY, "mediaSampleFrames": $SAMPLE_FRAMES, "asrThreads": $ASR_THREADS, "vlmMode": "$VLM_MODE", "vlmBackend": "metal"},
  "issues": $(json_array "$ISSUES"),
  "warnings": $(json_array "$WARNINGS")
}
EOF

if [ -n "$ISSUES" ]; then
  printf '%s\n' ENVIRONMENT_BLOCKED
  printf '%s\n' "$ISSUES" | while IFS= read -r issue; do printf 'ERROR: %s\n' "$issue"; done
  printf 'REPORT=%s\n' "$REPORT"
  exit 21
fi

cat > "$OPTIONS" <<EOF
export DOC_REDACTION_ENV_PROFILE='$PROFILE'
export DOC_REDACTION_APP_XMX='$APP_HEAP'
export DOC_REDACTION_WORKER_XMX='$WORKER_HEAP'
export DOC_REDACTION_WORKER_COMMIT_LIMIT='$WORKER_COMMIT_LIMIT'
export DOC_REDACTION_WORKER_CONCURRENCY='$WORKER_CONCURRENCY'
export DOC_REDACTION_MEDIA_SAMPLE_FRAMES='$SAMPLE_FRAMES'
export DOC_REDACTION_ASR_THREADS='$ASR_THREADS'
export DOC_REDACTION_VLM_MODE='$VLM_MODE'
EOF
chmod 600 "$OPTIONS"
printf 'ENVIRONMENT_READY=%s\n' "$PROFILE"
[ "$REPAIRED" = false ] || printf '%s\n' COMPONENTS_REPAIRED=1
printf 'REPORT=%s\n' "$REPORT"
exit 0
