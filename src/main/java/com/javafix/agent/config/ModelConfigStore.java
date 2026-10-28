package com.javafix.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reads and writes the per-user model configuration. */
public final class ModelConfigStore {

    public static final String ENV_BASE_URL = "JAVAFIX_LLM_BASE_URL";
    public static final String ENV_API_KEY = "JAVAFIX_LLM_API_KEY";
    public static final String ENV_MODEL = "JAVAFIX_LLM_MODEL";

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private final Path configFile;
    private final ObjectMapper mapper = new ObjectMapper();

    public ModelConfigStore(Path configFile) {
        this.configFile = configFile.toAbsolutePath().normalize();
    }

    public Optional<ModelConfig> load() throws IOException {
        if (Files.notExists(configFile)) {
            return Optional.empty();
        }
        StoredConfig stored = mapper.readValue(configFile.toFile(), StoredConfig.class);
        try {
            return Optional.of(new ModelConfig(stored.baseUrl(), stored.apiKey(), stored.model()));
        } catch (RuntimeException e) {
            throw new IOException("模型配置缺少有效的 baseUrl、apiKey 或 model", e);
        }
    }

    public void save(ModelConfig config) throws IOException {
        Path parent = configFile.getParent();
        if (parent != null) {
            createPrivateDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, "config-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), config);
            restrictFilePermissions(temporary);
            try {
                Files.move(temporary, configFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictFilePermissions(configFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static ModelConfig fromEnvironment(ModelConfig saved, Map<String, String> environment) {
        String baseUrl = override(environment, ENV_BASE_URL, saved == null ? null : saved.baseUrl());
        String apiKey = override(environment, ENV_API_KEY, saved == null ? null : saved.apiKey());
        String model = override(environment, ENV_MODEL, saved == null ? null : saved.model());
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(model)) {
            throw new IllegalStateException("模型配置不完整；请运行首次配置，或设置 JAVAFIX_LLM_BASE_URL、"
                    + "JAVAFIX_LLM_API_KEY 和 JAVAFIX_LLM_MODEL");
        }
        return new ModelConfig(baseUrl, apiKey, model);
    }

    private static String override(Map<String, String> environment, String key, String fallback) {
        String value = environment.get(key);
        return isBlank(value) ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void createPrivateDirectories(Path directory) throws IOException {
        boolean posix = supportsPosix(directory);
        if (posix) {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } else {
            Files.createDirectories(directory);
        }
    }

    private static void restrictFilePermissions(Path file) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        }
    }

    private static boolean supportsPosix(Path path) throws IOException {
        Path existing = path;
        while (existing != null && Files.notExists(existing)) {
            existing = existing.getParent();
        }
        return existing != null
                && Files.getFileStore(existing).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    private record StoredConfig(String baseUrl, String apiKey, String model) {
    }
}
