/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.tools;

import org.apache.pdfbox.Loader;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StressCorpusGeneratorTest {
    @TempDir
    Path temp;

    @Test
    void smokeCorpusIsDeterministicSyntheticAndParserValid() throws Exception {
        Path manifest = StressCorpusGenerator.generate(temp.resolve("stress"),
                StressCorpusGenerator.Profile.SMOKE);
        String json = Files.readString(manifest, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"syntheticOnly\":true"));
        assertTrue(json.contains("CONTENT_DENSITY"));

        Path office = manifest.getParent().resolve("office-content-ladder");
        try (XWPFDocument document = new XWPFDocument(
                Files.newInputStream(office.resolve("synthetic-docx-2mib.docx")))) {
            assertTrue(document.getParagraphs().size() > 100);
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                Files.newInputStream(office.resolve("synthetic-xlsx-2mib.xlsx")))) {
            assertTrue(workbook.getSheetAt(0).getLastRowNum() > 100);
        }
        try (XMLSlideShow presentation = new XMLSlideShow(
                Files.newInputStream(office.resolve("synthetic-pptx-2mib.pptx")))) {
            assertEquals(1, presentation.getSlides().size());
        }
        try (var pdf = Loader.loadPDF(manifest.getParent().resolve("pdf-content-ladder")
                .resolve("synthetic-pdf-4mib.pdf").toFile())) {
            assertTrue(pdf.getNumberOfPages() >= 1);
        }
    }
}
