# MeteorEvent — Paper 26.2 Meteor Eklentisi

Sıfırdan yazılmış, ProtocolLib **gerektirmeyen** (yalnızca güncel Paper API'siyle),
sinematik meteor etkinliği eklentisi.

## Klasör Yapısı

```
meteor-event/
├── pom.xml
└── src/main/
    ├── java/com/example/meteorevent/
    │   ├── MeteorEventPlugin.java     ana sınıf (onEnable/onDisable, config, auto-schedule)
    │   ├── EventSettings.java         config.yml -> immutable record
    │   ├── MeteorManager.java         aktif meteorları ve "donmuş" oyuncuları yönetir
    │   ├── task/MeteorTask.java       çekirdek: düşüş animasyonu, kamera kilidi, çarpışma
    │   ├── util/DebrisUtil.java       vektör matematiğiyle enkaz saçılımı
    │   ├── listener/FreezeListener.java        hareket kilidi (yaw/pitch serbest)
    │   ├── listener/DebrisLandingListener.java enkazın kalıcı blok olmasını engeller
    │   └── command/MeteorCommand.java /meteor start|stop|reload
    └── resources/
        ├── plugin.yml
        └── config.yml
```

## Nasıl Derlenir

1. `pom.xml` içindeki `<paper.version>` etiketini sunucunun tam Paper API
   sürümüyle eşleştir (Paper 26.2 build'inin hangi `paper-api` artifact
   sürümüne karşılık geldiğini repo.papermc.io üzerinden kontrol et — takvim
   sürümü ile Maven artifact sürümü birebir aynı yazılmıyor).
2. `mvn clean package`
3. Oluşan `target/meteor-event-1.0.0.jar` dosyasını sunucunun `plugins/`
   klasörüne koy, sunucuyu başlat/reload et.

## Nasıl Çalışıyor — Mimari Kararlar

### 1) Etkinliğin Başlaması
`MeteorEventPlugin`, `config.yml`'deki `event.auto-interval-minutes` değeri
0'dan büyükse `Bukkit.getScheduler().runTaskTimer(...)` ile periyodik olarak
`MeteorManager.startMeteorEvent(world, x, z)` çağırır; koordinat, yapılandırılan
`auto-max-radius` içinde rastgele seçilir. Elle tetiklemek için:
`/meteor start [x] [z]`.

### 2) Sinematik Görüntü (Display Entity)
`MeteorTask`, spawn anında bir `BlockDisplay` (magma_block) oluşturur ve
`Transformation` matrisiyle **15-20 kat** büyütür (`config: size-multiplier-min/max`).
Akıcı hareket için ProtocolLib yerine Paper'ın **kendi client-side
interpolasyon** mekanizması kullanılıyor:

- `Display#setTeleportDuration(int)` → konum (pozisyon) geçişini,
- `Display#setInterpolationDuration(int)` + `setInterpolationDelay(int)` →
  transform (ölçek/dönüş) geçişini yumuşatır.

Her `interpolation-step-ticks` (varsayılan 2 tick) bir sonraki ara noktaya
`teleport()` ile taşınır; istemci bu adımlar arasında otomatik olarak
yumuşak geçiş (lerp) uygular — sunucudan paket paket "titrek" hareket yerine
sinematik bir akış elde edilir. Ayrıca `Transformation`'ın rotasyon bileşeni
her adımda güncellenerek meteor kendi ekseninde döner (`fall.spin-speed`).

### 3) Kamera ve Kontrol Kilidi (ProtocolLib Kullanılmadan)
Bu kısım tamamen güncel Paper API'siyle çözüldü:

- **Bakış kilidi:** Her tick, yarıçap içindeki her oyuncu için
  `player.lookAt(meteorLocation, LookAnchor.EYES, LookAnchor.EYES)` çağrılır
  (Paper'ın `io.papermc.paper.entity.LookAnchor` API'si). Bu, paket düzeyinde
  doğrudan görüş açısını değiştirir; hareket doğrulama zincirinden geçmez.
- **Hareket kilidi:** `FreezeListener`, `PlayerMoveEvent`'i dinler; sadece
  **konum (x/y/z)** değiştiyse olayı `to` konumunu `from`'a çevirerek etkisiz
  kılar, fakat `to`'nun yaw/pitch değerlerini korur — böylece bizim
  `lookAt()` ile zorladığımız bakış açısı ezilmez, oyuncu sadece yerinde
  "donmuş" gibi kalır. Uçuş/koşu gibi yan durumlar da (`PlayerToggleFlightEvent`
  vb.) etkinlik süresince iptal edilir.
- Çarpışmadan `release-before-impact-ticks` kadar önce oyuncular otomatik
  serbest bırakılır (kamera "geri çekiliyormuş" hissi vermesin diye).

### 4) Çarpışma ve Dinamik Blok Saçılması
`impact()` metodu tetiklendiğinde:

1. `BlockDisplay` kaldırılır.
2. `Particle.EXPLOSION_EMITTER`, `Particle.LAVA`, `Particle.LARGE_SMOKE`
   parçacıkları + `ENTITY_GENERIC_EXPLODE` / `ENTITY_WITHER_BREAK_BLOCK`
   sesleri çalınır.
3. `DebrisUtil.scatterDebris(...)` çağrılır — bu metot, çarpışma noktasından
   **küresel koordinatlarda** rastgele yön vektörleri üretir:

   ```
   theta (azimuth) ∈ [0, 2π)         → tam daire
   phi   (elevation) ∈ [5°, 80°]     → çok yatay veya çok dikey olmasın
   yön   = (cosθ·cosφ, sinφ, sinθ·cosφ)
   hız   ∈ [debris.min-speed, debris.max-speed]  (rastgele)
   ```

   Her yön/hız kombinasyonuyla bir `FallingBlock` (taş/kobblestone/magma/bazalt,
   `debris.materials` içinden rastgele) spawn edilip `setVelocity(yön × hız)`
   uygulanır — gerçekçi, birbirinden farklı yörüngelerde saçılan bir enkaz
   görüntüsü oluşur.
4. **Grief koruması:** `debris.place-blocks: false` (varsayılan) olduğunda,
   her enkaz parçası `PersistentDataContainer` ile işaretlenir;
   `DebrisLandingListener`, bu işaretli parçaların yere değip gerçek bir
   bloğa dönüşmesini `EntityChangeBlockEvent` üzerinden **kesin olarak**
   iptal eder (zamanlayıcıya güvenmez, yarış durumlarına karşı sağlamdır).
   Sunucun buna izin vermek isterse `debris.place-blocks: true` yapabilirsin
   — o zaman enkaz gerçekten yere düşüp kalıcı bloklara dönüşür.

## Yapılandırma (config.yml)
Tüm sayısal/davranışsal değerler (yükseklik, düşüş süresi, ölçek aralığı,
dönme hızı, yarıçap, enkaz sayısı/hızı/ömrü, patlama ayarları) `config.yml`
üzerinden değiştirilebilir; `/meteor reload` ile sunucuyu yeniden
başlatmadan uygulanır.

## Bilinen Sınırlamalar / Genişletme Fikirleri
- Şu an düşüş yörüngesi düz bir çizgi (spawn → impact); isteğe bağlı olarak
  `lerp()` yerine bir Bezier eğrisi kullanılarak "eğik açıyla gelen meteor"
  efekti eklenebilir.
- Ses/parçacık paketleri şu an tüm dünyaya (yakın oyunculara) gönderiliyor;
  çok büyük sunucularda `Player#playSound` ile sadece yarıçap içindekilere
  sınırlamak performans açısından daha iyi olabilir.
- Folia (bölgeli/region-threaded) sunucularda `BukkitScheduler` yerine
  Folia'nın kendi `RegionScheduler`/`EntityScheduler` API'sine geçiş gerekir;
  bu kod klasik Paper (non-Folia) için yazılmıştır.
