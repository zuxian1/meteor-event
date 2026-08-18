from pathlib import Path
p = Path('zuxianmetinv14/src/main/java/com/zuxian/metin/ZuxianMetinPlugin.java')
s = p.read_text(encoding='utf-8')
needle = '    private void spawnGuardians(MetinNode node, int wave) {'
if '    private void spawnGuardians(MetinNode node) {' not in s:
    overload = '''    private void spawnGuardians(MetinNode node) {\n        node.guardianWave = Math.min(3, node.guardianWave + 1);\n        spawnGuardians(node, node.guardianWave);\n    }\n\n'''
    s = s.replace(needle, overload + needle, 1)
p.write_text(s, encoding='utf-8')
