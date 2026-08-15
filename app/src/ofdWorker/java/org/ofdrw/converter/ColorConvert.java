/*
 * Derived and modified from OFDRW 2.4.0, Copyright (c) 2020 Quan guanyu and
 * OFDRW contributors. Licensed under the Apache License, Version 2.0.
 * See LICENSE, NOTICE, and THIRD_PARTY_NOTICES.md.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ofdrw.converter;

import org.ofdrw.core.basicType.ST_Array;
import org.ofdrw.core.basicType.ST_RefID;
import org.ofdrw.core.pageDescription.color.color.CT_Color;
import org.ofdrw.core.pageDescription.color.colorSpace.CT_ColorSpace;
import org.ofdrw.core.pageDescription.color.colorSpace.CV;
import org.ofdrw.core.pageDescription.color.colorSpace.OFDColorSpaceType;
import org.ofdrw.core.pageDescription.color.colorSpace.Palette;
import org.ofdrw.reader.ResourceManage;

import java.util.List;

/**
 * iText-free compatibility implementation for OFDRW's AWT renderer.
 *
 * <p>The RGB/CMYK conversion behavior is derived from the Apache-2.0 licensed
 * OFDRW 2.4.0 ColorConvert source. PDF/iText-only methods are intentionally not
 * present because this worker never performs OFD-to-PDF conversion.</p>
 */
public final class ColorConvert {
    private ColorConvert() {
    }

    public static String convertOfdColorToHtml(ST_Array colorArray) {
        String colorString = colorArray.toString();
        if (colorString.contains("#")) {
            return "#" + colorString.replace("#", "").replace(" ", "");
        }
        Double[] values = colorArray.toDouble();
        if (values.length == 3) {
            return "rgb(" + values[0].intValue() + "," + values[1].intValue() + ","
                    + values[2].intValue() + ")";
        }
        if (values.length == 1) {
            return "rgb(" + values[0].intValue() + "," + values[0].intValue() + ","
                    + values[0].intValue() + ")";
        }
        return null;
    }

    public static int[] rgb(ResourceManage resourceManage, CT_Color colorValue) {
        if (colorValue == null) {
            return new int[] {0, 0, 0};
        }
        ST_Array values = colorValue.getValue();
        Integer index = colorValue.getIndex();
        if (values == null && index == null) {
            return new int[] {0, 0, 0};
        }
        CT_ColorSpace colorSpace = null;
        ST_RefID colorSpaceId = colorValue.getColorSpace();
        if (colorSpaceId != null && resourceManage != null) {
            colorSpace = resourceManage.getColorSpace(colorSpaceId.toString());
        }
        if (colorSpace == null) {
            if (values == null) {
                return new int[] {0, 0, 0};
            }
            colorSpace = new CT_ColorSpace(OFDColorSpaceType.RGB);
        }
        if (index != null) {
            Palette palette = colorSpace.getPalette();
            if (palette == null) {
                return new int[] {0, 0, 0};
            }
            List<CV> paletteValues = palette.getCVs();
            if (index < 0 || index >= paletteValues.size()) {
                return new int[] {0, 0, 0};
            }
            values = paletteValues.get(index).getColor();
        }
        if (values == null) {
            return new int[] {0, 0, 0};
        }
        int[] channels = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            String value = values.getArray().get(i);
            if (value.startsWith("#")) {
                channels[i] = Integer.parseInt(value.substring(1), 16);
            } else if (value.contains(".")) {
                channels[i] = (int) Float.parseFloat(value);
            } else {
                channels[i] = Integer.parseInt(value);
            }
        }
        return switch (colorSpace.getType()) {
            case RGB -> channels.length >= 3 ? new int[] {channels[0], channels[1], channels[2]}
                    : new int[] {0, 0, 0};
            case CMYK -> channels.length >= 4 ? cmykToRgb(channels[0], channels[1], channels[2], channels[3])
                    : new int[] {0, 0, 0};
            case GRAY -> channels.length >= 1 ? new int[] {channels[0], channels[0], channels[0]}
                    : new int[] {0, 0, 0};
        };
    }

    public static int[] cmykToRgb(int cyan, int magenta, int yellow, int black) {
        double c = clampPercent(cyan) / 100d;
        double m = clampPercent(magenta) / 100d;
        double y = clampPercent(yellow) / 100d;
        double k = clampPercent(black) / 100d;
        return new int[] {
                (int) Math.round(255d * (1d - c) * (1d - k)),
                (int) Math.round(255d * (1d - m) * (1d - k)),
                (int) Math.round(255d * (1d - y) * (1d - k))
        };
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
