package com.example.meteorevent.task;

import com.example.meteorevent.EventSettings;
import com.example.meteorevent.MeteorEventPlugin;
import com.example.meteorevent.MeteorManager;
import com.example.meteorevent.util.DebrisUtil;
import io.papermc.paper.entity.LookAnchor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tek bir meteor dususunu bastan sona yoneten gorev:
 *  1) Buyutulmus bir BlockDisplay (magma_block) spawn eder.
 *  2) Her tick, spawn -> impact arasinda konumu enterpole eder (client-side
 *     yumusak gecis icin setTeleportDuration/setInterpolationDuration kullanir).
 *  3) Yaricap icindeki oyunculari "dondurup" kameralarini meteora kilitler.
 *  4) Yere carpinca display'i kaldirir, patlama efekti + enkaz sacilimi yapar.
 */
public class MeteorTask extends BukkitRunnable {

    private final MeteorEventPlugin plugin;
    private final MeteorManager manager;
    private final EventSettings settings;

    private final Location spawnLocation;
    private final Location impactLocation;

    private final BlockDisplay display;
    private final float sizeMultiplier;
    private final int totalTicks;
    private final int stepTicks;

    private int elapsedTicks = 0;
    private float currentSpinAngle = 0f;

    /** Bu gorevin dondurdugu oyuncular; impact/iptal aninda hepsi serbest birakilir. */
    private final Set<UUID> managedFrozen = new HashSet<>();

    private boolean impacted = false;

    public MeteorTask(MeteorEventPlugin plugin, MeteorManager manager, EventSettings settings,
                       Location spawnLocation, Location impactLocation) {
        this.plugin = plugin;
        this.manager = manager;
        this.settings = settings;
        this.spawnLocation = spawnLocation.clone();
        this.impactLocation = impactLocation.clone();

        this.totalTicks = Math.max(1, settings.fallDurationTicks());
        this.stepTicks = Math.max(1, settings.interpolationStepTicks());

        double range = settings.sizeMultiplierMax() - settings.sizeMultiplierMin();
        this.sizeMultiplier = (float) (settings.sizeMultiplierMin() + Math.random() * range);

        this.display = spawnDisplay();
    }

    private BlockDisplay spawnDisplay() {
        BlockDisplay entity = spawnLocation.getWorld().spawn(spawnLocation, BlockDisplay.class, e -> {
            e.setBlock(Material.MAGMA_BLOCK.createBlockData());
            e.setBillboard(Display.Billboard.FIXED);
            e.setShadowRadius(4.0f);
            e.setShadowStrength(1.0f);
            e.setBrightness(new Display.Brightness(15, 15)); // gokten dusen kor magma - tam parlak

            e.setTeleportDuration(stepTicks);
            e.setInterpolationDuration(stepTicks);
            e.setInterpolationDelay(0);

            e.setTransformation(buildTransformation(0f));
        });
        return entity;
    }

    /**
     * Blogun (varsayilan 1x1x1) merkez etrafinda buyutulmesini ve donmesini
     * saglayan Transformation matrisini kurar.
     */
    private Transformation buildTransformation(float spinAngle) {
        // Blok orijini (0,0,0)-(1,1,1) oldugu icin, buyutulmus haliyle merkezi
        // sabit tutmak icin -( (scale-1) / 2 ) kadar geri kaydiriyoruz.
        float offset = -(sizeMultiplier - 1f) / 2f;
        Vector3f translation = new Vector3f(offset, offset, offset);
        Vector3f scale = new Vector3f(sizeMultiplier, sizeMultiplier, sizeMultiplier);

        Quaternionf spin = new Quaternionf(new AxisAngle4f(spinAngle, 0.3f, 1f, 0.15f));

        return new Transformation(translation, spin, scale, new Quaternionf());
    }

    @Override
    public void run() {
        if (impacted) {
            // guvenlik: gorev bir sekilde tekrar tetiklenirse hicbir sey yapma
            cancel();
            return;
        }

        elapsedTicks++;

        // --- Konum enterpolasyonu (yalnizca her stepTicks'te bir gonderilir) ---
        if (elapsedTicks % stepTicks == 0 || elapsedTicks >= totalTicks) {
            double fraction = Math.min(1.0, (double) elapsedTicks / totalTicks);
            Location interpolated = lerp(spawnLocation, impactLocation, fraction);

            display.setTeleportDuration(stepTicks);
            display.teleport(interpolated);

            currentSpinAngle += settings.spinSpeed() * stepTicks;
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(stepTicks);
            display.setTransformation(buildTransformation(currentSpinAngle));
        }

        // --- Kamera kilidi / dondurma (her tick, akicilik icin) ---
        if (settings.cameraEnabled()) {
            boolean releasePhase = (totalTicks - elapsedTicks) <= settings.releaseBeforeImpactTicks();
            updateFrozenPlayers(releasePhase);
        }

        // --- Carpisma kontrolu ---
        if (elapsedTicks >= totalTicks) {
            impact();
        }
    }

    private void updateFrozenPlayers(boolean releasePhase) {
        Location currentMeteorLoc = display.getLocation();

        Set<Player> nearby = new HashSet<>(
                currentMeteorLoc.getWorld().getNearbyPlayers(currentMeteorLoc, settings.cameraRadius())
        );

        // Yaricaptan cikan ya da release fazina girilen oyuncular serbest.
        for (UUID uuid : new HashSet<>(managedFrozen)) {
            Player p = plugin.getServer().getPlayer(uuid);
            boolean stillNearby = p != null && nearby.contains(p);
            if (p == null || releasePhase || !stillNearby) {
                if (p != null) {
                    manager.unfreeze(p);
                }
                managedFrozen.remove(uuid);
            }
        }

        if (releasePhase) {
            return; // yeni oyuncu dondurma, sadece serbest birakma fazindayiz
        }

        for (Player p : nearby) {
            if (!managedFrozen.contains(p.getUniqueId())) {
                manager.freeze(p);
                managedFrozen.add(p.getUniqueId());
            }
            // Her tick kamerayi zorla meteora kilitle.
            p.lookAt(currentMeteorLoc, LookAnchor.EYES, LookAnchor.EYES);
        }
    }

    private void impact() {
        impacted = true;

        Location impactCenter = impactLocation.clone();
        display.remove();

        // Tum dondurulmus oyunculari serbest birak.
        for (UUID uuid : managedFrozen) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                manager.unfreeze(p);
            }
        }
        managedFrozen.clear();

        playImpactEffects(impactCenter);
        DebrisUtil.scatterDebris(plugin, impactCenter, settings);

        if (settings.createExplosion()) {
            impactCenter.getWorld().createExplosion(
                    impactCenter,
                    settings.explosionPower(),
                    settings.explosionSetFire(),
                    settings.explosionBreakBlocks()
            );
        }

        manager.onTaskFinished(this);
        cancel();
    }

    private void playImpactEffects(Location center) {
        center.getWorld().spawnParticle(
                Particle.EXPLOSION_EMITTER,
                center.clone().add(0, 1, 0),
                Math.max(1, settings.impactParticleCount())
        );
        center.getWorld().spawnParticle(
                Particle.LAVA,
                center.clone().add(0, 1, 0),
                40, 2.5, 1.0, 2.5, 0.1
        );
        center.getWorld().spawnParticle(
                Particle.LARGE_SMOKE,
                center.clone().add(0, 1, 0),
                60, 3.0, 1.5, 3.0, 0.05
        );

        center.getWorld().playSound(
                center,
                Sound.ENTITY_GENERIC_EXPLODE,
                SoundCategory.HOSTILE,
                (float) settings.impactSoundVolume(),
                (float) settings.impactSoundPitch()
        );
        center.getWorld().playSound(
                center,
                Sound.ENTITY_WITHER_BREAK_BLOCK,
                SoundCategory.HOSTILE,
                (float) settings.impactSoundVolume() * 0.6f,
                0.6f
        );
    }

    /** Iki noktayi lineer olarak enterpole eder (dunya ayni oldugu varsayilir). */
    private Location lerp(Location from, Location to, double fraction) {
        double x = from.getX() + (to.getX() - from.getX()) * fraction;
        double y = from.getY() + (to.getY() - from.getY()) * fraction;
        double z = from.getZ() + (to.getZ() - from.getZ()) * fraction;
        return new Location(from.getWorld(), x, y, z);
    }

    /** Disaridan (komut / plugin kapanisi) zorla iptal edilmek istendiginde cagrilir. */
    public void cancelAndCleanup() {
        if (impacted) {
            return;
        }
        impacted = true;
        if (!display.isDead()) {
            display.remove();
        }
        for (UUID uuid : managedFrozen) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                manager.unfreeze(p);
            }
        }
        managedFrozen.clear();
        manager.onTaskFinished(this);
        try {
            cancel();
        } catch (IllegalStateException ignored) {
            // gorev zaten calismiyor olabilir, sorun degil
        }
    }
}
