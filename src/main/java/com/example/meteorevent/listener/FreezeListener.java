package com.example.meteorevent.listener;

import com.example.meteorevent.MeteorManager;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;

/**
 * MeteorManager.isFrozen(player) true oldugu surece oyuncunun konumunu
 * (x/y/z) kilitler fakat bakis yonunu (yaw/pitch) serbest birakir; ciddi
 * MeteorTask zaten her tick player.lookAt(...) ile kamerayi meteora
 * zorluyor, bu yuzden buradaki "serbest yaw/pitch" onun uzerine yazilir.
 */
public class FreezeListener implements Listener {

    private final MeteorManager manager;

    public FreezeListener(MeteorManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!manager.isFrozen(event.getPlayer())) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        boolean translated = from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ();

        if (!translated) {
            return; // sadece bakis degisti, buna izin ver (lookAt zaten bunu yapiyor)
        }

        // Konumu eski haline dondur ama oyuncunun (bizim zorladigimiz) bakis
        // acisini koru, boylece kamera kilidi bozulmaz.
        Location locked = from.clone();
        locked.setYaw(to.getYaw());
        locked.setPitch(to.getPitch());
        event.setTo(locked);
    }

    @EventHandler(ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (manager.isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onToggleSprint(PlayerToggleSprintEvent event) {
        if (manager.isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
