package io.github.dzotyara.llmcraft;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;

final class StartupGuide extends Screen {
    private static final java.nio.file.Path SEEN = LlmCraft.DIRECTORY.resolve("welcome-v1");

    private final Screen titleScreen;
    private final StudioScreen.Session session;
    private final Runnable openStudio;
    private final String consoleKey;
    private int left;
    private int top;
    private int panelWidth;

    StartupGuide(Screen titleScreen, StudioScreen.Session session, Runnable openStudio, String consoleKey) {
        super(Text.literal("Welcome to Text2Craft"));
        this.titleScreen = titleScreen;
        this.session = session;
        this.openStudio = openStudio;
        this.consoleKey = consoleKey;
    }

    static boolean seen() {
        return Files.isRegularFile(SEEN);
    }

    @Override
    protected void init() {
        panelWidth = Math.min(330, width - 32);
        top = Math.max(8, (height - 182) / 2);
        left = (width - panelWidth) / 2;
        button("Open Studio", 93, () -> {
            acknowledge();
            openStudio.run();
        });
        button("Set Up Ollama", 117, () -> {
            if (!session.ready) session.discover(client);
            client.setScreen(StudioScreen.modelSetup(session, this));
        });
        button("Later", 141, this::close);
    }

    private void button(String label, int y, Runnable action) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> action.run())
                .dimensions(left + 12, top + y, panelWidth - 24, 20).build());
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fillGradient(0, 0, width, height, 0xFF122426, 0xFF101517);
        context.fill(left, top, left + panelWidth, top + 174, 0xFF1F3033);
        context.fill(left, top, left + panelWidth, top + 1, 0xFFA8FFE0);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, getTitle(), width / 2, top + 14, 0xA8FFE0);
        line(context, "Try the Thunder Hammer in the Studio.", 38);
        line(context, "Make a structure from a prompt with local Ollama.", 51);
        line(context, "Aim the preview. " + client.options.attackKey.getBoundKeyLocalizedText().getString()
                + " places it.", 64);
        line(context, consoleKey + " opens Text2Craft while playing.", 77);
    }

    private void line(DrawContext context, String text, int y) {
        context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(text, panelWidth - 20),
                width / 2, top + y, 0xF1F8F6);
    }

    @Override
    public void close() {
        acknowledge();
        client.setScreen(titleScreen);
    }

    private static void acknowledge() {
        if (seen()) return;
        try {
            Files.createDirectories(SEEN.getParent());
            Files.writeString(SEEN, "1\n");
        } catch (IOException e) {
            LoggerFactory.getLogger(StartupGuide.class).warn("Could not save the welcome screen preference", e);
        }
    }
}
