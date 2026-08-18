from pathlib import Path
p = Path('zuxianmetinv14/src/main/java/com/zuxian/metin/ZuxianMetinPlugin.java')
s = p.read_text(encoding='utf-8')
s = s.replace('            spawnGuardians(node);', '            node.guardianWave = Math.min(3, node.guardianWave + 1);\n            spawnGuardians(node, node.guardianWave);')
p.write_text(s, encoding='utf-8')
