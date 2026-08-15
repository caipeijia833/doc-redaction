#!/bin/sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SOURCE_JAR="$PROJECT_ROOT/app/build/libs/doc-redaction-poc-0.1.0-poc-all.jar"
SOURCE_SBOM="$PROJECT_ROOT/app/build/reports/cyclonedx-direct/bom.json"
SOURCE_OFD_WORKER="$PROJECT_ROOT/app/build/ofd-worker"
DIST_ROOT="$PROJECT_ROOT/dist"
ARCH=$(uname -m)
PACKAGE_ROOT="$DIST_ROOT/doc-redaction-poc-macos-$ARCH"
ARCHIVE="$DIST_ROOT/doc-redaction-poc-macos-$ARCH.tar.gz"
SOURCE_PREFLIGHT="$PROJECT_ROOT/packaging/preflight-macos.sh"

require_arch() {
  target=$1
  description=$2
  details=$(file -b "$target" 2>/dev/null || true)
  case "$ARCH" in
    arm64) printf '%s' "$details" | grep -q 'arm64' || { printf 'ERROR: %s is not arm64: %s\n' "$description" "$details"; exit 1; } ;;
    x86_64) printf '%s' "$details" | grep -Eq 'x86_64|x86-64' || { printf 'ERROR: %s is not x86_64: %s\n' "$description" "$details"; exit 1; } ;;
  esac
}

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jlink" ]; then
  JLINK="$JAVA_HOME/bin/jlink"
else
  printf '%s\n' 'ERROR: JAVA_HOME must point to a macOS JDK 21.'
  exit 1
fi
if [ ! -f "$SOURCE_JAR" ]; then
  printf '%s\n' 'ERROR: Build the application JAR before packaging.'
  exit 1
fi
if [ ! -f "$SOURCE_SBOM" ]; then
  printf '%s\n' 'ERROR: Generate the CycloneDX SBOM before packaging.'
  exit 1
fi
if [ ! -f "$SOURCE_OFD_WORKER/doc-redaction-ofd-worker-0.1.0-poc.jar" ]; then
  printf '%s\n' 'ERROR: Build the isolated OFD worker before packaging.'
  exit 1
fi
if [ -z "${DOC_REDACTION_OCR_ROOT:-}" ] || [ -z "${DOC_REDACTION_MEDIA_ROOT:-}" ] || [ -z "${DOC_REDACTION_VLM_ROOT:-}" ]; then
  printf '%s\n' 'ERROR: Full offline packaging requires DOC_REDACTION_OCR_ROOT, DOC_REDACTION_MEDIA_ROOT and DOC_REDACTION_VLM_ROOT.'
  exit 1
fi
if [ ! -x "$DOC_REDACTION_VLM_ROOT/bin/llama-server" ] || [ ! -f "$DOC_REDACTION_VLM_ROOT/models/Qwen3VL-2B-Instruct-Q4_K_M.gguf" ] || [ ! -f "$DOC_REDACTION_VLM_ROOT/models/mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf" ]; then
  printf '%s\n' 'ERROR: DOC_REDACTION_VLM_ROOT is missing the same-architecture llama-server or pinned Qwen3-VL model files.'
  exit 1
fi
if [ ! -x "$DOC_REDACTION_OCR_ROOT/bin/tesseract" ] || [ ! -f "$DOC_REDACTION_OCR_ROOT/share/tessdata/chi_sim.traineddata" ] || [ ! -f "$DOC_REDACTION_OCR_ROOT/share/tessdata/eng.traineddata" ]; then
  printf '%s\n' 'ERROR: DOC_REDACTION_OCR_ROOT is missing a macOS tesseract binary or chi_sim/eng data.'
  exit 1
fi
if [ ! -x "$DOC_REDACTION_MEDIA_ROOT/ffmpeg/bin/ffmpeg" ] || [ ! -x "$DOC_REDACTION_MEDIA_ROOT/ffmpeg/bin/ffprobe" ] || [ ! -x "$DOC_REDACTION_MEDIA_ROOT/whisper/whisper-cli" ] || [ ! -f "$DOC_REDACTION_MEDIA_ROOT/opencv/lib/libopencv_java4130.dylib" ] || [ ! -f "$DOC_REDACTION_MEDIA_ROOT/models/ggml-base-q5_1.bin" ] || [ ! -f "$DOC_REDACTION_MEDIA_ROOT/models/face_detection_yunet_2023mar.onnx" ] || [ ! -f "$DOC_REDACTION_MEDIA_ROOT/models/license_plate_detection_lpd_yunet_2023mar.onnx" ]; then
  printf '%s\n' 'ERROR: DOC_REDACTION_MEDIA_ROOT does not contain the required same-architecture media runtime and models.'
  exit 1
fi
require_arch "$JAVA_HOME/bin/java" 'JDK runtime'
require_arch "$DOC_REDACTION_OCR_ROOT/bin/tesseract" 'Tesseract'
require_arch "$DOC_REDACTION_MEDIA_ROOT/ffmpeg/bin/ffmpeg" 'FFmpeg'
require_arch "$DOC_REDACTION_MEDIA_ROOT/whisper/whisper-cli" 'whisper.cpp'
require_arch "$DOC_REDACTION_MEDIA_ROOT/opencv/lib/libopencv_java4130.dylib" 'OpenCV JNI'
require_arch "$DOC_REDACTION_VLM_ROOT/bin/llama-server" 'llama.cpp'
if [ ! -f "$SOURCE_PREFLIGHT" ]; then
  printf '%s\n' 'ERROR: macOS environment preflight script is missing.'
  exit 1
fi

case "$PACKAGE_ROOT" in
  "$DIST_ROOT"/*) ;;
  *) printf '%s\n' 'ERROR: Refusing to replace a path outside dist.'; exit 1 ;;
esac

rm -rf -- "$PACKAGE_ROOT"
rm -f -- "$ARCHIVE"
mkdir -p "$PACKAGE_ROOT/app" "$PACKAGE_ROOT/data"
"$JLINK" --add-modules 'java.se,jdk.httpserver,jdk.unsupported,jdk.jartool,jdk.crypto.ec' --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output "$PACKAGE_ROOT/runtime"
cp "$SOURCE_JAR" "$PACKAGE_ROOT/app/doc-redaction-poc.jar"
cp -R "$SOURCE_OFD_WORKER" "$PACKAGE_ROOT/app/ofd-worker"
cp -R "$PROJECT_ROOT/samples" "$PACKAGE_ROOT/samples"
cp "$PROJECT_ROOT/packaging/RUNTIME_README.md" "$PACKAGE_ROOT/README.md"
cp "$PROJECT_ROOT/LICENSE" "$PACKAGE_ROOT/LICENSE"
cp "$PROJECT_ROOT/NOTICE" "$PACKAGE_ROOT/NOTICE"
cp "$SOURCE_SBOM" "$PACKAGE_ROOT/SBOM.cdx.json"
cp "$PROJECT_ROOT/THIRD_PARTY_NOTICES.md" "$PACKAGE_ROOT/THIRD_PARTY_NOTICES.md"
mkdir -p "$PACKAGE_ROOT/docs"
cp "$PROJECT_ROOT/docs/USER_GUIDE_ZH-CN.md" "$PACKAGE_ROOT/docs/USER_GUIDE_ZH-CN.md"
cp "$PROJECT_ROOT/docs/USER_GUIDE_EN.md" "$PACKAGE_ROOT/docs/USER_GUIDE_EN.md"
cp "$PROJECT_ROOT/docs/TROUBLESHOOTING.md" "$PACKAGE_ROOT/docs/TROUBLESHOOTING.md"
cp "$PROJECT_ROOT/docs/BACKUP_RESTORE_UNINSTALL.md" "$PACKAGE_ROOT/docs/BACKUP_RESTORE_UNINSTALL.md"
cp "$PROJECT_ROOT/docs/LOGGING_AND_DIAGNOSTICS.md" "$PACKAGE_ROOT/docs/LOGGING_AND_DIAGNOSTICS.md"
cp "$PROJECT_ROOT/VERSION" "$PACKAGE_ROOT/VERSION"
cp "$PROJECT_ROOT/DATA_SCHEMA_VERSION" "$PACKAGE_ROOT/DATA_SCHEMA_VERSION"
mkdir -p "$PACKAGE_ROOT/update"
cp "$PROJECT_ROOT/packaging/update/trusted-public-keys.properties" "$PACKAGE_ROOT/update/trusted-public-keys.properties"
cp -R "$DOC_REDACTION_OCR_ROOT" "$PACKAGE_ROOT/ocr"
cp -R "$DOC_REDACTION_MEDIA_ROOT" "$PACKAGE_ROOT/media"
cp -R "$DOC_REDACTION_VLM_ROOT" "$PACKAGE_ROOT/vlm"
cp "$SOURCE_PREFLIGHT" "$PACKAGE_ROOT/preflight-macos.sh"

cat > "$PACKAGE_ROOT/start-macos.command" <<'SCRIPT'
#!/bin/sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OFD_CLASSPATH="$SCRIPT_DIR/app/ofd-worker/doc-redaction-ofd-worker-0.1.0-poc.jar:$SCRIPT_DIR/app/ofd-worker/*"
"$SCRIPT_DIR/preflight-macos.sh" "$SCRIPT_DIR"
. "$SCRIPT_DIR/data/diagnostics/runtime-options.sh"
exec "$SCRIPT_DIR/runtime/bin/java" -Dfile.encoding=UTF-8 \
  "-Ddocredaction.environment.profile=$DOC_REDACTION_ENV_PROFILE" \
  "-Ddocredaction.worker.maxHeap=$DOC_REDACTION_WORKER_XMX" \
  "-Ddocredaction.worker.maxCommittedMemory=$DOC_REDACTION_WORKER_COMMIT_LIMIT" \
  "-Ddocredaction.worker.concurrency=$DOC_REDACTION_WORKER_CONCURRENCY" \
  "-Ddocredaction.media.maxSampleFrames=$DOC_REDACTION_MEDIA_SAMPLE_FRAMES" \
  "-Ddocredaction.media.asrThreads=$DOC_REDACTION_ASR_THREADS" \
  "-Ddocredaction.ofd.classpath=$OFD_CLASSPATH" \
  "-Ddocredaction.ocr.executable=$SCRIPT_DIR/ocr/bin/tesseract" \
  "-Ddocredaction.ocr.dataPath=$SCRIPT_DIR/ocr/share/tessdata" \
  "-Ddocredaction.media.ffmpeg=$SCRIPT_DIR/media/ffmpeg/bin/ffmpeg" \
  "-Ddocredaction.media.ffprobe=$SCRIPT_DIR/media/ffmpeg/bin/ffprobe" \
  "-Ddocredaction.media.whisper=$SCRIPT_DIR/media/whisper/whisper-cli" \
  "-Ddocredaction.media.whisperModel=$SCRIPT_DIR/media/models/ggml-base-q5_1.bin" \
  "-Ddocredaction.media.faceModel=$SCRIPT_DIR/media/models/face_detection_yunet_2023mar.onnx" \
  "-Ddocredaction.media.plateModel=$SCRIPT_DIR/media/models/license_plate_detection_lpd_yunet_2023mar.onnx" \
  "-Ddocredaction.media.opencvLibrary=$SCRIPT_DIR/media/opencv/lib/libopencv_java4130.dylib" \
  "-Ddocredaction.vlm.mode=$DOC_REDACTION_VLM_MODE" \
  "-Ddocredaction.vlm.executable=$SCRIPT_DIR/vlm/bin/llama-server" \
  "-Ddocredaction.vlm.model=$SCRIPT_DIR/vlm/models/Qwen3VL-2B-Instruct-Q4_K_M.gguf" \
  "-Ddocredaction.vlm.mmproj=$SCRIPT_DIR/vlm/models/mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf" \
  -Xms256m "-Xmx$DOC_REDACTION_APP_XMX" -jar "$SCRIPT_DIR/app/doc-redaction-poc.jar" --data "$SCRIPT_DIR/data" "$@"
SCRIPT
chmod +x "$PACKAGE_ROOT/start-macos.command" "$PACKAGE_ROOT/preflight-macos.sh"

mkdir -p "$PACKAGE_ROOT/repair"
(cd "$PACKAGE_ROOT" && find app runtime ocr media vlm update -type f -print | LC_ALL=C sort | while IFS= read -r file; do shasum -a 256 "$file"; done) > "$PACKAGE_ROOT/repair/COMPONENTS_SHA256SUMS.txt"
find "$PACKAGE_ROOT/app" "$PACKAGE_ROOT/runtime" "$PACKAGE_ROOT/ocr" "$PACKAGE_ROOT/media" "$PACKAGE_ROOT/vlm" "$PACKAGE_ROOT/update" -exec touch -t 202608110000 {} +
(cd "$PACKAGE_ROOT" && tar -czf repair/components.tar.gz app runtime ocr media vlm update)
shasum -a 256 "$PACKAGE_ROOT/repair/components.tar.gz" | awk '{print $1}' > "$PACKAGE_ROOT/repair/REPAIR_SHA256.txt"
(cd "$PACKAGE_ROOT" && find . -type f ! -name SHA256SUMS.txt -print | LC_ALL=C sort | while IFS= read -r file; do shasum -a 256 "$file"; done) > "$PACKAGE_ROOT/SHA256SUMS.txt"
find "$PACKAGE_ROOT" -exec touch -t 202608110000 {} +
(cd "$DIST_ROOT" && tar -czf "$(basename "$ARCHIVE")" "$(basename "$PACKAGE_ROOT")")
printf 'PACKAGE_OK=%s\n' "$ARCHIVE"
