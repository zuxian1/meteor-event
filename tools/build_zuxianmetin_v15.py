from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
subprocess.run(["python3", str(root / "tools/build_zuxianmetin_v14.py")], check=True)
subprocess.run(["python3", str(root / "tools/fix_zuxianmetin_v14.py")], check=True)

dst = root / "zuxianmetinv14"
java = dst / "src/main/java/com/zuxian/metin/ZuxianMetinPlugin.java"
s = java.read_text(encoding="utf-8")

# Only show ranking lines when that rank actually exists.
s = s.replace(
'''        lines.add(getConfig().getString("metin.hologram.line3", "&6#1 &e%top1_name% &7- &f%top1_damage%"));
        lines.add(getConfig().getString("metin.hologram.line4", "&f#2 %top2_name% &7- &f%top2_damage%"));
        lines.add(getConfig().getString("metin.hologram.line5", "&7#3 %top3_name% &8- &7%top3_damage%"));''',
'''        if (ranking.size() >= 1) lines.add(getConfig().getString("metin.hologram.line3", "&6#1 &e%top1_name% &7- &f%top1_damage%"));
        if (ranking.size() >= 2) lines.add(getConfig().getString("metin.hologram.line4", "&f#2 %top2_name% &7- &f%top2_damage%"));
        if (ranking.size() >= 3) lines.add(getConfig().getString("metin.hologram.line5", "&7#3 %top3_name% &8- &7%top3_damage%"));''',
1)

# Event shutdown now has its own visual/audio transition before bedrock.
s = s.replace(
'''            Block block = node.block();
            if (block != null && block.getType() != Material.BEDROCK) block.setType(inactive, false);
            removeHologram(node);''',
'''            Block block = node.block();
            if (block != null && block.getType() != Material.BEDROCK) {
                playMetinDeactivateEffects(node);
                block.setType(inactive, false);
            }
            removeHologram(node);''',
1)

marker = '''    private void playMetinRespawnEffects(MetinNode node) {'''
method = '''    private void playMetinDeactivateEffects(MetinNode node) {
        Location c = node.location().clone().add(0.5, 0.8, 0.5);
        World w = c.getWorld();
        if (w == null) return;
        w.spawnParticle(Particle.REVERSE_PORTAL, c, 55, 0.55, 0.75, 0.55, 0.08);
        w.spawnParticle(Particle.SMOKE, c, 30, 0.45, 0.55, 0.45, 0.035);
        w.spawnParticle(Particle.SOUL, c, 18, 0.40, 0.60, 0.40, 0.025);
        playConfiguredSound(c, "metin.sounds.deactivate-primary", Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 0.70f);
        playConfiguredSound(c, "metin.sounds.deactivate-secondary", Sound.BLOCK_BEACON_DEACTIVATE, 0.85f, 0.80f);
    }

'''
if method not in s:
    s = s.replace(marker, method + marker, 1)

# Version.
s = s.replace('1.4.0', '1.5.0')
java.write_text(s, encoding="utf-8")

config = dst / "src/main/resources/config.yml"
c = config.read_text(encoding="utf-8")

# Professional Turkish chat text; visual elements use small caps.
repls = {
'  title: "&5&lMETIN EVENTI BASLADI!"': '  title: "&5&lᴍᴇᴛɪɴ ᴇᴛᴋɪɴʟɪĞɪ ʙᴀŞʟᴀᴅɪ!"',
'  subtitle: "&fGitmek icin &d/warp metin"': '  subtitle: "&fᴇᴛᴋɪɴʟɪĞᴇ ᴋᴀᴛɪʟᴍᴀᴋ ɪÇɪɴ &d/warp metin"',
'    - "&5&lMETIN EVENTI BASLADI!"': '    - "&5&lMetin Etkinliği Başladı!"',
'    - "&fMetinleri kir, muhafizlari kes ve odulleri kap."': '    - "&fKadim Metinleri parçala, muhafızları yen ve ödüllerini kazan."',
'    - "&fGitmek icin: &d/warp metin"': '    - "&fEtkinlik alanına gitmek için: &d/warp metin"',
'    - "&c&lMETIN EVENTI SONA ERDI!"': '    - "&c&lMetin Etkinliği Sona Erdi!"',
'    - "&7Kalan metinler pasif hale getirildi."': '    - "&7Etkinlik süresi doldu. Kalan Metinler yeniden mühürlendi."',
'    line1: "&5&lKADIM METIN"': '    line1: "&5&lᴋᴀᴅɪᴍ ᴍᴇᴛɪɴ"',
'    line2: "&c❤ &f%health%&7/&f%max_health% HP"': '    line2: "&c❤ &f%health%&7/&f%max_health% ʜᴘ"',
'    line3: "&6#1 &e%top1_name% &7- &f%top1_damage%"': '    line3: "&6#1 &e%top1_name% &7• &f%top1_damage% ʜᴀꜱᴀʀ"',
'    line4: "&f#2 %top2_name% &7- &f%top2_damage%"': '    line4: "&f#2 %top2_name% &7• &f%top2_damage% ʜᴀꜱᴀʀ"',
'    line5: "&7#3 %top3_name% &8- &7%top3_damage%"': '    line5: "&7#3 %top3_name% &8• &7%top3_damage% ʜᴀꜱᴀʀ"',
'    line6: "&b⏱ &f%kalansure%"': '    line6: "&b⏱ &fᴋᴀʟᴀɴ ꜱÜʀᴇ: &b%kalansure%"',
'    guardians: "&5⚔ &cMuhafizlari oldur!"': '    guardians: "&5⚔ &cᴍᴜʜᴀꜰɪᴢʟᴀʀɪ ʏᴇɴ!"',
'  name: "&5&lMetin Muhafizi &7[&dSeviye %wave%&7]"': '  name: "&5&lᴍᴇᴛɪɴ ᴍᴜʜᴀꜰɪᴢɪ &7[&dꜱᴇᴠɪʏᴇ %wave%&7]"',
'  spawn-actionbar: "&5&lMUHAFIZ DALGASI %wave%! &fMetine devam etmek icin onlari oldur."': '  spawn-actionbar: "&5&lᴍᴜʜᴀꜰɪᴢ ᴅᴀʟɢᴀꜱɪ %wave%! &fᴍᴇᴛɪɴᴇ ᴅᴇᴠᴀᴍ ᴇᴛᴍᴇᴋ ɪÇɪɴ ᴍᴜʜᴀꜰɪᴢʟᴀʀɪ ʏᴇɴ."',
'  blocked-actionbar: "&cOnce Metin Muhafizlarini oldurmelisin!"': '  blocked-actionbar: "&cÖɴᴄᴇ ᴍᴇᴛɪɴ ᴍᴜʜᴀꜰɪᴢʟᴀʀɪɴɪ ʏᴇɴᴍᴇʟɪꜱɪɴ!"',
'  cleared-actionbar: "&aMuhafizlar yenildi! &fMetini kazmaya devam edebilirsin."': '  cleared-actionbar: "&aᴍᴜʜᴀꜰɪᴢʟᴀʀ ʏᴇɴɪʟᴅɪ! &fᴍᴇᴛɪɴɪ ᴘᴀʀÇᴀʟᴀᴍᴀʏᴀ ᴅᴇᴠᴀᴍ ᴇᴅᴇʙɪʟɪʀꜱɪɴ."',
'  break-actionbar: "&5&l✦ METIN PARCALANDI! ✦"': '  break-actionbar: "&5&l✦ ᴍᴇᴛɪɴ ᴘᴀʀÇᴀʟᴀɴᴅɪ! ✦"',
'  credits: "&fMetin Kredin: &d%credits%"': '  credits: "&fMetin Kredin: &d%credits%"',
'  next: "&fSonraki Metin Eventi: &d%time%"': '  next: "&fSonraki Metin Etkinliğine: &d%time%"',
'  active-remaining: "&fMetin Eventinin bitmesine: &d%time%"': '  active-remaining: "&fMetin Etkinliğinin bitmesine: &d%time%"',
'    - "&5&lMETIN ODULU &7- &e#%rank%"': '    - "&5&lMetin Ödülü &7- &e#%rank%"',
'    - "&fVerdigin Hasar: &c%damage% &7(%share%%)"': '    - "&fVerdiğin Hasar: &c%damage% &7(%share%%)"',
'    - "&fHasar Katsayin: &d%scale%%"': '    - "&fHasar Katkın: &d%scale%%"',
'    - "&fKazandigin Kredi: &d+%credits%"': '    - "&fKazandığın Kredi: &d+%credits%"',
'    - "&fMetin Parcacigi: &d+%fragments%"': '    - "&fMetin Parçacığı: &d+%fragments%"',
'    - "&fEk Oduller: &e%items%"': '    - "&fEk Ödüller: &e%items%"',
'  broadcast-break: "&5Metin parcandi! &e%winner% &fen fazla hasari verdi. &7(Son vurus: %killer%)"': '  broadcast-break: "&5Metin parçalandı! &e%winner% &fen yüksek hasarı verdi. &7(Son vuruş: %killer%)"',
'      message: "&5&lMETIN &fEventine &d1 saat &fkaldı! &7(/warp metin)"': '      message: "&5&lMETİN &fEtkinliğinin başlamasına &d1 saat &fkaldı. &7(/warp metin)"',
'      message: "&5&lMETIN &fEventine &d15 dakika &fkaldı! &7Hazırlan!"': '      message: "&5&lMETİN &fEtkinliğinin başlamasına &d15 dakika &fkaldı. &7Hazırlan!"',
'      message: "&5&lMETIN &fEventine &d1 dakika &fkaldı! &7(/warp metin)"': '      message: "&5&lMETİN &fEtkinliğinin başlamasına yalnızca &d1 dakika &fkaldı! &7(/warp metin)"',
}
for a, b in repls.items():
    c = c.replace(a, b)

# End-of-event effects are configurable.
c = c.replace('    respawn-secondary: BLOCK_BEACON_ACTIVATE', '    respawn-secondary: BLOCK_BEACON_ACTIVATE\n    deactivate-primary: BLOCK_RESPAWN_ANCHOR_DEPLETE\n    deactivate-secondary: BLOCK_BEACON_DEACTIVATE')
config.write_text(c, encoding="utf-8")

pom = dst / "pom.xml"
p = pom.read_text(encoding="utf-8").replace("<version>1.4.0</version>", "<version>1.5.0</version>")
p = p.replace("ZuxianMetin-1.4.0-Paper26.2", "ZuxianMetin-1.5.0-Paper26.2")
pom.write_text(p, encoding="utf-8")

plugin = dst / "src/main/resources/plugin.yml"
p = plugin.read_text(encoding="utf-8").replace("version: 1.4.0", "version: 1.5.0")
plugin.write_text(p, encoding="utf-8")
print("Generated final v1.5")
