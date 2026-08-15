/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

public enum JobStatus {
    UPLOADED,
    INSPECTING,
    AWAITING_CONFIRMATION,
    ANALYZING,
    AWAITING_REVIEW,
    PROCESSING,
    COMPLETED,
    INTERRUPTED,
    CANCELLED,
    FAILED
}
