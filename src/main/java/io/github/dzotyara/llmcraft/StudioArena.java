package io.github.dzotyara.llmcraft;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.LinkedHashMap;
import java.util.Map;

final class StudioArena {
    static final BlockPos CONSOLE = new BlockPos(0, 81, 1);
    private static final BlockPos SPAWN = new BlockPos(0, 81, 6);
    private static final BlockPos TARGET_AREA = new BlockPos(0, 81, -6);
    private static final BlockPos FOUNDATION = new BlockPos(0, 79, 6);
    private static final BlockPos PLOT_FOUNDATION = new BlockPos(0, 79, StructurePlacement.PLOT_MAX.getZ());
    private static final String TARGET_TAG = "llmcraft_target";

    static void prepare(ServerWorld world) {
        if (!world.getBlockState(FOUNDATION).isOf(Blocks.BEDROCK)) {
            for (int x = -8; x <= 8; x++) {
                for (int z = -13; z <= 9; z++) {
                    boolean edge = Math.abs(x) == 8 || z == -13 || z == 9;
                    world.setBlockState(new BlockPos(x, 80, z),
                            (edge ? Blocks.SEA_LANTERN : Blocks.POLISHED_DEEPSLATE).getDefaultState());
                }
            }
            world.setBlockState(CONSOLE, Blocks.CHISELED_DEEPSLATE.getDefaultState());
            world.setBlockState(CONSOLE.up(), Blocks.AMETHYST_BLOCK.getDefaultState());
            world.setBlockState(FOUNDATION, Blocks.BEDROCK.getDefaultState());
        }
        if (!world.getBlockState(PLOT_FOUNDATION).isOf(Blocks.BEDROCK)) {
            BlockPos min = StructurePlacement.PLOT_MIN;
            BlockPos max = StructurePlacement.PLOT_MAX;
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    boolean edge = x == min.getX() || x == max.getX() || z == min.getZ() || z == max.getZ();
                    var pos = new BlockPos(x, min.getY() - 1, z);
                    if (world.isAir(pos)) world.setBlockState(pos,
                            (edge ? Blocks.SEA_LANTERN : Blocks.POLISHED_DEEPSLATE).getDefaultState());
                }
            }
            for (int x = -2; x <= 2; x++) {
                for (int z = -15; z <= -14; z++) {
                    var pos = new BlockPos(x, 80, z);
                    if (world.isAir(pos)) world.setBlockState(pos, Blocks.POLISHED_DEEPSLATE.getDefaultState());
                }
            }
            world.setBlockState(PLOT_FOUNDATION, Blocks.BEDROCK.getDefaultState());
        }
        world.setSpawnPos(SPAWN, 180);
    }

    static void giveHammer(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        var inventory = player.getInventory();
        boolean hasHammer = inventory.contains(stack -> stack.isOf(LlmCraft.THUNDER_HAMMER));
        if (hasHammer) {
            for (int slot = 0; slot < 9; slot++) {
                if (inventory.getStack(slot).isOf(LlmCraft.THUNDER_HAMMER)) inventory.selectedSlot = slot;
            }
        } else {
            int slot = inventory.getEmptySlot();
            if (slot < 0) {
                player.sendMessage(Text.literal("Make room in your inventory for the hammer."), true);
                return;
            }
            inventory.setStack(slot, LlmCraft.THUNDER_HAMMER.getDefaultStack());
            if (slot < 9) inventory.selectedSlot = slot;
        }
        player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket(
                inventory.selectedSlot));
        if (world.getOtherEntities(null, new Box(TARGET_AREA).expand(7, 12, 5),
                target -> target.isAlive() && target.getCommandTags().contains(TARGET_TAG)).isEmpty()) {
            for (int x : new int[]{-2, 0, 2}) {
                HuskEntity target = EntityType.HUSK.create(world);
                if (target == null) continue;
                target.refreshPositionAndAngles(x + 0.5, 81, -6.5, 0, 0);
                target.setAiDisabled(true);
                target.setPersistent();
                target.setCustomName(Text.literal("Test Target"));
                target.addCommandTag(TARGET_TAG);
                world.spawnEntity(target);
            }
        }
    }

    static Map<BlockPos, BlockState> structure(Blueprint blueprint, BlockPos origin) {
        Block block = switch (blueprint.base()) {
            case "quartz_block" -> Blocks.QUARTZ_BLOCK;
            case "prismarine" -> Blocks.PRISMARINE;
            case "blackstone" -> Blocks.BLACKSTONE;
            default -> Blocks.STONE_BRICKS;
        };
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        int height = 3 + blueprint.power();
        if (blueprint.action().equals("arch")) {
            for (int y = 0; y < height; y++) {
                blocks.put(origin.add(-2, y, 0), block.getDefaultState());
                blocks.put(origin.add(2, y, 0), block.getDefaultState());
            }
            for (int x = -2; x <= 2; x++) blocks.put(origin.add(x, height, 0), block.getDefaultState());
        } else {
            for (int y = 0; y < height; y++) {
                for (int x = -2; x <= 2; x++) {
                    for (int z = -2; z <= 2; z++) {
                        if (Math.abs(x) == 2 || Math.abs(z) == 2 || y == 0) {
                            if (y < 3 && x == 0 && z == 2) continue;
                            blocks.put(origin.add(x, y, z), block.getDefaultState());
                        }
                    }
                }
            }
        }
        blocks.put(origin.add(0, height + 1, 0), Blocks.SEA_LANTERN.getDefaultState());
        return blocks;
    }
}
