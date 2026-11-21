package io.github.dzotyara.llmcraft;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

final class OllamaRuntime {
    private static final String VERSION = "v0.40.16";
    private static final URI RELEASE = URI.create("https://github.com/ollama/ollama/releases/download/" + VERSION + "/");
    private static final HttpClient DOWNLOAD = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    record Package(String archive, String launcher, long bytes, String sha256, boolean zip) {
        String megabytes() {
            return String.format(Locale.ROOT, "%.0f MB", bytes / 1_000_000.0);
        }
    }

    record Connection(URI endpoint, List<String> models) {}

    private final Path root;
    private final Package target = platform(System.getProperty("os.name"), System.getProperty("os.arch"));
    private volatile Process owned;
    private URI ownedEndpoint;

    OllamaRuntime(Path root) {
        this.root = root.toAbsolutePath();
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "Text2Craft Ollama shutdown"));
    }

    static Package platform(String os, String architecture) {
        String name = os.toLowerCase(Locale.ROOT);
        String arch = architecture.toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (!x64 && !arm64) return null;
        if (name.startsWith("windows")) {
            return x64
                    ? new Package("ollama-windows-amd64.zip", "ollama.exe", 1_618_244_995L,
                        "50fcf5ad54277f2fa5d173ebfc93bcb017adbc0c9ef2314daabc96ca9e6aa64e", true)
                    : new Package("ollama-windows-arm64.zip", "ollama.exe", 386_918_926L,
                        "86d2b329cae3125684ff29b506623710b68e1393f679594f1c6aa267beac31c2", true);
        }
        if (name.startsWith("mac") || name.startsWith("darwin")) {
            return new Package("ollama-darwin.tgz", "ollama", 186_116_182L,
                    "a743717494315da7cd352982b805f6b992d4b4452ca117fd5fbf86c4ee46fca0", false);
        }
        if (name.startsWith("linux")) {
            return x64
                    ? new Package("ollama-linux-amd64.tar.zst", "bin/ollama", 1_545_767_640L,
                        "87bfafb2acbc89b2e6a1be0a9043de6aa75fb450696f86bc426e65fc448f788d", false)
                    : new Package("ollama-linux-arm64.tar.zst", "bin/ollama", 1_600_610_073L,
                        "e45b8e6dcb86cb9f3057396351655079c9a4879b4ced2678c9350d375dcbc224", false);
        }
        return null;
    }

    boolean canInstall() {
        return target != null;
    }

    String downloadSize() {
        return target == null || installed() ? "" : target.megabytes();
    }

    private Path installation() {
        String platformName = target.archive.replaceFirst("\\.(zip|tgz|tar\\.zst)$", "");
        return root.resolve(VERSION + "-" + platformName);
    }

    private boolean installed() {
        return target != null && Files.isRegularFile(installation().resolve(target.launcher));
    }

    Connection connect(boolean allowDownload, Consumer<String> progress) throws IOException, InterruptedException {
        if (owned != null && owned.isAlive()) {
            try {
                return new Connection(ownedEndpoint, ModelProvider.localModels(ownedEndpoint));
            } catch (IOException offline) {
                stop();
            }
        }
        try {
            return new Connection(ModelProvider.DEFAULT_ENDPOINT, ModelProvider.localModels(ModelProvider.DEFAULT_ENDPOINT));
        } catch (IOException unavailable) {
            if (!installed() && !allowDownload) throw new IOException("Ollama is not running.", unavailable);
        }
        if (target == null) throw new IOException("Use the official Ollama installer for this platform.");
        if (!installed()) {
            try {
                install(progress);
            } catch (java.nio.file.FileSystemException e) {
                throw new IOException("Could not write Ollama files. Check free space and folder permissions.", e);
            }
        }
        return launch(progress);
    }

    private void install(Consumer<String> progress) throws IOException, InterruptedException {
        Files.createDirectories(root);
        Path archive = root.resolve(target.archive);
        progress.accept("Checking Ollama package…");
        if (!verified(archive, target)) {
            if (Files.getFileStore(root).getUsableSpace() < 3 * target.bytes) {
                throw new IOException("Free more disk space for Ollama and try again.");
            }
            download(archive, progress);
        }

        progress.accept("Extracting Ollama…");
        Path staging = Files.createTempDirectory(root, "extract-");
        try {
            if (target.zip) extractZip(archive, staging);
            else extractTar(archive, staging);
            if (!Files.isRegularFile(staging.resolve(target.launcher))) {
                throw new IOException("The Ollama executable was missing from the archive.");
            }
            Files.move(staging, installation(), StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(archive);
        } finally {
            if (Files.exists(staging)) {
                try (var files = Files.walk(staging)) {
                    for (Path item : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
                }
            }
        }
    }

    private void download(Path archive, Consumer<String> progress) throws IOException, InterruptedException {
        Path partial = Files.createTempFile(root, "download-", ".part");
        try {
            var request = HttpRequest.newBuilder(RELEASE.resolve(target.archive))
                    .timeout(Duration.ofMinutes(40)).GET().build();
            var reply = DOWNLOAD.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = reply.body(); var output = Files.newOutputStream(partial)) {
                if (reply.statusCode() != 200) throw new IOException("Ollama download returned HTTP " + reply.statusCode() + ".");
                byte[] buffer = new byte[128 * 1024];
                long bytes = 0;
                int lastPercent = -1;
                for (int read; (read = input.read(buffer)) != -1;) {
                    bytes += read;
                    if (bytes > target.bytes) throw new IOException("Ollama archive exceeded its published size.");
                    output.write(buffer, 0, read);
                    int percent = (int) (100 * bytes / target.bytes);
                    if (percent != lastPercent) {
                        progress.accept("Downloading Ollama · " + percent + "%");
                        lastPercent = percent;
                    }
                }
            }
            progress.accept("Verifying Ollama archive…");
            if (!verified(partial, target)) throw new IOException("Ollama archive failed its SHA-256 check.");
            Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    static boolean verified(Path archive, Package expected) throws IOException {
        if (!Files.isRegularFile(archive) || Files.size(archive) != expected.bytes) return false;
        try (var input = Files.newInputStream(archive)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[128 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest()).equals(expected.sha256);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static void extractZip(Path archive, Path staging) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                Path file = staging.resolve(entry.getName()).normalize();
                if (!file.startsWith(staging) || entry.getName().contains("\\")) {
                    throw new IOException("Invalid path in the Ollama ZIP.");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(file);
                } else {
                    Files.createDirectories(file.getParent());
                    try (var input = zip.getInputStream(entry)) { Files.copy(input, file); }
                }
            }
        }
    }

    private void extractTar(Path archive, Path staging) throws IOException, InterruptedException {
        // The pinned, verified release tarball may use executable modes and relative symlinks.
        Process extractor;
        try {
            extractor = new ProcessBuilder("tar", "-xf", archive.toString(), "-C", staging.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            throw new IOException("Install tar or use the official Ollama installer.", e);
        }
        try {
            if (!extractor.waitFor(5, TimeUnit.MINUTES) || extractor.exitValue() != 0) {
                throw new IOException("Could not unpack Ollama. Check tar and zstd support for this package.");
            }
        } finally {
            if (extractor.isAlive()) extractor.destroyForcibly();
        }
    }

    private Connection launch(Consumer<String> progress) throws IOException, InterruptedException {
        progress.accept("Starting local Ollama…");
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        String address = "127.0.0.1:" + port;
        URI url = URI.create("http://" + address);
        var command = new ProcessBuilder(installation().resolve(target.launcher).toString(), "serve")
                .directory(installation().toFile()).redirectErrorStream(true)
                .redirectOutput(root.resolve("server.log").toFile());
        command.environment().put("OLLAMA_HOST", address);
        command.environment().put("OLLAMA_MODELS", root.resolve("models").toString());
        command.environment().put("OLLAMA_NO_CLOUD", "1");
        try {
            owned = command.start();
        } catch (IOException e) {
            throw new IOException("Could not start Ollama. Check system permissions or use the official installer.", e);
        }
        ownedEndpoint = url;
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (owned.isAlive() && System.nanoTime() < deadline) {
                try {
                    return new Connection(url, ModelProvider.localModels(url));
                } catch (IOException starting) {
                    Thread.sleep(500);
                }
            }
            throw new IOException("Ollama could not start. See config/llmcraft/ollama/server.log.");
        } catch (IOException | InterruptedException e) {
            stop();
            throw e;
        }
    }

    void stop() {
        Process process = owned;
        if (process == null) return;
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        owned = null;
    }
}
