/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import org.apache.poi.openxml4j.util.ZipSecureFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class ProcessorRegistry {
    private final List<DocumentProcessor> processors;

    public ProcessorRegistry() {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(2L * 1024 * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(512L * 1024 * 1024);
        ZipSecureFile.setMaxFileCount(100_000L);
        this.processors = List.of(new DocxProcessor(), new XlsxProcessor(), new PptxProcessor(),
                new PdfProcessor(), new ImageProcessor(), new OfdProcessor(), new MediaProcessor());
    }

    public DocumentProcessor requireProcessor(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return processors.stream()
                .filter(processor -> processor.extensions().contains(extension))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前PoC不支持该文件格式: " + extension));
    }

    public List<String> supportedExtensions() {
        return processors.stream().flatMap(p -> p.extensions().stream()).sorted().toList();
    }
}
