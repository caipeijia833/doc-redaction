# OpenCV Java binding provenance

`opencv-4130.jar` is the Java binding extracted from the official OpenCV
4.13.0 Windows release. It is committed so a clean source checkout can compile
without relying on a maintainer-specific `.tools` cache. Native OpenCV
libraries are not committed; they are downloaded by the explicit preparation
script or distributed in an offline release package.

- Upstream: https://github.com/opencv/opencv
- Release: https://github.com/opencv/opencv/releases/tag/4.13.0
- Archive: `opencv-4.13.0-windows.exe`
- Archive SHA-256: `f0e98c302464d6860777a7015065e11b9b271b5394e6ba92663f0cf1fc303f2c`
- `opencv-4130.jar` SHA-256: `00e2c856933993d948910f77344ac99ce38b0f875af2866a164f8fff82e9fb5f`
- License: Apache-2.0; see `LICENSE-OPENCV.txt` and the repository's
  `THIRD_PARTY_NOTICES.md`.

The Gradle `verifyOpenCvBinding` task checks the JAR hash before compilation.
