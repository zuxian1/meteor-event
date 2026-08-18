from pathlib import Path
import shutil

root = Path(__file__).resolve().parents[1]
src_dir = root / "zuxianmetinv13"
dst_dir = root / "zuxianmetinv14"
if dst_dir.exists():
    shutil.rmtree(dst_dir)
shutil.copytree(src_dir, dst_dir)

java = dst_dir / "src/main/java/com/zuxian/metin/ZuxianMetinPlugin.java"
s = java.read_text(encoding="utf-8")

s = s.replace(
    "import org.bukkit.event.entity.EntityDeathEvent;\n",
    "import org.bukkit.event.entity.EntityDeathEvent;\nimport org.bukkit.event.entity.EntityDamageByEntityEvent;\nimport org.bukkit.event.entity.EntityTargetLivingEntityEvent;\n",
    1,
)

s = s.replace(
'''            node.currentHealth = maxHp;
            node.damage.clear();
            removeGuardians(node);
            node.nextGuardianHealth = maxHp - interval;''',
'''            node.currentHealth = maxHp;
            node.damage.clear();
            removeGuardians(node);
            node.guardianWave = 0;
            node.nextGuardianHealth = maxHp - interval;''',
    1,
)

s = s.replace(
'''    private void resumeEventVisuals() {
        double interval = Math.max(1.0, getConfig().getDouble("guardians.every-health", 300.0));
        for (MetinNode node : nodes.values()) {
            node.guardians.clear();
            node.nextGuardianHealth = Math.floor((node.currentHealth - 0.0001) / interval) * interval;
            if (node.currentHealth > 0) {
                spawnHologram(node);
                updateHologram(node);
            }
        }
    }''',
'''    private void resumeEventVisuals() {
        double interval = Math.max(1.0, getConfig().getDouble("guardians.every-health", 300.0));
        double maxHp = getConfig().getDouble("metin.max-health", 1000.0);
        for (MetinNode node : nodes.values()) {
            node.guardians.clear();
            node.nextGuardianHealth = Math.floor((node.currentHealth - 0.0001) / interval) * interval;
            node.guardianWave = Math.max(0, Math.min(3,
                    (int)Math.floor((maxHp - node.currentHealth + 0.0001) / interval)));
            if (node.currentHealth > 0) {
                spawnHologram(node);
                updateHologram(node);
            }
        }
    }''',
    1,
)

s = s.replace(
'''        if (node.nextGuardianHealth > 0.0 && node.currentHealth <= node.nextGuardianHealth) {
            spawnGuardians(node);
            node.nextGuardianHealth -= interval;
        }''',
'''        if (node.nextGuardianHealth > 0.0 && node.currentHealth <= node.nextGuardianHealth) {
            node.guardianWave = Math.min(3, node.guardianWave + 1);
            spawnGuardians(node, node.guardianWave);
            node.nextGuardianHealth -= interval;
        }''',
    1,
)

start = s.index("    private void spawnGuardians(MetinNode node) {")
end = s.index("    private Location findSafeGuardianSpawn", start)
replacement = r'''    private void spawnGuardians(MetinNode node, int wave) {
        World world = Bukkit.getWorld(node.world);
        if (world == null) return;

        wave = Math.max(1, Math.min(3, wave));
        String wavePath = "guardians.waves." + wave;
        int count = Math.max(1, getConfig().getInt(wavePath + ".count",
                getConfig().getInt("guardians.count", 3)));

        EntityType type;
        try {
            type = EntityType.valueOf(getConfig().getString(wavePath + ".type",
                    getConfig().getString("guardians.type", "HUSK")).toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            type = EntityType.HUSK;
        }

        double defaultHealth = wave == 1 ? 90.0 : wave == 2 ? 125.0 : 170.0;
        double defaultAttack = wave == 1 ? 8.5 : wave == 2 ? 10.5 : 12.5;
        double defaultSpeed = wave == 1 ? 0.27 : wave == 2 ? 0.29 : 0.31;
        double defaultKb = wave == 1 ? 0.20 : wave == 2 ? 0.30 : 0.40;

        double health = Math.max(1.0, getConfig().getDouble(wavePath + ".health", defaultHealth));
        double attack = Math.max(1.0, getConfig().getDouble(wavePath + ".attack-damage", defaultAttack));
        double speed = Math.max(0.05, getConfig().getDouble(wavePath + ".movement-speed", defaultSpeed));
        double kb = Math.max(0.0, Math.min(1.0,
                getConfig().getDouble(wavePath + ".knockback-resistance", defaultKb)));

        node.guardians.clear();
        Set<String> usedBlocks = new HashSet<>();

        for (int i = 0; i < count; i++) {
            Location spawn = findSafeGuardianSpawn(node, usedBlocks);
            usedBlocks.add(spawn.getBlockX() + ":" + spawn.getBlockY() + ":" + spawn.getBlockZ());

            playGuardianSpawnEffect(spawn);
            Entity raw = world.spawnEntity(spawn, type);
            if (!(raw instanceof LivingEntity mob)) {
                raw.remove();
                continue;
            }

            if (mob.getAttribute(Attribute.MAX_HEALTH) != null) {
                mob.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health);
                mob.setHealth(Math.min(health, mob.getAttribute(Attribute.MAX_HEALTH).getValue()));
            }
            if (mob.getAttribute(Attribute.ATTACK_DAMAGE) != null)
                mob.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(attack);
            if (mob.getAttribute(Attribute.MOVEMENT_SPEED) != null)
                mob.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(speed);
            if (mob.getAttribute(Attribute.KNOCKBACK_RESISTANCE) != null)
                mob.getAttribute(Attribute.KNOCKBACK_RESISTANCE).setBaseValue(kb);

            String defaultName = "&5&lMetin Muhafizi &7[&dSeviye %wave%&7]";
            String configuredName = getConfig().getString(wavePath + ".name",
                    getConfig().getString("guardians.name", defaultName));
            mob.setCustomName(color(configuredName.replace("%wave%", String.valueOf(wave))));
            mob.setCustomNameVisible(true);
            mob.setPersistent(false);
            mob.getPersistentDataContainer().set(guardianNodeKey, PersistentDataType.STRING, node.id);

            applyGuardianEquipment(mob, wave);
            node.guardians.add(mob.getUniqueId());

            if (mob instanceof Mob aiMob) {
                Player target = nearestParticipant(node, spawn);
                if (target != null) aiMob.setTarget(target);
            }
        }

        Location center = node.location().clone().add(0.5, 1.0, 0.5);
        playConfiguredSound(center, "guardians.spawn-sound", Sound.ENTITY_WITHER_SPAWN, 0.9f, 1.3f);

        double range = getConfig().getDouble("guardians.message-radius", 18.0);
        String action = getConfig().getString("guardians.spawn-actionbar",
                "&5&lMUHAFIZ DALGASI %wave%! &fMetine devam etmek icin onlari oldur.")
                .replace("%wave%", String.valueOf(wave));
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= range * range) {
                p.sendActionBar(color(action));
            }
        }
    }

    private void applyGuardianEquipment(LivingEntity mob, int wave) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;

        if (wave <= 1) {
            eq.setHelmet(new ItemStack(Material.IRON_HELMET));
            eq.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.CHAINMAIL_LEGGINGS));
            eq.setBoots(new ItemStack(Material.IRON_BOOTS));
            eq.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
        } else if (wave == 2) {
            eq.setHelmet(new ItemStack(Material.IRON_HELMET));
            eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
            eq.setBoots(new ItemStack(Material.IRON_BOOTS));
            eq.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
        } else {
            eq.setHelmet(new ItemStack(Material.DIAMOND_HELMET));
            eq.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
            eq.setBoots(new ItemStack(Material.DIAMOND_BOOTS));
            eq.setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        }

        eq.setHelmetDropChance(0f);
        eq.setChestplateDropChance(0f);
        eq.setLeggingsDropChance(0f);
        eq.setBootsDropChance(0f);
        eq.setItemInMainHandDropChance(0f);
    }

    private Player nearestParticipant(MetinNode node, Location from) {
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (UUID uuid : node.damage.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline() || p.getWorld() != from.getWorld()) continue;
            double d = p.getLocation().distanceSquared(from);
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
            }
        }
        return best;
    }

'''
s = s[:start] + replacement + s[end:]

marker = "    @EventHandler public void onGuardianDeath(EntityDeathEvent e) {"
idx = s.index(marker)
handlers = r'''    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGuardianTarget(EntityTargetLivingEntityEvent e) {
        String nodeId = e.getEntity().getPersistentDataContainer()
                .get(guardianNodeKey, PersistentDataType.STRING);
        if (nodeId == null || !(e.getTarget() instanceof Player target)) return;

        MetinNode node = nodes.get(nodeId.toLowerCase(Locale.ROOT));
        if (node == null || node.damage.containsKey(target.getUniqueId())) return;

        e.setCancelled(true);
        if (e.getEntity() instanceof Mob mob) {
            Player eligible = nearestParticipant(node, mob.getLocation());
            if (eligible != null) {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (mob.isValid() && !mob.isDead()) mob.setTarget(eligible);
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGuardianDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;

        String nodeId = e.getDamager().getPersistentDataContainer()
                .get(guardianNodeKey, PersistentDataType.STRING);
        if (nodeId == null) return;

        MetinNode node = nodes.get(nodeId.toLowerCase(Locale.ROOT));
        if (node == null || !node.damage.containsKey(victim.getUniqueId())) {
            e.setCancelled(true);
        }
    }

'''
s = s[:idx] + handlers + s[idx:]

s = s.replace(
'''                n.currentHealth = s.getDouble(
                        "health",
                        getConfig().getDouble("metin.max-health", 1000.0)
                );''',
'''                n.currentHealth = s.getDouble(
                        "health",
                        getConfig().getDouble("metin.max-health", 1000.0)
                );
                n.guardianWave = s.getInt("guardian-wave", 0);''',
    1,
)

s = s.replace(
'''            data.set(b + ".health", n.currentHealth);
            for (var d : n.damage.entrySet())''',
'''            data.set(b + ".health", n.currentHealth);
            data.set(b + ".guardian-wave", n.guardianWave);
            for (var d : n.damage.entrySet())''',
    1,
)

s = s.replace(
'''        double currentHealth, nextGuardianHealth;
        final Map<UUID, Double> damage''',
'''        double currentHealth, nextGuardianHealth;
        int guardianWave;
        final Map<UUID, Double> damage''',
    1,
)

java.write_text(s, encoding="utf-8")

config = dst_dir / "src/main/resources/config.yml"
c = config.read_text(encoding="utf-8")
c = c.replace('  name: "&5&lMetin Muhafizi"', '  name: "&5&lMetin Muhafizi &7[&dSeviye %wave%&7]"', 1)
needle = '''  knockback-resistance: 0.20
  spawn-radius-min: 2.0'''
waves = '''  knockback-resistance: 0.20
  waves:
    1:
      count: 3
      health: 90.0
      attack-damage: 8.5
      movement-speed: 0.27
      knockback-resistance: 0.20
    2:
      count: 3
      health: 125.0
      attack-damage: 10.5
      movement-speed: 0.29
      knockback-resistance: 0.30
    3:
      count: 3
      health: 170.0
      attack-damage: 12.5
      movement-speed: 0.31
      knockback-resistance: 0.40
  spawn-radius-min: 2.0'''
c = c.replace(needle, waves, 1)
c = c.replace('  spawn-actionbar: "&5&lMUHAFIZLAR ORTAYA CIKTI! &fMetine devam etmek icin onlari oldur."', '  spawn-actionbar: "&5&lMUHAFIZ DALGASI %wave%! &fMetine devam etmek icin onlari oldur."', 1)
config.write_text(c, encoding="utf-8")

pom = dst_dir / "pom.xml"
p = pom.read_text(encoding="utf-8").replace("<version>1.3.0</version>", "<version>1.4.0</version>")
p = p.replace("ZuxianMetin-1.3.0-Paper26.2", "ZuxianMetin-1.4.0-Paper26.2")
pom.write_text(p, encoding="utf-8")

plugin = dst_dir / "src/main/resources/plugin.yml"
p = plugin.read_text(encoding="utf-8").replace("version: 1.3.0", "version: 1.4.0")
plugin.write_text(p, encoding="utf-8")

print("Generated", dst_dir)
