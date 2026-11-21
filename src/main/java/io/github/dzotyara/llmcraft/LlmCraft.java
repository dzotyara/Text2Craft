package io.github.dzotyara.llmcraft;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class LlmCraft implements ModInitializer {
    static final String STUDIO_SAVE = "llmcraft-studio-4d19";
    static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve("llmcraft");
    static final Library<Blueprint> LIBRARY = new Library<>(DIRECTORY.resolve("library"),
            Blueprint::parse, Blueprint::name, Blueprint::json);
    static final Library<StructureBlueprint> STRUCTURES = new Library<>(DIRECTORY.resolve("structures"),
            StructureBlueprint::parse, StructureBlueprint::name, StructureBlueprint::json);
    static final BlueprintItem CREATION = new BlueprintItem(new Item.Settings().maxCount(1));
    public static final Item THUNDER_HAMMER = ThunderHammerItem.register();

    @Override
    public void onInitialize() {
        Registry.register(Registries.ITEM, Identifier.of("llmcraft", "creation"), CREATION);
        ThunderHammerItem.init();
        Registry.register(Registries.ITEM_GROUP, Identifier.of("llmcraft", "library"),
                FabricItemGroup.builder()
                        .displayName(Text.literal("✦ Text2Craft"))
                        .icon(THUNDER_HAMMER::getDefaultStack)
                        .entries((context, entries) -> {
                            entries.add(THUNDER_HAMMER);
                            LIBRARY.entries().values().forEach(blueprint -> entries.add(BlueprintItem.stack(blueprint, false)));
                        })
                        .build());

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (isStudio(server)) StudioArena.prepare(server.getOverworld());
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            var command = dispatcher.register(literal("text2craft")
                        .then(literal("catalog").executes(context -> {
                            var source = context.getSource();
                            var entries = LIBRARY.entries();
                            source.sendFeedback(() -> Text.literal(entries.isEmpty()
                                    ? "Keep a creation in Text2Craft Studio to add it to the catalog."
                                    : "Text2Craft catalog. Use /text2craft craft <id>."), false);
                            entries.forEach((id, blueprint) -> source.sendFeedback(() -> Text.literal(
                                    id + " — " + blueprint.name() + " (" + cost(blueprint) + " amethyst shards)"),
                                    false));
                            return entries.size();
                        }))
                        .then(literal("craft")
                                .then(argument("id", StringArgumentType.word())
                                        .suggests((context, builder) -> suggestions(builder))
                                        .executes(context -> deliver(context.getSource(),
                                                StringArgumentType.getString(context, "id"), true))))
                        .then(literal("give").requires(source -> source.hasPermissionLevel(2))
                                .then(argument("id", StringArgumentType.word())
                                        .suggests((context, builder) -> suggestions(builder))
                                        .executes(context -> deliver(context.getSource(),
                                                StringArgumentType.getString(context, "id"), false)))));
            dispatcher.register(literal("llmcraft").redirect(command));
        });
    }

    static boolean isStudio(MinecraftServer server) {
        return server != null && server.isSingleplayer()
                && STUDIO_SAVE.equals(server.getSavePath(WorldSavePath.ROOT).normalize().getFileName().toString());
    }

    private static CompletableFuture<Suggestions> suggestions(SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(LIBRARY.entries().keySet(), builder);
    }

    private static int cost(Blueprint blueprint) {
        return 2 + blueprint.power() * 2;
    }

    private static int deliver(ServerCommandSource source, String id, boolean craft)
            throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        Blueprint blueprint = LIBRARY.entries().get(id);
        if (blueprint == null) {
            source.sendError(Text.literal("Unknown Text2Craft creation."));
            return 0;
        }
        var inventory = player.getInventory();
        int cost = cost(blueprint);
        boolean pay = craft && !player.isCreative();
        if (pay && inventory.count(Items.AMETHYST_SHARD) < cost) {
            source.sendError(Text.literal("Requires " + cost + " amethyst shards."));
            return 0;
        }
        int freeSlot = inventory.getEmptySlot();
        if (freeSlot < 0) {
            source.sendError(Text.literal("Make room in your inventory first."));
            return 0;
        }
        if (pay) inventory.remove(stack -> stack.isOf(Items.AMETHYST_SHARD), cost, inventory);
        inventory.setStack(freeSlot, BlueprintItem.stack(blueprint, false));
        source.sendFeedback(() -> Text.literal("Received " + blueprint.name() + "."), false);
        return 1;
    }
}
