# DK Alarm 0.4.0 – ruční sken obrazovky

Ověřeno 3. 11. 2026. Zdrojový commit: `9b1dbd37f16b875fcda6973af1723c7a138ac02c`.
APK: `DKAlarm-0.4.0.apk`, 61 911 727 bajtů.
SHA-256: `5699d91467dd33c2d6f04d65f7587407a7f8e345dc288bdb88a85da4c9cf65b7`.

## Výsledky nové funkce

- Základní pravidla a převod RGBA s odsazením řádků: 59 kontrol prošlo.
- Android 15 (API 35): oba testy ScreenCaptureTest prošly.
- Android 16 (API 36): oba testy ScreenCaptureTest prošly.
- Odmítnutý systémový souhlas nevytvořil snímek ani aktivní relaci.
- Skutečná MediaProjection pořídila PNG testovacího displeje v nativním rozlišení; žádný zmenšený OCR náhled se nepoužil jako originál.
- Na API 35 měl snímek 1920 × 1080 pixelů. Rozpoznalo se 13 řádků a 3 Šlechty. Test kontroloval všechny tři cíle 555|417, zelenou barvu a časy 11:59:31, 11:59:31, 11:59:32.
- Po snímku zmizelo průběžné oznámení služby. Relace se ukončila.
- Sestavený manifest neobsahuje INTERNET, SYSTEM_ALERT_WINDOW ani BIND_ACCESSIBILITY_SERVICE. Obsahuje oprávnění foreground služby mediaProjection.
- Existující test nulových falešných Šlecht a ukládání/sérií alarmů také prošel.

## Rozsah a zbývající omezení

Tento výsledek ověřuje novou cestu pořízení a zpracování skutečného snímku. Neznamená bezchybné čtení libovolného screenshotu. Starší testy velmi drobných snímků stále odhalují chyby časů a test barev stále odhaluje jednu přehlédnutou červenou vlajku. Očekávané výsledky těchto testů nebyly sníženy. Řádky pod 24 pixelů vyžadují v nové verzi ruční potvrzení.

Na fyzickém Samsungu zatím nová funkce ověřena nebyla. Automatický test používá celou obrazovku; výběr samotné aplikace používá systémové API a obsluhu změny rozměrů, ale není pokryt samostatným automatizovaným scénářem. Export PNG a gesta náhledu nejsou součástí přístrojového testu.
