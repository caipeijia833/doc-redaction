/*
 * Derived and modified from OFDRW 2.4.0, Copyright (c) 2020 Quan guanyu and
 * OFDRW contributors. Licensed under the Apache License, Version 2.0.
 * See LICENSE, NOTICE, and THIRD_PARTY_NOTICES.md.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ofdrw.converter.utils;

/**
 * Minimal iText-free compatibility class for OFDRW's AWT renderer.
 * The formula matches the Apache-2.0 licensed OFDRW 2.4.0 implementation.
 */
public final class CommonUtil {
    private CommonUtil() {
    }

    public static double dpiToPpm(int dpi) {
        return dpi * (1d / 25.4d);
    }
}
