package io.github.dzotyara.llmcraft;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

record ModelProvider(URI endpoint, String model) {
    static final String LOCAL_MODEL = "qwen2.5-coder:7b";
    static final URI DEFAULT_ENDPOINT = URI.create("http://127.0.0.1:11434");
    static final List<String> DOWNLOADABLE = List.of("qwen2.5-coder:1.5b", "qwen2.5-coder:3b", LOCAL_MODEL);
    private static final int MAX_PROMPT_LENGTH = 2_000;
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(4)).build();

    ModelProvider(String model) {
        this(DEFAULT_ENDPOINT, model);
    }

    static ModelProvider discover() throws IOException, InterruptedException {
        var models = localModels(DEFAULT_ENDPOINT);
        return new ModelProvider(models.isEmpty() || models.contains(LOCAL_MODEL)
                ? LOCAL_MODEL : models.getFirst());
    }

    boolean installed() throws IOException, InterruptedException {
        return localModels(endpoint).contains(model);
    }

    static List<String> localModels(URI endpoint) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(endpoint.resolve("/api/tags"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        try {
            var models = new ArrayList<String>();
            var entries = JsonParser.parseString(send(request)).getAsJsonObject().getAsJsonArray("models");
            for (var entry : entries) {
                var details = entry.getAsJsonObject();
                String name = details.get("name").getAsString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!name.isBlank() && !lower.endsWith(":cloud") && !lower.endsWith("-cloud")
                        && !details.has("remote_host") && !details.has("remote_model")) {
                    var capabilities = details.getAsJsonArray("capabilities");
                    if (capabilities == null || capabilities.asList().stream()
                            .anyMatch(capability -> capability.getAsString().equals("completion"))) {
                        models.add(name);
                    }
                }
            }
            return models.stream().distinct().sorted().toList();
        } catch (RuntimeException e) {
            throw new IOException("Ollama returned an invalid model list.", e);
        }
    }

    void pull(Consumer<String> progress) throws IOException, InterruptedException {
        if (!DOWNLOADABLE.contains(model)) throw new IOException("Choose a model from the download catalog.");
        var request = post("/api/pull", Map.of("model", model, "stream", true))
                .timeout(Duration.ofMinutes(45)).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofLines());
        try (var stream = response.body()) {
            requireSuccess(response.statusCode());
            var lines = stream.iterator();
            String previous = "";
            while (lines.hasNext()) {
                if (Thread.interrupted()) throw new InterruptedException();
                String line = lines.next();
                if (line.isBlank()) continue;
                var event = JsonParser.parseString(line).getAsJsonObject();
                if (event.has("error")) throw new IOException("Ollama could not download this model.");
                String status = event.get("status").getAsString();
                if ("success".equals(status)) return;
                if (event.has("total") && event.has("completed") && event.get("total").getAsLong() > 0) {
                    int percent = (int) (100.0 * event.get("completed").getAsLong() / event.get("total").getAsLong());
                    status = "Downloading layer " + percent + "%";
                }
                if (!previous.equals(status)) {
                    progress.accept(status);
                    previous = status;
                }
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (RuntimeException e) {
            throw new IOException("Ollama returned invalid download progress.", e);
        }
        throw new IOException("Model download ended early.");
    }

    StructureBlueprint generate(String input) throws IOException, InterruptedException {
        if (input == null || input.isBlank() || input.length() > MAX_PROMPT_LENGTH) {
            throw new IOException("Enter a prompt of 1 to " + MAX_PROMPT_LENGTH + " characters.");
        }
        if (!installed()) throw new IOException("Select an installed local model.");
        try {
            return StructureBlueprint.parse(requestBlueprint(input, StructureBlueprint.INSTRUCTIONS));
        } catch (IllegalArgumentException firstFailure) {
            String repair = StructureBlueprint.INSTRUCTIONS + "\nYour previous attempt did not validate: "
                    + firstFailure.getMessage() + " Design the same request again using fewer, smaller boxes.";
            try {
                return StructureBlueprint.parse(requestBlueprint(input, repair));
            } catch (IllegalArgumentException secondFailure) {
                throw new IOException(secondFailure.getMessage(), secondFailure);
            }
        }
    }

    private String requestBlueprint(String input, String instructions) throws IOException, InterruptedException {
        var request = post("/api/generate", Map.of("model", model, "system", instructions,
                "prompt", input, "format", StructureBlueprint.schema(), "stream", false, "think", false,
                "options", Map.of("temperature", 0.1, "repeat_penalty", 1.05, "num_ctx", 8_192, "num_predict", 4_096)))
                .timeout(Duration.ofMinutes(5)).build();
        String response = send(request);
        try {
            var json = JsonParser.parseString(response).getAsJsonObject();
            if (!json.get("done").getAsBoolean()) throw new IOException("Ollama did not finish generating.");
            return json.get("response").getAsString();
        } catch (RuntimeException e) {
            throw new IOException("Ollama returned an invalid generation response.", e);
        }
    }

    private HttpRequest.Builder post(String path, Map<String, ?> data) {
        return HttpRequest.newBuilder(endpoint.resolve(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(data)));
    }

    private static String send(HttpRequest request) throws IOException, InterruptedException {
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode());
        return response.body();
    }

    private static void requireSuccess(int status) throws IOException {
        if (status != 200) throw new IOException("Ollama returned HTTP " + status + ".");
    }
}
