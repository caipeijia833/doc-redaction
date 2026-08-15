/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.rules.RedactionResult;
import io.github.caipeijia833.docredaction.rules.RuleEngine;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Event-stream OOXML rewriter. It never constructs a complete Word, Excel or PowerPoint
 * object model. ZIP entries are copied one at a time and XML text is buffered only for a
 * semantic unit such as a paragraph, shared string or cell.
 */
public final class OoxmlPackageProcessor {
    public enum Kind { DOCX, XLSX, PPTX }

    @FunctionalInterface
    public interface TextSink {
        /** Return false to stop scanning. */
        boolean accept(String location, String text);
    }

    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;
    private static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 8L * 1024 * 1024 * 1024;
    private static final long MAX_EMBEDDED_IMAGE_BYTES = 100L * 1024 * 1024;
    private static final int MAX_BLOCK_EVENTS = 250_000;
    private static final int MAX_BLOCK_TEXT_CHARS = 16 * 1024 * 1024;
    private static final Set<String> HEADER_FOOTER_ELEMENTS = Set.of(
            "oddHeader", "evenHeader", "firstHeader", "oddFooter", "evenFooter", "firstFooter");
    private static final Set<String> WORD_TEXT_ELEMENTS = Set.of("t", "instrText", "delText");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "bmp", "gif");

    private OoxmlPackageProcessor() {
    }

    static ProcessReport process(Kind kind, Path input, Path output, RuleEngine rules) throws Exception {
        ProcessReport report = new ProcessReport();
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Path temporary = output.resolveSibling(output.getFileName() + ".ooxml-" + UUID.randomUUID() + ".part");
        try (ZipFile source = new ZipFile(input.toFile());
                EmbeddedImageRedactor images = new EmbeddedImageRedactor(output, rules, report)) {
            validatePackage(source);
            try (OutputStream fileOutput = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    ZipOutputStream target = new ZipOutputStream(fileOutput)) {
                Enumeration<? extends ZipEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    writeEntry(kind, source, entry, target, rules, report, images);
                }
                target.finish();
            }
            moveAtomically(temporary, output);
            if (images.processedImages()) {
                report.warning(kind.name() + "内嵌位图已通过本地OCR检测并不可逆遮挡。");
            }
        } catch (Exception ex) {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(output);
            throw ex;
        }
        report.warning("Office文件已使用OOXML包级事件流重写；未构建完整文档对象模型。");
        return report;
    }

    public static void scanText(Path input, Kind kind, TextSink sink) throws Exception {
        try (ZipFile source = new ZipFile(input.toFile())) {
            validatePackage(source);
            Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = normalizedName(entry.getName());
                if (entry.isDirectory() || !isScannableXml(kind, name)) continue;
                try (InputStream stream = source.getInputStream(entry)) {
                    if (!scanXml(kind, name, stream, sink)) return;
                }
            }
        }
    }

    private static void writeEntry(Kind kind, ZipFile source, ZipEntry entry, ZipOutputStream target,
            RuleEngine rules, ProcessReport report, EmbeddedImageRedactor images) throws Exception {
        String name = normalizedName(entry.getName());
        ZipEntry outputEntry = new ZipEntry(name);
        if (entry.getTime() >= 0) outputEntry.setTime(entry.getTime());
        target.putNextEntry(outputEntry);
        try {
            if (entry.isDirectory()) return;
            rejectUnsafeEmbeddedObject(name);
            try (InputStream input = source.getInputStream(entry)) {
                if (isPackageThumbnail(name)) {
                    target.write(blankImage(extension(name)));
                    report.warning("已移除 Office 缩略图中的潜在敏感内容并替换为空白缩略图。");
                } else if (isEmbeddedImage(name)) {
                    if (entry.getSize() > MAX_EMBEDDED_IMAGE_BYTES) {
                        throw new IOException("Office内嵌图片超过100 MiB安全上限：" + name);
                    }
                    String extension = extension(name);
                    if (!IMAGE_EXTENSIONS.contains(extension)) {
                        throw new IOException("Office包含无法安全OCR的媒体格式：" + name);
                    }
                    byte[] data = readBounded(input, MAX_EMBEDDED_IMAGE_BYTES);
                    target.write(images.redact(data, extension));
                } else if (name.endsWith(".rels")) {
                    transformRelationships(input, target, rules, report);
                } else if (isTransformableXml(kind, name)) {
                    transformXml(kind, name, input, target, rules, report);
                } else {
                    input.transferTo(target);
                }
            }
        } finally {
            target.closeEntry();
        }
    }

    private static void transformXml(Kind kind, String name, InputStream input, OutputStream output,
            RuleEngine rules, ProcessReport report) throws Exception {
        XMLInputFactory inputFactory = secureInputFactory();
        XMLOutputFactory outputFactory = XMLOutputFactory.newFactory();
        XMLEventReader reader = inputFactory.createXMLEventReader(input);
        XMLEventWriter writer = outputFactory.createXMLEventWriter(new NonClosingOutputStream(output), "UTF-8");
        try {
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (!event.isStartElement()) {
                    if (event.isCharacters() && isGenericFreeTextXml(name)) {
                        Characters characters = event.asCharacters();
                        RedactionResult redacted = rules.redact(characters.getData());
                        report.add(redacted);
                        if (!redacted.matches().isEmpty()) {
                            XMLEventFactory factory = XMLEventFactory.newFactory();
                            event = characters.isCData() ? factory.createCData(redacted.redactedText())
                                    : factory.createCharacters(redacted.redactedText());
                            report.unitProcessed();
                        }
                    }
                    writer.add(event);
                    continue;
                }
                String local = event.asStartElement().getName().getLocalPart();
                if (name.equals("docProps/custom.xml") && local.equals("property")) {
                    List<XMLEvent> block = readBlock(reader, event);
                    sanitizeCustomProperty(block);
                    report.unitProcessed();
                    addAll(writer, block);
                } else if (isCoreMetadata(name) && isMetadataElement(local)) {
                    List<XMLEvent> block = readBlock(reader, event);
                    String metadataName = local.toLowerCase(Locale.ROOT);
                    if (Set.of("creator", "lastmodifiedby", "manager", "company", "author")
                            .contains(metadataName)) {
                        overrideCharacters(block, "已脱敏");
                    } else {
                        redactEvents(block, ignored -> true, rules, report, false);
                    }
                    addAll(writer, block);
                } else if (kind == Kind.DOCX && local.equals("p") && isWordContent(name)) {
                    List<XMLEvent> block = readBlock(reader, event);
                    redactEvents(block, WORD_TEXT_ELEMENTS::contains, rules, report, false);
                    sanitizeAuthorAttributes(block);
                    addAll(writer, block);
                } else if (kind == Kind.PPTX && local.equals("p") && isPowerPointContent(name)) {
                    List<XMLEvent> block = readBlock(reader, event);
                    redactEvents(block, textLocal -> textLocal.equals("t"), rules, report, false);
                    addAll(writer, block);
                } else if (kind == Kind.PPTX && local.equals("pt") && isChart(name)) {
                    List<XMLEvent> block = readBlock(reader, event);
                    redactEvents(block, textLocal -> textLocal.equals("v"), rules, report, true);
                    addAll(writer, block);
                } else if (kind == Kind.PPTX && local.equals("cm") && isPowerPointComments(name)) {
                    List<XMLEvent> block = readBlock(reader, event);
                    redactEvents(block, textLocal -> textLocal.equals("text"), rules, report, false);
                    sanitizeAuthorAttributes(block);
                    addAll(writer, block);
                } else if (kind == Kind.XLSX && local.equals("si") && isSharedStrings(name)) {
                    List<XMLEvent> block = readBlock(reader, event);
                    redactEvents(block, textLocal -> textLocal.equals("t"), rules, report, false);
                    addAll(writer, block);
                } else if (kind == Kind.XLSX && local.equals("c") && isWorksheet(name)) {
                    List<XMLEvent> block = readBlock(reader, event);
                    transformCell(block, rules, report);
                    addAll(writer, block);
                } else if (kind == Kind.XLSX && local.equals("text") && isExcelComments(name)) {
                    List<XMLEvent> block = readBlock(reader, event);
                    redactEvents(block, textLocal -> textLocal.equals("t"), rules, report, false);
                    addAll(writer, block);
                } else if (kind == Kind.XLSX && local.equals("author") && isExcelComments(name)) {
                    List<XMLEvent> block = readBlock(reader, event);
                    overrideCharacters(block, "redacted");
                    addAll(writer, block);
                } else if (kind == Kind.XLSX && HEADER_FOOTER_ELEMENTS.contains(local) && isWorksheet(name)) {
                    List<XMLEvent> block = readBlock(reader, event);
                    redactEvents(block, ignored -> true, rules, report, false);
                    addAll(writer, block);
                } else if (kind == Kind.XLSX && local.equals("sheet") && isWorkbook(name)) {
                    StartElement start = event.asStartElement();
                    Attribute sheetName = start.getAttributeByName(new QName("name"));
                    if (sheetName != null && !rules.detect(sheetName.getValue()).isEmpty()) {
                        throw new IOException("工作表名称包含敏感信息，流式处理无法安全更新全部公式引用");
                    }
                    writer.add(event);
                } else if (isGenericFreeTextXml(name)) {
                    writer.add(redactStartAttributes(event, rules, report));
                } else {
                    writer.add(redactKnownAuthorAttributes(event));
                    if (event.isStartElement() && reader.hasNext()) {
                        // Unknown XML is copied structurally. Character chunks are handled by the
                        // ordinary branch below and verified again by the residual scanner.
                    }
                }
            }
            writer.flush();
        } catch (XMLStreamException ex) {
            throw new IOException("OOXML部件解析失败：" + name, ex);
        } finally {
            try { reader.close(); } catch (Exception ignored) { }
            try { writer.close(); } catch (Exception ignored) { }
        }
    }

    private static void transformCell(List<XMLEvent> block, RuleEngine rules, ProcessReport report) throws Exception {
        StartElement cell = block.getFirst().asStartElement();
        Attribute typeAttribute = cell.getAttributeByName(new QName("t"));
        String type = typeAttribute == null ? "n" : typeAttribute.getValue();
        String formula = joinedText(block, local -> local.equals("f"));
        if (!formula.isBlank() && !rules.detect(formula).isEmpty()) {
            throw new IOException("Excel公式包含敏感信息，无法在不改变计算逻辑的情况下安全自动脱敏");
        }
        Predicate<String> targets;
        boolean numeric = false;
        if (type.equals("inlineStr")) {
            targets = local -> local.equals("t");
        } else if (type.equals("str")) {
            targets = local -> local.equals("v");
        } else if (!type.equals("s") && !type.equals("b") && !type.equals("e") && formula.isBlank()) {
            targets = local -> local.equals("v");
            numeric = true;
        } else {
            return;
        }
        boolean changed = redactEvents(block, targets, rules, report, numeric);
        if (changed && numeric) {
            block.set(0, withAttribute(cell, "t", "str"));
        }
    }

    private static boolean redactEvents(List<XMLEvent> events, Predicate<String> targetElements,
            RuleEngine rules, ProcessReport report, boolean numericReplacement) throws IOException {
        List<Integer> indexes = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        ArrayDeque<String> stack = new ArrayDeque<>();
        for (int i = 0; i < events.size(); i++) {
            XMLEvent event = events.get(i);
            if (event.isStartElement()) {
                stack.push(event.asStartElement().getName().getLocalPart());
            } else if (event.isEndElement()) {
                if (!stack.isEmpty()) stack.pop();
            } else if (event.isCharacters() && !stack.isEmpty() && targetElements.test(stack.peek())) {
                String data = event.asCharacters().getData();
                indexes.add(i);
                lengths.add(data.length());
                joined.append(data);
                if (joined.length() > MAX_BLOCK_TEXT_CHARS) {
                    throw new IOException("OOXML单个文本单元超过16 MiB安全缓冲上限；请拆分文档内容");
                }
            }
        }
        if (joined.isEmpty()) return false;
        String replacement = joined.toString();
        boolean changed = false;
        for (int pass = 0; pass < 8; pass++) {
            RedactionResult result = rules.redact(replacement);
            if (result.matches().isEmpty()) break;
            report.add(result);
            String next = numericReplacement
                    ? numericSafeReplacement(replacement, result) : result.redactedText();
            if (next.equals(replacement)) break;
            replacement = next;
            changed = true;
        }
        if (!changed) return false;
        int offset = 0;
        XMLEventFactory factory = XMLEventFactory.newFactory();
        for (int i = 0; i < indexes.size(); i++) {
            int length = lengths.get(i);
            Characters original = events.get(indexes.get(i)).asCharacters();
            String value = replacement.substring(offset, offset + length);
            events.set(indexes.get(i), original.isCData() ? factory.createCData(value) : factory.createCharacters(value));
            offset += length;
        }
        report.unitProcessed();
        return true;
    }

    private static String numericSafeReplacement(String source, RedactionResult result) {
        char[] output = source.toCharArray();
        result.matches().forEach(match -> {
            for (int i = match.start(); i < match.end(); i++) {
                char original = source.charAt(i);
                output[i] = Character.isDigit(original) ? '0' : original;
            }
        });
        return new String(output);
    }

    private static void transformRelationships(InputStream input, OutputStream output,
            RuleEngine rules, ProcessReport report) throws Exception {
        XMLInputFactory inputFactory = secureInputFactory();
        XMLEventReader reader = inputFactory.createXMLEventReader(input);
        XMLEventWriter writer = XMLOutputFactory.newFactory()
                .createXMLEventWriter(new NonClosingOutputStream(output), "UTF-8");
        try {
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (event.isStartElement()) {
                    StartElement start = event.asStartElement();
                    Attribute mode = start.getAttributeByName(new QName("TargetMode"));
                    Attribute target = start.getAttributeByName(new QName("Target"));
                    if (mode != null && mode.getValue().equalsIgnoreCase("External") && target != null) {
                        event = withAttribute(start, "Target", "about:blank");
                        report.warning("Office外部关系目标已移除，避免结果文件打开时连接外部地址。");
                    }
                }
                writer.add(event);
            }
            writer.flush();
        } finally {
            try { reader.close(); } catch (Exception ignored) { }
            try { writer.close(); } catch (Exception ignored) { }
        }
    }

    private static boolean scanXml(Kind kind, String name, InputStream input, TextSink sink) throws Exception {
        XMLEventReader reader = secureInputFactory().createXMLEventReader(input);
        try {
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (!event.isStartElement()) continue;
                String local = event.asStartElement().getName().getLocalPart();
                if (isCoreMetadata(name) && isMetadataElement(local)) {
                    if (!emitBlock(name, readBlock(reader, event), ignored -> true, sink)) return false;
                } else if (kind == Kind.DOCX && local.equals("p") && isWordContent(name)) {
                    if (!emitBlock(name, readBlock(reader, event), WORD_TEXT_ELEMENTS::contains, sink)) return false;
                } else if (kind == Kind.PPTX && local.equals("p") && isPowerPointContent(name)) {
                    if (!emitBlock(name, readBlock(reader, event), textLocal -> textLocal.equals("t"), sink)) return false;
                } else if (kind == Kind.PPTX && local.equals("pt") && isChart(name)) {
                    if (!emitBlock(name, readBlock(reader, event), textLocal -> textLocal.equals("v"), sink)) return false;
                } else if (kind == Kind.PPTX && local.equals("cm") && isPowerPointComments(name)) {
                    if (!emitBlock(name, readBlock(reader, event), textLocal -> textLocal.equals("text"), sink)) return false;
                } else if (kind == Kind.XLSX && local.equals("si") && isSharedStrings(name)) {
                    if (!emitBlock(name, readBlock(reader, event), textLocal -> textLocal.equals("t"), sink)) return false;
                } else if (kind == Kind.XLSX && local.equals("c") && isWorksheet(name)) {
                    List<XMLEvent> block = readBlock(reader, event);
                    StartElement cell = block.getFirst().asStartElement();
                    Attribute typeAttribute = cell.getAttributeByName(new QName("t"));
                    String type = typeAttribute == null ? "n" : typeAttribute.getValue();
                    Predicate<String> target = type.equals("inlineStr") ? textLocal -> textLocal.equals("t")
                            : type.equals("s") || type.equals("b") || type.equals("e")
                            ? ignored -> false : textLocal -> textLocal.equals("v") || textLocal.equals("f");
                    if (!emitBlock(name + cellReference(cell), block, target, sink)) return false;
                } else if (kind == Kind.XLSX && local.equals("text") && isExcelComments(name)) {
                    if (!emitBlock(name, readBlock(reader, event), textLocal -> textLocal.equals("t"), sink)) return false;
                } else if (kind == Kind.XLSX && HEADER_FOOTER_ELEMENTS.contains(local) && isWorksheet(name)) {
                    if (!emitBlock(name, readBlock(reader, event), ignored -> true, sink)) return false;
                } else if (kind == Kind.XLSX && local.equals("sheet") && isWorkbook(name)) {
                    Attribute sheetName = event.asStartElement().getAttributeByName(new QName("name"));
                    if (sheetName != null && !sink.accept(name + " 工作表名称", sheetName.getValue())) return false;
                } else if (isGenericFreeTextXml(name) && reader.peek() != null
                        && reader.peek().getEventType() == XMLStreamConstants.CHARACTERS) {
                    XMLEvent characters = reader.nextEvent();
                    if (!sink.accept(name, characters.asCharacters().getData())) return false;
                }
            }
            return true;
        } finally {
            try { reader.close(); } catch (Exception ignored) { }
        }
    }

    private static boolean emitBlock(String location, List<XMLEvent> block,
            Predicate<String> target, TextSink sink) {
        String text = joinedText(block, target);
        return text.isBlank() || sink.accept(location, text);
    }

    private static String joinedText(List<XMLEvent> events, Predicate<String> targetElements) {
        StringBuilder joined = new StringBuilder();
        ArrayDeque<String> stack = new ArrayDeque<>();
        for (XMLEvent event : events) {
            if (event.isStartElement()) stack.push(event.asStartElement().getName().getLocalPart());
            else if (event.isEndElement()) { if (!stack.isEmpty()) stack.pop(); }
            else if (event.isCharacters() && !stack.isEmpty() && targetElements.test(stack.peek())) {
                joined.append(event.asCharacters().getData());
            }
        }
        return joined.toString();
    }

    private static List<XMLEvent> readBlock(XMLEventReader reader, XMLEvent first) throws Exception {
        List<XMLEvent> events = new ArrayList<>();
        events.add(first);
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            XMLEvent event = reader.nextEvent();
            events.add(event);
            if (event.isStartElement()) depth++;
            else if (event.isEndElement()) depth--;
            if (events.size() > MAX_BLOCK_EVENTS) {
                throw new IOException("OOXML单个语义单元事件数超过安全上限；请拆分超大段落、单元格或幻灯片文本");
            }
        }
        if (depth != 0) throw new IOException("OOXML语义单元未正常闭合");
        return events;
    }

    private static void overrideCharacters(List<XMLEvent> events, String value) {
        XMLEventFactory factory = XMLEventFactory.newFactory();
        boolean written = false;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).isCharacters()) {
                events.set(i, factory.createCharacters(written ? "" : value));
                written = true;
            }
        }
    }

    private static void sanitizeAuthorAttributes(List<XMLEvent> events) {
        for (int i = 0; i < events.size(); i++) events.set(i, redactKnownAuthorAttributes(events.get(i)));
    }

    private static void sanitizeCustomProperty(List<XMLEvent> events) {
        ArrayDeque<String> stack = new ArrayDeque<>();
        XMLEventFactory factory = XMLEventFactory.newFactory();
        for (int i = 0; i < events.size(); i++) {
            XMLEvent event = events.get(i);
            if (event.isStartElement()) {
                stack.push(event.asStartElement().getName().getLocalPart().toLowerCase(Locale.ROOT));
            } else if (event.isEndElement()) {
                if (!stack.isEmpty()) stack.pop();
            } else if (event.isCharacters() && !event.asCharacters().isWhiteSpace() && !stack.isEmpty()) {
                Characters original = event.asCharacters();
                String value = safeCustomPropertyValue(stack.peek());
                events.set(i, original.isCData() ? factory.createCData(value) : factory.createCharacters(value));
            }
        }
    }

    private static String safeCustomPropertyValue(String type) {
        if (type.equals("bool")) return "false";
        if (type.equals("filetime") || type.equals("date")) return "1970-01-01T00:00:00Z";
        if (type.equals("clsid")) return "{00000000-0000-0000-0000-000000000000}";
        if (type.matches("(?:i[1248]|int|ui[1248]|uint|r[48]|decimal|cy|error)")) return "0";
        if (type.equals("blob") || type.equals("oblob") || type.equals("cf")) return "";
        return "redacted";
    }

    private static XMLEvent redactKnownAuthorAttributes(XMLEvent event) {
        if (!event.isStartElement()) return event;
        StartElement start = event.asStartElement();
        StartElement updated = start;
        for (String local : List.of("author", "initials", "userId", "displayName", "email")) {
            Attribute attribute = attributeByLocalName(updated, local);
            if (attribute != null && !attribute.getValue().isBlank()) updated = withAttribute(updated, local, "redacted");
        }
        return updated;
    }

    private static XMLEvent redactStartAttributes(XMLEvent event, RuleEngine rules, ProcessReport report) {
        if (!event.isStartElement()) return event;
        StartElement start = event.asStartElement();
        List<Attribute> attributes = new ArrayList<>();
        boolean changed = false;
        Iterator<?> iterator = start.getAttributes();
        while (iterator.hasNext()) {
            Attribute attribute = (Attribute) iterator.next();
            String local = attribute.getName().getLocalPart();
            if (Set.of("displayName", "userId", "author", "email").contains(local)
                    && !attribute.getValue().isBlank()) {
                attributes.add(XMLEventFactory.newFactory()
                        .createAttribute(attribute.getName(), "redacted"));
                changed = true;
                continue;
            }
            if (local.equals("name")) {
                RedactionResult redacted = rules.redact(attribute.getValue());
                report.add(redacted);
                if (!redacted.matches().isEmpty()) {
                    attributes.add(XMLEventFactory.newFactory().createAttribute(attribute.getName(), redacted.redactedText()));
                    changed = true;
                    continue;
                }
            }
            attributes.add(attribute);
        }
        if (!changed) return event;
        return createStart(start, attributes);
    }

    private static StartElement withAttribute(StartElement start, String localName, String value) {
        List<Attribute> attributes = new ArrayList<>();
        QName attributeName = null;
        Iterator<?> iterator = start.getAttributes();
        while (iterator.hasNext()) {
            Attribute attribute = (Attribute) iterator.next();
            if (attribute.getName().getLocalPart().equals(localName)) {
                attributeName = attribute.getName();
                attributes.add(XMLEventFactory.newFactory().createAttribute(attributeName, value));
            } else {
                attributes.add(attribute);
            }
        }
        if (attributeName == null) attributes.add(XMLEventFactory.newFactory().createAttribute(localName, value));
        return createStart(start, attributes);
    }

    private static StartElement createStart(StartElement original, List<Attribute> attributes) {
        List<javax.xml.stream.events.Namespace> namespaces = new ArrayList<>();
        Iterator<?> namespaceIterator = original.getNamespaces();
        while (namespaceIterator.hasNext()) namespaces.add((javax.xml.stream.events.Namespace) namespaceIterator.next());
        return XMLEventFactory.newFactory().createStartElement(original.getName(), attributes.iterator(), namespaces.iterator());
    }

    private static Attribute attributeByLocalName(StartElement start, String localName) {
        Iterator<?> iterator = start.getAttributes();
        while (iterator.hasNext()) {
            Attribute attribute = (Attribute) iterator.next();
            if (attribute.getName().getLocalPart().equals(localName)) return attribute;
        }
        return null;
    }

    private static void validatePackage(ZipFile source) throws IOException {
        int count = 0;
        long total = 0;
        Set<String> names = new HashSet<>();
        Enumeration<? extends ZipEntry> entries = source.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (++count > MAX_ENTRIES) throw new IOException("OOXML部件数超过100000项安全上限");
            String name = normalizedName(entry.getName());
            if (!names.add(name)) throw new IOException("OOXML包含重复部件名称：" + name);
            validateEntryName(name);
            long size = entry.getSize();
            if (size < 0) throw new IOException("OOXML部件大小未知：" + name);
            if (size > MAX_ENTRY_BYTES) throw new IOException("OOXML单个部件超过2 GiB安全上限：" + name);
            try { total = Math.addExact(total, size); }
            catch (ArithmeticException ex) { throw new IOException("OOXML声明展开量溢出", ex); }
            if (total > MAX_TOTAL_UNCOMPRESSED_BYTES) throw new IOException("OOXML声明展开总量超过8 GiB安全上限");
        }
        if (!names.contains("[Content_Types].xml")) throw new IOException("输入不是有效OOXML包：缺少[Content_Types].xml");
    }

    private static void validateEntryName(String name) throws IOException {
        if (name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("OOXML包含不安全部件路径：" + name);
        }
        for (String part : name.split("/")) {
            if (part.equals("..") || part.equals(".")) throw new IOException("OOXML包含路径穿越部件：" + name);
        }
    }

    private static void rejectUnsafeEmbeddedObject(String name) throws IOException {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("/embeddings/") || lower.contains("/activex/")
                || lower.endsWith("vbaproject.bin") || lower.startsWith("_xmlsignatures/")) {
            throw new IOException("Office包含无法证明已脱敏的嵌入对象、活动内容或数字签名部件：" + name);
        }
    }

    private static boolean isTransformableXml(Kind kind, String name) {
        return name.endsWith(".xml") && (isCoreMetadata(name) || isCustomOrExtendedMetadata(name)
                || kind == Kind.DOCX && name.startsWith("word/")
                || kind == Kind.XLSX && name.startsWith("xl/")
                || kind == Kind.PPTX && name.startsWith("ppt/"));
    }

    private static boolean isScannableXml(Kind kind, String name) {
        return isTransformableXml(kind, name);
    }

    private static boolean isCoreMetadata(String name) {
        return name.equals("docProps/core.xml") || name.equals("docProps/app.xml") || name.equals("docProps/custom.xml");
    }

    private static boolean isMetadataElement(String local) {
        return Set.of("title", "subject", "description", "keywords", "creator", "lastmodifiedby",
                "manager", "company", "author", "lpwstr", "bstr")
                .contains(local.toLowerCase(Locale.ROOT));
    }

    private static boolean isCustomOrExtendedMetadata(String name) {
        return name.startsWith("customXml/") || name.startsWith("docProps/")
                || name.contains("/persons/") || name.contains("/comments");
    }

    private static boolean isGenericFreeTextXml(String name) {
        return name.startsWith("customXml/") || name.equals("docProps/custom.xml")
                || name.equals("docProps/app.xml") || name.contains("/persons/")
                || name.contains("/comments");
    }

    private static boolean isWordContent(String name) {
        return name.startsWith("word/") && name.endsWith(".xml");
    }

    private static boolean isPowerPointContent(String name) {
        return name.startsWith("ppt/") && name.endsWith(".xml");
    }

    private static boolean isChart(String name) { return name.startsWith("ppt/charts/"); }
    private static boolean isPowerPointComments(String name) { return name.contains("/comments/") || name.contains("/comments/comment"); }
    private static boolean isSharedStrings(String name) { return name.equals("xl/sharedStrings.xml"); }
    private static boolean isWorksheet(String name) { return name.startsWith("xl/worksheets/") && name.endsWith(".xml"); }
    private static boolean isWorkbook(String name) { return name.equals("xl/workbook.xml"); }
    private static boolean isExcelComments(String name) { return name.startsWith("xl/comments") && name.endsWith(".xml"); }

    private static boolean isEmbeddedImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("word/media/") || lower.startsWith("xl/media/")
                || lower.startsWith("ppt/media/");
    }

    private static boolean isPackageThumbnail(String name) {
        return name.toLowerCase(Locale.ROOT).startsWith("docprops/thumbnail.");
    }

    private static byte[] blankImage(String extension) throws IOException {
        String format = extension.equals("jpg") ? "jpeg" : extension;
        if (!IMAGE_EXTENSIONS.contains(extension) || !ImageIO.getImageWritersByFormatName(format).hasNext()) {
            throw new IOException("Office 缩略图格式无法安全替换：" + extension);
        }
        BufferedImage image = new BufferedImage(1, 1,
                format.equals("jpeg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream(256);
        if (!ImageIO.write(image, format, output)) {
            throw new IOException("Office 缩略图格式无法安全替换：" + extension);
        }
        return output.toByteArray();
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String cellReference(StartElement cell) {
        Attribute reference = cell.getAttributeByName(new QName("r"));
        return reference == null ? "" : "!" + reference.getValue();
    }

    private static String normalizedName(String value) { return value.replace('\\', '/'); }

    private static byte[] readBounded(InputStream input, long maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        for (int read; (read = input.read(buffer)) >= 0;) {
            if (read == 0) continue;
            total += read;
            if (total > maximum) throw new IOException("内嵌媒体超过安全读取上限");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static XMLInputFactory secureInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        setProperty(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
        setProperty(factory, XMLInputFactory.IS_COALESCING, true);
        setProperty(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setProperty(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("禁止解析Office包中的外部XML实体");
        });
        return factory;
    }

    private static void setProperty(XMLInputFactory factory, String name, Object value) {
        try { factory.setProperty(name, value); } catch (IllegalArgumentException ignored) { }
    }

    private static void addAll(XMLEventWriter writer, List<XMLEvent> events) throws XMLStreamException {
        for (XMLEvent event : events) writer.add(event);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class NonClosingOutputStream extends FilterOutputStream {
        private NonClosingOutputStream(OutputStream output) { super(output); }
        @Override public void close() throws IOException { flush(); }
    }
}
