/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.ofd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicOfdCompatibilityTest {
    private static final Map<String, String> SHA256 = Map.of(
            "helloworld.ofd", "a7b6f9eceb7ca4eafc73a318ac06f5476af6010d0dbcc99337912049748e141e",
            "containsJPEG.ofd", "2cb4f2c28b2593a803f7f2516fc100ba6277a48816c4175e8636e0ed25c800bb",
            "signout.ofd", "ed321ed91a2b27c875ec60dcab5e409715a3d7d51735bd06616ba14a64866abb");

    @TempDir
    Path temp;

    @Test
    void rendersPinnedApacheLicensedOfdrwSamples() throws Exception {
        OfdBridgeRunner runner = new OfdBridgeRunner();
        for (Map.Entry<String, String> fixture : SHA256.entrySet()) {
            Path input = resource(fixture.getKey());
            assertEquals(fixture.getValue(), sha256(input), fixture.getKey());

            List<Path> pages = runner.renderAll(input,
                    temp.resolve(fixture.getKey() + "-pages"), 2.0d);
            assertFalse(pages.isEmpty(), fixture.getKey() + " rendered no pages");
            for (Path page : pages) {
                assertTrue(Files.size(page) > 0, page.toString());
                var image = ImageIO.read(page.toFile());
                assertNotNull(image, page.toString());
                assertTrue(image.getWidth() > 0 && image.getHeight() > 0, page.toString());
            }
        }
    }

    private static Path resource(String name) throws URISyntaxException {
        var url = PublicOfdCompatibilityTest.class.getResource("/public/ofdrw/" + name);
        assertNotNull(url, name);
        return Path.of(url.toURI());
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
