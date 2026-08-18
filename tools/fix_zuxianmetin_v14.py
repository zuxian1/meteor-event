from pathlib import Path
p = Path('zuxianmetinv14/src/main/java/com/zuxian/metin/ZuxianMetinPlugin.java')
s = p.read_text(encoding='utf-8')

# Ensure the Metin node carries wave state even if the generator's formatting-based patch misses it.
if 'int guardianWave;' not in s:
    s = s.replace(
        'double currentHealth, nextGuardianHealth;',
        'double currentHealth, nextGuardianHealth; int guardianWave;',
        1,
    )

# Ensure each new event starts guardian difficulty from wave 0.
if 'node.guardianWave = 0;' not in s:
    s = s.replace(
        'node.currentHealth = maxHp; node.damage.clear(); removeGuardians(node); node.nextGuardianHealth = maxHp - interval;',
        'node.currentHealth = maxHp; node.damage.clear(); removeGuardians(node); node.guardianWave = 0; node.nextGuardianHealth = maxHp - interval;',
        1,
    )

# Keep compatibility if an old threshold call still invokes the one-argument method.
needle = '    private void spawnGuardians(MetinNode node, int wave) {'
if '    private void spawnGuardians(MetinNode node) {' not in s:
    overload = '''    private void spawnGuardians(MetinNode node) {\n        node.guardianWave = Math.min(3, node.guardianWave + 1);\n        spawnGuardians(node, node.guardianWave);\n    }\n\n'''
    s = s.replace(needle, overload + needle, 1)

p.write_text(s, encoding='utf-8')
