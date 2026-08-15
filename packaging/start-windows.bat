@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

set "PREFLIGHT=%~dp0preflight-windows.ps1"
set "RUNTIME_OPTIONS=%~dp0data\diagnostics\runtime-options.cmd"

if not exist "%PREFLIGHT%" (
  echo ERROR: The offline environment preflight script is missing.
  pause
  exit /b 1
)

powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%PREFLIGHT%" -PackageRoot "%~dp0."
if errorlevel 1 (
  echo.
  echo Startup was blocked. See data\diagnostics\environment-report.json for details.
  pause
  exit /b 1
)

if not exist "%RUNTIME_OPTIONS%" (
  echo ERROR: Environment preflight did not create runtime options.
  pause
  exit /b 1
)
call "%RUNTIME_OPTIONS%"

set "JAVA_EXE=%~dp0runtime\bin\java.exe"
set "APP_JAR=%~dp0app\doc-redaction-poc.jar"
set "OFD_WORKER_JAR=%~dp0app\ofd-worker\doc-redaction-ofd-worker-0.1.0-poc.jar"
set "OFD_CLASSPATH=%OFD_WORKER_JAR%;%~dp0app\ofd-worker\*"
set "OCR_EXE=%~dp0ocr\bin\tesseract.exe"
set "OCR_DATA=%~dp0ocr\share\tessdata"
set "MEDIA_ROOT=%~dp0media"
set "OPENCV_DLL=%MEDIA_ROOT%\opencv\bin\opencv_java4130.dll"
set "VLM_EXE=%~dp0%DOC_REDACTION_VLM_RELATIVE_EXE%"

if not exist "%JAVA_EXE%" (
  echo ERROR: The bundled Java runtime is missing.
  pause
  exit /b 1
)

if not exist "%APP_JAR%" (
  echo ERROR: The application JAR is missing.
  pause
  exit /b 1
)

if not exist "%OFD_WORKER_JAR%" (
  echo ERROR: The isolated OFD worker is missing.
  pause
  exit /b 1
)

"%JAVA_EXE%" -Dfile.encoding=UTF-8 "-Ddocredaction.environment.profile=%DOC_REDACTION_ENV_PROFILE%" "-Ddocredaction.worker.maxHeap=%DOC_REDACTION_WORKER_XMX%" "-Ddocredaction.worker.maxCommittedMemory=%DOC_REDACTION_WORKER_COMMIT_LIMIT%" "-Ddocredaction.worker.requireWindowsJob=true" "-Ddocredaction.worker.concurrency=%DOC_REDACTION_WORKER_CONCURRENCY%" "-Ddocredaction.media.maxSampleFrames=%DOC_REDACTION_MEDIA_SAMPLE_FRAMES%" "-Ddocredaction.media.asrThreads=%DOC_REDACTION_ASR_THREADS%" "-Ddocredaction.ofd.classpath=%OFD_CLASSPATH%" "-Ddocredaction.ocr.executable=%OCR_EXE%" "-Ddocredaction.ocr.dataPath=%OCR_DATA%" "-Ddocredaction.media.ffmpeg=%MEDIA_ROOT%\ffmpeg\bin\ffmpeg.exe" "-Ddocredaction.media.ffprobe=%MEDIA_ROOT%\ffmpeg\bin\ffprobe.exe" "-Ddocredaction.media.whisper=%MEDIA_ROOT%\whisper\whisper-cli.exe" "-Ddocredaction.media.whisperModel=%MEDIA_ROOT%\models\ggml-base-q5_1.bin" "-Ddocredaction.media.faceModel=%MEDIA_ROOT%\models\face_detection_yunet_2023mar.onnx" "-Ddocredaction.media.plateModel=%MEDIA_ROOT%\models\license_plate_detection_lpd_yunet_2023mar.onnx" "-Ddocredaction.media.opencvLibrary=%OPENCV_DLL%" "-Ddocredaction.vlm.mode=%DOC_REDACTION_VLM_MODE%" "-Ddocredaction.vlm.executable=%VLM_EXE%" "-Ddocredaction.vlm.model=%~dp0vlm\models\Qwen3VL-2B-Instruct-Q4_K_M.gguf" "-Ddocredaction.vlm.mmproj=%~dp0vlm\models\mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf" -Xms256m -Xmx%DOC_REDACTION_APP_XMX% -jar "%APP_JAR%" --data "%~dp0data" %*
set "APP_EXIT=%ERRORLEVEL%"
if not "%APP_EXIT%"=="0" pause
exit /b %APP_EXIT%
