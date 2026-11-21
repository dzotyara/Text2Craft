package io.github.dzotyara.llmcraft;

import net.minecraft.util.math.BlockPos;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class OllamaBenchmark {
    private record Prompt(String id, String text, int width, int height, int depth, Set<String> palette, String detail) {
        static Prompt read(String line) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 7) throw new IllegalArgumentException("Invalid benchmark row: " + line);
            return new Prompt(fields[0], fields[1], Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
                    Integer.parseInt(fields[4]), Set.of(fields[5].split(",")), fields[6]);
        }

        List<String> mismatches(Map<BlockPos, String> blocks) {
            List<String> errors = new ArrayList<>();
            var x = blocks.keySet().stream().mapToInt(BlockPos::getX).summaryStatistics();
            var y = blocks.keySet().stream().mapToInt(BlockPos::getY).summaryStatistics();
            var z = blocks.keySet().stream().mapToInt(BlockPos::getZ).summaryStatistics();
            int actualWidth = x.getMax() - x.getMin() + 1;
            int actualHeight = y.getMax() - y.getMin() + 1;
            int actualDepth = z.getMax() - z.getMin() + 1;
            if (actualWidth != width || actualHeight != height || actualDepth != depth) {
                errors.add("bounds " + actualWidth + "×" + actualHeight + "×" + actualDepth);
            }
            if (!new HashSet<>(blocks.values()).equals(palette)) errors.add("palette");
            if (detail.startsWith("top=")
                    && blocks.entrySet().stream().noneMatch(entry ->
                    entry.getKey().getY() == y.getMax() && entry.getValue().equals(detail.substring(4)))) {
                errors.add("top material");
            }
            if (detail.startsWith("opening=")) {
                int center = (x.getMin() + x.getMax()) / 2;
                int height = Integer.parseInt(detail.substring(8));
                for (int level = y.getMin(); level < y.getMin() + height; level++) {
                    for (int depth = z.getMin(); depth <= z.getMax(); depth++) {
                        if (blocks.containsKey(new BlockPos(center, level, depth))) {
                            errors.add("blocked passage");
                            return errors;
                        }
                    }
                }
            }
            return errors;
        }
    }

    private OllamaBenchmark() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Invalid local Ollama port.");
        var provider = new ModelProvider(URI.create("http://127.0.0.1:" + port), args[1]);
        var output = Path.of(args[2]);
        int limit = Integer.parseInt(args[3]);
        List<Prompt> prompts = loadPrompts(limit);
        if (!provider.installed()) throw new IOException("Install the local model before running the benchmark.");
        Files.createDirectories(output);
        try (var results = Files.newBufferedWriter(output.resolve("results.tsv"))) {
            results.write("case\tseconds\tstatus\tblocks\tdetail\n");
            int valid = 0;
            int matched = 0;
            var latencies = new ArrayList<Double>();
            for (Prompt prompt : prompts) {
                var blueprintFile = output.resolve(prompt.id + ".json");
                Files.deleteIfExists(blueprintFile);
                long started = System.nanoTime();
                StructureBlueprint blueprint = null;
                String status;
                String detail;
                int count = 0;
                try {
                    blueprint = provider.generate(prompt.text);
                    var geometry = blueprint.blockIds();
                    var mismatches = prompt.mismatches(geometry);
                    valid++;
                    if (mismatches.isEmpty()) matched++;
                    count = geometry.size();
                    status = mismatches.isEmpty() ? "match" : "mismatch";
                    detail = String.join(", ", mismatches);
                } catch (IOException e) {
                    status = "invalid";
                    detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                double seconds = (System.nanoTime() - started) / 1e9;
                if (blueprint != null) Files.writeString(blueprintFile, blueprint.json());
                latencies.add(seconds);
                results.write(String.format(Locale.ROOT, "%s\t%.2f\t%s\t%d\t%s%n", prompt.id, seconds,
                        status, count, detail.replaceAll("[\\t\\r\\n]", " ")));
                results.flush();
                System.out.printf(Locale.ROOT, "%s %s %.1fs %s%n", prompt.id, status, seconds, detail);
            }
            var remaining = latencies.stream().skip(1).sorted().toList();
            int midpoint = remaining.size() / 2;
            String median = remaining.isEmpty() ? "n/a" : String.format(Locale.ROOT, "%.1fs",
                    (remaining.get((remaining.size() - 1) / 2) + remaining.get(midpoint)) / 2);
            System.out.printf(Locale.ROOT, "%s: %d/%d valid, %d/%d match checks, first %.1fs, remaining median %s%n",
                    provider.model(), valid, prompts.size(), matched, prompts.size(), latencies.getFirst(),
                    median);
        }
    }

    private static List<Prompt> loadPrompts(int limit) throws IOException {
        if (limit < 1 || limit > 30) throw new IllegalArgumentException("Benchmark limit is 1–30.");
        var input = OllamaBenchmark.class.getResourceAsStream("/ollama-benchmark.tsv");
        if (input == null) throw new IOException("Missing benchmark prompt file.");
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            List<Prompt> prompts = reader.lines().filter(line -> !line.isBlank()).map(Prompt::read).toList();
            if (prompts.size() != 30) throw new IOException("Expected 30 benchmark prompts.");
            return prompts.subList(0, limit);
        }
    }
}
