package com.plushiemod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Plushies and visual hay. Both are invisible, hitbox-less armor stands wearing the chosen item,
 * created only in the local ClientWorld. Clicks are detected with our own ray test.
 */
public class PlushManager {
    public static class Plush {
        public ArmorStandEntity stand;
        public double x, y, z;
        public float scale;
        public final float phase = new Random().nextFloat() * 6.28f;

        public Box box() {
            double s = scale;
            return new Box(x - 0.5 * s, y, z - 0.5 * s, x + 0.5 * s, y + 1.0 * s, z + 0.5 * s);
        }
    }

    private static final List<Plush> PLUSHES = new ArrayList<>();
    private static final List<Plush> DECOYS = new ArrayList<>();
    private static ClientWorld lastWorld;

    public static int plushCount() {
        return PLUSHES.size();
    }

    public static int decoyCount() {
        return DECOYS.size();
    }

    public static ItemStack stackOf(String id) {
        Identifier i = Identifier.tryParse(id);
        if (i != null && Registries.ITEM.containsId(i)) return new ItemStack(Registries.ITEM.get(i));
        return new ItemStack(Items.STICK);
    }

    private static ArmorStandEntity makeStand(ClientWorld w, ItemStack stack, double x, double y, double z) {
        ArmorStandEntity s = new ArmorStandEntity(w, x, y, z);
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean("Small", true);
        nbt.putBoolean("Marker", true);        // no hitbox at all
        nbt.putBoolean("Invisible", true);
        nbt.putBoolean("NoGravity", true);
        nbt.putBoolean("NoBasePlate", true);
        s.readNbt(nbt);
        s.equipStack(EquipmentSlot.HEAD, stack.copy());
        s.setId(MobManager.newId());
        s.setPosition(x, y, z);
        w.addEntity(s);
        return s;
    }

    private static Vec3d spot(MinecraftClient mc, boolean crosshair) {
        ClientPlayerEntity p = mc.player;
        Vec3d pos = p.getPos();
        if (crosshair) {
            HitResult hr = p.raycast(8.0, 1.0f, false);
            if (hr.getType() == HitResult.Type.BLOCK) pos = hr.getPos();
        }
        double gy = MobManager.ground(mc.world, pos.x, pos.y, pos.z);
        return new Vec3d(pos.x, gy, pos.z);
    }

    public static void placePlush(MinecraftClient mc, boolean crosshair) {
        if (mc.world == null || mc.player == null) return;
        PlushConfig cfg = PlushConfig.get();
        Vec3d v = spot(mc, crosshair);
        Plush pl = new Plush();
        pl.x = v.x;
        pl.y = v.y;
        pl.z = v.z;
        pl.scale = cfg.plushScale;
        pl.stand = makeStand(mc.world, stackOf(cfg.plushItem), v.x, v.y, v.z);
        PLUSHES.add(pl);
    }

    public static void placeDecoy(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        if (DECOYS.size() >= 20) return;
        PlushConfig cfg = PlushConfig.get();
        Vec3d v = spot(mc, true);
        Plush d = new Plush();
        d.x = v.x;
        d.y = v.y;
        d.z = v.z;
        d.scale = 0.9f;
        d.stand = makeStand(mc.world, stackOf(cfg.decoyItem), v.x, v.y, v.z);
        DECOYS.add(d);
    }

    public static void clearPlushes() {
        removeAll(PLUSHES);
    }

    public static void clearDecoys() {
        removeAll(DECOYS);
    }

    private static void removeAll(List<Plush> list) {
        ClientWorld w = MinecraftClient.getInstance().world;
        if (w != null) {
            for (Plush p : list) w.removeEntity(p.stand.getId(), Entity.RemovalReason.DISCARDED);
        }
        list.clear();
    }

    /** Which plush is under the crosshair (ignores blocks behind nothing; blocked by blocks in front). */
    public static Plush pick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || PLUSHES.isEmpty()) return null;
        Vec3d eye = p.getEyePos();
        Vec3d end = eye.add(p.getRotationVec(1.0f).multiply(6.0));
        HitResult bh = p.raycast(6.0, 1.0f, false);
        double blockD = bh.getType() == HitResult.Type.BLOCK ? bh.getPos().distanceTo(eye) : Double.MAX_VALUE;
        Plush best = null;
        double bestD = Double.MAX_VALUE;
        for (Plush pl : PLUSHES) {
            Box b = pl.box().expand(0.1);
            double d;
            if (b.contains(eye)) {
                d = 0;
            } else {
                Optional<Vec3d> o = b.raycast(eye, end);
                if (o.isEmpty()) continue;
                d = o.get().distanceTo(eye);
            }
            if (d > blockD + 0.05) continue;
            if (d < bestD) {
                bestD = d;
                best = pl;
            }
        }
        return best;
    }

    public static Vec3d nearestDecoy(double x, double z, double max) {
        Vec3d best = null;
        double bd = max;
        for (Plush d : DECOYS) {
            double dist = Math.hypot(d.x - x, d.z - z);
            if (dist < bd) {
                bd = dist;
                best = new Vec3d(d.x, d.y, d.z);
            }
        }
        return best;
    }

    public static boolean holdingFeed(PlayerEntity p) {
        String id = PlushConfig.get().feedItem;
        return matches(p.getMainHandStack(), id) || matches(p.getOffHandStack(), id);
    }

    private static boolean matches(ItemStack s, String id) {
        return !s.isEmpty() && Registries.ITEM.getId(s.getItem()).toString().equals(id);
    }

    public static void tick(MinecraftClient mc) {
        ClientWorld w = mc.world;
        if (w == null) {
            PLUSHES.clear();
            DECOYS.clear();
            lastWorld = null;
            return;
        }
        if (w != lastWorld) {
            PLUSHES.clear();
            DECOYS.clear();
            lastWorld = w;
        }
        PlushConfig cfg = PlushConfig.get();
        boolean dancing = PlushieMod.isDancing();
        int age = PlushieMod.danceAge();
        long time = w.getTime();

        for (Plush p : PLUSHES) {
            double hop = 0;
            float sq = 1f + 0.015f * (float) Math.sin(time * 0.1 + p.phase);
            float yaw = (float) Math.sin(time * 0.05 + p.phase) * 8f;
            if (dancing) {
                double ph = age * 0.34;
                double a = Math.abs(Math.sin(ph));
                hop = a * cfg.hopHeight;
                sq = 1f + 0.12f * (float) (a - 0.5);
                yaw = (float) Math.sin(age * 0.17) * 25f;
            }
            apply(p, hop, p.scale * sq, yaw);
        }
        for (Plush d : DECOYS) {
            apply(d, 0, d.scale, 0f);
        }
    }

    private static void apply(Plush p, double hop, float scale, float yaw) {
        ArmorStandEntity s = p.stand;
        s.setPosition(p.x, p.y + hop, p.z);
        s.setYaw(yaw);
        s.setBodyYaw(yaw);
        EntityAttributeInstance sa = s.getAttributeInstance(EntityAttributes.SCALE);
        if (sa != null && Math.abs(sa.getBaseValue() - scale) > 0.001) sa.setBaseValue(scale);
    }
}
