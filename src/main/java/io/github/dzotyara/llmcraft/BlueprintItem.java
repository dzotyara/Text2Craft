package io.github.dzotyara.llmcraft;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

final class BlueprintItem extends Item {
    BlueprintItem(Settings settings) {
        super(settings);
    }

    static ItemStack stack(Blueprint blueprint, boolean preview) {
        ItemStack stack = new ItemStack(LlmCraft.CREATION);
        NbtCompound data = new NbtCompound();
        data.putString("blueprint", blueprint.json());
        data.putBoolean("preview", preview);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data));
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(blueprint.name()));
        stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new net.minecraft.component.type.CustomModelDataComponent(
                switch (blueprint.base()) {
                    case "axe" -> 1;
                    case "sword" -> 2;
                    case "rod" -> 3;
                    default -> 0;
                }));
        return stack;
    }

    private Blueprint read(ServerWorld world, ItemStack stack) {
        NbtComponent component = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (component == null) return null;
        NbtCompound data = component.copyNbt();
        if (data.getBoolean("preview") && !LlmCraft.isStudio(world.getServer())) return null;
        try {
            return Blueprint.parse(data.getString("blueprint"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (!(world instanceof ServerWorld serverWorld)) return TypedActionResult.success(stack);
        Blueprint blueprint = read(serverWorld, stack);
        if (blueprint == null || blueprint.kind() != Blueprint.Kind.item) return TypedActionResult.pass(stack);
        int power = blueprint.power();
        if (blueprint.action().equals("heal")) {
            player.heal(power);
        } else {
            Box area = player.getBoundingBox().expand(4 + power / 2.0, 2, 4 + power / 2.0);
            for (LivingEntity target : world.getEntitiesByClass(LivingEntity.class, area,
                    entity -> entity.isAlive() && !(entity instanceof PlayerEntity) && entity != player)) {
                if (blueprint.action().equals("shock")) {
                    target.damage(world.getDamageSources().playerAttack(player), 2 + power);
                    serverWorld.spawnParticles(ParticleTypes.ELECTRIC_SPARK, target.getX(), target.getBodyY(0.5),
                            target.getZ(), 14, 0.35, 0.45, 0.35, 0.1);
                } else {
                    target.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 20 + 8 * power));
                }
            }
        }
        serverWorld.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getBodyY(0.5), player.getZ(),
                24, 0.7, 0.5, 0.7, 0.06);
        player.getItemCooldownManager().set(this, 30 + 10 * power);
        return TypedActionResult.consume(stack);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        if (!(context.getWorld() instanceof ServerWorld world) || context.getPlayer() == null) {
            return ActionResult.PASS;
        }
        Blueprint blueprint = read(world, context.getStack());
        if (blueprint == null || blueprint.kind() != Blueprint.Kind.structure) return ActionResult.PASS;
        if (!context.getPlayer().canModifyBlocks()) return ActionResult.FAIL;
        var origin = context.getBlockPos().offset(context.getSide());
        var blocks = StudioArena.structure(blueprint, origin);
        if (blocks.keySet().stream().anyMatch(pos -> !world.isInBuildLimit(pos) || !world.isChunkLoaded(pos)
                || !world.canPlayerModifyAt(context.getPlayer(), pos) || !world.getBlockState(pos).isAir()
                || !world.getOtherEntities(null, new Box(pos)).isEmpty())) {
            context.getPlayer().sendMessage(Text.literal("Clear more space for the structure."), true);
            return ActionResult.FAIL;
        }
        blocks.forEach(world::setBlockState);
        if (!context.getPlayer().isCreative()) context.getStack().decrement(1);
        return ActionResult.SUCCESS;
    }
}
