package com.example.meteorevent.listener;

import com.example.meteorevent.util.DebrisUtil;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * DebrisUtil'in "place-blocks: false" modunda isaretledigi FallingBlock'lar
 * yere degdigi an gercek bir bloga donusmeye calisir; bu olayi burada
 * iptal ederek zamanlamadan bagimsiz, %100 griefsiz bir sonuc garanti ederiz.
 */
public class DebrisLandingListener implements Listener {

    private final NamespacedKey debrisKey;

    public DebrisLandingListener(Plugin plugin) {
        this.debrisKey = new NamespacedKey(plugin, DebrisUtil.DEBRIS_KEY);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFallingBlockLand(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock fallingBlock)) {
            return;
        }
        Byte marker = fallingBlock.getPersistentDataContainer()
                .get(debrisKey, PersistentDataType.BYTE);
        if (marker != null && marker == (byte) 1) {
            event.setCancelled(true);
            fallingBlock.remove();
        }
    }
}
