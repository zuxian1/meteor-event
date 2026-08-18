package com.zuxian.metin;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public final class ZuxianMetinPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String, MetinNode> nodes = new LinkedHashMap<>();
    private final Map<UUID, Long> credits = new HashMap<>();
    private final Map<UUID, RewardSession> rewardSessions = new HashMap<>();
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
    private File dataFile;
    private YamlConfiguration data;
    private boolean eventActive;
    private long nextEventAt;
    private long eventEndAt;
    private BukkitTask ticker;
    private NamespacedKey guardianNodeKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        guardianNodeKey = new NamespacedKey(this, "metin_guardian_node");
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
        if (eventActive) {
            if (eventEndAt > now) resumeEventVisuals();
            else stopEvent(false);
        }
        if (!eventActive) nextEventAt = computeNextScheduledEvent(now);
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
        } else {
            if (nextEventAt <= 0L) nextEventAt = computeNextScheduledEvent(now);
            if (nextEventAt > 0L && now >= nextEventAt) startEvent(false);
        }
    }

    private long durationMillis() { return getConfig().getLong("event.duration-minutes", 60L) * 60000L; }
    public long getCredits(UUID uuid) { return credits.getOrDefault(uuid, 0L); }
    public boolean isEventActive() { return eventActive; }
    public long getNextEventAt() { return nextEventAt; }

    private ZoneId scheduleZone() {
        try { return ZoneId.of(getConfig().getString("event.timezone", "Europe/Istanbul")); }
        catch (Exception ignored) { return ZoneId.of("Europe/Istanbul"); }
    }

    private long computeNextScheduledEvent(long afterMillis) {
        ZoneId zone = scheduleZone();
        ZonedDateTime base = Instant.ofEpochMilli(afterMillis).atZone(zone);
        ZonedDateTime best = null;
        for (int addDays = 0; addDays <= 7; addDays++) {
            LocalDate date = base.toLocalDate().plusDays(addDays);
            String dayKey = dayConfigKey(date.getDayOfWeek());
            for (String rawTime : getConfig().getStringList("event.schedule." + dayKey)) {
                try {
                    LocalTime time = LocalTime.parse(rawTime.trim(), timeFormatter);
                    ZonedDateTime candidate = ZonedDateTime.of(date, time, zone);
                    if (!candidate.toInstant().isAfter(Instant.ofEpochMilli(afterMillis))) continue;
                    if (best == null || candidate.isBefore(best)) best = candidate;
                } catch (DateTimeParseException ignored) {
                    getLogger().warning("Gecersiz event saati: " + dayKey + " -> " + rawTime + " (HH:mm olmali)");
                }
            }
        }
        return best == null ? 0L : best.toInstant().toEpochMilli();
    }

    private String dayConfigKey(DayOfWeek day) { return day.name().toLowerCase(Locale.ROOT); }

    private DayOfWeek parseDay(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replace("ı","i").replace("ş","s").replace("ç","c").replace("ğ","g").replace("ü","u").replace("ö","o");
        return switch (s) {
            case "monday", "mon", "pazartesi", "pzt" -> DayOfWeek.MONDAY;
            case "tuesday", "tue", "sali", "sal" -> DayOfWeek.TUESDAY;
            case "wednesday", "wed", "carsamba", "car" -> DayOfWeek.WEDNESDAY;
            case "thursday", "thu", "persembe", "per" -> DayOfWeek.THURSDAY;
            case "friday", "fri", "cuma", "cum" -> DayOfWeek.FRIDAY;
            case "saturday", "sat", "cumartesi", "cmt" -> DayOfWeek.SATURDAY;
            case "sunday", "sun", "pazar", "paz" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    private String displayDay(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "Pazartesi"; case TUESDAY -> "Sali"; case WEDNESDAY -> "Carsamba";
            case THURSDAY -> "Persembe"; case FRIDAY -> "Cuma"; case SATURDAY -> "Cumartesi"; case SUNDAY -> "Pazar";
        };
    }

    private void startEvent(boolean admin) {
        if (eventActive) return;
        eventActive = true;
        eventEndAt = System.currentTimeMillis() + durationMillis();
        nextEventAt = 0L;
        Material activeMat = material("metin.active-material", Material.CRYING_OBSIDIAN);
        double maxHp = getConfig().getDouble("metin.max-health", 1000.0);
        double interval = Math.max(1.0, getConfig().getDouble("guardians.every-health", 300.0));
        for (MetinNode node : nodes.values()) {
            node.currentHealth = maxHp;
            node.damage.clear();
            node.guardians.clear();
            node.nextGuardianHealth = maxHp - interval;
            Block block = node.block();
            if (block != null) block.setType(activeMat, false);
            spawnHologram(node);
            updateHologram(node);
            playMetinRespawnEffects(node);
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
            removeGuardians(node);
        }
        nextEventAt = computeNextScheduledEvent(System.currentTimeMillis());
        if (broadcast) for (Player p : Bukkit.getOnlinePlayers()) sendLines(p, getConfig().getStringList("event.end-message"));
        saveData();
    }

    private void resumeEventVisuals() {
        double interval = Math.max(1.0, getConfig().getDouble("guardians.every-health", 300.0));
        for (MetinNode node : nodes.values()) {
            node.guardians.clear();
            node.nextGuardianHealth = Math.floor((node.currentHealth - 0.0001) / interval) * interval;
            if (node.currentHealth > 0) { spawnHologram(node); updateHologram(node); }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent e) {
        MetinNode node = findNode(e.getBlock().getLocation());
        if (node == null) return;
        e.setCancelled(true); e.setDropItems(false); e.setExpToDrop(0);
        if (!eventActive || node.currentHealth <= 0.0) return;
        if (hasAliveGuardians(node)) {
            e.getPlayer().sendActionBar(color(getConfig().getString("guardians.blocked-actionbar", "&cOnce Metin Muhafizlarini oldurmelisin!")));
            return;
        }
        double damage = getConfig().getDouble("metin.damage-per-break", 10.0);
        node.currentHealth = Math.max(0.0, node.currentHealth - damage);
        node.damage.merge(e.getPlayer().getUniqueId(), damage, Double::sum);
        Location hitLoc = node.location().clone().add(0.5, 0.65, 0.5);
        spawnConfiguredParticle(hitLoc, "metin.particles.hit", Particle.CRIT, 10);
        playConfiguredSound(hitLoc, "metin.sounds.mine", Sound.BLOCK_STONE_BREAK, 0.75f, 0.75f);
        checkGuardianThreshold(node);
        updateHologram(node);
        if (node.currentHealth <= 0.0) breakMetin(node, e.getPlayer());
    }

    private void checkGuardianThreshold(MetinNode node) {
        if (!getConfig().getBoolean("guardians.enabled", true) || node.currentHealth <= 0.0) return;
        double interval = Math.max(1.0, getConfig().getDouble("guardians.every-health", 300.0));
        if (node.nextGuardianHealth > 0.0 && node.currentHealth <= node.nextGuardianHealth) {
            spawnGuardians(node);
            node.nextGuardianHealth -= interval;
        }
    }

    private void spawnGuardians(MetinNode node) {
        World world = Bukkit.getWorld(node.world); if (world == null) return;
        int count = Math.max(1, getConfig().getInt("guardians.count", 2));
        EntityType type;
        try { type = EntityType.valueOf(getConfig().getString("guardians.type", "HUSK").toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { type = EntityType.HUSK; }
        double health = Math.max(1.0, getConfig().getDouble("guardians.health", 40.0));
        double attack = Math.max(1.0, getConfig().getDouble("guardians.attack-damage", 6.0));
        double radius = Math.max(1.5, getConfig().getDouble("guardians.spawn-radius", 3.0));
        Location center = node.location().clone().add(0.5, 1.0, 0.5);
        node.guardians.clear();
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2.0 * i / count + ThreadLocalRandom.current().nextDouble(-0.35, 0.35);
            Location spawn = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            spawn.setY(world.getHighestBlockYAt(spawn) + 1.0);
            Entity raw = world.spawnEntity(spawn, type);
            if (!(raw instanceof LivingEntity mob)) { raw.remove(); continue; }
            if (mob.getAttribute(Attribute.MAX_HEALTH) != null) {
                mob.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health);
                mob.setHealth(Math.min(health, mob.getAttribute(Attribute.MAX_HEALTH).getValue()));
            }
            if (mob.getAttribute(Attribute.ATTACK_DAMAGE) != null) mob.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(attack);
            mob.setCustomName(color(getConfig().getString("guardians.name", "&5&lMetin Muhafizi")));
            mob.setCustomNameVisible(true); mob.setPersistent(false);
            mob.getPersistentDataContainer().set(guardianNodeKey, PersistentDataType.STRING, node.id);
            if (mob.getEquipment() != null) {
                mob.getEquipment().setHelmet(new ItemStack(Material.IRON_HELMET));
                mob.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
                mob.getEquipment().setHelmetDropChance(0f); mob.getEquipment().setItemInMainHandDropChance(0f);
            }
            node.guardians.add(mob.getUniqueId());
        }
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, center, 45, 1.3, 0.8, 1.3, 0.07);
        world.spawnParticle(Particle.SMOKE, center, 35, 1.2, 0.6, 1.2, 0.05);
        playConfiguredSound(center, "guardians.spawn-sound", Sound.ENTITY_WITHER_SPAWN, 0.8f, 1.35f);
        double range = getConfig().getDouble("guardians.message-radius", 18.0);
        for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(center) <= range * range)
            p.sendActionBar(color(getConfig().getString("guardians.spawn-actionbar", "&5&lMUHAFIZLAR ORTAYA CIKTI! &fMetine devam etmek icin onlari oldur.")));
    }

    private boolean hasAliveGuardians(MetinNode node) {
        node.guardians.removeIf(uuid -> { Entity entity = Bukkit.getEntity(uuid); return entity == null || entity.isDead() || !entity.isValid(); });
        return !node.guardians.isEmpty();
    }

    private void removeGuardians(MetinNode node) {
        for (UUID uuid : new HashSet<>(node.guardians)) { Entity entity = Bukkit.getEntity(uuid); if (entity != null) entity.remove(); }
        node.guardians.clear();
    }

    @EventHandler public void onGuardianDeath(EntityDeathEvent e) {
        String nodeId = e.getEntity().getPersistentDataContainer().get(guardianNodeKey, PersistentDataType.STRING);
        if (nodeId == null) return;
        e.getDrops().clear(); e.setDroppedExp(0);
        MetinNode node = nodes.get(nodeId.toLowerCase(Locale.ROOT)); if (node == null) return;
        node.guardians.remove(e.getEntity().getUniqueId());
        if (!hasAliveGuardians(node)) {
            Location center = node.location().clone().add(0.5, 1.0, 0.5);
            playConfiguredSound(center, "guardians.cleared-sound", Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.3f);
            if (center.getWorld() != null) {
                center.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, center, 25, 0.8, 0.5, 0.8, 0.04);
                for (Player p : center.getWorld().getPlayers()) if (p.getLocation().distanceSquared(center) <= 324)
                    p.sendActionBar(color(getConfig().getString("guardians.cleared-actionbar", "&aMuhafizlar yenildi! &fMetini kazmaya devam edebilirsin.")));
            }
            updateHologram(node);
        }
    }

    private void breakMetin(MetinNode node, Player killer) {
        Block block = node.block(); if (block != null) block.setType(Material.BEDROCK, false);
        removeHologram(node); removeGuardians(node); playMetinBreakEffects(node);
        List<Map.Entry<UUID, Double>> ranking = node.damage.entrySet().stream().sorted(Map.Entry.<UUID, Double>comparingByValue().reversed()).limit(3).toList();
        for (int i = 0; i < ranking.size(); i++) {
            Player player = Bukkit.getPlayer(ranking.get(i).getKey());
            if (player != null) grantRankReward(player, i + 1, ranking.get(i).getValue());
        }
        String firstName = ranking.isEmpty() ? killer.getName() : Optional.ofNullable(Bukkit.getOfflinePlayer(ranking.get(0).getKey()).getName()).orElse(killer.getName());
        Bukkit.broadcastMessage(color(getConfig().getString("messages.broadcast-break", "&5Metin parcandi! &e%winner% &fen fazla hasari verdi.").replace("%winner%", firstName).replace("%killer%", killer.getName())));
        node.damage.clear(); saveData();
    }

    private void grantRankReward(Player player, int rank, double dealt) {
        String base = "rewards.ranks." + rank;
        int ce = randomInt(getConfig().getInt(base + ".credits.min", rank == 1 ? 200 : rank == 2 ? 120 : 70), getConfig().getInt(base + ".credits.max", rank == 1 ? 350 : rank == 2 ? 220 : 140));
        int fragments = randomInt(getConfig().getInt(base + ".fragments.min", rank == 1 ? 2 : 1), getConfig().getInt(base + ".fragments.max", rank == 1 ? 4 : rank == 2 ? 2 : 1));
        double mult = Math.max(0, getConfig().getDouble(base + ".item-chance-multiplier", rank == 1 ? 1.0 : rank == 2 ? 0.65 : 0.35));
        credits.merge(player.getUniqueId(), (long) ce, Long::sum);
        List<ItemStack> rewards = new ArrayList<>(); if (fragments > 0) rewards.add(createFragment(fragments));
        List<String> extra = new ArrayList<>();
        ConfigurationSection items = getConfig().getConfigurationSection("rewards.items");
        if (items != null) for (String key : items.getKeys(false)) {
            ConfigurationSection sec = items.getConfigurationSection(key); if (sec == null || !sec.getBoolean("enabled", true)) continue;
            double chance = Math.min(100, sec.getDouble("chance", 0) * mult); if (ThreadLocalRandom.current().nextDouble(100) > chance) continue;
            Material mat = Material.matchMaterial(sec.getString("material", "STONE")); if (mat == null) continue;
            int amount = Math.max(1, sec.getInt("amount", 1)); ItemStack stack = new ItemStack(mat, amount); String display = sec.getString("display-name");
            if (display != null && !display.isBlank()) { ItemMeta meta = stack.getItemMeta(); meta.setDisplayName(color(display)); stack.setItemMeta(meta); }
            rewards.add(stack); extra.add(amount + "x " + stripColor(display == null ? pretty(mat.name()) : display));
        }
        deliverRewards(player, rewards);
        for (String line : getConfig().getStringList("messages.rank-reward")) player.sendMessage(color(line.replace("%rank%", String.valueOf(rank)).replace("%damage%", formatNumber(dealt)).replace("%credits%", String.valueOf(ce)).replace("%fragments%", String.valueOf(fragments)).replace("%items%", extra.isEmpty() ? "Yok" : String.join(", ", extra))));
    }

    private void deliverRewards(Player player, List<ItemStack> rewards) {
        rewards.removeIf(Objects::isNull); if (rewards.isEmpty()) return;
        if (canFitAll(player, rewards)) { for (ItemStack item : rewards) player.getInventory().addItem(item); return; }
        int configured = getConfig().getInt("reward-gui.size", 54); int size = Math.max(9, Math.min(54, ((configured + 8) / 9) * 9));
        RewardHolder holder = new RewardHolder(); Inventory inv = Bukkit.createInventory(holder, size, color(getConfig().getString("reward-gui.title", "&cEnvanterin Dolu! &7- Item Bosalt"))); holder.inventory = inv;
        for (ItemStack item : rewards) { HashMap<Integer, ItemStack> overflow = inv.addItem(item); for (ItemStack extra : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), extra); }
        rewardSessions.put(player.getUniqueId(), new RewardSession(player, inv)); sendLines(player, getConfig().getStringList("reward-gui.warning-message")); player.openInventory(inv);
    }

    private boolean canFitAll(Player player, List<ItemStack> rewards) {
        ItemStack[] contents = player.getInventory().getStorageContents(); int empty = 0; for (ItemStack c : contents) if (c == null || c.getType().isAir()) empty++;
        int needed = 0; for (ItemStack reward : rewards) { int remaining = reward.getAmount(); for (ItemStack c : contents) { if (c != null && c.isSimilar(reward)) remaining -= Math.max(0, c.getMaxStackSize() - c.getAmount()); if (remaining <= 0) break; } if (remaining > 0) needed += (int)Math.ceil(remaining / (double)reward.getMaxStackSize()); }
        return needed <= empty;
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onRewardClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof RewardHolder) || !(e.getWhoClicked() instanceof Player player)) return;
        if (e.getRawSlot() >= e.getView().getTopInventory().getSize()) { if (e.isShiftClick()) e.setCancelled(true); return; }
        if (e.getCursor() != null && !e.getCursor().getType().isAir()) e.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> { if (isTopEmpty(e.getView().getTopInventory())) player.closeInventory(); });
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void onRewardDrag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof RewardHolder) { int top = e.getView().getTopInventory().getSize(); if (e.getRawSlots().stream().anyMatch(slot -> slot < top)) e.setCancelled(true); } }
    @EventHandler public void onRewardClose(InventoryCloseEvent e) { if (!(e.getInventory().getHolder() instanceof RewardHolder)) return; RewardSession s = rewardSessions.remove(e.getPlayer().getUniqueId()); if (s != null && !isTopEmpty(e.getInventory()) && e.getPlayer() instanceof Player p) dropRemaining(p, e.getInventory()); }
    private void dropRemaining(Player p, Inventory inv) { for (ItemStack item : inv.getContents()) if (item != null && !item.getType().isAir()) p.getWorld().dropItemNaturally(p.getLocation(), item); inv.clear(); }
    private boolean isTopEmpty(Inventory inv) { for (ItemStack i : inv.getContents()) if (i != null && !i.getType().isAir()) return false; return true; }

    private ItemStack createFragment(int amount) {
        ItemStack item = new ItemStack(material("rewards.fragment.material", Material.ECHO_SHARD), Math.max(1, amount)); ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(getConfig().getString("rewards.fragment.name", "&d&lMetin Parcacigi"))); meta.setLore(getConfig().getStringList("rewards.fragment.lore").stream().map(this::color).collect(Collectors.toList())); item.setItemMeta(meta); return item;
    }

    private void spawnHologram(MetinNode node) { removeHologram(node); World w = Bukkit.getWorld(node.world); if (w == null) return; double h = getConfig().getDouble("metin.hologram-height", 1.6); node.hologram = w.spawn(node.location().clone().add(0.5,h,0.5), TextDisplay.class, td -> { td.setBillboard(org.bukkit.entity.Display.Billboard.CENTER); td.setSeeThrough(false); td.setShadowed(true); td.setPersistent(false); }); }
    private void updateHologram(MetinNode node) {
        if (node.hologram == null || !node.hologram.isValid()) return; UUID lid = node.damage.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null); String leader = lid == null ? "-" : Optional.ofNullable(Bukkit.getOfflinePlayer(lid).getName()).orElse("-"); double ld = lid == null ? 0 : node.damage.getOrDefault(lid,0.0); double max = getConfig().getDouble("metin.max-health",1000);
        List<String> lines = new ArrayList<>(); lines.add(getConfig().getString("metin.hologram.line1","&5&lKADIM METIN")); lines.add(getConfig().getString("metin.hologram.line2","&c❤ &f%health%&7/&f%max_health% HP")); lines.add(getConfig().getString("metin.hologram.line3","&6⚔ &fLider: &e%leader% &7- &f%leader_damage%")); if (hasAliveGuardians(node)) lines.add(getConfig().getString("metin.hologram.guardians","&5⚔ &cMuhafizlari oldur!"));
        node.hologram.setText(color(String.join("\n", lines).replace("%health%",formatNumber(node.currentHealth)).replace("%max_health%",formatNumber(max)).replace("%leader%",leader).replace("%leader_damage%",formatNumber(ld))));
    }
    private void removeHologram(MetinNode node) { if (node.hologram != null) { node.hologram.remove(); node.hologram = null; } }

    private void playMetinRespawnEffects(MetinNode node) {
        Location c = node.location().clone().add(0.5,0.9,0.5); World w = c.getWorld(); if (w == null) return;
        w.spawnParticle(Particle.REVERSE_PORTAL,c,90,0.7,0.9,0.7,0.1); w.spawnParticle(Particle.SOUL_FIRE_FLAME,c,35,0.5,0.7,0.5,0.05); w.spawnParticle(Particle.ENCHANT,c,45,0.7,0.8,0.7,0.1);
        playConfiguredSound(c,"metin.sounds.respawn-primary",Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,1.3f,0.8f); playConfiguredSound(c,"metin.sounds.respawn-secondary",Sound.BLOCK_BEACON_ACTIVATE,1f,0.7f);
    }
    private void playMetinBreakEffects(MetinNode node) {
        Location c = node.location().clone().add(0.5,0.7,0.5); World w = c.getWorld(); if (w == null) return;
        w.spawnParticle(Particle.EXPLOSION_EMITTER,c,2,0.15,0.15,0.15,0); w.spawnParticle(Particle.PORTAL,c,140,0.75,0.9,0.75,0.35); w.spawnParticle(Particle.CRIT,c,90,0.8,0.8,0.8,0.25); w.spawnParticle(Particle.SOUL_FIRE_FLAME,c,55,0.65,0.8,0.65,0.06);
        playConfiguredSound(c,"metin.sounds.break-primary",Sound.ENTITY_GENERIC_EXPLODE,1.5f,0.72f); playConfiguredSound(c,"metin.sounds.break-secondary",Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,1.25f,0.85f); playConfiguredSound(c,"metin.sounds.break-impact",Sound.ENTITY_WITHER_BREAK_BLOCK,0.85f,0.8f);
        double r = getConfig().getDouble("metin.break-effect-radius",18); for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(c) <= r*r) p.sendActionBar(color(getConfig().getString("metin.break-actionbar","&5&l✦ METIN PARCALANDI! ✦")));
    }

    private void playConfiguredSound(Location loc, String path, Sound fallback, float fv, float fp) { if (loc.getWorld()==null) return; Sound s=fallback; try { String raw=getConfig().getString(path,fallback.name()); if(raw!=null)s=Sound.valueOf(raw.toUpperCase(Locale.ROOT)); } catch(Exception ignored){} float v=(float)getConfig().getDouble(path+"-volume",fv), p=(float)getConfig().getDouble(path+"-pitch",fp); loc.getWorld().playSound(loc,s,v,p); }
    private void spawnConfiguredParticle(Location loc,String path,Particle fallback,int count){ Particle p=fallback; try{p=Particle.valueOf(getConfig().getString(path,fallback.name()).toUpperCase(Locale.ROOT));}catch(Exception ignored){} if(loc.getWorld()!=null)loc.getWorld().spawnParticle(p,loc,count,0.35,0.35,0.35,0.03); }
    private Material material(String path,Material fallback){ Material m=Material.matchMaterial(getConfig().getString(path,fallback.name())); return m==null?fallback:m; }
    private MetinNode findNode(Location loc){ if(loc.getWorld()==null)return null; for(MetinNode n:nodes.values())if(n.world.equals(loc.getWorld().getName())&&n.x==loc.getBlockX()&&n.y==loc.getBlockY()&&n.z==loc.getBlockZ())return n; return null; }

    private void loadData(){ credits.clear(); nodes.clear(); eventActive=data.getBoolean("event.active",false); nextEventAt=data.getLong("event.next",0); eventEndAt=data.getLong("event.end",0); ConfigurationSection c=data.getConfigurationSection("credits"); if(c!=null)for(String k:c.getKeys(false))try{credits.put(UUID.fromString(k),c.getLong(k));}catch(Exception ignored){} ConfigurationSection ns=data.getConfigurationSection("nodes"); if(ns!=null)for(String id:ns.getKeys(false)){ConfigurationSection s=ns.getConfigurationSection(id); if(s==null)continue; MetinNode n=new MetinNode(id,s.getString("world","world"),s.getInt("x"),s.getInt("y"),s.getInt("z")); n.currentHealth=s.getDouble("health",getConfig().getDouble("metin.max-health",1000)); ConfigurationSection d=s.getConfigurationSection("damage"); if(d!=null)for(String k:d.getKeys(false))try{n.damage.put(UUID.fromString(k),d.getDouble(k));}catch(Exception ignored){} nodes.put(id.toLowerCase(Locale.ROOT),n);} }
    private void saveData(){ data.set("event.active",eventActive); data.set("event.next",nextEventAt); data.set("event.end",eventEndAt); data.set("credits",null); for(var e:credits.entrySet())data.set("credits."+e.getKey(),e.getValue()); data.set("nodes",null); for(MetinNode n:nodes.values()){String b="nodes."+n.id; data.set(b+".world",n.world);data.set(b+".x",n.x);data.set(b+".y",n.y);data.set(b+".z",n.z);data.set(b+".health",n.currentHealth);for(var d:n.damage.entrySet())data.set(b+".damage."+d.getKey(),d.getValue());} try{data.save(dataFile);}catch(IOException ex){getLogger().severe("data.yml kaydedilemedi: "+ex.getMessage());} }

    @Override public boolean onCommand(@NotNull CommandSender sender,@NotNull Command command,@NotNull String label,@NotNull String[] args){
        if(args.length==0){sender.sendMessage(color("&5&lZuxianMetin &7- &f/metin add, remove, list, start, stop, credits, next, schedule, reload"));return true;}
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("credits")){if(sender instanceof Player p)sender.sendMessage(color(getConfig().getString("messages.credits","&fMetin Kredin: &d%credits%").replace("%credits%",String.valueOf(getCredits(p.getUniqueId())))));return true;}
        if(sub.equals("next")){if(eventActive)sender.sendMessage(color(getConfig().getString("messages.active-remaining","&fMetin Eventinin bitmesine: &d%time%").replace("%time%",formatDuration(Math.max(0,eventEndAt-System.currentTimeMillis()))))); else if(nextEventAt>0)sender.sendMessage(color(getConfig().getString("messages.next","&fSonraki Metin Eventi: &d%time%").replace("%time%",formatDuration(Math.max(0,nextEventAt-System.currentTimeMillis()))))); else sender.sendMessage(color("&cTakvimde planlanmis Metin eventi yok.")); return true;}
        if(!sender.hasPermission("zuxianmetin.admin")){sender.sendMessage(color(getConfig().getString("messages.no-permission","&cYetkin yok.")));return true;}
        switch(sub){
            case "add"->{if(!(sender instanceof Player p)||args.length<2)return true; Block t=p.getTargetBlockExact(8);if(t==null){p.sendMessage(color("&cBir bloga bak."));return true;}String id=args[1].toLowerCase(Locale.ROOT);MetinNode n=new MetinNode(id,t.getWorld().getName(),t.getX(),t.getY(),t.getZ());n.currentHealth=getConfig().getDouble("metin.max-health",1000);nodes.put(id,n);t.setType(material("metin.inactive-material",Material.BEDROCK),false);saveData();p.sendMessage(color(getConfig().getString("messages.added","&a%id% metini kaydedildi.").replace("%id%",id)));}
            case "remove"->{if(args.length<2)return true;MetinNode n=nodes.remove(args[1].toLowerCase(Locale.ROOT));if(n!=null){removeHologram(n);removeGuardians(n);}saveData();sender.sendMessage(color(getConfig().getString("messages.removed","&a%id% metini silindi.").replace("%id%",args[1])));}
            case "list"->sender.sendMessage(color("&fMetinler: &d"+String.join(", ",nodes.keySet())));
            case "start"->{if(eventActive)sender.sendMessage(color(getConfig().getString("messages.already-active","&cEvent zaten aktif.")));else{startEvent(true);sender.sendMessage(color(getConfig().getString("messages.event-started-admin","&aMetin eventi baslatildi.")));}}
            case "stop"->{if(!eventActive)sender.sendMessage(color(getConfig().getString("messages.not-active","&cEvent aktif degil.")));else{stopEvent(false);sender.sendMessage(color(getConfig().getString("messages.event-stopped-admin","&aMetin eventi durduruldu.")));}}
            case "reload"->{reloadConfig();if(!eventActive)nextEventAt=computeNextScheduledEvent(System.currentTimeMillis());sender.sendMessage(color("&aZuxianMetin config ve takvim yenilendi."));}
            case "setcredits"->{if(args.length<3)return true;Player p=Bukkit.getPlayerExact(args[1]);if(p==null)return true;try{credits.put(p.getUniqueId(),Long.parseLong(args[2]));saveData();sender.sendMessage(color("&aKredi ayarlandi."));}catch(Exception ex){sender.sendMessage(color("&cGecersiz kredi miktari."));}}
            case "schedule"->handleScheduleCommand(sender,args);
            default->sender.sendMessage(color("&cBilinmeyen alt komut."));
        }return true;
    }

    private void handleScheduleCommand(CommandSender sender,String[] args){
        if(args.length<2||args[1].equalsIgnoreCase("list")){sender.sendMessage(color("&5&lMetin Event Takvimi &7("+scheduleZone().getId()+")"));boolean any=false;for(DayOfWeek d:DayOfWeek.values()){List<String> times=getConfig().getStringList("event.schedule."+dayConfigKey(d));if(!times.isEmpty()){any=true;sender.sendMessage(color("&f"+displayDay(d)+": &d"+String.join(", ",times)));}}if(!any)sender.sendMessage(color("&7Takvim bos. /metin schedule add pzt 20:00"));return;}
        if(args.length<4){sender.sendMessage(color("&fKullanim: &d/metin schedule add|remove <gun> <HH:mm>"));return;} DayOfWeek day=parseDay(args[2]);if(day==null){sender.sendMessage(color("&cGecersiz gun."));return;}String time=args[3];try{LocalTime.parse(time,timeFormatter);}catch(Exception ex){sender.sendMessage(color("&cSaat HH:mm formatinda olmali. Ornek: 20:00"));return;}String path="event.schedule."+dayConfigKey(day);List<String> times=new ArrayList<>(getConfig().getStringList(path));if(args[1].equalsIgnoreCase("add")){if(!times.contains(time))times.add(time);times.sort(String::compareTo);getConfig().set(path,times);saveConfig();sender.sendMessage(color("&a"+displayDay(day)+" "+time+" takvime eklendi."));}else if(args[1].equalsIgnoreCase("remove")){times.remove(time);getConfig().set(path,times);saveConfig();sender.sendMessage(color("&a"+displayDay(day)+" "+time+" takvimden silindi."));}else{sender.sendMessage(color("&cKullanim: /metin schedule add|remove <gun> <HH:mm>"));return;}if(!eventActive)nextEventAt=computeNextScheduledEvent(System.currentTimeMillis());saveData();
    }

    @Override public List<String> onTabComplete(@NotNull CommandSender sender,@NotNull Command command,@NotNull String alias,@NotNull String[] args){if(args.length==1)return Arrays.asList("add","remove","list","start","stop","credits","next","schedule","reload","setcredits");if(args.length==2&&args[0].equalsIgnoreCase("remove"))return new ArrayList<>(nodes.keySet());if(args.length==2&&args[0].equalsIgnoreCase("schedule"))return Arrays.asList("list","add","remove");if(args.length==3&&args[0].equalsIgnoreCase("schedule")&&(args[1].equalsIgnoreCase("add")||args[1].equalsIgnoreCase("remove")))return Arrays.asList("pzt","sali","carsamba","persembe","cuma","cumartesi","pazar");return Collections.emptyList();}
    private int randomInt(int min,int max){if(max<min){int t=min;min=max;max=t;}return ThreadLocalRandom.current().nextInt(min,max+1);} private String formatDuration(long ms){long t=Math.max(0,ms/1000),h=t/3600,m=(t%3600)/60,s=t%60;if(h>0)return h+"s "+m+"dk";if(m>0)return m+"dk "+s+"sn";return s+"sn";} private String formatNumber(double d){return Math.abs(d-Math.rint(d))<0.0001?String.valueOf((long)d):String.format(Locale.US,"%.1f",d);} private String pretty(String s){return Arrays.stream(s.toLowerCase(Locale.ROOT).split("_")).map(x->x.isEmpty()?x:Character.toUpperCase(x.charAt(0))+x.substring(1)).collect(Collectors.joining(" "));} private String stripColor(String s){return ChatColor.stripColor(color(s));} private String color(String s){return s==null?"":ChatColor.translateAlternateColorCodes('&',s);} private void sendLines(Player p,List<String> lines){for(String s:lines)p.sendMessage(color(s));}

    private static final class RewardHolder implements InventoryHolder{private Inventory inventory;@Override public Inventory getInventory(){return inventory;}} private record RewardSession(Player player,Inventory inventory){}
    private final class MetinNode{final String id,world;final int x,y,z;double currentHealth,nextGuardianHealth;final Map<UUID,Double> damage=new HashMap<>();final Set<UUID> guardians=new HashSet<>();TextDisplay hologram;MetinNode(String id,String world,int x,int y,int z){this.id=id;this.world=world;this.x=x;this.y=y;this.z=z;}Location location(){return new Location(Bukkit.getWorld(world),x,y,z);}Block block(){World w=Bukkit.getWorld(world);return w==null?null:w.getBlockAt(x,y,z);}}
    private static final class MetinExpansion extends PlaceholderExpansion{private final ZuxianMetinPlugin plugin;MetinExpansion(ZuxianMetinPlugin plugin){this.plugin=plugin;}@Override public @NotNull String getIdentifier(){return "zuxianmetin";}@Override public @NotNull String getAuthor(){return "zuxian";}@Override public @NotNull String getVersion(){return plugin.getDescription().getVersion();}@Override public boolean persist(){return true;}@Override public String onPlaceholderRequest(Player player,@NotNull String params){if(params.equalsIgnoreCase("credits"))return player==null?"0":String.valueOf(plugin.getCredits(player.getUniqueId()));if(params.equalsIgnoreCase("active"))return plugin.isEventActive()?"AKTIF":"PASIF";if(params.equalsIgnoreCase("next")){if(plugin.isEventActive())return plugin.formatDuration(Math.max(0,plugin.eventEndAt-System.currentTimeMillis()));if(plugin.getNextEventAt()<=0)return "-";return plugin.formatDuration(Math.max(0,plugin.getNextEventAt()-System.currentTimeMillis()));}return null;}}
}
