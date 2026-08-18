package com.zuxian.metin;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public final class ZuxianMetinPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String, MetinNode> nodes = new LinkedHashMap<>();
    private final Map<UUID, Long> credits = new HashMap<>();
    private final Map<UUID, Long> lastHit = new HashMap<>();
    private final Map<UUID, RewardSession> rewardSessions = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;
    private boolean eventActive;
    private long nextEventAt;
    private long eventEndAt;
    private BukkitTask ticker;

    @Override public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("metin")).setExecutor(this);
        Objects.requireNonNull(getCommand("metin")).setTabCompleter(this);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new MetinExpansion(this).register();
            getLogger().info("PlaceholderAPI aktif: %zuxianmetin_credits%");
        }
        long now = System.currentTimeMillis();
        if (nextEventAt <= 0L) nextEventAt = now + intervalMillis();
        if (eventActive) {
            if (eventEndAt > now) resumeEventVisuals(); else stopEvent(false);
        }
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        saveData();
    }

    @Override public void onDisable() {
        if (ticker != null) ticker.cancel();
        for (MetinNode node : nodes.values()) removeHologram(node);
        for (RewardSession session : new ArrayList<>(rewardSessions.values())) dropRemaining(session.player(), session.inventory());
        rewardSessions.clear();
        saveData();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (eventActive) {
            if (now >= eventEndAt) stopEvent(true);
        } else if (now >= nextEventAt) startEvent(false);
    }

    private long intervalMillis() { return getConfig().getLong("event.interval-hours", 4L) * 3600000L; }
    private long durationMillis() { return getConfig().getLong("event.duration-minutes", 60L) * 60000L; }
    public long getCredits(UUID uuid) { return credits.getOrDefault(uuid, 0L); }
    public boolean isEventActive() { return eventActive; }
    public long getNextEventAt() { return nextEventAt; }

    private void startEvent(boolean admin) {
        if (eventActive) return;
        eventActive = true;
        long now = System.currentTimeMillis();
        eventEndAt = now + durationMillis();
        nextEventAt = now + intervalMillis();
        Material activeMat = material("metin.active-material", Material.CRYING_OBSIDIAN);
        double maxHp = getConfig().getDouble("metin.max-health", 1000.0);
        for (MetinNode node : nodes.values()) {
            node.currentHealth = maxHp;
            node.damage.clear();
            Block block = node.block();
            if (block != null) block.setType(activeMat, false);
            spawnHologram(node);
            updateHologram(node);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(color(getConfig().getString("event.title", "&5&lMETIN EVENTI BASLADI!")), color(getConfig().getString("event.subtitle", "&fGitmek icin &d/warp metin")), 10, 70, 20);
            sendLines(p, getConfig().getStringList("event.start-message"));
        }
        if (admin) getLogger().info("Metin eventi admin tarafindan baslatildi.");
        saveData();
    }

    private void stopEvent(boolean broadcast) {
        eventActive = false;
        eventEndAt = 0L;
        Material inactive = material("metin.inactive-material", Material.BEDROCK);
        for (MetinNode node : nodes.values()) {
            Block block = node.block();
            if (block != null && block.getType() != Material.BEDROCK) block.setType(inactive, false);
            removeHologram(node);
            node.damage.clear();
        }
        if (broadcast) for (Player p : Bukkit.getOnlinePlayers()) sendLines(p, getConfig().getStringList("event.end-message"));
        saveData();
    }

    private void resumeEventVisuals() {
        for (MetinNode node : nodes.values()) if (node.currentHealth > 0) { spawnHologram(node); updateHologram(node); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent e) {
        if (findNode(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.LEFT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        MetinNode node = findNode(e.getClickedBlock().getLocation());
        if (node == null) return;
        e.setCancelled(true);
        if (!eventActive || node.currentHealth <= 0) return;
        long now = System.currentTimeMillis();
        long cooldown = getConfig().getLong("metin.hit-cooldown-ms", 250L);
        long last = lastHit.getOrDefault(e.getPlayer().getUniqueId(), 0L);
        if (now - last < cooldown) return;
        lastHit.put(e.getPlayer().getUniqueId(), now);
        double damage = getConfig().getDouble("metin.damage-per-hit", 10.0);
        node.currentHealth = Math.max(0.0, node.currentHealth - damage);
        node.damage.merge(e.getPlayer().getUniqueId(), damage, Double::sum);
        spawnConfiguredParticle(node.location().clone().add(0.5, 0.6, 0.5), "metin.particles.hit", Particle.CRIT, 6);
        updateHologram(node);
        if (node.currentHealth <= 0.0) breakMetin(node, e.getPlayer());
    }

    private void breakMetin(MetinNode node, Player killer) {
        UUID winnerId = node.damage.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(killer.getUniqueId());
        double winnerDamage = node.damage.getOrDefault(winnerId, 0.0);
        Player winner = Bukkit.getPlayer(winnerId);
        Block block = node.block();
        if (block != null) block.setType(Material.BEDROCK, false);
        removeHologram(node);
        spawnConfiguredParticle(node.location().clone().add(0.5, 1.0, 0.5), "metin.particles.break", Particle.PORTAL, 60);

        int earnedCredits = randomInt(getConfig().getInt("rewards.credits.min", 150), getConfig().getInt("rewards.credits.max", 300));
        int fragments = randomInt(getConfig().getInt("rewards.fragment.min", 1), getConfig().getInt("rewards.fragment.max", 3));
        List<ItemStack> itemRewards = new ArrayList<>();
        itemRewards.add(createFragment(fragments));
        List<String> rewardNames = new ArrayList<>();
        rewardNames.add(fragments + "x Metin Parcacigi");

        ConfigurationSection items = getConfig().getConfigurationSection("rewards.items");
        if (items != null) for (String key : items.getKeys(false)) {
            ConfigurationSection sec = items.getConfigurationSection(key);
            if (sec == null || !sec.getBoolean("enabled", true)) continue;
            if (ThreadLocalRandom.current().nextDouble(100.0) > sec.getDouble("chance", 0.0)) continue;
            Material mat = Material.matchMaterial(sec.getString("material", "STONE"));
            if (mat == null) continue;
            int amount = Math.max(1, sec.getInt("amount", 1));
            ItemStack stack = new ItemStack(mat, amount);
            String display = sec.getString("display-name");
            if (display != null && !display.isBlank()) {
                ItemMeta meta = stack.getItemMeta();
                meta.setDisplayName(color(display));
                stack.setItemMeta(meta);
            }
            itemRewards.add(stack);
            rewardNames.add(amount + "x " + stripColor(display == null ? pretty(mat.name()) : display));
        }

        if (winner != null) {
            credits.merge(winnerId, (long) earnedCredits, Long::sum);
            deliverRewards(winner, itemRewards);
            for (String line : getConfig().getStringList("messages.winner")) winner.sendMessage(color(line
                    .replace("%damage%", formatNumber(winnerDamage))
                    .replace("%credits%", String.valueOf(earnedCredits))
                    .replace("%fragments%", String.valueOf(fragments))
                    .replace("%items%", rewardNames.size() <= 1 ? "Yok" : String.join(", ", rewardNames.subList(1, rewardNames.size())))));
        }

        String broad = getConfig().getString("messages.broadcast-break", "&5Metin parcandi! &e%winner% &fodulleri kazandi.");
        String winnerName = winner != null ? winner.getName() : Bukkit.getOfflinePlayer(winnerId).getName();
        if (winnerName == null) winnerName = winnerId.toString();
        Bukkit.broadcastMessage(color(broad.replace("%winner%", winnerName).replace("%killer%", killer.getName())));
        node.damage.clear();
        saveData();
    }

    private void deliverRewards(Player player, List<ItemStack> rewards) {
        rewards.removeIf(Objects::isNull);
        if (rewards.isEmpty()) return;
        if (canFitAll(player, rewards)) {
            for (ItemStack item : rewards) player.getInventory().addItem(item);
            return;
        }
        int configured = getConfig().getInt("reward-gui.size", 54);
        int size = Math.max(9, Math.min(54, ((configured + 8) / 9) * 9));
        RewardHolder holder = new RewardHolder();
        Inventory inv = Bukkit.createInventory(holder, size, color(getConfig().getString("reward-gui.title", "&cEnvanterin Dolu! &7- Item Bosalt")));
        holder.inventory = inv;
        for (ItemStack item : rewards) {
            HashMap<Integer, ItemStack> overflow = inv.addItem(item);
            for (ItemStack extra : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
        rewardSessions.put(player.getUniqueId(), new RewardSession(player, inv));
        sendLines(player, getConfig().getStringList("reward-gui.warning-message"));
        player.openInventory(inv);
    }

    private boolean canFitAll(Player player, List<ItemStack> rewards) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int emptySlots = 0;
        for (ItemStack c : contents) if (c == null || c.getType().isAir()) emptySlots++;
        int needed = 0;
        for (ItemStack reward : rewards) {
            int remaining = reward.getAmount();
            for (ItemStack c : contents) {
                if (c != null && c.isSimilar(reward)) remaining -= Math.max(0, c.getMaxStackSize() - c.getAmount());
                if (remaining <= 0) break;
            }
            if (remaining > 0) needed += (int) Math.ceil(remaining / (double) reward.getMaxStackSize());
        }
        return needed <= emptySlots;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRewardClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof RewardHolder)) return;
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getRawSlot() >= e.getView().getTopInventory().getSize()) {
            if (e.isShiftClick()) e.setCancelled(true);
            return;
        }
        if (e.getCursor() != null && !e.getCursor().getType().isAir()) e.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> { if (isTopEmpty(e.getView().getTopInventory())) player.closeInventory(); });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRewardDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof RewardHolder)) return;
        int top = e.getView().getTopInventory().getSize();
        if (e.getRawSlots().stream().anyMatch(slot -> slot < top)) e.setCancelled(true);
    }

    @EventHandler public void onRewardClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof RewardHolder)) return;
        RewardSession session = rewardSessions.remove(e.getPlayer().getUniqueId());
        if (session == null) return;
        if (!isTopEmpty(e.getInventory()) && e.getPlayer() instanceof Player player) dropRemaining(player, e.getInventory());
    }

    private void dropRemaining(Player player, Inventory inv) {
        for (ItemStack item : inv.getContents()) if (item != null && !item.getType().isAir()) player.getWorld().dropItemNaturally(player.getLocation(), item);
        inv.clear();
    }
    private boolean isTopEmpty(Inventory inv) { for (ItemStack i : inv.getContents()) if (i != null && !i.getType().isAir()) return false; return true; }

    private ItemStack createFragment(int amount) {
        Material mat = material("rewards.fragment.material", Material.ECHO_SHARD);
        ItemStack item = new ItemStack(mat, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(getConfig().getString("rewards.fragment.name", "&d&lMetin Parcacigi")));
        meta.setLore(getConfig().getStringList("rewards.fragment.lore").stream().map(this::color).collect(Collectors.toList()));
        item.setItemMeta(meta);
        return item;
    }

    private void spawnHologram(MetinNode node) {
        removeHologram(node);
        World world = Bukkit.getWorld(node.world);
        if (world == null) return;
        double h = getConfig().getDouble("metin.hologram-height", 1.6);
        node.hologram = world.spawn(node.location().clone().add(0.5, h, 0.5), TextDisplay.class, td -> {
            td.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            td.setSeeThrough(false);
            td.setShadowed(true);
            td.setPersistent(false);
        });
    }

    private void updateHologram(MetinNode node) {
        if (node.hologram == null || !node.hologram.isValid()) return;
        UUID leaderId = node.damage.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        String leader = leaderId == null ? "-" : Optional.ofNullable(Bukkit.getOfflinePlayer(leaderId).getName()).orElse("-");
        double leaderDamage = leaderId == null ? 0.0 : node.damage.getOrDefault(leaderId, 0.0);
        double max = getConfig().getDouble("metin.max-health", 1000.0);
        String text = String.join("\n", List.of(
                getConfig().getString("metin.hologram.line1", "&5&lKADIM METIN"),
                getConfig().getString("metin.hologram.line2", "&c❤ &f%health%&7/&f%max_health% HP"),
                getConfig().getString("metin.hologram.line3", "&6⚔ &fLider: &e%leader% &7- &f%leader_damage%")
        )).replace("%health%", formatNumber(node.currentHealth)).replace("%max_health%", formatNumber(max)).replace("%leader%", leader).replace("%leader_damage%", formatNumber(leaderDamage));
        node.hologram.setText(color(text));
    }

    private void removeHologram(MetinNode node) { if (node.hologram != null) { node.hologram.remove(); node.hologram = null; } }

    private void spawnConfiguredParticle(Location loc, String path, Particle fallback, int count) {
        Particle particle = fallback;
        try { particle = Particle.valueOf(getConfig().getString(path, fallback.name()).toUpperCase(Locale.ROOT)); } catch (Exception ignored) {}
        if (loc.getWorld() != null) loc.getWorld().spawnParticle(particle, loc, count, 0.35, 0.35, 0.35, 0.03);
    }

    private MetinNode findNode(Location loc) {
        if (loc.getWorld() == null) return null;
        for (MetinNode node : nodes.values()) if (node.world.equals(loc.getWorld().getName()) && node.x == loc.getBlockX() && node.y == loc.getBlockY() && node.z == loc.getBlockZ()) return node;
        return null;
    }

    private void loadData() {
        credits.clear();
        ConfigurationSection c = data.getConfigurationSection("credits");
        if (c != null) for (String key : c.getKeys(false)) try { credits.put(UUID.fromString(key), c.getLong(key)); } catch (IllegalArgumentException ignored) {}
        nodes.clear();
        ConfigurationSection n = data.getConfigurationSection("metins");
        if (n != null) for (String id : n.getKeys(false)) {
            ConfigurationSection s = n.getConfigurationSection(id);
            if (s == null) continue;
            MetinNode node = new MetinNode(id, s.getString("world", "world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
            node.currentHealth = s.getDouble("health", getConfig().getDouble("metin.max-health", 1000.0));
            ConfigurationSection dmg = s.getConfigurationSection("damage");
            if (dmg != null) for (String key : dmg.getKeys(false)) try { node.damage.put(UUID.fromString(key), dmg.getDouble(key)); } catch (IllegalArgumentException ignored) {}
            nodes.put(id.toLowerCase(Locale.ROOT), node);
        }
        eventActive = data.getBoolean("event.active", false);
        nextEventAt = data.getLong("event.next-at", 0L);
        eventEndAt = data.getLong("event.end-at", 0L);
    }

    private void saveData() {
        data.set("credits", null);
        for (Map.Entry<UUID, Long> e : credits.entrySet()) data.set("credits." + e.getKey(), e.getValue());
        data.set("metins", null);
        for (MetinNode node : nodes.values()) {
            String base = "metins." + node.id;
            data.set(base + ".world", node.world); data.set(base + ".x", node.x); data.set(base + ".y", node.y); data.set(base + ".z", node.z); data.set(base + ".health", node.currentHealth);
            for (Map.Entry<UUID, Double> d : node.damage.entrySet()) data.set(base + ".damage." + d.getKey(), d.getValue());
        }
        data.set("event.active", eventActive); data.set("event.next-at", nextEventAt); data.set("event.end-at", eventEndAt);
        try { data.save(dataFile); } catch (IOException ex) { getLogger().severe("data.yml kaydedilemedi: " + ex.getMessage()); }
    }

    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) { sender.sendMessage(color("&5&lZuxianMetin &7| /metin add <id>, remove <id>, list, start, stop, next, credits, reload")); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("credits")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only"); return true; }
            sender.sendMessage(color(msg("credits").replace("%credits%", String.valueOf(getCredits(p.getUniqueId()))))); return true;
        }
        if (sub.equals("next")) { sender.sendMessage(color(msg("next").replace("%time%", eventActive ? "Event aktif" : formatDuration(Math.max(0, nextEventAt - System.currentTimeMillis()))))); return true; }
        if (!sender.hasPermission("zuxianmetin.admin")) { sender.sendMessage(color(msg("no-permission"))); return true; }
        switch (sub) {
            case "add" -> {
                if (!(sender instanceof Player p) || args.length < 2) { sender.sendMessage("/metin add <id>"); return true; }
                Block target = p.getTargetBlockExact(8);
                if (target == null) { p.sendMessage(color("&cBir bloga bak.")); return true; }
                String id = args[1].toLowerCase(Locale.ROOT);
                MetinNode node = new MetinNode(id, target.getWorld().getName(), target.getX(), target.getY(), target.getZ());
                node.currentHealth = getConfig().getDouble("metin.max-health", 1000.0);
                nodes.put(id, node);
                target.setType(material("metin.inactive-material", Material.BEDROCK), false);
                sender.sendMessage(color(msg("added").replace("%id%", id))); saveData();
            }
            case "remove" -> {
                if (args.length < 2) { sender.sendMessage("/metin remove <id>"); return true; }
                MetinNode removed = nodes.remove(args[1].toLowerCase(Locale.ROOT)); if (removed != null) removeHologram(removed);
                sender.sendMessage(color(msg("removed").replace("%id%", args[1]))); saveData();
            }
            case "list" -> sender.sendMessage(color("&fMetinler: &d" + String.join(", ", nodes.keySet())));
            case "start" -> { if (eventActive) sender.sendMessage(color(msg("already-active"))); else { startEvent(true); sender.sendMessage(color(msg("event-started-admin"))); } }
            case "stop" -> { if (!eventActive) sender.sendMessage(color(msg("not-active"))); else { stopEvent(true); sender.sendMessage(color(msg("event-stopped-admin"))); } }
            case "reload" -> { reloadConfig(); sender.sendMessage(color("&aConfig yenilendi.")); }
            case "givecredits" -> {
                if (args.length < 3) { sender.sendMessage("/metin givecredits <oyuncu> <miktar>"); return true; }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                try { long amount = Long.parseLong(args[2]); credits.merge(target.getUniqueId(), amount, Long::sum); sender.sendMessage(color("&aKredi eklendi.")); saveData(); }
                catch (NumberFormatException ex) { sender.sendMessage(color("&cMiktar sayi olmali.")); }
            }
            default -> sender.sendMessage(color("&cBilinmeyen alt komut."));
        }
        return true;
    }

    @Override public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return List.of("add", "remove", "list", "start", "stop", "next", "credits", "reload", "givecredits").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) return new ArrayList<>(nodes.keySet());
        return Collections.emptyList();
    }

    private String msg(String key) { return getConfig().getString("messages." + key, ""); }
    private void sendLines(CommandSender sender, List<String> lines) { for (String line : lines) sender.sendMessage(color(line)); }
    private Material material(String path, Material fallback) { Material m = Material.matchMaterial(getConfig().getString(path, fallback.name())); return m == null ? fallback : m; }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private String stripColor(String s) { return ChatColor.stripColor(color(s)); }
    private static int randomInt(int min, int max) { if (max < min) { int t=min; min=max; max=t; } return ThreadLocalRandom.current().nextInt(min, max + 1); }
    private static String pretty(String s) { return Arrays.stream(s.toLowerCase(Locale.ROOT).split("_")).map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(Collectors.joining(" ")); }
    private static String formatNumber(double d) { return Math.abs(d - Math.rint(d)) < 0.001 ? String.valueOf((long)Math.rint(d)) : String.format(Locale.US, "%.1f", d); }
    public static String formatDuration(long ms) { long total=Math.max(0,ms/1000L), h=total/3600L, m=(total%3600L)/60L, s=total%60L; return h>0?h+"s "+m+"dk":m>0?m+"dk "+s+"sn":s+"sn"; }

    private final class MetinNode {
        final String id, world; final int x,y,z; double currentHealth; final Map<UUID,Double> damage=new HashMap<>(); TextDisplay hologram;
        MetinNode(String id,String world,int x,int y,int z){this.id=id;this.world=world;this.x=x;this.y=y;this.z=z;}
        Location location(){World w=Bukkit.getWorld(world);return new Location(w,x,y,z);}
        Block block(){World w=Bukkit.getWorld(world);return w==null?null:w.getBlockAt(x,y,z);}
    }
    private static final class RewardHolder implements InventoryHolder { Inventory inventory; @Override public @NotNull Inventory getInventory(){return inventory;} }
    private record RewardSession(Player player, Inventory inventory) {}

    private static final class MetinExpansion extends PlaceholderExpansion {
        private final ZuxianMetinPlugin plugin;
        MetinExpansion(ZuxianMetinPlugin plugin){this.plugin=plugin;}
        @Override public @NotNull String getIdentifier(){return "zuxianmetin";}
        @Override public @NotNull String getAuthor(){return "zuxian";}
        @Override public @NotNull String getVersion(){return plugin.getDescription().getVersion();}
        @Override public boolean persist(){return true;}
        @Override public String onRequest(OfflinePlayer player,@NotNull String params){
            if(params.equalsIgnoreCase("credits")) return player==null?"0":String.valueOf(plugin.getCredits(player.getUniqueId()));
            if(params.equalsIgnoreCase("active")) return plugin.isEventActive()?"true":"false";
            if(params.equalsIgnoreCase("next")) return plugin.isEventActive()?"Aktif":formatDuration(Math.max(0,plugin.getNextEventAt()-System.currentTimeMillis()));
            return null;
        }
    }
}
