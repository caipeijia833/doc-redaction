/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.archive;

import io.github.caipeijia833.docredaction.processor.ProcessorRegistry;
import io.github.caipeijia833.docredaction.rules.RuleEngine;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveProcessorIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void inspectsAndProcessesOnlySafeSupportedEntriesByDefault() throws Exception {
        Path input = temp.resolve("卷宗.zip");
        createInputZip(input);

        ArchiveInspection inspection = new ArchiveInspector().inspect(input);

        assertEquals("zip", inspection.format());
        assertEquals(8, inspection.fileCount());
        assertEquals(1, inspection.processableCount());
        assertEquals(7, inspection.excludedByDefaultCount());
        assertTrue(inspection.counts().getOrDefault(ArchiveCategory.DANGEROUS, 0) >= 2);

        Path output = temp.resolve("卷宗_脱敏.zip");
        new ArchiveProcessor(new ProcessorRegistry(), RuleEngine.createDefault())
                .process(input, output, temp.resolve("work"), false);

        try (ZipFile zip = ZipFile.builder().setPath(output).get()) {
            ZipArchiveEntry redacted = zip.getEntry("材料/手机号REDACTED.docx");
            assertNotNull(redacted);
            try (InputStream content = zip.getInputStream(redacted); XWPFDocument document = new XWPFDocument(content)) {
                assertFalse(document.getParagraphs().getFirst().getText().contains("13800138000"));
            }
            assertNotNull(zip.getEntry("_doc_redaction_manifest.json"));
            assertNull(zip.getEntry("说明.txt"));
            assertNull(zip.getEntry("run.ps1"));
            assertNull(zip.getEntry("../escape.txt"));
            assertNull(zip.getEntry("../windows-escape.txt"));
            assertNull(zip.getEntry("/rooted-escape.txt"));
            assertNull(zip.getEntry("C:/drive-escape.txt"));
            assertNull(zip.getEntry("folder/../../nested-escape.txt"));
        }
    }

    @Test
    void includesOrdinaryUnsupportedEntriesOnlyAfterConfirmationFlag() throws Exception {
        Path input = temp.resolve("卷宗.zip");
        createInputZip(input);
        Path output = temp.resolve("卷宗_含未处理.zip");

        new ArchiveProcessor(new ProcessorRegistry(), RuleEngine.createDefault())
                .process(input, output, temp.resolve("work-include"), true);

        try (ZipFile zip = ZipFile.builder().setPath(output).get()) {
            assertNotNull(zip.getEntry("说明_REDACTED.txt"));
            assertNull(zip.getEntry("run.ps1"));
            assertNull(zip.getEntry("../escape.txt"));
        }
    }

    @Test
    void appliesPerEntryDecisionsAndNeverKeepsDangerousEntries() throws Exception {
        Path input = temp.resolve("decisions.zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(input.toFile())) {
            add(zip, "case.docx", syntheticDocx());
            add(zip, "notes.txt", "ordinary attachment".getBytes(StandardCharsets.UTF_8));
            add(zip, "run.ps1", "Write-Host unsafe".getBytes(StandardCharsets.UTF_8));
        }
        Path output = temp.resolve("decisions-redacted.zip");

        new ArchiveProcessor(new ProcessorRegistry(), RuleEngine.createDefault())
                .process(input, output, temp.resolve("work-decisions"), true, Map.of(
                        1, ArchiveEntryAction.EXCLUDE,
                        2, ArchiveEntryAction.KEEP_UNPROCESSED,
                        3, ArchiveEntryAction.KEEP_UNPROCESSED));

        try (ZipFile zip = ZipFile.builder().setPath(output).get()) {
            assertNull(zip.getEntry("case.docx"));
            assertNotNull(zip.getEntry("notes.txt"));
            assertNull(zip.getEntry("run.ps1"));
            ZipArchiveEntry manifest = zip.getEntry("_doc_redaction_manifest.json");
            assertNotNull(manifest);
            String json;
            try (InputStream content = zip.getInputStream(manifest)) {
                json = new String(content.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertTrue(json.contains("\"originalPath\":\"notes.txt\""));
            assertTrue(json.contains("\"action\":\"included_unprocessed\""));
            assertTrue(json.contains("\"originalPath\":\"run.ps1\""));
            assertTrue(json.contains("\"action\":\"excluded\""));
        }
    }

    @Test
    void processesTarArchive() throws Exception {
        processAndVerifyTar("case.tar", false);
    }

    @Test
    void processesTarGzArchive() throws Exception {
        processAndVerifyTar("case.tar.gz", true);
    }

    @Test
    void processesSevenZipArchive() throws Exception {
        Path input = temp.resolve("case.7z");
        Path sourceDocument = temp.resolve("source.docx");
        Files.write(sourceDocument, syntheticDocx());
        try (SevenZOutputFile archive = new SevenZOutputFile(input.toFile())) {
            SevenZArchiveEntry entry = archive.createArchiveEntry(sourceDocument.toFile(), "case/document.docx");
            archive.putArchiveEntry(entry);
            archive.write(sourceDocument);
            archive.closeArchiveEntry();
            SevenZArchiveEntry traversal = archive.createArchiveEntry(sourceDocument.toFile(), "../escape.docx");
            archive.putArchiveEntry(traversal);
            archive.write(sourceDocument);
            archive.closeArchiveEntry();
        }

        ArchiveInspection inspection = new ArchiveInspector().inspect(input);
        assertEquals("7z", inspection.format());
        assertEquals(1, inspection.processableCount());

        Path output = temp.resolve("case-redacted.7z");
        new ArchiveProcessor(new ProcessorRegistry(), RuleEngine.createDefault())
                .process(input, output, temp.resolve("work-7z"), false);

        try (SevenZFile archive = SevenZFile.builder().setPath(output).get()) {
            SevenZArchiveEntry documentEntry = null;
            boolean foundManifest = false;
            for (SevenZArchiveEntry entry : archive.getEntries()) {
                assertTrue(ArchivePolicy.isSafeRelativePath(entry.getName()), entry.getName());
                if (entry.getName().equals("case/document.docx")) {
                    documentEntry = entry;
                }
                if (entry.getName().equals("_doc_redaction_manifest.json")) {
                    foundManifest = true;
                }
            }
            assertNotNull(documentEntry);
            try (InputStream content = archive.getInputStream(documentEntry);
                 XWPFDocument document = new XWPFDocument(content)) {
                assertFalse(document.getParagraphs().getFirst().getText().contains("13800138000"));
            }
            assertTrue(foundManifest);
        }
    }

    @Test
    void recognizesButRejectsRar() throws Exception {
        Path rar = temp.resolve("case.rar");
        Files.write(rar, new byte[] {0x52, 0x61, 0x72, 0x21});

        ArchiveInspection inspection = new ArchiveInspector().inspect(rar);

        assertEquals("rar", inspection.format());
        assertTrue(inspection.rejected());
        assertFalse(inspection.warnings().isEmpty());
    }

    @Test
    void rejectsZipWithSuspiciousCompressionRatio() throws Exception {
        Path input = temp.resolve("compression-bomb.zip");
        byte[] repeated = new byte[2 * 1024 * 1024];
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(input.toFile())) {
            add(zip, "case/repeated.txt", repeated);
        }

        ArchiveInspection inspection = new ArchiveInspector().inspect(input);

        assertTrue(inspection.rejected());
        assertTrue(inspection.warnings().stream().anyMatch(message -> message.contains("压缩倍率")));
    }

    private void processAndVerifyTar(String fileName, boolean gzip) throws Exception {
        Path input = temp.resolve(fileName);
        byte[] documentBytes = syntheticDocx();
        try (OutputStream raw = Files.newOutputStream(input);
             OutputStream encoded = gzip ? new GzipCompressorOutputStream(raw) : raw;
             TarArchiveOutputStream archive = new TarArchiveOutputStream(encoded)) {
            TarArchiveEntry entry = new TarArchiveEntry("case/document.docx");
            entry.setSize(documentBytes.length);
            entry.setModTime(0);
            archive.putArchiveEntry(entry);
            archive.write(documentBytes);
            archive.closeArchiveEntry();
            TarArchiveEntry traversal = new TarArchiveEntry("../escape.docx");
            traversal.setSize(documentBytes.length);
            traversal.setModTime(0);
            archive.putArchiveEntry(traversal);
            archive.write(documentBytes);
            archive.closeArchiveEntry();
        }

        ArchiveInspection inspection = new ArchiveInspector().inspect(input);
        assertEquals(gzip ? "tar.gz" : "tar", inspection.format());
        assertEquals(1, inspection.processableCount());

        Path output = temp.resolve(gzip ? "case-redacted.tar.gz" : "case-redacted.tar");
        new ArchiveProcessor(new ProcessorRegistry(), RuleEngine.createDefault())
                .process(input, output, temp.resolve(gzip ? "work-targz" : "work-tar"), false);

        boolean foundDocument = false;
        boolean foundManifest = false;
        try (InputStream raw = Files.newInputStream(output);
             InputStream decoded = gzip ? new GzipCompressorInputStream(raw) : raw;
             TarArchiveInputStream archive = new TarArchiveInputStream(decoded)) {
            TarArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                assertTrue(ArchivePolicy.isSafeRelativePath(entry.getName()), entry.getName());
                if (entry.getName().equals("case/document.docx")) {
                    foundDocument = true;
                    try (XWPFDocument document = new XWPFDocument(archive)) {
                        assertFalse(document.getParagraphs().getFirst().getText().contains("13800138000"));
                    }
                    break;
                }
                if (entry.getName().equals("_doc_redaction_manifest.json")) {
                    foundManifest = true;
                }
            }
        }
        assertTrue(foundDocument);

        try (InputStream raw = Files.newInputStream(output);
             InputStream decoded = gzip ? new GzipCompressorInputStream(raw) : raw;
             TarArchiveInputStream archive = new TarArchiveInputStream(decoded)) {
            TarArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (entry.getName().equals("_doc_redaction_manifest.json")) {
                    foundManifest = true;
                    break;
                }
            }
        }
        assertTrue(foundManifest);
    }

    private static void createInputZip(Path target) throws Exception {
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(target.toFile())) {
            add(zip, "材料/手机号13800138000.docx", syntheticDocx());
            add(zip, "说明_13800138000.txt", "未检查文本".getBytes(StandardCharsets.UTF_8));
            add(zip, "run.ps1", "Write-Host unsafe".getBytes(StandardCharsets.UTF_8));
            add(zip, "../escape.txt", "escape".getBytes(StandardCharsets.UTF_8));
            add(zip, "..\\windows-escape.txt", "escape".getBytes(StandardCharsets.UTF_8));
            add(zip, "/rooted-escape.txt", "escape".getBytes(StandardCharsets.UTF_8));
            add(zip, "C:\\drive-escape.txt", "escape".getBytes(StandardCharsets.UTF_8));
            add(zip, "folder/../../nested-escape.txt", "escape".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void add(ZipArchiveOutputStream zip, String name, byte[] bytes) throws Exception {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setSize(bytes.length);
        zip.putArchiveEntry(entry);
        zip.write(bytes);
        zip.closeArchiveEntry();
    }

    private static byte[] syntheticDocx() throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("手机号13800138000");
            document.write(output);
            return output.toByteArray();
        }
    }
}
