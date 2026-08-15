/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.archive;

/** User-confirmed handling policy for a single non-directory archive entry. */
public enum ArchiveEntryAction {
    REDACT,
    EXCLUDE,
    KEEP_UNPROCESSED
}
