package io.github.dzotyara.llmcraft;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorLayer;
import net.minecraft.world.level.LevelInfo;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.server.network.ServerPlayerEntity;

public final class StudioClient implements ClientModInitializer {
    private static final StudioScreen.Session SESSION = new StudioScreen.Session();
    private boolean guideShown;

    @Override
    public void onInitializeClient() {
        guideShown = StartupGuide.seen();
        KeyBinding key = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.llmcraft.studio",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.llmcraft"));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("✦ LLMCraft Studio"),
                                button -> openWorld(client, screen))
                        .dimensions(8, height - 30, 122, 20).build());
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Guide"),
                                button -> showGuide(client, screen, key))
                        .dimensions(134, height - 30, 48, 20).build());
            }
        });
        KeyBinding rotate = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.llmcraft.rotate",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.llmcraft"));
        KeyBinding materials = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.llmcraft.materials",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.llmcraft"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!guideShown && client.getOverlay() == null && client.currentScreen instanceof TitleScreen title) {
                showGuide(client, title, key);
            }
            while (key.wasPressed()) {
                if (client.currentScreen == null && canBuild(client)) showPanel(client);
            }
            while (rotate.wasPressed()) {
                if (client.currentScreen == null) BuildPreview.rotate(client);
            }
            while (materials.wasPressed()) {
                if (client.currentScreen == null && !BuildPreview.materials(client).isEmpty()) {
                    client.setScreen(new StudioScreen(SESSION, StudioScreen.Panel.MATERIALS));
                }
            }
            BuildPreview.tick(client);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (inStudio(client)) SESSION.discover(client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            BuildPreview.revoke();
            SESSION.leaveWorld();
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient || !hit.getBlockPos().equals(StudioArena.CONSOLE.up())) return ActionResult.PASS;
            MinecraftClient client = MinecraftClient.getInstance();
            if (!inStudio(client)) return ActionResult.PASS;
            showPanel(client);
            return ActionResult.SUCCESS;
        });
        WorldRenderEvents.AFTER_ENTITIES.register(BuildPreview::render);
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen != null || client.options.hudHidden) return;
            BuildPreview.renderHud(client, context, rotate.getBoundKeyLocalizedText().getString(),
                    materials.getBoundKeyLocalizedText().getString());
            String mode = SESSION.started != 0
                    ? "Generating… " + SESSION.elapsedSeconds() + "s · Esc to cancel" : SESSION.modelLabel();
            StudioHud.render(client, context, key, mode);
        });
    }

    private void showGuide(MinecraftClient client, Screen title, KeyBinding key) {
        guideShown = true;
        client.setScreen(new StartupGuide(title, SESSION, () -> openWorld(client, title),
                key.getBoundKeyLocalizedText().getString()));
    }

    private static void openWorld(MinecraftClient client, Screen parent) {
        var loader = client.createIntegratedServerLoader();
        if (client.getLevelStorage().levelExists(LlmCraft.STUDIO_SAVE)) {
            loader.start(LlmCraft.STUDIO_SAVE, () -> client.setScreen(parent));
            return;
        }

        GameRules rules = new GameRules();
        rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
        rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
        rules.get(GameRules.SPAWN_RADIUS).set(0, null);
        LevelInfo info = new LevelInfo("LLMCraft Studio", GameMode.CREATIVE, false, Difficulty.NORMAL,
                true, rules, DataConfiguration.SAFE_MODE);
        loader.createAndStart(LlmCraft.STUDIO_SAVE, info, new GeneratorOptions(0L, false, false), registries -> {
            var biomes = registries.get(RegistryKeys.BIOME);
            var config = FlatChunkGeneratorConfig.getDefaultConfig(biomes.getReadOnlyWrapper(),
                    registries.get(RegistryKeys.STRUCTURE_SET).getReadOnlyWrapper(),
                    registries.get(RegistryKeys.PLACED_FEATURE).getReadOnlyWrapper())
                    .with(List.of(new FlatChunkGeneratorLayer(1, Blocks.AIR)),
                            Optional.of(RegistryEntryList.of()), biomes.entryOf(BiomeKeys.THE_VOID));
            return WorldPresets.createDemoOptions(registries).with(registries, new FlatChunkGenerator(config));
        }, parent);
    }

    private static void showPanel(MinecraftClient client) {
        boolean studio = inStudio(client);
        if (studio && !SESSION.ready) SESSION.discover(client);
        client.setScreen(new StudioScreen(SESSION, studio ? StudioScreen.Panel.STUDIO : StudioScreen.Panel.LIBRARY));
    }

    static boolean inStudio(MinecraftClient client) {
        return client.world != null && client.world.getRegistryKey().equals(World.OVERWORLD)
                && LlmCraft.isStudio(client.getServer());
    }

    static boolean canBuild(MinecraftClient client) {
        return client.world != null && client.player != null && client.getServer() != null
                && !client.player.isSpectator();
    }

    public static boolean cancelGeneration(MinecraftClient client) {
        if (!inStudio(client) || !SESSION.cancel()) return false;
        if (client.player != null) client.player.sendMessage(Text.literal("Generation cancelled."), true);
        return true;
    }

    static boolean onLocalServer(MinecraftClient client, Consumer<ServerPlayerEntity> action) {
        if (!canBuild(client)) return false;
        var server = client.getServer();
        var id = client.player.getUuid();
        var world = client.world.getRegistryKey();
        server.execute(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player != null && player.getServerWorld().getRegistryKey().equals(world)) action.accept(player);
        });
        return true;
    }
}
