#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_JAR="$SCRIPT_DIR/app/build/libs/doc-redaction-poc-0.1.0-poc-all.jar"
OFD_CLASSPATH="$SCRIPT_DIR/app/build/ofd-worker/doc-redaction-ofd-worker-0.1.0-poc.jar:$SCRIPT_DIR/app/build/ofd-worker/*"

if [ ! -f "$APP_JAR" ]; then
  printf '%s\n' 'ERROR: Application JAR was not found. Build the project first.'
  exit 1
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_EXE="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_EXE=$(command -v java)
else
  printf '%s\n' 'ERROR: Java 21 is required. Install a local Temurin 21 runtime first.'
  exit 1
fi

if [ -n "${DOC_REDACTION_OCR_ROOT:-}" ] && [ -n "${DOC_REDACTION_MEDIA_ROOT:-}" ] && [ -n "${DOC_REDACTION_VLM_ROOT:-}" ]; then
  exec "$JAVA_EXE" -Dfile.encoding=UTF-8 "-Ddocredaction.worker.maxCommittedMemory=4096m" "-Ddocredaction.ofd.classpath=$OFD_CLASSPATH" \
    "-Ddocredaction.ocr.executable=$DOC_REDACTION_OCR_ROOT/bin/tesseract" \
    "-Ddocredaction.ocr.dataPath=$DOC_REDACTION_OCR_ROOT/share/tessdata" \
    "-Ddocredaction.media.ffmpeg=$DOC_REDACTION_MEDIA_ROOT/ffmpeg/bin/ffmpeg" \
    "-Ddocredaction.media.ffprobe=$DOC_REDACTION_MEDIA_ROOT/ffmpeg/bin/ffprobe" \
    "-Ddocredaction.media.whisper=$DOC_REDACTION_MEDIA_ROOT/whisper/whisper-cli" \
    "-Ddocredaction.media.whisperModel=$DOC_REDACTION_MEDIA_ROOT/models/ggml-base-q5_1.bin" \
    "-Ddocredaction.media.faceModel=$DOC_REDACTION_MEDIA_ROOT/models/face_detection_yunet_2023mar.onnx" \
    "-Ddocredaction.media.plateModel=$DOC_REDACTION_MEDIA_ROOT/models/license_plate_detection_lpd_yunet_2023mar.onnx" \
    "-Ddocredaction.media.opencvLibrary=$DOC_REDACTION_MEDIA_ROOT/opencv/lib/libopencv_java4130.dylib" \
    "-Ddocredaction.vlm.mode=auto" \
    "-Ddocredaction.vlm.executable=$DOC_REDACTION_VLM_ROOT/bin/llama-server" \
    "-Ddocredaction.vlm.model=$DOC_REDACTION_VLM_ROOT/models/Qwen3VL-2B-Instruct-Q4_K_M.gguf" \
    "-Ddocredaction.vlm.mmproj=$DOC_REDACTION_VLM_ROOT/models/mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf" \
    -Xms256m -Xmx4096m -jar "$APP_JAR" --data "$SCRIPT_DIR/data" "$@"
fi
exec "$JAVA_EXE" -Dfile.encoding=UTF-8 "-Ddocredaction.worker.maxCommittedMemory=4096m" "-Ddocredaction.ofd.classpath=$OFD_CLASSPATH" \
  -Xms256m -Xmx4096m -jar "$APP_JAR" --data "$SCRIPT_DIR/data" "$@"
