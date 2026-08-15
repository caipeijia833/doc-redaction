/*
 * Derived and modified from OFDRW 2.4.0, Copyright (c) 2020 Quan guanyu and
 * OFDRW contributors. Licensed under the Apache License, Version 2.0.
 * See LICENSE, NOTICE, and THIRD_PARTY_NOTICES.md.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ofdrw.converter;

import org.ofdrw.converter.font.FontWrapper;
import org.ofdrw.converter.font.MemoryTTFDataStream;
import org.ofdrw.converter.font.TTFDataStream;
import org.ofdrw.converter.font.TrueTypeCollection;
import org.ofdrw.converter.font.TrueTypeFont;
import org.ofdrw.core.text.font.CT_Font;
import org.ofdrw.reader.ResourceLocator;

import java.awt.Font;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Minimal iText-free font loader for OFDRW's AWT image renderer.
 *
 * <p>The method signatures and font-selection flow required by AWTMaker are
 * derived from OFDRW 2.4.0's Apache-2.0 licensed FontLoader. PDF font methods
 * are deliberately absent: this worker renders images and never creates PDF.
 * Embedded OFD fonts are preferred; otherwise a local system font is used.</p>
 */
public final class FontLoader {
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("宋体", "simsun"), Map.entry("新宋体", "simsun"),
            Map.entry("仿宋", "fangsong"), Map.entry("楷体", "kaiti"),
            Map.entry("微软雅黑", "microsoft yahei"), Map.entry("黑体", "simhei"),
            Map.entry("小标宋体", "方正小标宋简体"));
    private static final FontLoader INSTANCE = new FontLoader();

    private final Map<String, Path> fonts = new ConcurrentHashMap<>();
    private TrueTypeFont defaultFont;

    private FontLoader() {
        for (Path directory : systemFontDirectories()) {
            scan(directory);
        }
        defaultFont = loadPreferredDefault();
    }

    public static FontLoader getInstance() {
        return INSTANCE;
    }

    public TrueTypeFont loadDefaultFont() {
        return defaultFont;
    }

    public FontWrapper<TrueTypeFont> loadFontSimilar(ResourceLocator locator, CT_Font font) {
        if (font == null) {
            return new FontWrapper<>(defaultFont, true);
        }
        TrueTypeFont loaded = null;
        boolean replaced = false;
        try {
            if (font.getFontFile() != null) {
                loaded = loadFontFile(locator.getFile(font.getFontFile()), font.getFontName());
            }
        } catch (Exception ignored) {
            // Corrupt or unsupported embedded fonts fall through to a system replacement.
        }
        if (loaded == null) {
            replaced = true;
            Path replacement = find(font.getFontName(), font.getFamilyName());
            if (replacement != null) {
                try {
                    loaded = loadFontFile(replacement, font.getFontName());
                } catch (Exception ignored) {
                    // The pinned default remains the fail-closed local fallback.
                }
            }
        }
        return new FontWrapper<>(loaded == null ? defaultFont : loaded, replaced);
    }

    private Path find(String fontName, String familyName) {
        for (String value : List.of(fontName == null ? "" : fontName,
                familyName == null ? "" : familyName)) {
            String key = normalize(value);
            Path exact = fonts.get(key);
            if (exact != null) {
                return exact;
            }
            String alias = ALIASES.get(value);
            if (alias != null) {
                Path mapped = fonts.get(normalize(alias));
                if (mapped != null) {
                    return mapped;
                }
            }
        }
        return null;
    }

    private TrueTypeFont loadPreferredDefault() {
        List<String> preferred = List.of("simsun", "宋体", "microsoft yahei", "微软雅黑",
                "pingfang sc", "songti sc", "noto sans cjk sc", "arial", "dejavu sans");
        for (String name : preferred) {
            Path path = fonts.get(normalize(name));
            if (path != null) {
                try {
                    return loadFontFile(path, name);
                } catch (Exception ignored) {
                    // Try the next locally installed font.
                }
            }
        }
        for (Path path : fonts.values()) {
            try {
                return loadFontFile(path, null);
            } catch (Exception ignored) {
                // Continue until a parser-compatible font is found.
            }
        }
        return null;
    }

    private void scan(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory, 3)) {
            walk.filter(Files::isRegularFile)
                    .filter(FontLoader::supported)
                    .forEach(this::index);
        } catch (IOException ignored) {
            // A missing or unreadable optional font directory is not fatal.
        }
    }

    private void index(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        fonts.putIfAbsent(normalize(dot < 0 ? fileName : fileName.substring(0, dot)), path);
        try {
            Font awt = Font.createFont(Font.TRUETYPE_FONT, path.toFile());
            for (Locale locale : List.of(Locale.getDefault(), Locale.CHINA, Locale.CHINESE, Locale.ENGLISH)) {
                fonts.putIfAbsent(normalize(awt.getFontName(locale)), path);
                fonts.putIfAbsent(normalize(awt.getFamily(locale)), path);
            }
        } catch (Exception ignored) {
            // TTC and some subset fonts remain addressable by filename.
        }
    }

    private static TrueTypeFont loadFontFile(Path path, String requestedName) throws IOException {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        try (FileInputStream input = new FileInputStream(path.toFile())) {
            TTFDataStream stream = new MemoryTTFDataStream(input);
            if (lower.endsWith(".ttc")) {
                TrueTypeCollection collection = new TrueTypeCollection().parse(stream);
                TrueTypeFont fallback = null;
                for (int index = 0; index < collection.getNumFonts(); index++) {
                    TrueTypeFont candidate = collection.getFontAtIndex(index);
                    if (fallback == null) {
                        fallback = candidate;
                    }
                    if (requestedName != null && candidate.psName != null
                            && candidate.psName.equalsIgnoreCase(requestedName)) {
                        return candidate;
                    }
                }
                return fallback;
            }
            return new TrueTypeFont().parse(stream);
        }
    }

    private static boolean supported(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace("-", " ").replace("_", " ").replaceAll("\\s+", " ");
    }

    private static List<Path> systemFontDirectories() {
        List<Path> result = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            result.add(Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "Fonts"));
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                result.add(Path.of(localAppData, "Microsoft", "Windows", "Fonts"));
            }
        } else if (os.contains("mac")) {
            result.add(Path.of("/System/Library/Fonts"));
            result.add(Path.of("/Library/Fonts"));
            result.add(Path.of(System.getProperty("user.home", "."), "Library", "Fonts"));
        } else {
            result.add(Path.of("/usr/share/fonts"));
            result.add(Path.of("/usr/local/share/fonts"));
            result.add(Path.of(System.getProperty("user.home", "."), ".local", "share", "fonts"));
        }
        return result;
    }
}
