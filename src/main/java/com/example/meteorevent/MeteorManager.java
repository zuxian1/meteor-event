package com.example.meteorevent;

import com.example.meteorevent.task.MeteorTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aktif meteor etkinliklerinin yasam dongusunu yonetir.
 * Ayni anda birden fazla meteor dusebilir; her biri kendi MeteorTask'inda calisir.
 */
public class MeteorManager {

    private final MeteorEventPlugin plugin;
    private EventSettings settings;

    /** Su an calismakta olan gorevler (iptal/temizlik icin referans tutulur). */
    private final Set<MeteorTask> activeTasks = ConcurrentHashMap.newKeySet();

    /**
     * Su anda "dondurulmus" (hareketi kilitli, kamerasi meteora sabitlenmis) oyuncular.
     * FreezeListener bu seti okuyarak PlayerMoveEvent'i iptal eder.
     */
    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();

    public MeteorManager(MeteorEventPlugin plugin, EventSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public void updateSettings(EventSettings settings) {
        this.settings = settings;
    }

    public EventSettings getSettings() {
        return settings;
    }

    /**
     * Verilen dunyada, verilen X/Z koordinatinin ustunden bir meteor dusurur.
     * Zemin yuksekligi otomatik hesaplanir (world.getHighestBlockYAt).
     */
    public MeteorTask startMeteorEvent(World world, double x, double z) {
        int groundY = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
        Location impact = new Location(world, x, groundY, z);
        Location spawn = impact.clone().add(0, settings.spawnHeightAboveGround(), 0);

        MeteorTask task = new MeteorTask(plugin, this, settings, spawn, impact);
        activeTasks.add(task);
        task.runTaskTimer(plugin, 0L, 1L);
        return task;
    }

    /** Sunucudaki tum aktif meteor etkinliklerini derhal iptal eder ve temizler. */
    public void stopAll() {
        for (MeteorTask task : activeTasks) {
            task.cancelAndCleanup();
        }
        activeTasks.clear();
        frozenPlayers.clear();
    }

    public void shutdown() {
        stopAll();
    }

    /** MeteorTask, kendi isi bittiginde bunu cagirarak kayittan dusurur. */
    public void onTaskFinished(MeteorTask task) {
        activeTasks.remove(task);
    }

    // ---- Dondurma/kamera durumu (FreezeListener tarafindan okunur) ----

    public void freeze(Player player) {
        frozenPlayers.add(player.getUniqueId());
    }

    public void unfreeze(Player player) {
        frozenPlayers.remove(player.getUniqueId());
    }

    public boolean isFrozen(Player player) {
        return frozenPlayers.contains(player.getUniqueId());
    }

    public Set<UUID> getFrozenPlayers() {
        return Collections.unmodifiableSet(frozenPlayers);
    }

    public boolean hasActiveMeteors() {
        return !activeTasks.isEmpty();
    }
}
