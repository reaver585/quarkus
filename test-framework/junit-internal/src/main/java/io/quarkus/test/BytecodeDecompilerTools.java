package io.quarkus.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.jar.Manifest;

import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.junit.jupiter.api.extension.ExtensionContext;

class BytecodeDecompilerTools {

    private static final DateTimeFormatter TEST_RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Map<String, Object> VINEFLOWER_OPTIONS = createVineflowerOptions();

    static void decompileClassDump(Path classInputDir, Path decompiledOutputDir, StringBuilder index) throws Exception {
        if (!Files.exists(classInputDir)) {
            return;
        }
        Files.createDirectories(decompiledOutputDir);

        ByteArrayOutputStream decompilerOutputBuffer = new ByteArrayOutputStream();
        try (PrintStream loggerStream = new PrintStream(decompilerOutputBuffer, true, StandardCharsets.UTF_8)) {
            BaseDecompiler decompiler = new BaseDecompiler(new DirectoryResultSaver(decompiledOutputDir), VINEFLOWER_OPTIONS,
                    new PrintStreamLogger(loggerStream));
            decompiler.addSource(classInputDir.toFile());
            decompiler.decompileContext();

            Files.write(decompiledOutputDir.resolve("decompile.log"), decompilerOutputBuffer.toByteArray());
            index.append("DECOMPILED ").append(classInputDir).append(" -> ").append(decompiledOutputDir).append('\n');
        } catch (Exception e) {
            Files.write(decompiledOutputDir.resolve("decompile.log"), decompilerOutputBuffer.toByteArray());
            index.append("DECOMPILE_FAILED ").append(classInputDir).append(" -> ").append(decompiledOutputDir)
                    .append(" (").append(e.getClass().getSimpleName()).append(": ")
                    .append(Objects.toString(e.getMessage(), "no message")).append(")\n");
        }
    }

    private static Map<String, Object> createVineflowerOptions() {
        Map<String, Object> options = new HashMap<>(IFernflowerPreferences.getDefaults());
        options.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "0");
        options.put(IFernflowerPreferences.REMOVE_BRIDGE, "0");
        return Map.copyOf(options);
    }

    record InMemoryClassDiff(
            Map<String, byte[]> reference,
            Map<String, byte[]> current,
            TreeSet<String> missing,
            TreeSet<String> extra,
            TreeSet<String> changed) {

        static InMemoryClassDiff of(Map<String, byte[]> reference, Map<String, byte[]> current) {
            TreeSet<String> missing = new TreeSet<>(reference.keySet());
            missing.removeAll(current.keySet());

            TreeSet<String> extra = new TreeSet<>(current.keySet());
            extra.removeAll(reference.keySet());

            TreeSet<String> changed = new TreeSet<>();
            for (String resource : reference.keySet()) {
                byte[] currentBytes = current.get(resource);
                if (currentBytes != null && !Arrays.equals(reference.get(resource), currentBytes)) {
                    changed.add(resource);
                }
            }

            return new InMemoryClassDiff(reference, current, missing, extra, changed);
        }

        Path dumpReproducibilityMismatch(int run, ExtensionContext extensionContext) {
            String testRunId = createTestRunId(extensionContext);
            Path baseDir = Path.of("target", "debug").resolve(testRunId).resolve("reproducibility-mismatch");
            Path run1Dir = baseDir.resolve("run-1");
            Path runNDir = baseDir.resolve("run-" + run);

            StringBuilder index = new StringBuilder();
            index.append("run-1 vs run-").append(run).append('\n');

            try {
                Files.createDirectories(run1Dir);
                Files.createDirectories(runNDir);

                for (String resource : missing) {
                    writeClassDump(run1Dir, resource, reference.get(resource));
                    index.append("MISSING_IN_RUN_").append(run).append(' ').append(resource)
                            .append(" run-1-sha256=").append(sha256(reference.get(resource))).append('\n');
                }
                for (String resource : extra) {
                    writeClassDump(runNDir, resource, current.get(resource));
                    index.append("EXTRA_IN_RUN_").append(run).append(' ').append(resource)
                            .append(" run-").append(run).append("-sha256=").append(sha256(current.get(resource)))
                            .append('\n');
                }
                for (String resource : changed) {
                    writeClassDump(run1Dir, resource, reference.get(resource));
                    writeClassDump(runNDir, resource, current.get(resource));
                    index.append("CHANGED ").append(resource)
                            .append(" run-1-sha256=").append(sha256(reference.get(resource)))
                            .append(" run-").append(run).append("-sha256=").append(sha256(current.get(resource)))
                            .append('\n');
                }

                decompileClassDump(run1Dir, baseDir.resolve("run-1-decompiled"), index);
                decompileClassDump(runNDir, baseDir.resolve("run-" + run + "-decompiled"), index);

                Files.writeString(baseDir.resolve("mismatch.txt"), index.toString());
                return baseDir;
            } catch (Exception e) {
                throw new IllegalStateException("Unable to dump reproducibility mismatch classes to " + baseDir, e);
            }
        }

        private String sha256(byte[] data) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(data);
                StringBuilder builder = new StringBuilder(hash.length * 2);
                for (byte b : hash) {
                    builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                    builder.append(Character.forDigit(b & 0xF, 16));
                }
                return builder.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 not available", e);
            }
        }

        boolean isEmpty() {
            return missing.isEmpty() && extra.isEmpty() && changed.isEmpty();
        }

        @Override
        public String toString() {
            return "missing=" + missing().size() + " " + pretty(missing())
                    + ", extra=" + extra().size() + " " + pretty(extra())
                    + ", changed=" + changed().size() + " " + pretty(changed());
        }

        private void writeClassDump(Path runDir, String resource, byte[] data) throws Exception {
            Path outputFile = runDir.resolve(resource);
            Files.createDirectories(outputFile.getParent());
            Files.write(outputFile, data);
        }

        private String createTestRunId(ExtensionContext extensionContext) {
            String timestamp = LocalDateTime.now().format(TEST_RUN_ID_FORMAT);
            String randomSuffix = String.format("%06x", ThreadLocalRandom.current().nextInt(0x1000000));
            return extensionContext.getRequiredTestClass().getName() + "/" + timestamp + "-" + randomSuffix;
        }

        private String pretty(TreeSet<String> values) {
            return "[" + String.join(", ", values) + "]";
        }
    }

    private static final class DirectoryResultSaver implements IResultSaver {

        private final Path outputDir;

        private DirectoryResultSaver(Path outputDir) {
            this.outputDir = outputDir;
        }

        @Override
        public void saveFolder(String path) {
            createDirectories(outputDir.resolve(path));
        }

        @Override
        public void copyFile(String source, String path, String entryName) {
            Path target = resolve(path, entryName);
            createDirectories(target.getParent());
            try {
                Files.copy(Path.of(source), target);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to copy file '" + source + "' to " + target, e);
            }
        }

        @Override
        public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
            Path target = resolve(path, entryName);
            createDirectories(target.getParent());
            try {
                Files.writeString(target, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to write decompiled class '" + qualifiedName + "' to " + target, e);
            }
        }

        @Override
        public void createArchive(String path, String archiveName, Manifest manifest) {
        }

        @Override
        public void saveDirEntry(String path, String archiveName, String entryName) {
            Path target = resolve(path, entryName);
            createDirectories(target);
        }

        @Override
        public void copyEntry(String source, String path, String archiveName, String entryName) {
            Path target = resolve(path, entryName);
            createDirectories(target.getParent());
            try {
                Files.copy(Path.of(source), target);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to copy archive entry from '" + source + "' to " + target, e);
            }
        }

        @Override
        public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {
            Path target = resolve(path, entryName);
            createDirectories(target.getParent());
            try {
                Files.writeString(target, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to write decompiled archive class '" + qualifiedName + "' to " + target,
                        e);
            }
        }

        @Override
        public void closeArchive(String path, String archiveName) {
        }

        private Path resolve(String path, String entryName) {
            if (path == null || path.isEmpty()) {
                return outputDir.resolve(entryName);
            }
            return outputDir.resolve(path).resolve(entryName);
        }

        private static void createDirectories(Path dir) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create directory " + dir, e);
            }
        }
    }
}
