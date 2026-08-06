package com.example.meteorevent;

import com.example.meteorevent.command.MeteorCommand;
import com.example.meteorevent.listener.DebrisLandingListener;
import com.example.meteorevent.listener.FreezeListener;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * MeteorEvent - Ana plugin sinifi.
 *
 * Sorumluluklari:
 *  - config.yml yukleme/yeniden yukleme
 *  - MeteorManager'i olusturup yasam dongusunu yonetme
 *  - komut ve listener kaydi
 *  - config'te belirtilmisse otomatik (periyodik) etkinlik zamanlayicisi
 */
public final class MeteorEventPlugin extends JavaPlugin {

    private MeteorManager meteorManager;
    private EventSettings settings;

    private int autoTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.settings = EventSettings.fromConfig(getConfig());

        this.meteorManager = new MeteorManager(this, settings);

        // Kamera kilidi / donma mekanigi icin hareket dinleyicisi.
        Bukkit.getPluginManager().registerEvents(new FreezeListener(meteorManager), this);
        // Enkazin kalici blok olarak yere yerlesmesini kesin olarak engeller.
        Bukkit.getPluginManager().registerEvents(new DebrisLandingListener(this), this);

        // /meteor komutu
        MeteorCommand commandExecutor = new MeteorCommand(this, meteorManager);
        var meteorPluginCommand = getCommand("meteor");
        if (meteorPluginCommand != null) {
            meteorPluginCommand.setExecutor(commandExecutor);
            meteorPluginCommand.setTabCompleter(commandExecutor);
        }

        scheduleAutoEvents();

        getLogger().info("MeteorEvent aktif edildi. (auto-interval=" + settings.autoIntervalMinutes() + " dk)");
    }

    @Override
    public void onDisable() {
        if (autoTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autoTaskId);
        }
        if (meteorManager != null) {
            meteorManager.shutdown();
        }
        getLogger().info("MeteorEvent devre disi birakildi.");
    }

    /** config.yml'i diskten yeniden okur ve tum bagimli bilesenlere yansitir. */
    public void reload() {
        reloadConfig();
        this.settings = EventSettings.fromConfig(getConfig());
        this.meteorManager.updateSettings(settings);

        if (autoTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autoTaskId);
            autoTaskId = -1;
        }
        scheduleAutoEvents();
    }

    private void scheduleAutoEvents() {
        int minutes = settings.autoIntervalMinutes();
        if (minutes <= 0) {
            return; // otomatik tetikleme kapali
        }
        long periodTicks = minutes * 60L * 20L;

        autoTaskId = Bukkit.getScheduler().runTaskTimer(this, this::triggerRandomAutoEvent, periodTicks, periodTicks)
                .getTaskId();
    }

    private void triggerRandomAutoEvent() {
        List<World> candidateWorlds = resolveConfiguredWorlds();
        if (candidateWorlds.isEmpty()) {
            return;
        }
        World world = candidateWorlds.get((int) (Math.random() * candidateWorlds.size()));

        int radius = settings.autoMaxRadius();
        double angle = Math.random() * Math.PI * 2.0;
        double dist = Math.random() * radius;
        double x = Math.cos(angle) * dist;
        double z = Math.sin(angle) * dist;

        meteorManager.startMeteorEvent(world, x, z);
    }

    private List<World> resolveConfiguredWorlds() {
        List<String> names = settings.worlds();
        List<World> result = new ArrayList<>();
        if (names.isEmpty()) {
            result.addAll(Bukkit.getWorlds());
            return result;
        }
        for (String name : names) {
            World w = Bukkit.getWorld(name);
            if (w != null) {
                result.add(w);
            }
        }
        return result;
    }

    public MeteorManager getMeteorManager() {
        return meteorManager;
    }

    public EventSettings getSettings() {
        return settings;
    }
}
