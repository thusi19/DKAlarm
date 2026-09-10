# Architektura pracovní verze

`core/Rules.java` je čisté Java jádro: potvrzení jednotky z textu, normalizace, HH:mm:ss, datum a 55–65sekundové série oddělené podle vesnic. `core/TableGeometry.java` rozděluje obrázek na béžové řádky a detekuje svislé hranice sloupců. `core/TableParser.java` dostává OCR slova se souřadnicemi; Šlechtu hledá jen v povelu a příchod jen v příslušném sloupci.

`analysis/ScreenshotAnalyzer.kt` spouští přibalený ML Kit postupně na zvětšených řádcích. Nepoužívá korunku. Barva je nezávislá vlastnost; neověřené hnědé odstíny vrací UNKNOWN.

`model/Models.kt` odděluje jméno, souřadnice, datum+čas, příznak Šlechty, jistotu jednotky a barvu. `MainActivity` přijímá obrázky, ViewModel vlastní stav a běh analýzy. Compose obrazovka nabízí kontrolu, opravy a správu alarmů.

`storage/AlarmStore.kt` ukládá potvrzené důležité útoky do lokálního JSON v privátních SharedPreferences. Klíč nezávisí na screenshotu. Záznamy po zaznění zůstávají jako historie pro pořadí série.

`alarm/AlarmScheduler.kt` počítá aktivní sloty z celého uloženého seznamu. Sloty jsou identifikované URI, čímž se vyhne kolizím celočíselných hashů PendingIntent. Přeregistrace nejprve zruší dřívější sloty. Oprávnění se kontrolují před registrací. V Androidu používá setAlarmClock.

`AlarmReceiver` před spuštěním kontroluje, že slot je stále platný. `AlarmSoundService` spojí souběžná oznámení, používá alarmový audio kanál, ztišení a časový limit. `BootReceiver` obnovuje budoucí alarmy po restartu, aktualizaci a změnách času.

Hranice ověření: čisté Java jádro a geometrie jsou spustitelné lokálně. Lokální test obrázků používá Tesseract pouze jako aproximaci vstupu do stejného parseru. Android/ML Kit/Kotlin a AlarmManager vyžadují sestavení a přístrojové testy. Viz TEST_REPORT.md.
