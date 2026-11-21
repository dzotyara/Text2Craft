package io.github.dzotyara.llmcraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class OllamaRuntimeCheck {
    static void run() throws Exception {
        if (!OllamaRuntime.platform("Windows 11", "amd64").archive().equals("ollama-windows-amd64.zip")
                || !OllamaRuntime.platform("Windows 11", "aarch64").archive().equals("ollama-windows-arm64.zip")
                || !OllamaRuntime.platform("Mac OS X", "aarch64").launcher().equals("ollama")
                || !OllamaRuntime.platform("Linux", "arm64").launcher().equals("bin/ollama")
                || OllamaRuntime.platform("Linux", "i386") != null) throw new AssertionError("Wrong package");

        Path test = Files.createTempDirectory("llmcraft-ollama-check");
        try {
            Path archive = test.resolve("package.zip");
            Path unpack = Files.createDirectory(test.resolve("unpack"));
            zip(archive, "lib/example.txt");
            var digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive));
            var asset = new OllamaRuntime.Package("package.zip", "ollama.exe", Files.size(archive),
                    HexFormat.of().formatHex(digest), true);
            if (!OllamaRuntime.verified(archive, asset)) throw new AssertionError("Matching ZIP rejected");
            OllamaRuntime.extractZip(archive, unpack);
            if (!Files.isRegularFile(unpack.resolve("lib/example.txt"))) throw new AssertionError("ZIP not extracted");
            zip(archive, "../escaped.txt");
            if (OllamaRuntime.verified(archive, asset)) throw new AssertionError("Changed ZIP accepted");
            try {
                OllamaRuntime.extractZip(archive, unpack);
                throw new AssertionError("Path traversal accepted");
            } catch (IOException expected) {
                if (Files.exists(test.resolve("escaped.txt"))) throw new AssertionError("ZIP wrote outside destination");
            }
        } finally {
            try (var paths = Files.walk(test)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void zip(Path archive, String entry) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(1);
            zip.closeEntry();
        }
    }
}
