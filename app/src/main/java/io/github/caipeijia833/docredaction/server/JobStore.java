/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists job state both as human-readable properties and in a transactional
 * SQLite index. The properties files retain backwards compatibility while the
 * database provides deterministic ordering, recovery indexing and audit data.
 */
public final class JobStore {
    private static final int SQLITE_BUSY_TIMEOUT_MILLIS = 5_000;

    private final Path dataRoot;
    private final Path projectsRoot;
    private final Path databasePath;
    private final ConcurrentHashMap<String, JobRecord> jobs = new ConcurrentHashMap<>();
    private final Map<String, JobStatus> lastPersistedStatus = new ConcurrentHashMap<>();

    public JobStore(Path dataRoot) throws IOException {
        this.dataRoot = dataRoot.toAbsolutePath().normalize();
        this.projectsRoot = this.dataRoot.resolve("projects").toAbsolutePath().normalize();
        this.databasePath = this.dataRoot.resolve("jobs.db").toAbsolutePath().normalize();
        Files.createDirectories(projectsRoot);
        initializeDatabase();
        loadExisting();
        synchronizeIndex();
    }

    public Path dataRoot() {
        return dataRoot;
    }

    public Path databasePath() {
        return databasePath;
    }

    public Path createJobDirectory(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IOException("非法任务标识");
        }
        Path directory = projectsRoot.resolve(id).normalize();
        if (!directory.startsWith(projectsRoot)) {
            throw new IOException("非法任务目录");
        }
        // A job UUID must own a newly-created real directory. Accepting an existing
        // symlink here would let another local process redirect plaintext or vault data.
        Files.createDirectory(directory);
        Files.createDirectory(directory.resolve("original"));
        Files.createDirectory(directory.resolve("output"));
        Files.createDirectory(directory.resolve("work"));
        Files.createDirectory(directory.resolve("vault"));
        return directory;
    }

    public void put(JobRecord job) throws IOException {
        jobs.put(job.id(), job);
        save(job);
    }

    public Optional<JobRecord> get(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public List<JobRecord> list() {
        List<JobRecord> ordered = new ArrayList<>();
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM jobs ORDER BY created_at DESC, id DESC");
                ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                JobRecord record = jobs.get(results.getString(1));
                if (record != null) {
                    ordered.add(record);
                }
            }
            return List.copyOf(ordered);
        } catch (SQLException ex) {
            // Keep history readable if the index is temporarily unavailable.
            return jobs.values().stream()
                    .sorted(Comparator.comparing(JobRecord::createdAt).reversed())
                    .toList();
        }
    }

    public void save(JobRecord job) throws IOException {
        PersistentSnapshot snapshot = PersistentSnapshot.capture(job);
        synchronized (this) {
            writeProperties(snapshot);
            boolean statusChanged = lastPersistedStatus.get(snapshot.id()) != snapshot.status();
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                try {
                    upsert(connection, snapshot);
                    if (statusChanged) {
                        insertAuditEvent(connection, snapshot, "STATUS_TRANSITION", null);
                    }
                    connection.commit();
                    lastPersistedStatus.put(snapshot.id(), snapshot.status());
                } catch (SQLException ex) {
                    rollbackQuietly(connection);
                    throw ex;
                }
            } catch (SQLException ex) {
                throw new IOException("无法写入本地任务数据库", ex);
            }
        }
    }

    public void recordEvent(JobRecord job, String eventType, String details) throws IOException {
        PersistentSnapshot snapshot = PersistentSnapshot.capture(job);
        synchronized (this) {
            try (Connection connection = connect()) {
                insertAuditEvent(connection, snapshot, eventType, sanitizeAuditDetails(details));
            } catch (SQLException ex) {
                throw new IOException("无法写入本地审计事件", ex);
            }
        }
    }

    public synchronized void remove(String id) throws IOException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM jobs WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
            jobs.remove(id);
            lastPersistedStatus.remove(id);
        } catch (SQLException ex) {
            throw new IOException("无法删除本地任务索引", ex);
        }
    }

    public long dataUsageBytes() throws IOException {
        if (!Files.exists(dataRoot)) {
            return 0L;
        }
        try (var paths = Files.walk(dataRoot)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException ignored) {
                            return 0L;
                        }
                    })
                    .reduce(0L, (left, right) -> {
                        try {
                            return Math.addExact(left, right);
                        } catch (ArithmeticException ex) {
                            return Long.MAX_VALUE;
                        }
                    });
        }
    }

    private void initializeDatabase() throws IOException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA wal_autocheckpoint=1000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS jobs (
                        id TEXT PRIMARY KEY,
                        project_id TEXT NOT NULL,
                        project_name TEXT NOT NULL,
                        original_name TEXT NOT NULL,
                        status TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        sha256 TEXT,
                        size_bytes INTEGER NOT NULL,
                        state_json TEXT NOT NULL,
                        properties_path TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS jobs_project_created
                    ON jobs(project_id, created_at DESC)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS audit_events (
                        event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        job_id TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        event_at TEXT NOT NULL,
                        status TEXT NOT NULL,
                        details TEXT,
                        FOREIGN KEY(job_id) REFERENCES jobs(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS audit_events_job_time
                    ON audit_events(job_id, event_at DESC)
                    """);
        } catch (SQLException ex) {
            throw new IOException("无法初始化本地任务数据库", ex);
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + SQLITE_BUSY_TIMEOUT_MILLIS);
        }
        return connection;
    }

    private void writeProperties(PersistentSnapshot snapshot) throws IOException {
        if (snapshot.id() == null || !snapshot.id().matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IOException("非法任务标识");
        }
        Path directory = projectsRoot.resolve(snapshot.id()).normalize();
        if (!directory.startsWith(projectsRoot)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("非法任务目录");
        }
        Path temp = directory.resolve("job.properties." + UUID.randomUUID() + ".tmp");
        Path target = directory.resolve("job.properties");
        try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            snapshot.properties().store(writer, "doc-redaction job state");
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallback) {
                Files.deleteIfExists(temp);
                fallback.addSuppressed(ex);
                throw fallback;
            }
        }
    }

    private void upsert(Connection connection, PersistentSnapshot snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO jobs (
                    id, project_id, project_name, original_name, status,
                    created_at, updated_at, sha256, size_bytes, state_json, properties_path
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    project_id=excluded.project_id,
                    project_name=excluded.project_name,
                    original_name=excluded.original_name,
                    status=excluded.status,
                    created_at=excluded.created_at,
                    updated_at=excluded.updated_at,
                    sha256=excluded.sha256,
                    size_bytes=excluded.size_bytes,
                    state_json=excluded.state_json,
                    properties_path=excluded.properties_path
                """)) {
            statement.setString(1, snapshot.id());
            statement.setString(2, snapshot.projectId());
            statement.setString(3, snapshot.projectName());
            statement.setString(4, snapshot.originalName());
            statement.setString(5, snapshot.status().name());
            statement.setString(6, snapshot.createdAt());
            statement.setString(7, snapshot.updatedAt());
            statement.setString(8, snapshot.sha256());
            statement.setLong(9, snapshot.size());
            statement.setString(10, snapshot.stateJson());
            statement.setString(11, projectsRoot.resolve(snapshot.id()).resolve("job.properties").toString());
            statement.executeUpdate();
        }
    }

    private void insertAuditEvent(Connection connection, PersistentSnapshot snapshot, String eventType, String details)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_events(job_id, event_type, event_at, status, details)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, snapshot.id());
            statement.setString(2, eventType);
            statement.setString(3, snapshot.updatedAt());
            statement.setString(4, snapshot.status().name());
            statement.setString(5, details);
            statement.executeUpdate();
        }
    }

    private void loadExisting() throws IOException {
        try (var directories = Files.list(projectsRoot)) {
            for (Path directory : directories
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                Path stateFile = directory.resolve("job.properties");
                if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Properties properties = new Properties();
                try (BufferedReader reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                    JobRecord record = JobRecord.fromProperties(properties);
                    validateLoadedRecord(directory, record);
                    jobs.put(record.id(), record);
                    lastPersistedStatus.put(record.id(), record.status());
                } catch (IOException | RuntimeException ex) {
                    // A corrupt history entry is skipped instead of preventing startup.
                }
            }
        }
    }

    private static void validateLoadedRecord(Path directory, JobRecord record) throws IOException {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        String directoryId = normalizedDirectory.getFileName().toString();
        if (record.id() == null || !record.id().equals(directoryId)
                || !record.id().matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IOException("任务状态标识与目录不一致");
        }
        validateStoredPath(record.inputPath(), normalizedDirectory.resolve("original"), true);
        validateStoredPath(record.outputPath(), normalizedDirectory.resolve("output"), false);
        validateStoredPath(record.vaultPath(), normalizedDirectory.resolve("vault"), false);
    }

    private static void validateStoredPath(Path path, Path allowedRoot, boolean required) throws IOException {
        if (path == null) {
            if (required) {
                throw new IOException("任务状态缺少必需路径");
            }
            return;
        }
        Path normalizedRoot = allowedRoot.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot) || normalized.equals(normalizedRoot)
                || Files.isSymbolicLink(normalizedRoot) || Files.isSymbolicLink(normalized)) {
            throw new IOException("任务状态包含越界或符号链接路径");
        }
    }

    private void synchronizeIndex() throws IOException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                for (JobRecord job : jobs.values()) {
                    upsert(connection, PersistentSnapshot.capture(job));
                }
                connection.commit();
            } catch (SQLException ex) {
                rollbackQuietly(connection);
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IOException("无法同步本地任务索引", ex);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the primary database error.
        }
    }

    private static String sanitizeAuditDetails(String details) {
        if (details == null || details.isBlank()) {
            return null;
        }
        String value = details.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    /** Captured before entering the store monitor to prevent JobRecord/JobStore lock inversion. */
    private record PersistentSnapshot(String id, String projectId, String projectName, String originalName,
            JobStatus status, String createdAt, String updatedAt, String sha256, long size,
            String stateJson, Properties properties) {
        static PersistentSnapshot capture(JobRecord job) {
            synchronized (job) {
                return new PersistentSnapshot(job.id(), job.projectId(), job.projectName(), job.originalName(),
                        job.status(), job.createdAt().toString(), job.updatedAt().toString(), job.sha256(),
                        job.size(), job.toJson(), job.toProperties());
            }
        }
    }
}
