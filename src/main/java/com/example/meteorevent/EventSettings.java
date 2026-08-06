package com.example.meteorevent;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * config.yml icerigini tek bir immutable nesnede toplar; boylece
 * MeteorTask/MeteorManager gibi siniflar dogrudan FileConfiguration'a
 * bagimli olmaz ve test edilmesi/okunmasi kolaylasir.
 */
public record EventSettings(
        int autoIntervalMinutes,
        List<String> worlds,
        int autoMaxRadius,

        int spawnHeightAboveGround,
        int fallDurationTicks,
        int interpolationStepTicks,
        double sizeMultiplierMin,
        double sizeMultiplierMax,
        double spinSpeed,

        boolean cameraEnabled,
        int cameraRadius,
        int releaseBeforeImpactTicks,

        boolean createExplosion,
        float explosionPower,
        boolean explosionSetFire,
        boolean explosionBreakBlocks,
        int impactParticleCount,
        double impactSoundVolume,
        double impactSoundPitch,

        int debrisCount,
        double debrisMinSpeed,
        double debrisMaxSpeed,
        int debrisLifetimeTicks,
        boolean debrisPlaceBlocks,
        List<Material> debrisMaterials
) {

    public static EventSettings fromConfig(FileConfiguration cfg) {
        List<Material> materials = new ArrayList<>();
        for (String raw : cfg.getStringList("debris.materials")) {
            Material mat = Material.matchMaterial(raw);
            if (mat != null) {
                materials.add(mat);
            }
        }
        if (materials.isEmpty()) {
            materials.add(Material.STONE);
        }

        return new EventSettings(
                cfg.getInt("event.auto-interval-minutes", 30),
                new ArrayList<>(cfg.getStringList("event.worlds")),
                cfg.getInt("event.auto-max-radius", 500),

                cfg.getInt("fall.spawn-height-above-ground", 140),
                cfg.getInt("fall.duration-ticks", 100),
                Math.max(1, cfg.getInt("fall.interpolation-step-ticks", 2)),
                cfg.getDouble("fall.size-multiplier-min", 15.0),
                cfg.getDouble("fall.size-multiplier-max", 20.0),
                cfg.getDouble("fall.spin-speed", 0.05),

                cfg.getBoolean("camera.enabled", true),
                cfg.getInt("camera.radius", 60),
                cfg.getInt("camera.release-before-impact-ticks", 5),

                cfg.getBoolean("impact.create-explosion", false),
                (float) cfg.getDouble("impact.explosion-power", 0.0),
                cfg.getBoolean("impact.explosion-set-fire", false),
                cfg.getBoolean("impact.explosion-break-blocks", false),
                cfg.getInt("impact.particle-count", 4),
                cfg.getDouble("impact.sound-volume", 3.0),
                cfg.getDouble("impact.sound-pitch", 0.8),

                cfg.getInt("debris.count", 60),
                cfg.getDouble("debris.min-speed", 0.4),
                cfg.getDouble("debris.max-speed", 1.4),
                cfg.getInt("debris.lifetime-ticks", 60),
                cfg.getBoolean("debris.place-blocks", false),
                materials
        );
    }
}
