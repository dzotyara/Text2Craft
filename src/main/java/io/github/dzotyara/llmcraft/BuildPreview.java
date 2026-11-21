package io.github.dzotyara.llmcraft;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BuildPreview {
    private static final BlockRotation[] ROTATIONS = {
            BlockRotation.NONE, BlockRotation.CLOCKWISE_90, BlockRotation.CLOCKWISE_180,
            BlockRotation.COUNTERCLOCKWISE_90
    };
    private static Preview active;
    private static boolean holdAttack;

    private static final class Preview {
        final ClientWorld world;
        final StructureBlueprint blueprint;
        final Map<Item, Integer> requirements;
        Map<BlockPos, BlockState> relative;
        List<BlockPos> surface;
        BlockPos anchor;
        Map<BlockPos, BlockState> blocks = Map.of();
        int rotation;
        int centerX;
        int centerZ;
        int checkIn;
        boolean placing;
        boolean checking;
        StructurePlacement.Result result;

        Preview(ClientWorld world, StructureBlueprint blueprint) {
            this.world = world;
            this.blueprint = blueprint;
            this.requirements = StructurePlacement.requirements(blueprint.blocks());
            rotateTo(0);
        }

        void rotateTo(int index) {
            rotation = index;
            BlockRotation turn = ROTATIONS[index];
            relative = new HashMap<>();
            blueprint.blocks().forEach((pos, state) -> relative.put(pos.rotate(turn), state.rotate(turn)));
            centerX = center(Direction.Axis.X);
            centerZ = center(Direction.Axis.Z);
            surface = relative.keySet().stream()
                    .filter(pos -> java.util.Arrays.stream(Direction.values())
                            .anyMatch(direction -> !relative.containsKey(pos.offset(direction))))
                    .toList();
            anchor = null;
            checkIn = 0;
        }

        private int center(Direction.Axis axis) {
            var stats = relative.keySet().stream().mapToInt(pos -> pos.getComponentAlongAxis(axis)).summaryStatistics();
            return Math.floorDiv(stats.getMin() + stats.getMax(), 2);
        }
    }

    static void open(MinecraftClient client, StructureBlueprint blueprint) {
        if (!StudioClient.canBuild(client)) return;
        StudioClient.onLocalServer(client, ThunderHammerItem::cancelFlight);
        active = new Preview(client.world, blueprint);
        client.setScreen(null);
        updateTarget(client, active);
    }

    static void rotate(MinecraftClient client) {
        if (active == null || active.placing) return;
        active.rotateTo((active.rotation + 1) % ROTATIONS.length);
        updateTarget(client, active);
    }

    static void revoke() {
        active = null;
        holdAttack = false;
    }

    public static boolean cancel() {
        if (active == null) return false;
        if (!active.placing) active = null;
        return true;
    }

    public static boolean interceptAttack(MinecraftClient client) {
        Preview preview = active;
        if (preview == null) return holdAttack;
        holdAttack = true;
        if (preview.placing) return true;

        updateTarget(client, preview);
        if (preview.anchor == null || !StudioClient.canBuild(client)) {
            message(client, "Aim at the top of a block to position the structure.");
            return true;
        }
        preview.placing = true;
        Map<BlockPos, BlockState> blocks = preview.blocks;
        boolean studio = StudioClient.inStudio(client);
        if (!StudioClient.onLocalServer(client, player -> {
            var result = StructurePlacement.place(player, blocks, studio);
            client.execute(() -> {
                if (active != preview) return;
                preview.placing = false;
                preview.result = result;
                message(client, result.message());
                if (result.success()) active = null;
            });
        })) {
            preview.placing = false;
        }
        return true;
    }

    public static boolean blockBreaking() {
        return active != null || holdAttack;
    }

    public static boolean blocksItemUse(MinecraftClient client) {
        return active != null && client.player != null
                && (client.player.getMainHandStack().isOf(LlmCraft.THUNDER_HAMMER)
                || client.player.getOffHandStack().isOf(LlmCraft.THUNDER_HAMMER));
    }

    static List<String> materials(MinecraftClient client) {
        Preview preview = active;
        if (preview == null || client.player == null || client.player.isCreative()) return List.of();
        return preview.requirements.entrySet().stream()
                .map(entry -> {
                    int have = StructurePlacement.available(client.player.getInventory(), entry.getKey());
                    int needed = entry.getValue();
                    int missing = Math.max(0, needed - have);
                    return entry.getKey().getName().getString() + "  " + have + "/" + needed
                            + (missing == 0 ? "" : " (" + missing + " missing)");
                })
                .sorted().toList();
    }

    static void tick(MinecraftClient client) {
        if (!client.options.attackKey.isPressed()) holdAttack = false;
        Preview preview = active;
        if (preview == null) return;
        if (client.world != preview.world || !StudioClient.canBuild(client)) {
            revoke();
            return;
        }
        if (client.currentScreen != null || preview.placing) return;
        updateTarget(client, preview);
        if (preview.anchor == null || preview.checkIn-- > 0 || preview.checking) return;
        preview.checkIn = 4;
        Map<BlockPos, BlockState> blocks = preview.blocks;
        boolean studio = StudioClient.inStudio(client);
        preview.checking = StudioClient.onLocalServer(client, player -> {
            var result = StructurePlacement.check(player, blocks, studio);
            client.execute(() -> {
                preview.checking = false;
                if (active == preview && preview.blocks == blocks && !preview.placing) preview.result = result;
            });
        });
    }

    private static void updateTarget(MinecraftClient client, Preview preview) {
        if (client.player == null || client.world != preview.world) return;
        var hit = client.player.raycast(40, 1, false);
        BlockPos target = null;
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            target = block.getBlockPos().offset(block.getSide());
        }
        if (StudioClient.inStudio(client) && (target == null || !StructurePlacement.inPlot(target))) {
            BlockPos min = StructurePlacement.PLOT_MIN;
            BlockPos max = StructurePlacement.PLOT_MAX;
            target = new BlockPos(Math.floorDiv(min.getX() + max.getX(), 2), min.getY(),
                    Math.floorDiv(min.getZ() + max.getZ(), 2));
        }
        BlockPos anchor = target == null ? null : target.add(-preview.centerX, 0, -preview.centerZ);
        if (java.util.Objects.equals(anchor, preview.anchor)) return;
        preview.anchor = anchor;
        preview.result = null;
        preview.checkIn = 0;
        if (anchor == null) {
            preview.blocks = Map.of();
            return;
        }
        Map<BlockPos, BlockState> absolute = new HashMap<>();
        preview.relative.forEach((pos, state) -> absolute.put(anchor.add(pos), state));
        preview.blocks = Map.copyOf(absolute);
    }

    static void render(WorldRenderContext context) {
        Preview preview = active;
        if (preview == null || preview.anchor == null || preview.world != context.world()
                || MinecraftClient.getInstance().currentScreen != null) return;
        var matrices = context.matrixStack();
        var consumers = context.consumers();
        if (matrices == null || consumers == null) return;
        boolean blocked = preview.result != null && !preview.result.success();
        float r = blocked ? 1 : 0.29f;
        float g = blocked ? 0.32f : 1;
        float b = blocked ? 0.28f : 0.74f;
        var camera = context.camera().getPos();
        var buffer = consumers.getBuffer(RenderLayer.getDebugFilledBox());
        matrices.push();
        matrices.translate(preview.anchor.getX() - camera.x, preview.anchor.getY() - camera.y,
                preview.anchor.getZ() - camera.z);
        for (BlockPos pos : preview.surface) {
            WorldRenderer.renderFilledBox(matrices, buffer, (double) pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, r, g, b, 0.27f);
        }
        matrices.pop();
    }

    static void renderHud(MinecraftClient client, DrawContext context, String rotateKey, String materialsKey) {
        Preview preview = active;
        if (preview == null || client.player == null) return;
        int width = context.getScaledWindowWidth();
        int y = context.getScaledWindowHeight() - 108;
        int panel = Math.min(width - 16, 262);
        String status = preview.placing ? "Placing…" : preview.anchor == null ? "Aim at a block"
                : preview.result != null && !preview.result.success() ? preview.result.message()
                : client.options.attackKey.getBoundKeyLocalizedText().getString() + " · Place    " + rotateKey + " · Rotate    Esc · Cancel";
        context.fill((width - panel) / 2, y - 4, (width + panel) / 2,
                y + (client.player.isCreative() ? 23 : 34), 0xDA162429);
        draw(context, client, preview.blueprint.name(), width, panel, y, 0xA8FFE0);
        draw(context, client, status, width, panel, y + 11, 0xFFFFFF);
        if (client.player.isCreative()) return;
        List<String> missing = new ArrayList<>();
        preview.requirements.forEach((item, count) -> {
            int shortage = count - StructurePlacement.available(client.player.getInventory(), item);
            if (shortage > 0) missing.add(shortage + " " + item.getName().getString());
        });
        if (missing.isEmpty()) {
            draw(context, client, "Materials available · " + materialsKey + " for list", width, panel, y + 22, 0xA8FFE0);
        } else {
            missing.sort(String::compareTo);
            String details = String.join(", ", missing.subList(0, Math.min(3, missing.size())));
            if (missing.size() > 3) details += " +" + (missing.size() - 3) + " types";
            draw(context, client, materialsKey + " · Missing: " + details, width, panel, y + 22, 0xFFA0A0);
        }
    }

    private static void draw(DrawContext context, MinecraftClient client, String text, int width, int panel, int y, int color) {
        context.drawCenteredTextWithShadow(client.textRenderer, client.textRenderer.trimToWidth(text, panel - 12),
                width / 2, y, color);
    }

    static void undo(MinecraftClient client) {
        StudioClient.onLocalServer(client, player -> {
            var result = StructurePlacement.undo(player);
            client.execute(() -> message(client, result.message()));
        });
    }

    private static void message(MinecraftClient client, String text) {
        if (client.player != null) client.player.sendMessage(Text.literal(text), true);
    }
}
