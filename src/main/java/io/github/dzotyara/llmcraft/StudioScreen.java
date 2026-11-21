package io.github.dzotyara.llmcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class StudioScreen extends Screen {
    enum Panel { STUDIO, BUILD, MODEL, MODELS, LIBRARY, MATERIALS }

    static final class Session {
        ModelProvider model;
        private final OllamaRuntime runtime = new OllamaRuntime(LlmCraft.DIRECTORY.resolve("ollama"));
        URI endpoint;
        List<String> installedModels = List.of();
        String selected;
        boolean ready;
        boolean checking;
        boolean downloading;
        String modelStatus = "Checking for Ollama…";
        String status = "";
        String prompt = "";
        StructureBlueprint draft;
        boolean saved;
        boolean saving;
        long requestId;
        long started;
        Thread generationThread;

        String modelLabel() {
            if (checking || downloading) return modelStatus;
            return ready ? "Ollama · " + model.model() : modelStatus;
        }

        void discover(MinecraftClient client) {
            connect(client, false);
        }

        void connect(MinecraftClient client, boolean install) {
            if (checking || downloading) return;
            checking = true;
            modelStatus = "Checking for Ollama…";
            if (install && selected == null) selected = ModelProvider.LOCAL_MODEL;
            run(() -> runtime.connect(install, message -> client.execute(() -> modelStatus = message)))
                    .whenComplete((connection, error) -> client.execute(() -> {
                checking = false;
                if (error != null) {
                    endpoint = null;
                    installedModels = List.of();
                    model = null;
                    ready = false;
                    modelStatus = errorMessage(error, "Ollama setup failed.");
                    return;
                }
                endpoint = connection.endpoint();
                installedModels = connection.models();
                if (selected == null || (!installedModels.contains(selected) && !ModelProvider.DOWNLOADABLE.contains(selected))) {
                    selected = installedModels.isEmpty() || installedModels.contains(ModelProvider.LOCAL_MODEL)
                            ? ModelProvider.LOCAL_MODEL : installedModels.getFirst();
                }
                choose(selected);
                if (install && !ready) download(client);
            }));
        }

        void choose(String name) {
            selected = name;
            ready = endpoint != null && installedModels.contains(name);
            model = ready ? new ModelProvider(endpoint, name) : null;
            if (endpoint != null) modelStatus = ready ? "Model ready" : "Download the selected model";
        }

        void download(MinecraftClient client) {
            if (checking || downloading) return;
            if (endpoint == null) {
                connect(client, true);
                return;
            }
            if (ready || !ModelProvider.DOWNLOADABLE.contains(selected)) return;
            downloading = true;
            var requested = new ModelProvider(endpoint, selected);
            modelStatus = "Starting download…";
            run(() -> {
                requested.pull(message -> client.execute(() -> modelStatus = message));
                return requested;
            }).whenComplete((downloaded, error) -> client.execute(() -> {
                downloading = false;
                if (error == null) {
                    var models = new LinkedHashSet<>(installedModels);
                    models.add(downloaded.model());
                    installedModels = List.copyOf(models);
                    choose(downloaded.model());
                    modelStatus = "Model installed";
                } else {
                    modelStatus = errorMessage(error, "Could not download model.");
                }
            }));
        }

        void generate(MinecraftClient client) {
            if (!ready || started != 0 || prompt.isBlank() || !StudioClient.inStudio(client)) return;
            ClientWorld world = client.world;
            ModelProvider selected = model;
            String input = prompt.strip();
            long id = ++requestId;
            started = System.nanoTime();
            status = "Generating…";
            BuildPreview.revoke();
            CompletableFuture<StructureBlueprint> request = new CompletableFuture<>();
            generationThread = Thread.startVirtualThread(() -> {
                try {
                    request.complete(selected.generate(input));
                } catch (IOException | InterruptedException | RuntimeException e) {
                    request.completeExceptionally(e);
                }
            });
            request.whenComplete((blueprint, error) -> client.execute(() -> {
                if (id != requestId || client.world != world) return;
                long seconds = elapsedSeconds();
                generationThread = null;
                started = 0;
                if (error != null) {
                    status = errorMessage(error, "Generation failed.");
                    message(client, status + " Open Build to retry.");
                    return;
                }
                draft = blueprint;
                saved = false;
                status = "Ready · " + seconds + "s";
                if (client.currentScreen == null) {
                    BuildPreview.open(client, blueprint);
                } else {
                    message(client, "Structure ready. Open Build to preview it.");
                }
            }));
        }

        long elapsedSeconds() {
            return (System.nanoTime() - started) / 1_000_000_000;
        }

        boolean cancel() {
            if (started == 0) return false;
            requestId++;
            started = 0;
            if (generationThread != null) generationThread.interrupt();
            generationThread = null;
            status = "Generation cancelled.";
            return true;
        }

        void leaveWorld() {
            cancel();
            draft = null;
            saved = false;
            status = "";
        }

        boolean busy() {
            return checking || downloading;
        }
    }

    private final Session session;
    private final Panel panel;
    private final int requestedPage;
    private final Screen returnScreen;
    private int top;
    private int left;
    private int panelWidth;
    private ButtonWidget modelAction;
    private ButtonWidget chooseModel;

    StudioScreen(Session session, Panel panel) {
        this(session, panel, 0, null);
    }

    static StudioScreen modelSetup(Session session, Screen returnScreen) {
        return new StudioScreen(session, Panel.MODEL, 0, returnScreen);
    }

    private StudioScreen(Session session, Panel panel, int page, Screen returnScreen) {
        super(Text.literal(switch (panel) {
            case STUDIO -> "Text2Craft Studio";
            case BUILD -> "Build";
            case MODEL -> "Ollama Setup";
            case MODELS -> "Choose a Model";
            case LIBRARY -> "Structure Library";
            case MATERIALS -> "Structure Materials";
        }));
        this.session = session;
        this.panel = panel;
        this.requestedPage = page;
        this.returnScreen = returnScreen;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 24, 348);
        left = (width - panelWidth) / 2;
        top = Math.max(8, (height - 214) / 2);
        switch (panel) {
            case STUDIO -> studio();
            case BUILD -> build();
            case MODEL -> model();
            case MODELS -> modelCatalog();
            case LIBRARY -> library();
            case MATERIALS -> materials();
        }
    }

    private void studio() {
        fullButton("Get Hammer", 48, () -> {
            if (StudioClient.onLocalServer(client, player -> {
                if (LlmCraft.isStudio(player.getServer())) StudioArena.giveHammer(player);
            })) close();
        });
        fullButton("Build", 73, () -> change(Panel.BUILD));
        fullButton("Structure Library", 98, () -> change(Panel.LIBRARY));
        fullButton("Ollama Setup", 123, () -> change(Panel.MODEL));
        fullButton("Back to Arena", 162, this::close);
    }

    private void build() {
        TextFieldWidget field = new TextFieldWidget(textRenderer, left, top + 44, panelWidth, 20,
                Text.literal("Describe a structure"));
        field.setMaxLength(1000);
        field.setPlaceholder(Text.literal("Describe a structure…"));
        field.setText(session.prompt);
        field.setChangedListener(value -> session.prompt = value);
        addDrawableChild(field);
        setInitialFocus(field);

        fullButton(session.started != 0 ? "Cancel Generation" : "Generate", 72, () -> {
            if (session.cancel()) {
                change(Panel.BUILD);
            } else if (!session.ready) {
                change(Panel.MODEL);
            } else if (session.prompt.isBlank()) {
                session.status = "Describe the structure you want to build.";
            } else {
                session.generate(client);
                close();
            }
        });
        splitButton("Preview Result", 105, false, () -> {
            if (session.draft != null) BuildPreview.open(client, session.draft);
        }).active = session.draft != null;
        splitButton(session.saved ? "Saved" : "Save to Library", 105, true, this::save)
                .active = session.draft != null && !session.saved && !session.saving;
        splitButton("Undo Placement", 130, false, () -> {
            BuildPreview.undo(client);
            close();
        });
        splitButton("Ollama Setup", 130, true, () -> change(Panel.MODEL));
        fullButton("Back to Studio", 162, () -> change(Panel.STUDIO));
    }

    private void save() {
        var blueprint = session.draft;
        if (blueprint == null || session.saving || session.saved) return;
        session.saving = true;
        session.status = "Saving…";
        run(() -> LlmCraft.STRUCTURES.save(blueprint)).whenComplete((id, error) -> client.execute(() -> {
            session.saving = false;
            if (session.draft != blueprint) return;
            session.saved = error == null;
            session.status = error == null ? "Saved to the Structure Library" : "Could not save the structure.";
            if (client.currentScreen == this) change(Panel.BUILD);
        }));
    }

    private void model() {
        chooseModel = fullButton("Choose Model", 46, () ->
                client.setScreen(new StudioScreen(session, Panel.MODELS, 0, this)));
        splitButton("Check Connection", 72, false, () -> session.discover(client));
        splitButton("Official Installer", 72, true, () ->
                Util.getOperatingSystem().open(URI.create("https://ollama.com/download")));
        modelAction = fullButton("Download Model", 104, () -> session.download(client));
        refreshModelActions();
        fullButton("Back", 162, () -> {
            if (returnScreen != null) close();
            else change(Panel.BUILD);
        });
    }

    private void modelCatalog() {
        var names = new LinkedHashSet<>(ModelProvider.DOWNLOADABLE);
        names.addAll(session.installedModels);
        var models = new ArrayList<>(names);
        int last = (models.size() - 1) / 5;
        int page = Math.min(requestedPage, last);
        for (int index = 0; index < 5 && page * 5 + index < models.size(); index++) {
            String name = models.get(page * 5 + index);
            String detail = session.installedModels.contains(name) ? "Installed" : modelSize(name);
            String label = name + " · " + detail;
            fullButton(textRenderer.trimToWidth(label, panelWidth - 12), 40 + index * 21, () -> {
                session.choose(name);
                close();
            }).active = !session.busy();
        }
        pageButtons(page, last);
        fullButton("Back", 173, this::close);
    }

    @Override
    public void tick() {
        if (panel == Panel.MODEL) refreshModelActions();
    }

    private void refreshModelActions() {
        modelAction.setMessage(Text.literal(session.endpoint == null ? "Install Ollama & Model" : "Download Model"));
        modelAction.active = !session.busy() && !session.ready
                && (session.endpoint != null || session.runtime.canInstall());
        chooseModel.active = !session.busy();
    }

    private static String modelSize(String name) {
        return switch (name) {
            case "qwen2.5-coder:1.5b" -> "986 MB";
            case "qwen2.5-coder:3b" -> "1.9 GB";
            case ModelProvider.LOCAL_MODEL -> "4.7 GB";
            default -> "";
        };
    }

    private void library() {
        var blueprints = new ArrayList<>(LlmCraft.STRUCTURES.entries().values());
        Collections.reverse(blueprints);
        int pageSize = 5;
        int last = Math.max(0, (blueprints.size() - 1) / pageSize);
        int page = Math.min(requestedPage, last);
        if (blueprints.isEmpty()) {
            var empty = fullButton("Save a structure in the Studio to add it here", 54, () -> {});
            empty.active = false;
        }
        for (int i = 0; i < pageSize && page * pageSize + i < blueprints.size(); i++) {
            var blueprint = blueprints.get(page * pageSize + i);
            fullButton(textRenderer.trimToWidth(blueprint.name(), panelWidth - 12), 40 + i * 21,
                    () -> BuildPreview.open(client, blueprint));
        }
        pageButtons(page, last);
        splitButton("Undo Placement", 173, false, () -> {
            BuildPreview.undo(client);
            close();
        });
        splitButton("Back", 173, true, () -> {
            if (StudioClient.inStudio(client)) change(Panel.STUDIO);
            else close();
        });
    }

    private void materials() {
        var amounts = BuildPreview.materials(client);
        int last = Math.max(0, (amounts.size() - 1) / 8);
        int page = Math.min(requestedPage, last);
        for (int i = 0; i < 8 && page * 8 + i < amounts.size(); i++) {
            var text = Text.literal(textRenderer.trimToWidth(amounts.get(page * 8 + i), panelWidth - 6));
            addDrawableChild(new TextWidget(left, top + 44 + i * 12, panelWidth, 10, text, textRenderer).alignLeft());
        }
        pageButtons(page, last);
        fullButton("Back to Preview", 173, this::close);
    }

    private void pageButtons(int page, int last) {
        splitButton("Previous", 149, false, () -> showPage(page - 1)).active = page > 0;
        splitButton("Next", 149, true, () -> showPage(page + 1)).active = page < last;
    }

    private void showPage(int page) {
        client.setScreen(new StudioScreen(session, panel, page, returnScreen));
    }

    private void change(Panel next) {
        client.setScreen(new StudioScreen(session, next));
    }

    private ButtonWidget fullButton(String title, int y, Runnable action) {
        return button(title, left, top + y, panelWidth, action);
    }

    private ButtonWidget splitButton(String title, int y, boolean right, Runnable action) {
        int half = (panelWidth - 4) / 2;
        return button(title, left + (right ? half + 4 : 0), top + y, half, action);
    }

    private ButtonWidget button(String title, int x, int y, int width, Runnable action) {
        return addDrawableChild(ButtonWidget.builder(Text.literal(title), button -> action.run())
                .dimensions(x, y, width, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(left - 8, top - 5, left + panelWidth + 8, top + 209, 0xEB182327);
        context.fill(left - 8, top - 5, left + panelWidth + 8, top - 4, 0xFFA8FFE0);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, getTitle(), width / 2, top + 6, 0xA8FFE0);
        String subtitle = switch (panel) {
            case LIBRARY -> "Select a saved structure to place it";
            case MATERIALS -> "In your inventory / required";
            case MODELS -> "Installed models and available downloads";
            case BUILD -> session.draft == null ? session.modelLabel() : session.draft.name();
            default -> session.modelLabel();
        };
        context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(subtitle, panelWidth),
                width / 2, top + 24, 0xE0E0E0);
        if (panel == Panel.MODEL) {
            String selected = session.selected == null ? ModelProvider.LOCAL_MODEL : session.selected;
            String size = session.installedModels.contains(selected) ? "Installed" : modelSize(selected);
            String packageSize = session.endpoint == null ? session.runtime.downloadSize() : "";
            String summary = selected + (size.isEmpty() ? "" : " · " + size)
                    + (packageSize.isEmpty() ? "" : " + " + packageSize + " Ollama");
            context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(summary, panelWidth),
                    width / 2, top + 137, 0xA8FFE0);
        }
        if (panel == Panel.BUILD) {
            String status = session.started != 0 ? "Generating… " + session.elapsedSeconds() + "s" : session.status;
            context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(status, panelWidth),
                    width / 2, top + 192, 0xA8FFE0);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (returnScreen != null) client.setScreen(returnScreen);
        else super.close();
    }

    private static <T> CompletableFuture<T> run(Job<T> job) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return job.run();
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        });
    }

    private static String errorMessage(Throwable error, String fallback) {
        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
        if (cause instanceof java.net.ConnectException) return "Could not connect to Ollama.";
        if (cause instanceof java.net.http.HttpTimeoutException) return "Ollama timed out.";
        return cause instanceof IOException && cause.getMessage() != null ? cause.getMessage() : fallback;
    }

    private static void message(MinecraftClient minecraft, String content) {
        if (minecraft.player != null) minecraft.player.sendMessage(Text.literal(content), true);
    }

    private interface Job<T> {
        T run() throws IOException, InterruptedException;
    }
}
