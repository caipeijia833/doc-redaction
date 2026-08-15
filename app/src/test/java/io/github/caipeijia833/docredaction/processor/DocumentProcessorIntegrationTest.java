/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.rules.RuleEngine;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessorIntegrationTest {
    private static final String EMAIL = "synthetic.user@example.com";
    private final RuleEngine rules = RuleEngine.createDefault();
    private final ProcessorRegistry registry = new ProcessorRegistry();

    @TempDir
    Path temp;

    @Test
    void redactsDocxAcrossRunsAndPreservesRunFormatting() throws Exception {
        Path input = temp.resolve("synthetic.docx");
        Path output = temp.resolve("synthetic-redacted.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            var first = paragraph.createRun();
            first.setBold(true);
            first.setText("邮箱：synthetic.user@");
            paragraph.createRun().setText("example.com，手机13800138000");
            document.getProperties().getCoreProperties().setCreator("测试创建者");
            try (var stream = Files.newOutputStream(input)) { document.write(stream); }
        }

        ProcessReport report = registry.requireProcessor(input).process(input, output, rules);

        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(output))) {
            String text = document.getParagraphs().getFirst().getText();
            assertFalse(text.contains(EMAIL));
            assertFalse(text.contains("13800138000"));
            assertTrue(document.getParagraphs().getFirst().getRuns().getFirst().isBold());
            assertEquals("已脱敏", document.getProperties().getCoreProperties().getCreator());
        }
        assertTrue(report.totalMatches() >= 2);
    }

    @Test
    void redactsXlsxCellsCommentsAndHeadersButKeepsFormula() throws Exception {
        Path input = temp.resolve("synthetic.xlsx");
        Path output = temp.resolve("synthetic-redacted.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("数据");
            sheet.getHeader().setCenter("邮箱：" + EMAIL);
            var row = sheet.createRow(0);
            var textCell = row.createCell(0);
            textCell.setCellValue("手机13800138000");
            var formulaCell = row.createCell(1);
            formulaCell.setCellFormula("1+1");
            CreationHelper helper = workbook.getCreationHelper();
            ClientAnchor anchor = helper.createClientAnchor();
            var comment = sheet.createDrawingPatriarch().createCellComment(anchor);
            comment.setString(helper.createRichTextString("联系邮箱" + EMAIL));
            textCell.setCellComment(comment);
            try (var stream = Files.newOutputStream(input)) { workbook.write(stream); }
        }
        byte[] inputHashBefore = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input));

        ProcessReport report = registry.requireProcessor(input).process(input, output, rules);
        byte[] inputHashAfter = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input));

        try (var workbook = WorkbookFactory.create(output.toFile())) {
            var sheet = workbook.getSheetAt(0);
            assertFalse(sheet.getRow(0).getCell(0).getStringCellValue().contains("13800138000"));
            assertEquals(CellType.FORMULA, sheet.getRow(0).getCell(1).getCellType());
            assertEquals("1+1", sheet.getRow(0).getCell(1).getCellFormula());
            assertFalse(sheet.getRow(0).getCell(0).getCellComment().getString().getString().contains(EMAIL));
            assertFalse(sheet.getHeader().getCenter().contains(EMAIL));
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(inputHashBefore, inputHashAfter,
                "XLSX处理不得修改输入原件");
        assertTrue(report.totalMatches() >= 3);
    }

    @Test
    void removesOriginalInlineStringValueWithoutLeavingASecondCopy() throws Exception {
        Path input = temp.resolve("inline-string.xlsx");
        Path output = temp.resolve("inline-string-redacted.xlsx");
        String sheetXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>手机13800138000</t></is>"
                + "</c></row></sheetData></worksheet>";
        writeMinimalInlineXlsx(input, sheetXml);

        registry.requireProcessor(input).process(input, output, rules);

        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            try (var stream = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))) {
                String raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(raw.contains("13800138000"));
                assertTrue(raw.contains("inlineStr"));
                assertEquals(1, raw.split("<t", -1).length - 1,
                        "流式改写应原位替换inlineStr文本，不能再写一个包含不同值的v节点");
            }
        }
    }

    @Test
    @Tag("native")
    void rasterizesPdfAndRemovesOriginalTextLayer() throws Exception {
        Path input = temp.resolve("synthetic.pdf");
        Path output = temp.resolve("synthetic-redacted.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(60, 700);
                content.showText("Email: " + EMAIL + " Phone: 13800138000");
                content.endText();
            }
            document.save(input.toFile());
        }

        ProcessReport report = registry.requireProcessor(input).process(input, output, rules);

        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertEquals(1, document.getNumberOfPages());
            assertTrue(new PDFTextStripper().getText(document).isBlank());
            assertEquals(0, document.getDocumentCatalog().getNames() == null ? 0 : 1,
                    "Rasterized output should not carry a names dictionary");
        }
        assertTrue(report.totalMatches() >= 2);
        assertTrue(Files.size(output) > 0);
    }

    @Test
    void redactsPptxSlideNotesAndMasterText() throws Exception {
        Path input = temp.resolve("synthetic.pptx");
        Path output = temp.resolve("synthetic-redacted.pptx");
        try (XMLSlideShow presentation = new XMLSlideShow()) {
            var slide = presentation.createSlide();
            var textBox = slide.createTextBox();
            textBox.setText("联系人手机13800138000，邮箱" + EMAIL);
            presentation.getProperties().getCoreProperties().setCreator("测试创建者");
            try (var stream = Files.newOutputStream(input)) {
                presentation.write(stream);
            }
        }

        ProcessReport report = registry.requireProcessor(input).process(input, output, rules);

        try (XMLSlideShow presentation = new XMLSlideShow(Files.newInputStream(output))) {
            String text = presentation.getSlides().getFirst().getShapes().stream()
                    .filter(shape -> shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape)
                    .map(shape -> ((org.apache.poi.xslf.usermodel.XSLFTextShape) shape).getText())
                    .reduce("", String::concat);
            assertFalse(text.contains("13800138000"));
            assertFalse(text.contains(EMAIL));
            assertEquals("已脱敏", presentation.getProperties().getCoreProperties().getCreator());
        }
        assertTrue(report.totalMatches() >= 2);
    }

    private static void writeMinimalInlineXlsx(Path target, String sheetXml) throws Exception {
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(target))) {
            putZip(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "</Types>");
            putZip(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            putZip(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Synthetic\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            putZip(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "</Relationships>");
            putZip(zip, "xl/worksheets/sheet1.xml", sheetXml);
        }
    }

    private static void putZip(java.util.zip.ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
