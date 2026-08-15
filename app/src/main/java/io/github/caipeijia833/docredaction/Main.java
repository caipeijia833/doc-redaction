/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction;

import io.github.caipeijia833.docredaction.processor.DocumentProcessor;
import io.github.caipeijia833.docredaction.processor.ProcessReport;
import io.github.caipeijia833.docredaction.processor.OpenCvRuntime;
import io.github.caipeijia833.docredaction.processor.ProcessorRegistry;
import io.github.caipeijia833.docredaction.rules.RuleEngine;
import io.github.caipeijia833.docredaction.server.JobService;
import io.github.caipeijia833.docredaction.server.JobStore;
import io.github.caipeijia833.docredaction.server.InstanceLock;
import io.github.caipeijia833.docredaction.server.RedactionServer;
import io.github.caipeijia833.docredaction.tools.SyntheticSampleGenerator;
import io.github.caipeijia833.docredaction.tools.StressCorpusGenerator;
import io.github.caipeijia833.docredaction.tools.LargeFileAcceptanceRunner;
import io.github.caipeijia833.docredaction.util.JsonUtil;
import io.github.caipeijia833.docredaction.update.OfflineUpdateSecurity;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("log4j2.statusLoggerLevel", "OFF");
        if (args.length > 0 && "--opencv-probe".equals(args[0])) {
            System.out.println("OPENCV_READY=" + OpenCvRuntime.loadAndVersion());
            return;
        }
        if (args.length > 0 && "--verify-update".equals(args[0])) {
            if (args.length != 5) {
                throw new IllegalArgumentException("Usage: --verify-update <manifest> <signature> <trusted-keys> <payload>");
            }
            var verified = OfflineUpdateSecurity.verify(Path.of(args[1]), Path.of(args[2]),
                    Path.of(args[3]), Path.of(args[4]));
            System.out.println("UPDATE_VERIFIED=" + verified.version() + ";key=" + verified.keyId()
                    + ";files=" + verified.fileCount() + ";bytes=" + verified.totalBytes());
            return;
        }
        if (args.length > 0 && "--sign-update".equals(args[0])) {
            if (args.length != 4) {
                throw new IllegalArgumentException("Usage: --sign-update <manifest> <private-pkcs8> <signature>");
            }
            OfflineUpdateSecurity.sign(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            System.out.println("UPDATE_SIGNED=" + Path.of(args[3]).toAbsolutePath().normalize());
            return;
        }
        if (args.length > 0 && "--create-update-manifest".equals(args[0])) {
            if (args.length != 5) {
                throw new IllegalArgumentException("Usage: --create-update-manifest <payload> <version> <key-id> <manifest>");
            }
            var created = OfflineUpdateSecurity.createManifest(Path.of(args[1]), args[2], args[3], Path.of(args[4]));
            System.out.println("UPDATE_MANIFEST_CREATED=" + created.version() + ";files="
                    + created.fileCount() + ";bytes=" + created.totalBytes());
            return;
        }
        if (args.length > 0 && "--generate-update-keypair".equals(args[0])) {
            if (args.length != 4) {
                throw new IllegalArgumentException("Usage: --generate-update-keypair <private-pkcs8> <public-keys.properties> <key-id>");
            }
            OfflineUpdateSecurity.generateKeyPair(Path.of(args[1]), Path.of(args[2]), args[3]);
            System.out.println("UPDATE_KEYPAIR_CREATED key=" + args[3]);
            return;
        }
        if (args.length > 0 && "--assert-instance-stopped".equals(args[0])) {
            if (args.length != 2) {
                throw new IllegalArgumentException("Usage: --assert-instance-stopped <data-root>");
            }
            try (InstanceLock ignored = InstanceLock.acquire(Path.of(args[1]))) {
                System.out.println("INSTANCE_STOPPED=1");
            }
            return;
        }
        if (args.length > 0 && "--process".equals(args[0])) {
            runCli(args);
            return;
        }
        if (args.length > 0 && "--generate-samples".equals(args[0])) {
            if (args.length != 2) {
                throw new IllegalArgumentException("用法: --generate-samples <输出目录>");
            }
            Path directory = Path.of(args[1]).toAbsolutePath().normalize();
            SyntheticSampleGenerator.generate(directory);
            System.out.println("SYNTHETIC_SAMPLES_CREATED=" + directory);
            return;
        }
        if (args.length > 0 && "--generate-stress-corpus".equals(args[0])) {
            if (args.length < 2 || args.length > 3) {
                throw new IllegalArgumentException(
                        "用法: --generate-stress-corpus <输出目录> [smoke|full]");
            }
            Path directory = Path.of(args[1]).toAbsolutePath().normalize();
            StressCorpusGenerator.Profile profile = StressCorpusGenerator.Profile.parse(
                    args.length == 3 ? args[2] : "full");
            Path manifest = StressCorpusGenerator.generate(directory, profile);
            System.out.println("SYNTHETIC_STRESS_MANIFEST=" + manifest);
            return;
        }
        if (args.length > 0 && "--acceptance-run".equals(args[0])) {
            if (args.length != 4) {
                throw new IllegalArgumentException(
                        "用法: --acceptance-run <输入文件> <隔离数据目录> <报告JSON>");
            }
            Path report = LargeFileAcceptanceRunner.run(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            System.out.println("LARGE_FILE_ACCEPTANCE_REPORT=" + report);
            return;
        }
        if (args.length > 0 && "--generate-office-tier".equals(args[0])) {
            if (args.length != 3) {
                throw new IllegalArgumentException("用法: --generate-office-tier <输出目录> <目标MiB>");
            }
            long mib = Long.parseLong(args[2]);
            var generated = StressCorpusGenerator.generateOfficeTier(Path.of(args[1]),
                    Math.multiplyExact(mib, 1024L * 1024L));
            generated.forEach(path -> System.out.println("SYNTHETIC_OFFICE_FILE=" + path));
            return;
        }
        runServer(args);
    }

    private static void runServer(String[] args) throws Exception {
        int port = intOption(args, "--port", 8765);
        Path dataRoot = Path.of(stringOption(args, "--data", "data")).toAbsolutePath().normalize();
        boolean openBrowser = !hasOption(args, "--no-open");
        try (InstanceLock ignored = InstanceLock.acquire(dataRoot)) {
        RuleEngine rules = RuleEngine.createDefault(dataRoot.resolve("config").resolve("rules.properties"));
        ProcessorRegistry registry = new ProcessorRegistry();
        JobStore store = new JobStore(dataRoot);
        JobService jobs = new JobService(store, registry, rules);
        RedactionServer server = new RedactionServer(port, jobs, rules);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            jobs.close();
        }, "local-shutdown"));
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.port() + "/");
        System.out.println("DOC_REDACTION_URL=" + uri);
        System.out.println("仅监听本机回环地址；按 Ctrl+C 停止服务。");
        if (openBrowser && Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(uri);
            } catch (Exception ex) {
                System.out.println("浏览器未自动打开，请手动访问上方地址。");
            }
        }
        new CountDownLatch(1).await();
        }
    }

    private static void runCli(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("用法: --process <输入文件> <输出文件>");
        }
        Path input = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("输入文件不存在: " + input);
        }
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        RuleEngine rules = RuleEngine.createDefault();
        DocumentProcessor processor = new ProcessorRegistry().requireProcessor(input);
        ProcessReport report = processor.process(input, output, rules);
        String json = "{" +
                "\"input\":" + JsonUtil.quote(input.toString()) + ',' +
                "\"output\":" + JsonUtil.quote(output.toString()) + ',' +
                "\"unitsProcessed\":" + report.unitsProcessed() + ',' +
                "\"totalMatches\":" + report.totalMatches() + ',' +
                "\"counts\":" + JsonUtil.stringMap(report.counts()) + ',' +
                "\"warnings\":" + JsonUtil.stringArray(report.warnings()) +
                "}";
        System.out.println(json);
    }

    private static boolean hasOption(String[] args, String option) {
        for (String arg : args) {
            if (option.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static int intOption(String[] args, String option, int fallback) {
        return Integer.parseInt(stringOption(args, option, Integer.toString(fallback)));
    }

    private static String stringOption(String[] args, String option, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (option.equals(args[i])) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
