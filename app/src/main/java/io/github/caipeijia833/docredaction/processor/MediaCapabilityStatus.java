/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import java.util.List;

/** Sanitized capability snapshot suitable for the local configuration API. */
public record MediaCapabilityStatus(
        boolean available,
        String ffmpegVersion,
        String whisperVersion,
        String openCvVersion,
        boolean faceModelAvailable,
        boolean plateModelAvailable,
        List<String> problems) {

    public MediaCapabilityStatus {
        problems = List.copyOf(problems);
    }

    public static MediaCapabilityStatus inspect(LocalOcrEngine.Capability ocrCapability) {
        MediaToolchain.Capability capability = new MediaToolchain().capability();
        java.util.ArrayList<String> problems = new java.util.ArrayList<>(capability.problems());
        if (ocrCapability == null || !ocrCapability.available()) {
            problems.add(ocrCapability == null ? "本地Tesseract OCR能力检查失败" : ocrCapability.message());
        }
        String openCvVersion = "";
        try {
            openCvVersion = OpenCvRuntime.loadAndVersion();
        } catch (java.io.IOException ex) {
            problems.add(ex.getMessage());
        }
        return new MediaCapabilityStatus(problems.isEmpty(), capability.ffmpegVersion(),
                capability.whisperVersion(), openCvVersion, capability.faceModelAvailable(),
                capability.plateModelAvailable(), problems);
    }
}
