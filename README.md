# DK Alarm

Nativní Android aplikace pro analýzu ručně pořízených screenshotů Divokých kmenů. Zpracování probíhá lokálně přibaleným ML Kit. Aplikace nemá oprávnění INTERNET, přihlášení do hry ani automatické obnovování stránky.

## Použití

1. Nainstaluj APK, povol oznámení a přesné alarmy. V záložce Alarmy vyzkoušej zvuk.
2. Nastav datum pořízení screenshotu a vyber obrázek, nebo jej sdílej do DK Alarmu.
3. Zkontroluj rozpoznané útoky. Každý řádek lze upravit a prohlédnout na zvětšeném výřezu. Nečitelný čas ani nejistou Šlechtu aplikace nemá nahrazovat odhadem.
4. Stiskni Aktivovat potvrzené alarmy. Nevyřešené řádky zůstanou ke kontrole. V záložce Alarmy ověř uložené položky.

Zobrazuje se název cílové vesnice, Šlechta a barva samostatně, příchod a čas alarmu. Alarm dostane potvrzená Šlechta jakékoliv barvy a červený či hnědý útok. Zazní 60 sekund před příchodem. Zelený bez Šlechty se nealarmuje. Minulý čas se automaticky neposouvá na další den.

U série s rozestupy 55–65 sekund na jednu vesnici zazní 1., 3., 5. atd. Jiná vesnice má nezávislou sérii. Události ve stejné sekundě tvoří společný alarm; milisekundy se ignorují. Uložení potlačených i již odehraných položek zachovává pořadí při dalších importech a restartu. Opakovaný import shodných útoků nevytváří duplicity.

## OCR

Béžová tabulka se rozděluje na řádky a sloupce. Povel, Cíl a Příchod se čtou odděleně v blocích několika řádků, aby ikonky a výchozí vesnice nezasahovaly do názvu jednotky. Sloupec příchodu se navíc hledá podle textu dne.

Šlechta se potvrzuje textem Šlechta/Slechta ve sloupci povelu. Druhé čtení používá obraz se zvýrazněným textem. Omezené odchylky Sechta/Siechta vyžadují textové potvrzení ve druhém čtení. Korunka sama nikdy nepotvrzuje Šlechtu. Ostatní nečitelné názvy zůstávají NEJISTÉ / ZKONTROLOVAT.

Barevný detektor hledá zelené, červené a hnědé pixely ve vymezeném prostoru vlajky. Neznámou barvu lze ručně opravit. Přesnost závisí na rozlišení, čitelnosti a vzhledu tabulky; výsledky je potřeba zkontrolovat.

## Sestavení a ověření

JDK 17, Gradle 8.11.1, SDK Platform 36, Build Tools 36.0.0. Android 8 a novější.

```sh
sh tests/run-core-tests.sh
./gradlew assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

GitHub Actions v `.github/workflows/build-apk.yml` sestavuje APK a spouští skutečné testy ML Kit na emulátoru Android 15. APK je v artefaktu `DKAlarm-text-apk`, výsledky a logy v `android-test-results`. Samotné úspěšné sestavení není úspěšný test rozpoznávání.

Regresní sada zahrnuje čtyři původní snímky a pět nových snímků z 11. 9. 2026. Testy ověřují počty Šlecht, cílové souřadnice, časy a barvy. Snímky bez Šlechty musí vrátit nulu a současně dostatečný počet řádků. Zveřejněné kopie mají odstraněné okolí prohlížeče a údaje výchozích vesnic/hráčů; pixely povelu, cíle a příchodu zůstávají zachované.

`AlarmIntegrationTest` ověřuje skutečný AlarmManager, uložení, duplicity a nezávislé minutové série dvou vesnic. Testy na emulátoru nenahrazují ověření zvuku, zamčené obrazovky a úspor energie konkrétního telefonu.

Balíček: `com.dkalarm.text`. Debug APK z různých sestavení mohou mít jiný podpis. Pokud Android aktualizaci odmítne, je nutné před instalací odinstalovat starou verzi; tím se zruší její uložené alarmy. Pro distribuci s trvalými aktualizacemi je nutný stabilní soukromý podpisový klíč.
