/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.archive;

import io.github.caipeijia833.docredaction.util.JsonUtil;

public record ArchiveEntryInfo(
        int index,
        String path,
        boolean directory,
        long size,
        long compressedSize,
        ArchiveCategory category,
        String reason,
        boolean safePath,
        boolean readable) {

    public boolean processedByDefault() {
        return !directory && safePath && readable && category == ArchiveCategory.REDACTABLE;
    }

    public String toJson() {
        return "{" +
                "\"index\":" + index + ',' +
                "\"path\":" + JsonUtil.quote(path) + ',' +
                "\"directory\":" + directory + ',' +
                "\"size\":" + size + ',' +
                "\"compressedSize\":" + compressedSize + ',' +
                "\"category\":" + JsonUtil.quote(category.name()) + ',' +
                "\"reason\":" + JsonUtil.quote(reason) + ',' +
                "\"safePath\":" + safePath + ',' +
                "\"readable\":" + readable + ',' +
                "\"processedByDefault\":" + processedByDefault() +
                "}";
    }
}
