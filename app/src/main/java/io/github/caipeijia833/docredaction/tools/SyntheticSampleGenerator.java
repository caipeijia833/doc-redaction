/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.tools;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SyntheticSampleGenerator {
    private static final String NOTICE = "【完全合成测试数据，不对应任何真实个人、企业或案件】";
    private static final String EMAIL = "synthetic.user@example.com";
    private static final String PHONE = "13800138000";

    private SyntheticSampleGenerator() {
    }

    public static void generate(Path directory) throws Exception {
        Files.createDirectories(directory);
        generateDocx(directory.resolve("合成验收样例.docx"));
        generateXlsx(directory.resolve("合成验收样例.xlsx"));
        generatePptx(directory.resolve("合成验收样例.pptx"));
        generatePdf(directory.resolve("synthetic-acceptance-sample.pdf"));
    }

    private static void generateDocx(Path target) throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.getProperties().getCoreProperties().setCreator("合成样例生成器");
            document.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT)
                    .createParagraph().createRun().setText("内部验收材料 - 手机" + PHONE);
            document.createParagraph().createRun().setText(NOTICE);
            var paragraph = document.createParagraph();
            paragraph.createRun().setText("姓名：测试甲，身份证号：");
            paragraph.createRun().setText(validResidentId("99999919900101001"));
            document.createParagraph().createRun().setText("手机：" + PHONE + "，邮箱：" + EMAIL);
            document.createParagraph().createRun().setText("地址：测试省测试市演示区演示路1号");
            document.createParagraph().createRun().setText("单位：测试脱敏科技有限公司");
            document.createParagraph().createRun().setText("统一社会信用代码：" + validCreditCode("99999999M00000000"));
            document.createParagraph().createRun().setText("银行卡：" + appendLuhnCheckDigit("999999999999999"));
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("字段");
            table.getRow(0).getCell(1).setText("合成值");
            table.getRow(1).getCell(0).setText("联系邮箱");
            table.getRow(1).getCell(1).setText(EMAIL);
            try (var output = Files.newOutputStream(target)) {
                document.write(output);
            }
        }
    }

    private static void generateXlsx(Path target) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.getProperties().getCoreProperties().setCreator("合成样例生成器");
            var sheet = workbook.createSheet("合成数据");
            sheet.getHeader().setCenter("内部验收 - " + EMAIL);
            String[][] data = {
                    {"说明", NOTICE},
                    {"姓名", "姓名：测试乙"},
                    {"手机", PHONE},
                    {"邮箱", EMAIL},
                    {"地址", "地址：测试省测试市演示区演示路2号"},
                    {"网络", "IP地址：192.0.2.25，MAC：02:00:00:00:00:01"}
            };
            for (int rowIndex = 0; rowIndex < data.length; rowIndex++) {
                var row = sheet.createRow(rowIndex);
                row.createCell(0).setCellValue(data[rowIndex][0]);
                row.createCell(1).setCellValue(data[rowIndex][1]);
            }
            var formula = sheet.getRow(1).createCell(2);
            formula.setCellFormula("1+1");
            CreationHelper helper = workbook.getCreationHelper();
            ClientAnchor anchor = helper.createClientAnchor();
            var comment = sheet.createDrawingPatriarch().createCellComment(anchor);
            comment.setString(helper.createRichTextString("备注邮箱：" + EMAIL));
            sheet.getRow(2).getCell(1).setCellComment(comment);
            sheet.autoSizeColumn(0);
            sheet.setColumnWidth(1, 52 * 256);
            try (var output = Files.newOutputStream(target)) {
                workbook.write(output);
            }
        }
    }

    private static void generatePdf(Path target) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(55, 720);
                content.showText("SYNTHETIC TEST DATA ONLY");
                content.newLineAtOffset(0, -24);
                content.showText("Email: " + EMAIL);
                content.newLineAtOffset(0, -24);
                content.showText("Phone: " + PHONE);
                content.newLineAtOffset(0, -24);
                content.showText("IPv4: 192.0.2.25");
                content.endText();
            }
            document.save(target.toFile());
        }
    }

    private static void generatePptx(Path target) throws Exception {
        try (XMLSlideShow presentation = new XMLSlideShow()) {
            presentation.getProperties().getCoreProperties().setCreator("合成样例生成器");
            var slide = presentation.createSlide();
            var title = slide.createTextBox();
            title.setAnchor(new Rectangle(48, 42, 620, 72));
            title.setText("智能文档脱敏合成验收样例");
            var body = slide.createTextBox();
            body.setAnchor(new Rectangle(48, 140, 620, 260));
            body.setText(NOTICE + "\n姓名：测试丙\n手机：" + PHONE + "\n邮箱：" + EMAIL
                    + "\n地址：测试省测试市演示区演示路3号\n单位：测试脱敏科技有限公司");
            try (var output = Files.newOutputStream(target)) {
                presentation.write(output);
            }
        }
    }

    private static String validResidentId(String first17) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] checks = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < first17.length(); i++) {
            sum += (first17.charAt(i) - '0') * weights[i];
        }
        return first17 + checks[sum % 11];
    }

    private static String validCreditCode(String first17) {
        String alphabet = "0123456789ABCDEFGHJKLMNPQRTUWXY";
        int[] weights = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
        int sum = 0;
        for (int i = 0; i < first17.length(); i++) {
            sum += alphabet.indexOf(first17.charAt(i)) * weights[i];
        }
        return first17 + alphabet.charAt((31 - sum % 31) % 31);
    }

    private static String appendLuhnCheckDigit(String prefix) {
        for (int digit = 0; digit <= 9; digit++) {
            String candidate = prefix + digit;
            int sum = 0;
            boolean alternate = false;
            for (int i = candidate.length() - 1; i >= 0; i--) {
                int value = candidate.charAt(i) - '0';
                if (alternate) {
                    value *= 2;
                    if (value > 9) value -= 9;
                }
                sum += value;
                alternate = !alternate;
            }
            if (sum % 10 == 0) return candidate;
        }
        throw new IllegalStateException("Unable to generate Luhn value");
    }
}
