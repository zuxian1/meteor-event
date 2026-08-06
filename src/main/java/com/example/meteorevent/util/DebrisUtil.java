package com.example.meteorevent.util;

import com.example.meteorevent.EventSettings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.FallingBlock;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Carpisma merkezinden disariya, kure yuzeyine dagilmis rastgele yon
 * vektorleriyle enkaz (tas/lav bloklari) sacan yardimci sinif.
 *
 * Matematik: kure uzerinde rastgele bir nokta secmek icin
 *   azimuth (theta)  : 0 - 2*PI arasi tam daire
 *   elevation (phi)  : 5deg - 80deg arasi (tamamen yataya veya dikeye
 *                       cakismasin diye sinirlandirilmis), boylece parcalar
 *                       gorsel olarak "patlayarak yukari-disari firlamis" gibi durur.
 * Bu iki aciyla birim vektor hesaplanip rastgele bir hizla carpiliyor.
 */
public final class DebrisUtil {

    /** Bu tag'e sahip FallingBlock'lar, DebrisLandingListener tarafindan
     *  "gercek blok olarak yere yerlesmesi yasak" olarak isaretlenir. */
    public static final String DEBRIS_KEY = "meteorevent_debris_no_place";

    private DebrisUtil() {
    }

    public static void scatterDebris(Plugin plugin, Location center, EventSettings settings) {
        List<Material> materials = settings.debrisMaterials();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        int count = Math.max(0, settings.debrisCount());
        int lifetime = Math.max(1, settings.debrisLifetimeTicks());
        boolean placeBlocks = settings.debrisPlaceBlocks();

        NamespacedKey debrisKey = new NamespacedKey(plugin, DEBRIS_KEY);

        for (int i = 0; i < count; i++) {
            double theta = random.nextDouble(0, Math.PI * 2.0);
            double phiDeg = random.nextDouble(5.0, 80.0);
            double phi = Math.toRadians(phiDeg);

            // Kure yuzeyi -> Kartezyen birim vektor (Y = yukari eksen).
            double dx = Math.cos(theta) * Math.cos(phi);
            double dy = Math.sin(phi);
            double dz = Math.sin(theta) * Math.cos(phi);

            double speed = random.nextDouble(settings.debrisMinSpeed(), settings.debrisMaxSpeed());
            Vector velocity = new Vector(dx, dy, dz).multiply(speed);

            Material material = materials.get(random.nextInt(materials.size()));

            // Merkezden hafif rastgele bir baslangic ofseti; hepsi tam ayni
            // noktadan cikmasin diye (gorsel olarak daha gercekci patlama).
            Location spawnAt = center.clone().add(
                    random.nextDouble(-0.4, 0.4),
                    0.3 + random.nextDouble(0, 0.6),
                    random.nextDouble(-0.4, 0.4)
            );

            FallingBlock block = center.getWorld().spawnFallingBlock(spawnAt, material.createBlockData());
            block.setVelocity(velocity);
            block.setDropItem(false);
            block.setHurtEntities(false);

            if (!placeBlocks) {
                // Griefsiz mod: enkaz gorsel bir efekt olarak kalir. Zamanlamaya
                // guvenmek yerine (erken yere carpabilir), varligi PDC ile
                // isaretliyoruz; DebrisLandingListener bu isareti gorunce
                // EntityChangeBlockEvent'i iptal ederek gercek blok olusumunu
                // kesin olarak engeller. Zamanlayici ise sadece havada uzun
                // sure asili kalmasin diye bir guvenlik agi.
                block.getPersistentDataContainer().set(debrisKey, PersistentDataType.BYTE, (byte) 1);
                block.setCancelDrop(true);
                scheduleRemoval(plugin, block, lifetime);
            }
            // placeBlocks = true ise hicbir sey yapmiyoruz; blok normal sekilde
            // yere dusup kalici hale gelecek (grief riskini bilerek kabul eden
            // sunucular icin).
        }
    }

    private static void scheduleRemoval(Plugin plugin, FallingBlock block, int lifetimeTicks) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!block.isDead()) {
                block.remove();
            }
        }, lifetimeTicks);
    }
}
