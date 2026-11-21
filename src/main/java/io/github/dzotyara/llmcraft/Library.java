package io.github.dzotyara.llmcraft;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.function.Function;

final class Library<T> {
    private final Path directory;
    private final Function<String, T> parse;
    private final Function<T, String> name;
    private final Function<T, String> json;
    private final Map<String, T> entries = new LinkedHashMap<>();

    Library(Path directory, Function<String, T> parse, Function<T, String> name, Function<T, String> json) {
        this.directory = directory;
        this.parse = parse;
        this.name = name;
        this.json = json;
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().matches("[a-z0-9-]+\\.json"))
                    .sorted().forEach(this::load);
        } catch (IOException e) {
            LoggerFactory.getLogger("LLMCraft").warn("Could not read the local library", e);
        }
    }

    private void load(Path file) {
        String filename = file.getFileName().toString();
        try {
            if (Files.size(file) > 262_144) throw new IOException("Blueprint is too large");
            entries.put(filename.substring(0, filename.length() - 5), parse.apply(Files.readString(file)));
        } catch (IOException | IllegalArgumentException e) {
            LoggerFactory.getLogger("LLMCraft").warn("Could not load {}", filename, e);
        }
    }

    synchronized Map<String, T> entries() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    synchronized String save(T blueprint) throws IOException {
        Files.createDirectories(directory);
        String slug = name.apply(blueprint).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        String id = (slug.isEmpty() ? "creation" : slug) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path temporary = Files.createTempFile(directory, "creation-", ".tmp");
        try {
            Files.writeString(temporary, json.apply(blueprint));
            Files.move(temporary, directory.resolve(id + ".json"), StandardCopyOption.ATOMIC_MOVE);
            entries.put(id, blueprint);
            return id;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
