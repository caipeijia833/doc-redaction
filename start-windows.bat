@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

set "JAVA_EXE=%~dp0.tools\jdk-dist\jdk-21.0.12+8\bin\java.exe"
set "APP_JAR=%~dp0app\build\libs\doc-redaction-poc-0.1.0-poc-all.jar"
set "OFD_WORKER_JAR=%~dp0app\build\ofd-worker\doc-redaction-ofd-worker-0.1.0-poc.jar"
set "OFD_CLASSPATH=%OFD_WORKER_JAR%;%~dp0app\build\ofd-worker\*"
set "OCR_EXE=%~dp0.tools\ocr-minimal-build\runtime\bin\tesseract.exe"
set "OCR_DATA=%~dp0.tools\ocr-minimal-build\runtime\share\tessdata"
for /d %%D in ("%~dp0.tools\media-components\ffmpeg-8.1-win64-lgpl-shared\*") do set "FFMPEG_ROOT=%%~fD"
set "WHISPER_ROOT=%~dp0.tools\media-components\whisper-1.9.2-win64\Release"
set "MEDIA_MODELS=%~dp0.tools\media-models"
set "OPENCV_DLL=%~dp0.tools\opencv-official-4.13.0\opencv\build\java\x64\opencv_java4130.dll"
set "WHISPER_MODEL=%~dp0.tools\media-downloads\ggml-base-q5_1.bin"
set "VLM_EXE=%~dp0.tools\vlm-runtime\cpu\llama-server.exe"
set "VLM_MODE=auto"
if not exist "%VLM_EXE%" set "VLM_MODE=disabled"

if not exist "%JAVA_EXE%" (
  echo ERROR: Bundled project JDK was not found.
  echo Run build-windows.ps1 after restoring the .tools directory.
  pause
  exit /b 1
)

if not exist "%APP_JAR%" (
  echo ERROR: Application JAR was not found.
  echo Run build-windows.ps1 first.
  pause
  exit /b 1
)

if not exist "%OFD_WORKER_JAR%" (
  echo ERROR: Isolated OFD worker was not found.
  echo Run build-windows.ps1 first.
  pause
  exit /b 1
)

if exist "%OCR_EXE%" if exist "%OCR_DATA%\chi_sim.traineddata" if exist "%FFMPEG_ROOT%\bin\ffmpeg.exe" if exist "%WHISPER_ROOT%\whisper-cli.exe" (
  "%JAVA_EXE%" -Dfile.encoding=UTF-8 "-Ddocredaction.worker.requireWindowsJob=true" "-Ddocredaction.worker.maxCommittedMemory=4096m" "-Ddocredaction.ofd.classpath=%OFD_CLASSPATH%" "-Ddocredaction.ocr.executable=%OCR_EXE%" "-Ddocredaction.ocr.dataPath=%OCR_DATA%" "-Ddocredaction.media.ffmpeg=%FFMPEG_ROOT%\bin\ffmpeg.exe" "-Ddocredaction.media.ffprobe=%FFMPEG_ROOT%\bin\ffprobe.exe" "-Ddocredaction.media.whisper=%WHISPER_ROOT%\whisper-cli.exe" "-Ddocredaction.media.whisperModel=%WHISPER_MODEL%" "-Ddocredaction.media.faceModel=%MEDIA_MODELS%\face_detection_yunet_2023mar.onnx" "-Ddocredaction.media.plateModel=%MEDIA_MODELS%\license_plate_detection_lpd_yunet_2023mar.onnx" "-Ddocredaction.media.opencvLibrary=%OPENCV_DLL%" "-Ddocredaction.vlm.mode=%VLM_MODE%" "-Ddocredaction.vlm.executable=%VLM_EXE%" "-Ddocredaction.vlm.model=%~dp0.tools\vlm-models\Qwen3VL-2B-Instruct-Q4_K_M.gguf" "-Ddocredaction.vlm.mmproj=%~dp0.tools\vlm-models\mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf" -Xms256m -Xmx4096m -jar "%APP_JAR%" --data "%~dp0data"
) else if exist "%OCR_EXE%" (
  "%JAVA_EXE%" -Dfile.encoding=UTF-8 "-Ddocredaction.worker.requireWindowsJob=true" "-Ddocredaction.worker.maxCommittedMemory=4096m" "-Ddocredaction.ofd.classpath=%OFD_CLASSPATH%" "-Ddocredaction.ocr.executable=%OCR_EXE%" "-Ddocredaction.ocr.dataPath=%OCR_DATA%" -Xms256m -Xmx4096m -jar "%APP_JAR%" --data "%~dp0data"
) else (
  "%JAVA_EXE%" -Dfile.encoding=UTF-8 "-Ddocredaction.worker.requireWindowsJob=true" "-Ddocredaction.worker.maxCommittedMemory=4096m" "-Ddocredaction.ofd.classpath=%OFD_CLASSPATH%" -Xms256m -Xmx4096m -jar "%APP_JAR%" --data "%~dp0data"
)
set "APP_EXIT=%ERRORLEVEL%"
if not "%APP_EXIT%"=="0" pause
exit /b %APP_EXIT%
