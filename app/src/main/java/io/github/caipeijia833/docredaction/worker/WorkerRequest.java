/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.worker;

import io.github.caipeijia833.docredaction.archive.ArchiveEntryAction;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record WorkerRequest(
        WorkerOperation operation,
        Path input,
        Path output,
        Path workDirectory,
        Path ruleSettings,
        Path keyDirectory,
        String projectId,
        String processingMode,
        boolean includeUnprocessed,
        Map<Integer, ArchiveEntryAction> archiveEntryActions,
        int page,
        List<String> ignoredValues,
        List<String> selectedRuleCategories) {
    public static final int REQUEST_MAGIC = 0x44525731;
    public static final int RESPONSE_MAGIC = 0x44525231;
    private static final int MAX_STRING_BYTES = 1024 * 1024;

    public WorkerRequest {
        archiveEntryActions = archiveEntryActions == null ? Map.of() : Map.copyOf(archiveEntryActions);
        ignoredValues = ignoredValues == null ? List.of() : List.copyOf(ignoredValues);
        selectedRuleCategories = selectedRuleCategories == null ? List.of() : List.copyOf(selectedRuleCategories);
    }

    public void writeTo(DataOutput outputStream) throws IOException {
        outputStream.writeInt(REQUEST_MAGIC);
        writeString(outputStream, operation.name());
        writePath(outputStream, input);
        writePath(outputStream, output);
        writePath(outputStream, workDirectory);
        writePath(outputStream, ruleSettings);
        writePath(outputStream, keyDirectory);
        writeString(outputStream, projectId);
        writeString(outputStream, processingMode);
        outputStream.writeBoolean(includeUnprocessed);
        outputStream.writeInt(archiveEntryActions.size());
        for (Map.Entry<Integer, ArchiveEntryAction> entry : archiveEntryActions.entrySet()) {
            outputStream.writeInt(entry.getKey());
            writeString(outputStream, entry.getValue().name());
        }
        outputStream.writeInt(page);
        outputStream.writeInt(ignoredValues.size());
        for (String value : ignoredValues) {
            writeString(outputStream, value);
        }
        outputStream.writeInt(selectedRuleCategories.size());
        for (String category : selectedRuleCategories) {
            writeString(outputStream, category);
        }
    }

    public static WorkerRequest readFrom(DataInput inputStream) throws IOException {
        if (inputStream.readInt() != REQUEST_MAGIC) {
            throw new IOException("工作进程请求协议无效");
        }
        WorkerOperation operation = WorkerOperation.valueOf(readString(inputStream));
        Path input = readPath(inputStream);
        Path output = readPath(inputStream);
        Path workDirectory = readPath(inputStream);
        Path ruleSettings = readPath(inputStream);
        Path keyDirectory = readPath(inputStream);
        String projectId = readString(inputStream);
        String processingMode = readString(inputStream);
        boolean includeUnprocessed = inputStream.readBoolean();
        int archiveActionCount = inputStream.readInt();
        if (archiveActionCount < 0 || archiveActionCount > 10_000) {
            throw new IOException("工作进程压缩包决策数量无效");
        }
        Map<Integer, ArchiveEntryAction> archiveEntryActions = new LinkedHashMap<>();
        for (int i = 0; i < archiveActionCount; i++) {
            int index = inputStream.readInt();
            ArchiveEntryAction action;
            try {
                action = ArchiveEntryAction.valueOf(readString(inputStream));
            } catch (IllegalArgumentException ex) {
                throw new IOException("工作进程压缩包决策无效", ex);
            }
            if (index <= 0 || archiveEntryActions.putIfAbsent(index, action) != null) {
                throw new IOException("工作进程压缩包决策索引无效");
            }
        }
        int page = inputStream.readInt();
        int ignoredCount = inputStream.readInt();
        if (ignoredCount < 0 || ignoredCount > 5_000) {
            throw new IOException("工作进程忽略项数量无效");
        }
        List<String> ignored = new ArrayList<>(ignoredCount);
        for (int i = 0; i < ignoredCount; i++) {
            ignored.add(readString(inputStream));
        }
        int categoryCount = inputStream.readInt();
        if (categoryCount < 0 || categoryCount > 100) {
            throw new IOException("工作进程规则分类数量无效");
        }
        List<String> categories = new ArrayList<>(categoryCount);
        for (int i = 0; i < categoryCount; i++) {
            categories.add(readString(inputStream));
        }
        return new WorkerRequest(operation, input, output, workDirectory, ruleSettings, keyDirectory,
                projectId, processingMode, includeUnprocessed, archiveEntryActions, page, ignored, categories);
    }

    public static void writeString(DataOutput output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("工作进程协议字段过长");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    public static String readString(DataInput input) throws IOException {
        int length = input.readInt();
        if (length == -1) {
            return null;
        }
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("工作进程协议字段长度无效");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writePath(DataOutput output, Path path) throws IOException {
        writeString(output, path == null ? null : path.toAbsolutePath().normalize().toString());
    }

    private static Path readPath(DataInput input) throws IOException {
        String value = readString(input);
        return value == null ? null : Path.of(value).toAbsolutePath().normalize();
    }
}
