package io.github.dzotyara.llmcraft;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class StructurePlacementCheck {
    public static void main(String[] args) {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();

        var counts = StructurePlacement.requirements(Map.of(
                BlockPos.ORIGIN, Blocks.BLACKSTONE.getDefaultState(),
                BlockPos.ORIGIN.up(), Blocks.BLACKSTONE.getDefaultState(),
                BlockPos.ORIGIN.east(), Blocks.SEA_LANTERN.getDefaultState()));
        expect(counts.equals(Map.of(Items.BLACKSTONE, 2, Items.SEA_LANTERN, 1)),
                "Counts use the items corresponding to final block states");

        var spent = new ItemStack(Items.BLACKSTONE, 4);
        spent.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Building stock"));
        var generic = new ItemStack(Items.BLACKSTONE, 60);
        var inventory = new ArrayList<>(List.of(generic, spent.copyWithCount(62), ItemStack.EMPTY));
        expect(StructurePlacement.addRefund(inventory, spent), "Refund fits across two stacks");
        expect(inventory.get(0).getCount() == 60 && inventory.get(1).getCount() == 64
                        && inventory.get(2).getCount() == 2
                        && ItemStack.areItemsAndComponentsEqual(inventory.get(2), spent)
                        && spent.getCount() == 4,
                "Refund preserves components and leaves the original receipt usable");
        expect(!StructurePlacement.addRefund(inventory, new ItemStack(Items.STONE_BRICKS)),
                "Full inventory rejects a refund that needs a new stack");
        expect(StructurePlacement.inPlot(StructurePlacement.PLOT_MIN)
                        && StructurePlacement.inPlot(StructurePlacement.PLOT_MAX)
                        && !StructurePlacement.inPlot(StructurePlacement.PLOT_MAX.up()),
                "The Studio plot includes its bounds");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
