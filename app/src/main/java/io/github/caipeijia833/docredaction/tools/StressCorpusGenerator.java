/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.tools;

import io.github.caipeijia833.docredaction.util.JsonUtil;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates deterministic, wholly synthetic large-document test data.
 *
 * <p>The corpus deliberately separates physical-size boundary tests from
 * content-density tests. It must never be treated as real personal data or as
 * proof that every generated tier is processable on every machine.</p>
 */
public final class StressCorpusGenerator {
    public static final long ONE_GIB = 1024L * 1024 * 1024;
    private static final long MIB = 1024L * 1024;
    private static final int COPY_BUFFER_BYTES = 8 * 1024 * 1024;
    private static final long SEED = 0x53414E4954495A45L;
    private static final String NOTICE_ZH = "【完全合成压力测试数据，不对应任何真实个人、企业或案件】";
    private static final String NOTICE_EN = "[WHOLLY SYNTHETIC STRESS DATA; NO REAL PERSON, COMPANY OR CASE]";
    private static final String PHONE = "13800138000";
    private static final String EMAIL = "synthetic.user@example.com";
    private static final String CASE_NO = "（2026）测0101民初0001号";

    private StressCorpusGenerator() {
    }

    public enum Profile {
        SMOKE,
        FULL;

        public static Profile parse(String value) {
            if (value == null || value.isBlank()) {
                return FULL;
            }
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public static Path generate(Path root, Profile profile) throws Exception {
        Path outputRoot = root.toAbsolutePath().normalize();
        Files.createDirectories(outputRoot);
        Path control = outputRoot.resolve("control-samples");
        SyntheticSampleGenerator.generate(control);

        List<CorpusItem> items = new ArrayList<>();
        addExisting(items, outputRoot, control.resolve("合成验收样例.docx"), "CONTROL", 0L,
                "小型DOCX规则回归样例");
        addExisting(items, outputRoot, control.resolve("合成验收样例.xlsx"), "CONTROL", 0L,
                "小型XLSX规则回归样例");
        addExisting(items, outputRoot, control.resolve("合成验收样例.pptx"), "CONTROL", 0L,
                "小型PPTX规则回归样例");
        addExisting(items, outputRoot, control.resolve("synthetic-acceptance-sample.pdf"), "CONTROL", 0L,
                "小型PDF规则回归样例");

        long[] officeTiers = profile == Profile.FULL
                ? new long[]{64 * MIB, 128 * MIB, 256 * MIB, 512 * MIB, ONE_GIB}
                : new long[]{2 * MIB};
        long[] pdfTiers = profile == Profile.FULL
                ? new long[]{256 * MIB, 512 * MIB, ONE_GIB}
                : new long[]{4 * MIB};

        Path office = outputRoot.resolve("office-content-ladder");
        Files.createDirectories(office);
        for (long tier : officeTiers) {
            String label = sizeLabel(tier);
            Path docx = office.resolve("synthetic-docx-" + label + ".docx");
            generateDocx(docx, tier);
            addExisting(items, outputRoot, docx, "CONTENT_DENSITY", tier,
                    "有效DOCX；主体XML为真实段落，不使用随机尾部填充");

            Path xlsx = office.resolve("synthetic-xlsx-" + label + ".xlsx");
            generateXlsx(xlsx, tier);
            addExisting(items, outputRoot, xlsx, "CONTENT_DENSITY", tier,
                    "有效XLSX；内联字符串工作表，敏感值按固定间隔出现");

            Path pptx = office.resolve("synthetic-pptx-" + label + ".pptx");
            generatePptx(pptx, tier);
            addExisting(items, outputRoot, pptx, "CONTENT_DENSITY", tier,
                    "有效PPTX；单页多段落用于对象模型压力测试");
        }

        Path pdf = outputRoot.resolve("pdf-content-ladder");
        Files.createDirectories(pdf);
        for (long tier : pdfTiers) {
            Path target = pdf.resolve("synthetic-pdf-" + sizeLabel(tier) + ".pdf");
            generatePdf(target, tier);
            addExisting(items, outputRoot, target, "CONTENT_AND_PHYSICAL_SIZE", tier,
                    "有效PDF；多页内容流接近目标大小，末尾仅用PDF允许的空白补齐精确尺寸");
        }

        if (profile == Profile.FULL) {
            Path archives = outputRoot.resolve("archive-boundary");
            Files.createDirectories(archives);
            Path exactTar = archives.resolve("synthetic-mixed-dossier-exact-1gib.tar");
            generateExactTar(exactTar, control, ONE_GIB);
            addExisting(items, outputRoot, exactTar, "EXACT_UPLOAD_AND_ARCHIVE_BOUNDARY", ONE_GIB,
                    "精确1GiB有效TAR；含可处理控制文档及默认不进入结果包的unsupported/padding.bin");
        }

        writeExpectations(outputRoot.resolve("EXPECTED_SYNTHETIC_VALUES.json"));
        writeReadme(outputRoot.resolve("README-STRESS-CORPUS.md"), profile);
        Path manifest = outputRoot.resolve("manifest.json");
        writeManifest(manifest, profile, items);
        return manifest;
    }

    public static List<Path> generateOfficeTier(Path root, long targetBytes) throws IOException {
        if (targetBytes < MIB || targetBytes > ONE_GIB) {
            throw new IllegalArgumentException("Office压力样本目标必须在1 MiB至1 GiB之间");
        }
        Path output = root.toAbsolutePath().normalize();
        Files.createDirectories(output);
        String label = sizeLabel(targetBytes);
        Path docx = output.resolve("synthetic-docx-" + label + ".docx");
        Path xlsx = output.resolve("synthetic-xlsx-" + label + ".xlsx");
        Path pptx = output.resolve("synthetic-pptx-" + label + ".pptx");
        generateDocx(docx, targetBytes);
        generateXlsx(xlsx, targetBytes);
        generatePptx(pptx, targetBytes);
        return List.of(docx, xlsx, pptx);
    }

    private static void generateDocx(Path target, long targetBytes) throws IOException {
        Path payload = createPayloadFile(target, ".document.xml");
        try {
            try (CountingOutputStream output = countingOutput(payload)) {
                writeUtf8(output, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                        + "<w:body>");
                long goal = Math.max(MIB, targetBytes - 2 * MIB);
                long index = 0;
                while (output.count() < goal) {
                    writeUtf8(output, docxParagraph(index++));
                    if ((index & 0x3fff) == 0) {
                        output.flush();
                    }
                }
                writeUtf8(output, "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr></w:body></w:document>");
            }
            try (ZipOutputStream zip = zip(target)) {
                putStoredText(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                        + "</Types>");
                putStoredText(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                        + "</Relationships>");
                putStoredFile(zip, "word/document.xml", payload);
            }
        } finally {
            Files.deleteIfExists(payload);
        }
    }

    private static String docxParagraph(long index) {
        String content = regularRecord(index);
        return "<w:p><w:r><w:t xml:space=\"preserve\">" + xml(content) + "</w:t></w:r></w:p>";
    }

    private static void generateXlsx(Path target, long targetBytes) throws IOException {
        Path payload = createPayloadFile(target, ".sheet1.xml");
        try {
            long goal = Math.max(MIB, targetBytes - 2 * MIB);
            int minimumPayloadPerRow = (int) Math.min(32_000L,
                    Math.max(16L, (goal + 1_048_575L) / 1_048_576L));
            String cellFiller = deterministicAsciiFiller(minimumPayloadPerRow);
            try (CountingOutputStream output = countingOutput(payload)) {
                writeUtf8(output, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
                int row = 1;
                while (output.count() < goal && row <= 1_048_576) {
                    String value = regularRecord(row);
                    writeUtf8(output, "<row r=\"" + row + "\"><c r=\"A" + row
                            + "\" t=\"inlineStr\"><is><t>" + xml(value)
                            + "</t></is></c><c r=\"B" + row
                            + "\" t=\"inlineStr\"><is><t>" + hex(mix(SEED + row)) + cellFiller
                            + "</t></is></c></row>");
                    row++;
                    if ((row & 0x3fff) == 0) {
                        output.flush();
                    }
                }
                if (output.count() < goal) {
                    throw new IOException("XLSX达到1048576行限制但仍未达到目标内容量");
                }
                writeUtf8(output, "</sheetData></worksheet>");
            }
            try (ZipOutputStream zip = zip(target)) {
                putStoredText(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                        + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                        + "</Types>");
                putStoredText(zip, "_rels/.rels", packageRelationship("xl/workbook.xml"));
                putStoredText(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                        + "<sheets><sheet name=\"Synthetic\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
                putStoredText(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                        + "</Relationships>");
                putStoredFile(zip, "xl/worksheets/sheet1.xml", payload);
            }
        } finally {
            Files.deleteIfExists(payload);
        }
    }

    private static void generatePptx(Path target, long targetBytes) throws IOException {
        Path payload = createPayloadFile(target, ".slide1.xml");
        try {
            try (CountingOutputStream output = countingOutput(payload)) {
                writeUtf8(output, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                        + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                        + "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                        + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
                        + "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Synthetic stress text\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                        + "<p:spPr><a:xfrm><a:off x=\"457200\" y=\"457200\"/><a:ext cx=\"8229600\" cy=\"5943600\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/>");
                long goal = Math.max(MIB, targetBytes - 2 * MIB);
                long index = 0;
                while (output.count() < goal) {
                    writeUtf8(output, "<a:p><a:r><a:rPr lang=\"zh-CN\"/><a:t>" + xml(regularRecord(index++))
                            + "</a:t></a:r><a:endParaRPr lang=\"zh-CN\"/></a:p>");
                    if ((index & 0x3fff) == 0) {
                        output.flush();
                    }
                }
                writeUtf8(output, "</p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>");
            }
            try (ZipOutputStream zip = zip(target)) {
                putStoredText(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>"
                        + "<Override PartName=\"/ppt/slides/slide1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
                        + "</Types>");
                putStoredText(zip, "_rels/.rels", packageRelationship("ppt/presentation.xml"));
                putStoredText(zip, "ppt/presentation.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                        + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                        + "<p:sldIdLst><p:sldId id=\"256\" r:id=\"rId1\"/></p:sldIdLst>"
                        + "<p:sldSz cx=\"9144000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>");
                putStoredText(zip, "ppt/_rels/presentation.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide1.xml\"/>"
                        + "</Relationships>");
                putStoredFile(zip, "ppt/slides/slide1.xml", payload);
            }
        } finally {
            Files.deleteIfExists(payload);
        }
    }

    private static void generatePdf(Path target, long targetBytes) throws IOException {
        int pageCount = (int) Math.max(1L, targetBytes / (8 * MIB));
        long contentBudget = Math.max(MIB, targetBytes - 4 * MIB);
        long perPage = contentBudget / pageCount;
        int objectCount = 3 + pageCount * 2;
        long[] offsets = new long[objectCount + 1];
        Files.createDirectories(target.getParent());
        try (RandomAccessFile file = new RandomAccessFile(target.toFile(), "rw")) {
            file.setLength(0L);
            writeAscii(file, "%PDF-1.7\n% synthetic stress corpus\n");
            writePdfObject(file, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
            StringBuilder kids = new StringBuilder("<< /Type /Pages /Count ").append(pageCount).append(" /Kids [");
            for (int page = 0; page < pageCount; page++) {
                kids.append(4 + page * 2).append(" 0 R ");
            }
            kids.append("] >>");
            writePdfObject(file, offsets, 2, kids.toString());
            writePdfObject(file, offsets, 3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
            for (int page = 0; page < pageCount; page++) {
                int pageObject = 4 + page * 2;
                int contentObject = pageObject + 1;
                writePdfObject(file, offsets, pageObject, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                        + "/Resources << /Font << /F1 3 0 R >> >> /Contents " + contentObject + " 0 R >>");
                offsets[contentObject] = file.getFilePointer();
                writeAscii(file, contentObject + " 0 obj\n<< /Length " + perPage + " >>\nstream\n");
                writePdfContent(file, perPage, page);
                writeAscii(file, "\nendstream\nendobj\n");
            }
            long xref = file.getFilePointer();
            writeAscii(file, "xref\n0 " + (objectCount + 1) + "\n0000000000 65535 f \n");
            for (int object = 1; object <= objectCount; object++) {
                writeAscii(file, String.format(Locale.ROOT, "%010d 00000 n \n", offsets[object]));
            }
            writeAscii(file, "trailer\n<< /Size " + (objectCount + 1) + " /Root 1 0 R >>\nstartxref\n"
                    + xref + "\n%%EOF\n");
            if (file.length() > targetBytes) {
                throw new IOException("PDF生成结果超过目标大小，无法安全截断: " + file.length());
            }
            byte[] spaces = new byte[1024 * 1024];
            java.util.Arrays.fill(spaces, (byte) ' ');
            long remaining = targetBytes - file.length();
            while (remaining > 0) {
                int write = (int) Math.min(spaces.length, remaining);
                file.write(spaces, 0, write);
                remaining -= write;
            }
        }
    }

    private static void writePdfContent(RandomAccessFile file, long length, int page) throws IOException {
        String visible = "BT /F1 11 Tf 50 750 Td (SYNTHETIC PAGE " + (page + 1)
                + " PHONE 13800138000 EMAIL synthetic.user@example.com) Tj ET\n";
        byte[] prefix = visible.getBytes(StandardCharsets.US_ASCII);
        if (prefix.length > length) {
            throw new IOException("PDF内容预算过小");
        }
        file.write(prefix);
        long remaining = length - prefix.length;
        byte[] comment = ("% " + NOTICE_EN + " " + hex(mix(SEED + page)) + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        while (remaining > 0) {
            int write = (int) Math.min(comment.length, remaining);
            file.write(comment, 0, write);
            remaining -= write;
        }
    }

    private static void generateExactTar(Path target, Path control, long targetBytes) throws IOException {
        List<Path> sources = List.of(
                control.resolve("合成验收样例.docx"),
                control.resolve("合成验收样例.xlsx"),
                control.resolve("合成验收样例.pptx"),
                control.resolve("synthetic-acceptance-sample.pdf"));
        long used = 0L;
        for (Path source : sources) {
            used += 512L + roundUp512(Files.size(source));
        }
        long fillerSize = targetBytes - used - 512L - 1024L;
        if (fillerSize <= 0 || fillerSize % 512L != 0L) {
            throw new IOException("无法计算精确TAR填充条目大小");
        }
        Files.createDirectories(target.getParent());
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
                new BufferedOutputStream(Files.newOutputStream(target), COPY_BUFFER_BYTES))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_ERROR);
            for (Path source : sources) {
                TarArchiveEntry entry = new TarArchiveEntry("processable/" + source.getFileName());
                entry.setSize(Files.size(source));
                entry.setModTime(0L);
                tar.putArchiveEntry(entry);
                Files.copy(source, tar);
                tar.closeArchiveEntry();
            }
            TarArchiveEntry padding = new TarArchiveEntry("unsupported/padding.bin");
            padding.setSize(fillerSize);
            padding.setModTime(0L);
            tar.putArchiveEntry(padding);
            byte[] zeros = new byte[COPY_BUFFER_BYTES];
            long remaining = fillerSize;
            while (remaining > 0) {
                int write = (int) Math.min(zeros.length, remaining);
                tar.write(zeros, 0, write);
                remaining -= write;
            }
            tar.closeArchiveEntry();
            tar.finish();
        }
        long actual = Files.size(target);
        if (actual != targetBytes) {
            throw new IOException("精确TAR大小不匹配: expected=" + targetBytes + ", actual=" + actual);
        }
    }

    private static String regularRecord(long index) {
        String random = hex(mix(SEED + index)) + hex(mix(SEED ^ index));
        if (index % 4096L == 0L) {
            return NOTICE_ZH + " " + NOTICE_EN + " 记录=" + index + " 姓名：测试甲 手机：" + PHONE
                    + " 邮箱：" + EMAIL + " 案号：" + CASE_NO + " IPv4：192.0.2.25 token=" + random;
        }
        return NOTICE_ZH + " " + NOTICE_EN + " 记录=" + index
                + " 分类=公开演示 数据校验=" + random + " 非敏感占位内容=" + random + random;
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static String hex(long value) {
        return String.format(Locale.ROOT, "%016x", value);
    }

    private static String deterministicAsciiFiller(int length) {
        String seed = "SYNTHETIC-NON-SENSITIVE-0123456789ABCDEF-";
        StringBuilder value = new StringBuilder(length);
        while (value.length() < length) value.append(seed);
        value.setLength(length);
        return value.toString();
    }

    private static ZipOutputStream zip(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        return new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target), COPY_BUFFER_BYTES));
    }

    private static void putStoredText(ZipOutputStream zip, String name, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipEntry entry = storedEntry(name, bytes.length, crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void putStoredFile(ZipOutputStream zip, String name, Path file) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file), COPY_BUFFER_BYTES)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    crc.update(buffer, 0, read);
                }
            }
        }
        ZipEntry entry = storedEntry(name, Files.size(file), crc.getValue());
        zip.putNextEntry(entry);
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file), COPY_BUFFER_BYTES)) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }

    private static ZipEntry storedEntry(String name, long size, long crc) {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(size);
        entry.setCompressedSize(size);
        entry.setCrc(crc);
        entry.setTime(0L);
        return entry;
    }

    private static String packageRelationship(String target) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\""
                + target + "\"/></Relationships>";
    }

    private static Path createPayloadFile(Path target, String suffix) throws IOException {
        Path tempRoot = target.getParent().resolve(".generation-work");
        Files.createDirectories(tempRoot);
        return Files.createTempFile(tempRoot, "payload-", suffix);
    }

    private static CountingOutputStream countingOutput(Path target) throws IOException {
        return new CountingOutputStream(new BufferedOutputStream(Files.newOutputStream(target), COPY_BUFFER_BYTES));
    }

    private static void writePdfObject(RandomAccessFile file, long[] offsets, int object, String body)
            throws IOException {
        offsets[object] = file.getFilePointer();
        writeAscii(file, object + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void writeAscii(RandomAccessFile file, String value) throws IOException {
        file.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeUtf8(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static long roundUp512(long size) {
        return ((size + 511L) / 512L) * 512L;
    }

    private static String sizeLabel(long bytes) {
        return bytes == ONE_GIB ? "1gib" : (bytes / MIB) + "mib";
    }

    private static void addExisting(List<CorpusItem> items, Path root, Path file, String purpose,
            long targetBytes, String notes) throws IOException {
        items.add(new CorpusItem(root.relativize(file).toString().replace('\\', '/'), purpose,
                targetBytes, Files.size(file), sha256(file), notes));
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file), COPY_BUFFER_BYTES)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeManifest(Path target, Profile profile, List<CorpusItem> items) throws IOException {
        StringBuilder json = new StringBuilder("{\n  \"schemaVersion\":1,\n  \"profile\":")
                .append(JsonUtil.quote(profile.name().toLowerCase(Locale.ROOT)))
                .append(",\n  \"generatedAt\":").append(JsonUtil.quote(Instant.now().toString()))
                .append(",\n  \"seed\":").append(JsonUtil.quote(Long.toUnsignedString(SEED)))
                .append(",\n  \"syntheticOnly\":true,\n  \"items\":[\n");
        for (int i = 0; i < items.size(); i++) {
            CorpusItem item = items.get(i);
            json.append("    {\"path\":").append(JsonUtil.quote(item.path()))
                    .append(",\"purpose\":").append(JsonUtil.quote(item.purpose()))
                    .append(",\"targetBytes\":").append(item.targetBytes())
                    .append(",\"actualBytes\":").append(item.actualBytes())
                    .append(",\"sha256\":").append(JsonUtil.quote(item.sha256()))
                    .append(",\"notes\":").append(JsonUtil.quote(item.notes())).append('}');
            json.append(i + 1 == items.size() ? '\n' : ",\n");
        }
        json.append("  ]\n}\n");
        Files.writeString(target, json, StandardCharsets.UTF_8);
    }

    private static void writeExpectations(Path target) throws IOException {
        String json = "{\n"
                + "  \"syntheticOnly\": true,\n"
                + "  \"noticeZh\": " + JsonUtil.quote(NOTICE_ZH) + ",\n"
                + "  \"noticeEn\": " + JsonUtil.quote(NOTICE_EN) + ",\n"
                + "  \"expectedSensitiveValues\": ["
                + JsonUtil.quote(PHONE) + ", " + JsonUtil.quote(EMAIL) + ", " + JsonUtil.quote(CASE_NO)
                + ", \"192.0.2.25\"],\n"
                + "  \"expectedRuleFamilies\": [\"CN_MOBILE_PHONE\", \"EMAIL\", \"CASE_NUMBER\", \"IPV4\"]\n"
                + "}\n";
        Files.writeString(target, json, StandardCharsets.UTF_8);
    }

    private static void writeReadme(Path target, Profile profile) throws IOException {
        String text = "# 本地合成压力语料 / Local Synthetic Stress Corpus\n\n"
                + "本目录由程序在本机生成，不含真实个人、企业或案件信息。当前配置：`"
                + profile.name().toLowerCase(Locale.ROOT) + "`。\n\n"
                + "- `control-samples`：小型规则与格式回归样例。\n"
                + "- `office-content-ladder`：真实OOXML主体内容阶梯；用于发现对象模型内存放大，不代表全部阶梯已通过。\n"
                + "- `pdf-content-ladder`：有效PDF多页内容流阶梯。\n"
                + "- `archive-boundary`：精确1GiB上传和卷宗预检边界；其中不支持文件默认不得进入结果包。\n"
                + "- `manifest.json`：实际字节数、SHA-256、用途和证据说明。\n\n"
                + "大文件不得提交到Git仓库或放入正式安装包。验收必须分别记录上传、解析、脱敏、格式保留四种结果。\n";
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }

    private record CorpusItem(String path, String purpose, long targetBytes, long actualBytes,
            String sha256, String notes) {
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        private CountingOutputStream(OutputStream output) {
            super(output);
        }

        long count() {
            return count;
        }

        @Override
        public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            count += length;
        }
    }
}
