/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import org.opencv.core.Core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Loads the hash-pinned official OpenCV JNI library without extracting code at runtime. */
public final class OpenCvRuntime {
    private static final String EXPECTED_VERSION = "4.13.0";
    private static volatile boolean loaded;

    private OpenCvRuntime() {
    }

    public static synchronized String loadAndVersion() throws IOException {
        if (loaded) {
            return Core.VERSION;
        }
        List<Path> candidates = configuredCandidates();
        UnsatisfiedLinkError lastError = null;
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(candidate)) {
                continue;
            }
            try {
                System.load(candidate.toAbsolutePath().normalize().toString());
                verifyVersion();
                loaded = true;
                return Core.VERSION;
            } catch (UnsatisfiedLinkError ex) {
                lastError = ex;
            }
        }
        if (candidates.isEmpty()) {
            try {
                System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
                verifyVersion();
                loaded = true;
                return Core.VERSION;
            } catch (UnsatisfiedLinkError ex) {
                lastError = ex;
            }
        }
        String suffix = lastError == null || lastError.getMessage() == null
                ? "" : "：" + lastError.getMessage().replaceAll("[\\r\\n\\t]+", " ").trim();
        throw new IOException("未找到或无法加载经过校验的OpenCV " + EXPECTED_VERSION + "本地运行库" + suffix,
                lastError);
    }

    private static List<Path> configuredCandidates() {
        String configured = System.getProperty("docredaction.media.opencvLibrary", "").trim();
        if (!configured.isBlank()) {
            return List.of(Path.of(configured));
        }
        String name = libraryFileName();
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of("media", "opencv", "bin", name));
        candidates.add(Path.of("..", ".tools", "opencv-official-4.13.0", "opencv",
                "build", "java", architectureDirectory(), name));
        return List.copyOf(candidates);
    }

    private static String libraryFileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return Core.NATIVE_LIBRARY_NAME + ".dll";
        }
        if (os.contains("mac")) {
            return "lib" + Core.NATIVE_LIBRARY_NAME + ".dylib";
        }
        return "lib" + Core.NATIVE_LIBRARY_NAME + ".so";
    }

    private static String architectureDirectory() {
        return System.getProperty("os.arch", "").toLowerCase(Locale.ROOT).contains("64") ? "x64" : "x86";
    }

    private static void verifyVersion() throws IOException {
        if (!EXPECTED_VERSION.equals(Core.VERSION)) {
            throw new IOException("OpenCV运行库版本不匹配：需要" + EXPECTED_VERSION + "，实际" + Core.VERSION);
        }
    }
}
