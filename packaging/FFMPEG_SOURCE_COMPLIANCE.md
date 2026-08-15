# FFmpeg source-compliance gate

The Windows package currently uses BtbN `win64-lgpl-shared` FFmpeg `n8.1.2-34-g9b6c8969e0-20260812`. Runtime preflight rejects `--enable-gpl` and `--enable-nonfree`, requires shared FFmpeg libraries, and packages LGPL-3.0-or-later text.

`tools/Prepare-FfmpegSourceCompliance.ps1` downloads and verifies:

- FFmpeg commit `9b6c8969e05b4f0b29f0f85cd501be6b3e582e6b` source;
- BtbN FFmpeg-Builds commit `2a3249ec58228c661e7ff8fdc9ea997b18aa912b` build recipe.

That is necessary but not yet asserted to be a complete corresponding-source closure. The selected BtbN build enables many optional libraries and statically links dependencies into the shared FFmpeg libraries. Before publishing the Windows binary Release, choose one verified route:

1. archive the exact source for every LGPL-covered statically linked component selected by the pinned build recipe, including patches and relink instructions; or
2. replace the component with a reproducible, source-complete minimal LGPL shared build containing only the codecs, demuxers, muxers and filters used by this application.

The release bundler requires `.tools/source-compliance/FFMPEG_SOURCE_CLOSURE.json` with `status: complete`. The preparation script intentionally writes `status: incomplete` until one of the routes above is independently verified. Do not change the status merely to bypass the gate.

This gate affects binary Release publication, not publication of the Apache-2.0 project source repository without bundled native binaries.
