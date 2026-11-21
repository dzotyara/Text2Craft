package io.github.dzotyara.llmcraft;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

final class StructurePlacement {
    static final BlockPos PLOT_MIN = new BlockPos(-11, 81, -39);
    static final BlockPos PLOT_MAX = new BlockPos(12, 104, -16);
    private static final int MAX_RANGE = 64;
    // Neighbor updates during a batch could modify blocks outside the rollback snapshot.
    private static final int WRITE_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
    private static final Logger LOGGER = LoggerFactory.getLogger(StructurePlacement.class);
    // shortcut: undo lives for the server session, add durable history if undo must survive a restart.
    private static final Map<MinecraftServer, Map<UUID, Placement>> HISTORY =
            Collections.synchronizedMap(new WeakHashMap<>());

    record Result(boolean success, String message) {}

    private record Placement(RegistryKey<World> dimension, Map<BlockPos, BlockState> before,
                             Map<BlockPos, BlockState> placed, List<ItemStack> spent) {}

    static Map<Item, Integer> requirements(Map<BlockPos, BlockState> blocks) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (BlockState state : blocks.values()) {
            counts.merge(state.getBlock().asItem(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    static int available(PlayerInventory inventory, Item item) {
        int count = 0;
        for (int slot = 0; slot <= PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStack(inventorySlot(slot));
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    static Result check(ServerPlayerEntity player, Map<BlockPos, BlockState> blocks, boolean studio) {
        Result eligibility = playerCheck(player);
        if (!eligibility.success()) return eligibility;

        ServerWorld world = player.getServerWorld();
        studio |= LlmCraft.isStudio(player.server);
        if (studio && !world.getRegistryKey().equals(World.OVERWORLD)) {
            return new Result(false, "Build on the Studio plot in the Overworld.");
        }
        if (blocks == null || blocks.isEmpty() || blocks.size() > 4096) {
            return new Result(false, "Invalid structure size.");
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        var eye = player.getEyePos();
        for (var entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            if (pos == null || state == null || !StructureBlueprint.isAllowed(state)) {
                return new Result(false, "Invalid block in the structure.");
            }
            if (pos.getSquaredDistance(eye) > MAX_RANGE * MAX_RANGE) {
                return new Result(false, "Move closer to the structure.");
            }
            Result location = checkLocation(player, pos);
            if (!location.success()) return location;
            if (studio && !inPlot(pos)) return new Result(false, "Keep the entire structure inside the Studio plot.");
            if (!world.getBlockState(pos).isAir()) return new Result(false, "The structure overlaps existing blocks.");
            if (!state.canPlaceAt(world, pos) || !world.canPlace(state, pos, ShapeContext.absent())) {
                return new Result(false, "The structure overlaps a player or entity, or cannot be placed there.");
            }

            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        if (maxX - minX >= 24 || maxY - minY >= 24 || maxZ - minZ >= 24) {
            return new Result(false, "Structures must fit within 24 × 24 × 24 blocks.");
        }

        if (!player.isCreative()) {
            List<String> missing = new ArrayList<>();
            requirements(blocks).forEach((item, required) -> {
                int shortage = required - available(player.getInventory(), item);
                if (shortage > 0) missing.add(shortage + " × " + item.getName().getString());
            });
            if (!missing.isEmpty()) return new Result(false, "Missing " + String.join(", ", missing) + ".");
        }
        return new Result(true, "Ready to place.");
    }

    static Result place(ServerPlayerEntity player, Map<BlockPos, BlockState> blocks, boolean studio) {
        Result readiness = check(player, blocks, studio);
        if (!readiness.success()) return readiness;

        PlayerInventory inventory = player.getInventory();
        List<ItemStack> remaining = player.isCreative() ? List.of() : inventoryCopy(inventory);
        List<ItemStack> spent = new ArrayList<>();
        if (!player.isCreative()) {
            Map<Item, Integer> due = new HashMap<>(requirements(blocks));
            for (ItemStack stack : remaining) {
                int count = Math.min(stack.getCount(), due.getOrDefault(stack.getItem(), 0));
                if (count == 0) continue;
                due.put(stack.getItem(), due.get(stack.getItem()) - count);
                spent.add(stack.split(count));
            }
        }

        ServerWorld world = player.getServerWorld();
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        blocks.forEach((pos, state) -> {
            before.put(pos.toImmutable(), world.getBlockState(pos));
            placed.put(pos.toImmutable(), state);
        });
        if (!write(world, placed, before)) return new Result(false, "The structure could not be placed.");
        if (!remaining.isEmpty()) commitInventory(inventory, remaining);
        HISTORY.computeIfAbsent(player.server, ignored -> new HashMap<>()).put(player.getUuid(),
                new Placement(world.getRegistryKey(), before, placed, spent));
        return new Result(true, "Placed " + placed.size() + " blocks.");
    }

    static Result undo(ServerPlayerEntity player) {
        Result eligibility = playerCheck(player);
        if (!eligibility.success()) return eligibility;
        Map<UUID, Placement> history = HISTORY.get(player.server);
        Placement last = history == null ? null : history.get(player.getUuid());
        ServerWorld world = player.getServerWorld();
        if (last == null || !last.dimension().equals(world.getRegistryKey())) {
            return new Result(false, "There is no placement to undo in this dimension.");
        }
        for (var entry : last.placed().entrySet()) {
            Result location = checkLocation(player, entry.getKey());
            if (!location.success()) return location;
            if (world.getBlockState(entry.getKey()) != entry.getValue()) {
                return new Result(false, "The structure has changed since placement.");
            }
        }

        PlayerInventory inventory = player.getInventory();
        List<ItemStack> refunded = last.spent().isEmpty() ? List.of() : inventoryCopy(inventory);
        for (ItemStack stack : last.spent()) {
            if (!addRefund(refunded, stack)) {
                return new Result(false, "Make room in your inventory for the returned blocks.");
            }
        }
        if (!write(world, last.before(), last.placed())) return new Result(false, "The placement could not be undone.");
        if (!refunded.isEmpty()) commitInventory(inventory, refunded);
        history.remove(player.getUuid());
        return new Result(true, "Undid the last placement.");
    }

    private static Result playerCheck(ServerPlayerEntity player) {
        if (player == null || !player.server.isOnThread() || !player.isAlive()
                || player.server.getPlayerManager().getPlayer(player.getUuid()) != player) {
            return new Result(false, "No active player is available for building.");
        }
        GameMode mode = player.interactionManager.getGameMode();
        if (!player.canModifyBlocks() || (mode != GameMode.CREATIVE && mode != GameMode.SURVIVAL)
                || player.getServerWorld().isDebugWorld()) {
            return new Result(false, "Building requires Creative or Survival with permission to modify blocks.");
        }
        return new Result(true, "");
    }

    private static Result checkLocation(ServerPlayerEntity player, BlockPos pos) {
        ServerWorld world = player.getServerWorld();
        if (!world.isInBuildLimit(pos) || !world.getWorldBorder().contains(pos)) {
            return new Result(false, "The structure exceeds the world height or border.");
        }
        if (!player.canModifyAt(world, pos)) return new Result(false, "You cannot build in this area.");
        if (!world.getChunkManager().isChunkLoaded(ChunkSectionPos.getSectionCoord(pos.getX()),
                ChunkSectionPos.getSectionCoord(pos.getZ()))) {
            return new Result(false, "Part of the structure is in an unloaded chunk.");
        }
        return new Result(true, "");
    }

    static boolean inPlot(BlockPos pos) {
        return pos.getX() >= PLOT_MIN.getX() && pos.getX() <= PLOT_MAX.getX()
                && pos.getY() >= PLOT_MIN.getY() && pos.getY() <= PLOT_MAX.getY()
                && pos.getZ() >= PLOT_MIN.getZ() && pos.getZ() <= PLOT_MAX.getZ();
    }

    private static int inventorySlot(int slot) {
        return slot == PlayerInventory.MAIN_SIZE ? PlayerInventory.OFF_HAND_SLOT : slot;
    }

    private static List<ItemStack> inventoryCopy(PlayerInventory inventory) {
        List<ItemStack> stacks = new ArrayList<>(PlayerInventory.MAIN_SIZE + 1);
        for (int slot = 0; slot <= PlayerInventory.MAIN_SIZE; slot++) {
            stacks.add(inventory.getStack(inventorySlot(slot)).copy());
        }
        return stacks;
    }

    private static void commitInventory(PlayerInventory inventory, List<ItemStack> stacks) {
        for (int slot = 0; slot < stacks.size(); slot++) {
            int target = inventorySlot(slot);
            ItemStack stack = stacks.get(slot);
            if (!ItemStack.areEqual(inventory.getStack(target), stack)) inventory.setStack(target, stack);
        }
        inventory.markDirty();
    }

    static boolean addRefund(List<ItemStack> inventory, ItemStack refund) {
        int remaining = refund.getCount();
        for (ItemStack stack : inventory) {
            if (remaining == 0) return true;
            if (stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(stack, refund)) continue;
            int amount = Math.min(remaining, stack.getMaxCount() - stack.getCount());
            if (amount <= 0) continue;
            stack.increment(amount);
            remaining -= amount;
        }
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            if (!inventory.get(slot).isEmpty()) continue;
            int amount = Math.min(remaining, refund.getMaxCount());
            inventory.set(slot, refund.copyWithCount(amount));
            remaining -= amount;
        }
        return remaining == 0;
    }

    private static boolean write(ServerWorld world, Map<BlockPos, BlockState> target,
                                 Map<BlockPos, BlockState> original) {
        var attempted = new ArrayList<BlockPos>(target.size());
        try {
            for (var entry : target.entrySet()) {
                BlockPos pos = entry.getKey();
                attempted.add(pos);
                if (!world.setBlockState(pos, entry.getValue(), WRITE_FLAGS)
                        || world.getBlockState(pos) != entry.getValue()) {
                    throw new IllegalStateException("Block write failed at " + pos.toShortString());
                }
            }
            return true;
        } catch (RuntimeException failure) {
            LOGGER.error("Text2Craft block update failed; restoring previous blocks", failure);
        }

        for (BlockPos pos : attempted.reversed()) {
            BlockState originalState = original.get(pos);
            try {
                if (world.getBlockState(pos) != originalState
                        && (!world.setBlockState(pos, originalState, WRITE_FLAGS)
                        || world.getBlockState(pos) != originalState)) {
                    LOGGER.error("Text2Craft could not restore block at {}", pos.toShortString());
                }
            } catch (RuntimeException failure) {
                LOGGER.error("Text2Craft could not restore block at " + pos.toShortString(), failure);
            }
        }
        return false;
    }
}
