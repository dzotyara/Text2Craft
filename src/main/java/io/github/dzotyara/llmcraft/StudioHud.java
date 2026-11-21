package io.github.dzotyara.llmcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;

import java.util.ArrayList;
import java.util.List;

final class StudioHud {
    private static final int PANEL = 0xDA162429;
    private static final int MINT = 0xA8FFE0;
    private static final int WHITE = 0xF1F8F6;
    private static final int MARGIN = 8;

    static void render(MinecraftClient client, DrawContext context, KeyBinding consoleKey, String modelStatus) {
        if (client.currentScreen != null || client.options.hudHidden || !StudioClient.canBuild(client)) return;

        boolean studio = StudioClient.inStudio(client);
        List<String> instructions = new ArrayList<>();
        instructions.add(key(consoleKey) + (studio ? "  Get Hammer / Build" : "  Structure Library"));
        if (!BuildPreview.blockBreaking()) {
            boolean main = client.player.getMainHandStack().isOf(LlmCraft.THUNDER_HAMMER);
            if (main) instructions.add(key(client.options.attackKey) + "  Strike");
            if (main || client.player.getOffHandStack().isOf(LlmCraft.THUNDER_HAMMER)) {
                instructions.add(key(client.options.useKey) + "  Throw and Return");
            }
        }

        int available = context.getScaledWindowWidth() - 2 * MARGIN;
        String title = studio ? "LLMCraft Studio" : "LLMCraft";
        int textWidth = client.textRenderer.getWidth(title);
        for (String instruction : instructions) textWidth = Math.max(textWidth, client.textRenderer.getWidth(instruction));
        int cardWidth = Math.min(available, textWidth + 14);
        int cardHeight = 20 + 11 * instructions.size();
        context.fill(MARGIN, MARGIN, MARGIN + cardWidth, MARGIN + cardHeight, PANEL);
        context.fill(MARGIN, MARGIN, MARGIN + 1, MARGIN + cardHeight, 0xFFA8FFE0);
        draw(context, client, title, MARGIN + 7, MARGIN + 5, cardWidth - 14, MINT);
        for (int i = 0; i < instructions.size(); i++) {
            draw(context, client, instructions.get(i), MARGIN + 7, MARGIN + 17 + i * 11, cardWidth - 14, WHITE);
        }
        if (!studio || modelStatus.isBlank()) return;
        int statusWidth = client.textRenderer.getWidth(modelStatus) + 14;
        boolean fitsBeside = statusWidth <= available - cardWidth - MARGIN;
        int statusX = fitsBeside ? context.getScaledWindowWidth() - MARGIN - statusWidth : MARGIN;
        int statusY = fitsBeside ? MARGIN : 2 * MARGIN + cardHeight;
        statusWidth = Math.min(statusWidth, available);
        context.fill(statusX, statusY, statusX + statusWidth, statusY + 18, PANEL);
        draw(context, client, modelStatus, statusX + 7, statusY + 5, statusWidth - 14, MINT);
    }

    private static String key(KeyBinding binding) {
        return "[" + binding.getBoundKeyLocalizedText().getString() + "]";
    }

    private static void draw(DrawContext context, MinecraftClient client, String text, int x, int y,
                             int width, int color) {
        context.drawTextWithShadow(client.textRenderer, client.textRenderer.trimToWidth(text, width), x, y, color);
    }
}
