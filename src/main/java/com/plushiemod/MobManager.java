package com.plushiemod;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.Flutterer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.FlyingEntity;
import net.minecraft.entity.mob.WaterCreatureEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Spawns real mob entities ONLY inside the local ClientWorld (they never exist on the server)
 * and moves them with a tiny client-side "AI".
 */
public class MobManager {
    private static final int MAX_MOBS = 160;
    private static final Random RNG = new Random();
    private static final List<FakeMob> MOBS = new ArrayList<>();
    private static ClientWorld lastWorld;
    private static int nextId = -1_000_000; // negative ids never clash with server entity ids
    private static int rebuildIn = -1;

    public static int newId() {
        return nextId--;
    }

    /** Called by the GUI whenever mob settings change (debounced). */
    public static void requestRebuild() {
        rebuildIn = 12;
    }

    public static int count() {
        return MOBS.size();
    }

    /** Finds the floor height at (x, z) near refY. */
    public static double ground(World w, double x, double refY, double z) {
        int bx = MathHelper.floor(x);
        int bz = MathHelper.floor(z);
        int start = MathHelper.floor(refY) + 2;
        int stop = Math.max(w.getBottomY() + 1, start - 14);
        for (int y = start; y > stop; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            BlockPos below = pos.down();
            BlockState bs = w.getBlockState(below);
            VoxelShape shapeBelow = bs.getCollisionShape(w, below);
            if (shapeBelow.isEmpty()) continue;
            VoxelShape here = w.getBlockState(pos).getCollisionShape(w, pos);
            if (!here.isEmpty()) continue;
            return below.getY() + shapeBelow.getMax(Direction.Axis.Y);
        }
        return refY;
    }

    public static void tick(MinecraftClient mc) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        if (w == null || p == null) {
            MOBS.clear();
            lastWorld = null;
            return;
        }
        PlushConfig cfg = PlushConfig.get();
        if (w != lastWorld) {
            lastWorld = w;
            MOBS.clear();
            spawnAll(w, p);
        }
        if (rebuildIn >= 0 && --rebuildIn < 0) {
            spawnAll(w, p);
        }
        boolean feeding = PlushManager.holdingFeed(p);
        boolean dancing = PlushieMod.isDancing();
        int age = PlushieMod.danceAge();
        for (FakeMob m : MOBS) {
            m.update(w, p, cfg, feeding, dancing, age);
        }
    }

    private static void despawnAll(ClientWorld w) {
        for (FakeMob m : MOBS) {
            w.removeEntity(m.entity.getId(), Entity.RemovalReason.DISCARDED);
        }
        MOBS.clear();
    }

    private static void spawnAll(ClientWorld w, ClientPlayerEntity p) {
        despawnAll(w);
        PlushConfig cfg = PlushConfig.get();
        if (!cfg.mobsEnabled) return;
        int total = 0;
        for (Map.Entry<String, PlushConfig.MobEntry> en : new ArrayList<>(cfg.mobs.entrySet())) {
            Identifier id = Identifier.tryParse(en.getKey());
            if (id == null || !Registries.ENTITY_TYPE.containsId(id)) continue;
            EntityType<?> type = Registries.ENTITY_TYPE.get(id);
            PlushConfig.MobEntry me = en.getValue();
            for (int i = 0; i < me.count && total < MAX_MOBS; i++) {
                FakeMob m = create(w, p, type, me, cfg);
                if (m != null) {
                    MOBS.add(m);
                    total++;
                }
            }
        }
    }

    private static FakeMob create(ClientWorld w, ClientPlayerEntity p, EntityType<?> type,
                                  PlushConfig.MobEntry me, PlushConfig cfg) {
        Entity raw;
        try {
            raw = type.create(w, SpawnReason.COMMAND);
        } catch (Throwable t) {
            return null;
        }
        if (!(raw instanceof LivingEntity le)) return null;

        le.setId(newId());
        le.noClip = true;               // never pushes the player around
        le.setNoGravity(true);
        le.setInvulnerable(true);
        le.setSilent(true);

        if (me.baby) {
            if (le instanceof PassiveEntity pe) pe.setBaby(true);
            else if (le instanceof ZombieEntity ze) ze.setBaby(true);
        }

        EntityAttributeInstance sa = le.getAttributeInstance(EntityAttributes.SCALE);
        if (sa != null) {
            sa.setBaseValue(MathHelper.clamp(me.scale, 0.0625f, 16f));
            le.calculateDimensions();
        }

        if (le instanceof SheepEntity sh) {
            if (cfg.sheepColor == -2) {
                sh.setCustomName(Text.literal("jeb_"));   // vanilla rainbow sheep trick
                sh.setCustomNameVisible(false);
            } else if (cfg.sheepColor == -1) {
                sh.setColor(DyeColor.byId(RNG.nextInt(16)));
            } else {
                sh.setColor(DyeColor.byId(MathHelper.clamp(cfg.sheepColor, 0, 15)));
            }
        }

        boolean flyer = le instanceof Flutterer || le instanceof FlyingEntity || le instanceof WaterCreatureEntity
                || type == EntityType.BAT || type == EntityType.BLAZE || type == EntityType.VEX
                || type == EntityType.ENDER_DRAGON;

        double speed = 0.05;
        EntityAttributeInstance ms = le.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
        if (ms != null) speed = MathHelper.clamp(ms.getValue() * 0.45, 0.02, 0.18);

        FakeMob m = new FakeMob(le, flyer, speed);
        m.placeAround(w, p, cfg);
        le.refreshPositionAndAngles(m.x, m.groundY, m.z, m.yaw, 0f);
        w.addEntity(le);
        return m;
    }

    // =====================================================================
    private static class FakeMob {
        final LivingEntity entity;
        final boolean flyer;
        final double speed;
        final float phase = RNG.nextFloat() * 6.2831f;
        final double danceAngle = RNG.nextDouble() * Math.PI * 2;
        final double danceRadius = 1.6 + RNG.nextDouble() * 2.2;

        double x, z, groundY;
        double tx, tz;
        boolean hasTarget = false;
        int idle = RNG.nextInt(60);
        float yaw = RNG.nextFloat() * 360f;
        int age = 0;

        FakeMob(LivingEntity e, boolean flyer, double speed) {
            this.entity = e;
            this.flyer = flyer;
            this.speed = speed;
        }

        void placeAround(ClientWorld w, ClientPlayerEntity p, PlushConfig cfg) {
            double ang = RNG.nextDouble() * Math.PI * 2;
            double r = 2 + RNG.nextDouble() * Math.max(1, cfg.radius - 2);
            x = p.getX() + Math.cos(ang) * r;
            z = p.getZ() + Math.sin(ang) * r;
            groundY = ground(w, x, p.getY(), z);
            hasTarget = false;
        }

        void update(ClientWorld w, ClientPlayerEntity p, PlushConfig cfg, boolean feeding,
                    boolean dancing, int danceAge) {
            age++;
            double px = p.getX(), pz = p.getZ();
            double distP = Math.hypot(px - x, pz - z);

            if (distP > cfg.radius + 18) {
                placeAround(w, p, cfg);
                distP = Math.hypot(px - x, pz - z);
            }

            double gx = x, gz = z, stop = 0.3, mul = 1.0;
            boolean go = false, lookPlayer = false, eating = false;
            Vec3d decoy = null;

            if (dancing) {
                gx = px + Math.cos(danceAngle) * danceRadius;
                gz = pz + Math.sin(danceAngle) * danceRadius;
                go = true;
                mul = 2.4;
                lookPlayer = true;
            } else if (feeding && distP < cfg.temptRadius) {
                gx = px;
                gz = pz;
                stop = 2.2;
                go = distP > stop;
                mul = 1.7;
                lookPlayer = true;
            } else if ((decoy = PlushManager.nearestDecoy(x, z, cfg.temptRadius)) != null) {
                gx = decoy.x + Math.cos(danceAngle) * 0.9;
                gz = decoy.z + Math.sin(danceAngle) * 0.9;
                stop = 0.35;
                double d = Math.hypot(gx - x, gz - z);
                go = d > stop;
                mul = 1.4;
                eating = !go;
            } else {
                if (distP > cfg.radius + 4) {
                    hasTarget = true;
                    double ang = RNG.nextDouble() * Math.PI * 2;
                    tx = px + Math.cos(ang) * 3;
                    tz = pz + Math.sin(ang) * 3;
                    idle = 0;
                    mul = 1.6;
                }
                if (idle > 0) {
                    idle--;
                } else {
                    if (!hasTarget) {
                        double ang = RNG.nextDouble() * Math.PI * 2;
                        double r = RNG.nextDouble() * cfg.radius;
                        tx = px + Math.cos(ang) * r;
                        tz = pz + Math.sin(ang) * r;
                        hasTarget = true;
                    }
                    gx = tx;
                    gz = tz;
                    go = true;
                    if (Math.hypot(gx - x, gz - z) <= stop) {
                        hasTarget = false;
                        idle = 30 + RNG.nextInt(100);
                        go = false;
                    }
                }
            }

            boolean moving = false;
            float targetYaw = yaw;
            if (go) {
                double dx = gx - x, dz = gz - z;
                double d = Math.hypot(dx, dz);
                if (d > 0.001) {
                    double step = Math.min(speed * cfg.wanderSpeed * mul, d);
                    double nx = x + dx / d * step;
                    double nz = z + dz / d * step;
                    double ng = ground(w, nx, groundY + 0.5, nz);
                    if (ng - groundY > 1.1 || groundY - ng > 4.0) {
                        hasTarget = false;
                        idle = 10;
                    } else {
                        x = nx;
                        z = nz;
                        groundY += (ng - groundY) * 0.5;
                        moving = true;
                        targetYaw = (float) (MathHelper.atan2(dz, dx) * 57.29577951308232) - 90f;
                    }
                }
            } else {
                groundY += (ground(w, x, groundY + 0.5, z) - groundY) * 0.5;
            }

            if (!moving) {
                if (lookPlayer) {
                    targetYaw = (float) (MathHelper.atan2(pz - z, px - x) * 57.29577951308232) - 90f;
                } else if (decoy != null) {
                    targetYaw = (float) (MathHelper.atan2(decoy.z - z, decoy.x - x) * 57.29577951308232) - 90f;
                }
            }
            yaw += MathHelper.wrapDegrees(targetYaw - yaw) * 0.25f;

            double hop = 0;
            float shimmy = 0f;
            if (dancing) {
                double ph = danceAge * 0.3 + phase;
                hop = Math.abs(Math.sin(ph)) * cfg.hopHeight * 0.55;
                shimmy = (float) Math.sin(danceAge * 0.4 + phase) * 22f;
            }
            double fly = flyer ? 1.2 + Math.sin(age * 0.1 + phase) * 0.2 : 0;

            entity.setPosition(x, groundY + hop + fly, z);
            float ry = yaw + shimmy;
            entity.setYaw(ry);
            entity.setBodyYaw(ry);
            entity.setHeadYaw(ry);
            entity.setPitch(eating ? (float) (22 + Math.sin(age * 0.35) * 14) : 0f);
        }
    }
}
