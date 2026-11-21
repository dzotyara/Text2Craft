package io.github.dzotyara.llmcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

final class ThunderHammerItem extends Item {
    private static final Identifier ID = Identifier.of("llmcraft", "thunder_hammer");
    private static final CustomModelDataComponent IN_FLIGHT = new CustomModelDataComponent(1);
    private static final int THROW_TICKS = 15;
    private static final int COOLDOWN_TICKS = 42;
    private static final Map<ServerPlayerEntity, Flight> FLIGHTS = new HashMap<>();

    private ThunderHammerItem() {
        super(new Settings().maxCount(1).attributeModifiers(AttributeModifiersComponent.builder()
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, new EntityAttributeModifier(BASE_ATTACK_DAMAGE_MODIFIER_ID,
                        7, EntityAttributeModifier.Operation.ADD_VALUE), AttributeModifierSlot.MAINHAND)
                .add(EntityAttributes.GENERIC_ATTACK_SPEED, new EntityAttributeModifier(BASE_ATTACK_SPEED_MODIFIER_ID,
                        -2.7, EntityAttributeModifier.Operation.ADD_VALUE), AttributeModifierSlot.MAINHAND)
                .build()));
    }

    static Item register() {
        return Registry.register(Registries.ITEM, ID, new ThunderHammerItem());
    }

    static void init() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            ItemStack held = player.getMainHandStack();
            return held.getItem() instanceof ThunderHammerItem
                    && IN_FLIGHT.equals(held.get(DataComponentTypes.CUSTOM_MODEL_DATA))
                    ? ActionResult.FAIL : ActionResult.PASS;
        });
        ServerTickEvents.END_WORLD_TICK.register(world -> FLIGHTS.values().removeIf(flight -> {
            if (flight.world != world || flight.tick()) return false;
            flight.finish();
            return true;
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> cancelFlight(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> FLIGHTS.values().removeIf(flight -> {
            if (flight.world.getServer() != server) return false;
            flight.finish();
            return true;
        }));
    }

    static void cancelFlight(ServerPlayerEntity player) {
        Flight flight = FLIGHTS.remove(player);
        if (flight != null) flight.finish();
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (player.isSpectator() || player.getItemCooldownManager().isCoolingDown(this)) {
            return TypedActionResult.fail(stack);
        }
        if (world instanceof ServerWorld serverWorld && player instanceof ServerPlayerEntity owner) {
            if (FLIGHTS.containsKey(owner)) return TypedActionResult.fail(stack);
            Flight flight = new Flight(serverWorld, owner, stack, hand);
            if (!serverWorld.spawnEntity(flight.model)) return TypedActionResult.fail(stack);
            FLIGHTS.put(owner, flight);
            // The display is transient and the player's stack is the only owned item.
            stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, IN_FLIGHT);
            player.getItemCooldownManager().set(this, COOLDOWN_TICKS);
            play(serverWorld, flight.position, SoundEvents.ITEM_TRIDENT_THROW.value(), 0.7f, 1.35f);
        }
        return TypedActionResult.success(stack, world.isClient());
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        PlayerEntity player = context.getPlayer();
        return player == null ? ActionResult.PASS
                : use(context.getWorld(), player, context.getHand()).getResult();
    }

    @Override
    public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity target, Hand hand) {
        return use(user.getWorld(), user, hand).getResult();
    }

    @Override
    public boolean canMine(BlockState state, World world, BlockPos pos, PlayerEntity miner) {
        return false;
    }

    @Override
    public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (target.getWorld() instanceof ServerWorld world) {
            Vec3d center = new Vec3d(target.getX(), target.getBodyY(0.55), target.getZ());
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, center.x, center.y, center.z,
                    16, 0.25, 0.32, 0.25, 0.12);
            play(world, center, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, 0.75f, 1.45f);
        }
        return true;
    }

    @Override
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        if (entity instanceof ServerPlayerEntity player
                && IN_FLIGHT.equals(stack.get(DataComponentTypes.CUSTOM_MODEL_DATA))) {
            Flight flight = FLIGHTS.get(player);
            if (flight == null || flight.stack != stack) stack.remove(DataComponentTypes.CUSTOM_MODEL_DATA);
        }
    }

    private static void play(ServerWorld world, Vec3d pos, SoundEvent sound, float volume, float pitch) {
        world.playSound(null, pos.x, pos.y, pos.z, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private static Vec3d handPosition(ServerPlayerEntity owner, Hand hand) {
        Vec3d forward = owner.getRotationVec(1);
        double side = owner.getMainArm() == Arm.RIGHT ? 0.37 : -0.37;
        if (hand == Hand.OFF_HAND) side = -side;
        double yaw = Math.toRadians(owner.getYaw());
        return owner.getEyePos().add(forward.multiply(0.25))
                .add(Math.cos(yaw) * -side, -0.26, Math.sin(yaw) * -side);
    }

    private static final class Flight {
        private final ServerWorld world;
        private final ServerPlayerEntity owner;
        private final ItemStack stack;
        private final Hand hand;
        private final FlyingModel model;
        private Vec3d position;
        private Vec3d velocity;
        private Vec3d returnStart;
        private Vec3d impact;
        private int age;
        private int returnAge;
        private int returnDuration;

        private Flight(ServerWorld world, ServerPlayerEntity owner, ItemStack stack, Hand hand) {
            this.world = world;
            this.owner = owner;
            this.stack = stack;
            this.hand = hand;
            position = owner.getEyePos();
            velocity = owner.getRotationVec(1).multiply(1.35);
            model = new FlyingModel(world);
            model.setPosition(position);
            model.setItemStack(stack.copy());
            model.setTeleportDuration(1);
            model.setInterpolationDuration(1);
            model.setTransformation(transform(0));
        }

        private boolean tick() {
            if (owner.isDisconnected() || owner.isSpectator() || !owner.isAlive() || owner.isRemoved()
                    || owner.getWorld() != world || model.isRemoved() || !hasStack()
                    || position.squaredDistanceTo(owner.getPos()) > 40 * 40) return false;

            age++;
            Vec3d previous = position;
            if (returnStart == null) {
                flyOut();
            } else {
                Vec3d destination = handPosition(owner, hand);
                double progress = (double) ++returnAge / returnDuration;
                double lift = Math.min(3.2, 0.5 + returnStart.distanceTo(destination) * 0.16);
                position = returnStart.lerp(destination, progress).add(0, Math.sin(progress * Math.PI) * lift, 0);
                if (!world.isChunkLoaded(ChunkPos.toLong(BlockPos.ofFloored(position)))) return false;
                if (impact != null && returnAge <= 5) ring(0.32 + returnAge * 0.36);
                if (returnAge == returnDuration) {
                    play(world, destination, SoundEvents.BLOCK_ANVIL_HIT, 0.55f, 1.7f);
                    return false;
                }
            }
            model.setPosition(position);
            model.setStartInterpolation(0);
            model.setTransformation(transform(age));
            trail(previous.lerp(position, 0.45));
            trail(position);
            return true;
        }

        private boolean hasStack() {
            var inventory = owner.getInventory();
            for (int slot = 0; slot < inventory.size(); slot++) {
                if (inventory.getStack(slot) == stack) return true;
            }
            return false;
        }

        private void flyOut() {
            Vec3d next = position.add(velocity);
            if (!world.isChunkLoaded(ChunkPos.toLong(BlockPos.ofFloored(next)))) {
                startReturn();
                return;
            }
            var block = world.raycast(new RaycastContext(position, next, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, owner));
            boolean hitBlock = block.getType() == HitResult.Type.BLOCK;
            Vec3d end = hitBlock ? block.getPos() : next;
            var hit = ProjectileUtil.getEntityCollision(world, model, position, end,
                    new Box(position, end).expand(0.9), entity -> entity instanceof LivingEntity living
                            && living != owner && living.isAlive() && living.canBeHitByProjectile()
                            && (!(entity instanceof PlayerEntity target) || owner.shouldDamagePlayer(target)));
            position = hit == null ? end : hit.getPos();
            if (hit != null) hit.getEntity().damage(world.getDamageSources().playerAttack(owner), 10.0f);
            if (hit != null || hitBlock) {
                impact = position;
                flash();
                startReturn();
            } else if (age >= THROW_TICKS) {
                startReturn();
            } else {
                velocity = velocity.add(0, -0.008, 0);
            }
        }

        private void startReturn() {
            returnStart = position;
            returnDuration = Math.max(6, (int) Math.ceil(position.distanceTo(owner.getEyePos()) / 1.55));
        }

        private void flash() {
            play(world, impact, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.82f, 1.25f);
            world.spawnParticles(ParticleTypes.FLASH, impact.x, impact.y + 0.16, impact.z, 1, 0, 0, 0, 0);
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, impact.x, impact.y, impact.z,
                    28, 0.42, 0.30, 0.42, 0.17);
            for (int step = 1; step <= 8; step++) {
                double shift = Math.sin(step * 1.6) * 0.20;
                Vec3d point = impact.add(shift, step * 0.24, -shift);
                world.spawnParticles(ParticleTypes.END_ROD, point.x, point.y, point.z, 2, 0.04, 0.11, 0.04, 0);
            }
            ring(0.32);
        }

        private void ring(double radius) {
            for (int segment = 0; segment < 16; segment++) {
                double angle = segment * Math.PI / 8;
                world.spawnParticles(ParticleTypes.END_ROD,
                        impact.x + Math.cos(angle) * radius, impact.y + 0.12, impact.z + Math.sin(angle) * radius,
                        1, 0, 0, 0, 0);
            }
        }

        private void trail(Vec3d point) {
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z, 4, 0.11, 0.11, 0.11, 0.04);
        }

        private static AffineTransformation transform(int age) {
            return new AffineTransformation(null, new Quaternionf().rotationXYZ(
                    (float) (age * 0.61), (float) (age * 0.22), 0.58f), new Vector3f(1.2f), null);
        }

        private void finish() {
            stack.remove(DataComponentTypes.CUSTOM_MODEL_DATA);
            model.discard();
        }
    }

    private static final class FlyingModel extends DisplayEntity.ItemDisplayEntity {
        private FlyingModel(ServerWorld world) {
            super(EntityType.ITEM_DISPLAY, world);
        }

        @Override
        public boolean shouldSave() {
            return false;
        }
    }
}
