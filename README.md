# DK Alarm – textová detekce, pracovní verze 0.3.0

**Stav: zdrojový projekt, nikoli dokončené instalační APK.** V tomto prostředí nebyl dostupný Android SDK ani Gradle a stažení nástrojů neprošlo. Android část není zkompilovaná ani ověřená na telefonu. Rozpoznávání celostránkového pozitivního vzorku zatím nesplňuje akceptační test. Podrobnosti jsou v `TEST_REPORT.md`.

## Co je implementované

- Výběr obrázku a přijetí ručního sdílení / otevření obrázku z jiné aplikace.
- Lokální OCR pomocí přibaleného ML Kit. Manifest odstraňuje síťová oprávnění i ze závislostí; skutečný sloučený manifest je nutné ověřit po sestavení.
- Oddělení řádků béžové tabulky podle pozadí a sloupců podle svislých hran; podporované podklady zahrnují orientaci na výšku i na šířku a oříznuté záhlaví.
- Šlechta se potvrzuje výhradně přesným textem `Šlechta` / `Slechta` ve sloupci povelu. Diakritika a velikost písmen nevadí. Jiné nebo poškozené názvy jednotek jsou nejisté; nemění se automaticky na potvrzenou Šlechtu. Detektor korunky byl odstraněn.
- Sloupec výchozí vesnice se nepoužívá k detekci Šlechty. Negativní vzorek obsahující výchozí vesnici `Šlechta (421|550)` je součástí testů.
- Název cílové vesnice se uchovává samostatně od souřadnic. Příchod se čte pouze ze sloupce Příchod, nikdy jako náhradní údaj z Dorazí za / Strážní věž.
- Časy mají přesnost na sekundy. Millisekundová přípona je zahozena a její nečitelnost není chyba.
- Výběr data pořízení obrázku; podpora `dnes`, `zítra`, `dne dd.MM.` a explicitního roku. Herní časová zóna je `Europe/Prague`. Nečitelný den se musí potvrdit; minulý čas se neposouvá automaticky na zítřek.
- Alarm minutu před příchodem: Šlechta jakékoliv barvy, červený a hnědý útok. Zelený bez Šlechty se nealarmuje.
- Rozestup 55–65 sekund tvoří minutovou sérii v jedné cílové vesnici. Zazní první, třetí, pátý atd. Události v jedné sekundě se spojí do jednoho zvukového slotu. Jiná vesnice má nezávislou sérii.
- Trvalé uložení všech potvrzených důležitých útoků, včetně potlačených a odehraných. Díky tomu se pořadí série neztrácí mezi importy či po restartu. Duplicitní import nevytváří další shodné alarmy.
- Přehled uložených alarmů, jejich stav a zrušení jednotlivě či hromadně. Obnovení po restartu, změně času a návratu do aplikace.
- `AlarmManager.setAlarmClock`, kontrola přesných alarmů a notifikací, vlastní zvuk Šlechty, lokální služba pro zvuk, ztišení a minutový limit zvuku. Souběžné alarmy se zobrazí společně a Šlechta má zvukovou prioritu.
- U každého útoku se samostatně ukládá `villageName`, `coordinates`, `arrivalTime`, `isNoble`, `nobleState` a `attackColor`. Název notifikace obsahuje všechny rozpoznané typy i vesnici.

## Co není dokončené

1. Sestavení Kotlin/Android části, lint a přístrojové testy nebyly spuštěny. Nelze zatím zaručit bezchybnou kompilaci nebo běh.
2. Celostránkový vzorek má velmi drobný text. Lokální Tesseract jej nečte dostatečně; ML Kit musí projít skutečnými přístrojovými testy. Nesmí se označit za hotové jen proto, že negativní vzorky vrátí nulu.
3. Barevný detektor je konzervativní: automaticky zkouší zelenou a červenou. Hnědou bez ověřeného pozitivního barevného vzorku ponechá jako UNKNOWN. Uživatel ji může potvrdit ručně. Barevnou detekci a její kalibraci ještě otestovat na telefonu.
4. Nebyly ověřeny zamčená obrazovka, režim spánku, restart a chování konkrétního Samsungu. Test zvuku z otevřené aplikace sám nenahrazuje test naplánovaného alarmu.

## Jak sestavit

Projekt otevři v Android Studiu, nainstaluj JDK 17, SDK Platform 36 a Build Tools 36.0.0. Potřebuje přístup ke Google Maven, Maven Central a Gradle distribuci.

```sh
chmod +x gradlew
./tests/run-core-tests.sh
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest lintDebug
```

Windows: `BUILD_APK_WINDOWS.bat` připraví potřebné nástroje a spustí sestavení. Skript nebyl v tomto prostředí proveden.

Výstup: `app/build/outputs/apk/debug/app-debug.apk`.

Je také připraven `.github/workflows/build-apk.yml`; workflow sestaví aplikaci a balíček přístrojových testů. Sám nenahrazuje spuštění testů na zařízení.

Balíček je `com.dkalarm.text`, záměrně samostatný od starých verzí. Staré APK ani podpisový klíč nejsou přibalené. Při budoucí instalaci zruš alarmy v původní aplikaci, aby neběžely oba programy současně. Pro aktualizace nové aplikace je nutné zachovat její podpisový klíč.

## Povinné testy před předáním APK

Připoj Android telefon s USB laděním nebo spusť emulátor:

```sh
./gradlew connectedDebugAndroidTest
```

`ScreenshotAcceptanceTest` požaduje 0 Šlecht na obou negativních obrázcích, 4 na zvětšeném pozitivním a 12 na celostránkovém pozitivním. Ověřuje i cílové souřadnice a čitelnost času. Tyto testy jsou připravené, ale dosud NEBYLY spuštěny. Pokud selžou, upravit OCR a zopakovat; nepřepsat očekávané počty jen kvůli zelenému výsledku.

Na fyzickém telefonu navíc ověřit:

- ruční sdílení screenshotu a opětovný import bez duplicit;
- rozpoznané názvy, barvy, dny a časy proti zobrazenému řádku;
- minutu před dopadem při zhasnuté obrazovce a uspání telefonu;
- série 1/3/5 pro dvě různé vesnice, včetně postupného importu;
- dva souběžné alarmy a jejich úplná oznámení;
- ztišení, obnovení po restartu a odmítnutá/odebraná oprávnění;
- sloučený manifest bez oprávnění INTERNET.

## Postup v aplikaci po úspěšném sestavení a otestování

1. Povol oznámení a přesné alarmy; zkontroluj zvuk v záložce Alarmy.
2. Nastav skutečné datum pořízení screenshotu.
3. Vyber obrázek nebo ho sdílej do DK Alarmu.
4. Zkontroluj označené řádky. Je k dispozici zvětšený výřez posouvatelný do stran, úprava názvu, času, Šlechty i barvy. Nečitelné řádky můžeš odškrtnout.
5. Zvol Aktivovat potvrzené alarmy. Nevyřešené řádky zůstanou ke kontrole a neblokují ostatní.
6. V záložce Alarmy ověř aktivní a potlačené položky.

Aplikace nemá přihlášení, herní prohlížeč, obnovování stránky ani komunikaci s účtem. Vstupem jsou pouze obrázky zvolené uživatelem.

## Technické zdroje

- [ML Kit – přibalené OCR pro Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [Android – plánování alarmů](https://developer.android.com/develop/background-work/services/alarms)
- [AGP 8.10 – SDK 36, Gradle 8.11.1 a JDK 17](https://developer.android.com/build/releases/agp-8-10-0-release-notes)
