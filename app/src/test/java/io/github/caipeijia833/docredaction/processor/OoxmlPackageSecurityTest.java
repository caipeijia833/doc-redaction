/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.rules.RuleEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OoxmlPackageSecurityTest {
    private final RuleEngine rules = RuleEngine.createDefault();
    private final ProcessorRegistry registry = new ProcessorRegistry();

    @TempDir
    Path temp;

    @Test
    void convertsSensitiveNumericCellsToStringWithoutLeavingTheOriginal() throws Exception {
        Path input = temp.resolve("numeric.xlsx");
        Path output = temp.resolve("numeric-redacted.xlsx");
        writeMinimalXlsx(input, "<row r=\"1\"><c r=\"A1\"><v>4532015112830366</v></c></row>");

        registry.requireProcessor(input).process(input, output, rules);

        String xml = readEntry(output, "xl/worksheets/sheet1.xml");
        assertFalse(xml.contains("4532015112830366"));
        assertTrue(xml.contains("t=\"str\""));
    }

    @Test
    void repeatsMaskingWhenTheFirstPassCreatesANewIdentifierBoundary() throws Exception {
        Path input = temp.resolve("fixed-point.xlsx");
        Path output = temp.resolve("fixed-point-redacted.xlsx");
        String source = "数据校验=319e0644382066414834e020c7359e53";
        writeMinimalXlsx(input, inlineRow(1, source));

        registry.requireProcessor(input).process(input, output, rules);

        String xml = readEntry(output, "xl/worksheets/sheet1.xml");
        assertFalse(xml.contains("4834e020c7359e53"));
        assertFalse(xml.contains("e020c7359e53319e"),
                "第一轮遮罩产生的新16位意大利税号边界也必须在同一语义单元内继续处理");
    }

    @Test
    void failsClosedWhenAFormulaContainsSensitiveLiteralData() throws Exception {
        Path input = temp.resolve("formula.xlsx");
        Path output = temp.resolve("formula-redacted.xlsx");
        writeMinimalXlsx(input, "<row r=\"1\"><c r=\"A1\" t=\"str\"><f>&quot;user@example.com&quot;</f>"
                + "<v>user@example.com</v></c></row>");

        IOException failure = assertThrows(IOException.class,
                () -> registry.requireProcessor(input).process(input, output, rules));
        assertTrue(failure.getMessage().contains("公式包含敏感信息"));
        assertFalse(Files.exists(output));
    }

    @Test
    void scrubsCustomPropertyValuesAndRejectsEmbeddedOleObjects() throws Exception {
        Path input = temp.resolve("custom.docx");
        Path output = temp.resolve("custom-redacted.docx");
        writeMinimalDocx(input, false);

        registry.requireProcessor(input).process(input, output, rules);

        String custom = readEntry(output, "docProps/custom.xml");
        assertFalse(custom.contains("Alice Johnson"));
        assertFalse(custom.contains("13800138000"));
        assertTrue(custom.contains("redacted"));

        Path unsafe = temp.resolve("embedded.docx");
        Path unsafeOutput = temp.resolve("embedded-redacted.docx");
        writeMinimalDocx(unsafe, true);
        IOException failure = assertThrows(IOException.class,
                () -> registry.requireProcessor(unsafe).process(unsafe, unsafeOutput, rules));
        assertTrue(failure.getMessage().contains("嵌入对象"));
        assertFalse(Files.exists(unsafeOutput));
    }

    @Test
    void completeResidualScanFindsAHitBeyondTheOldFiveThousandItemBoundary() throws Exception {
        Path original = temp.resolve("original.xlsx");
        Path output = temp.resolve("output.xlsx");
        StringBuilder before = new StringBuilder(900_000);
        StringBuilder after = new StringBuilder(900_000);
        for (int row = 1; row <= 5_001; row++) {
            String value = String.format("person%04d@example.com", row);
            before.append(inlineRow(row, value));
            after.append(inlineRow(row, row == 5_001 ? value : "safe-" + row));
        }
        writeMinimalXlsx(original, before.toString());
        writeMinimalXlsx(output, after.toString());

        IOException failure = assertThrows(IOException.class,
                () -> ResidualScanner.verify(original, output, rules, new ProcessReport()));
        assertTrue(failure.getMessage().contains("EMAIL_ADDRESS"));
    }

    @Test
    void removesEveryExternalOfficeRelationshipEvenWithoutSensitiveText() throws Exception {
        Path input = temp.resolve("external.docx");
        Path output = temp.resolve("external-redacted.docx");
        writeMinimalDocx(input, false);

        registry.requireProcessor(input).process(input, output, rules);

        String relationships = readEntry(output, "word/_rels/document.xml.rels");
        assertFalse(relationships.contains("https://example.invalid/tracker"));
        assertTrue(relationships.contains("about:blank"));
    }

    private static String inlineRow(int row, String value) {
        return "<row r=\"" + row + "\"><c r=\"A" + row
                + "\" t=\"inlineStr\"><is><t>" + value + "</t></is></c></row>";
    }

    private static void writeMinimalXlsx(Path target, String rows) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "</Types>");
            put(zip, "_rels/.rels", relationships("xl/workbook.xml"));
            put(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Synthetic\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "</Relationships>");
            put(zip, "xl/worksheets/sheet1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<sheetData>" + rows + "</sheetData></worksheet>");
        }
    }

    private static void writeMinimalDocx(Path target, boolean embedded) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "<Override PartName=\"/docProps/custom.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.custom-properties+xml\"/>"
                    + "</Types>");
            put(zip, "_rels/.rels", relationships("word/document.xml"));
            put(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body><w:p><w:r><w:t>Safe</w:t></w:r></w:p></w:body></w:document>");
            put(zip, "word/_rels/document.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId9\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" "
                    + "Target=\"https://example.invalid/tracker\" TargetMode=\"External\"/></Relationships>");
            put(zip, "docProps/custom.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/custom-properties\" "
                    + "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">"
                    + "<property fmtid=\"{D5CDD505-2E9C-101B-9397-08002B2CF9AE}\" pid=\"2\" name=\"Owner\">"
                    + "<vt:lpwstr>Alice Johnson 13800138000</vt:lpwstr></property></Properties>");
            if (embedded) put(zip, "word/embeddings/object1.bin", "synthetic");
        }
    }

    private static String relationships(String target) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\""
                + target + "\"/></Relationships>";
    }

    private static void put(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String readEntry(Path file, String name) throws Exception {
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            try (var input = zip.getInputStream(zip.getEntry(name))) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
