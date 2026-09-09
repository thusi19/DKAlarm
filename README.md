# DK Alarm

Android aplikace pro ruční analýzu screenshotu přehledu příchozích útoků v Divokých kmenech a vytvoření přesných lokálních alarmů.

## Zásadní omezení

DK Alarm se **nikdy nepřihlašuje do hry**, neobnovuje web, nečte účet na pozadí, neposílá herní příkazy a neprovádí herní akce. Vstupem je pouze obrázek, který uživatel ručně vybere nebo nasdílí.

OCR je záměrně bundlované přes ML Kit (`com.google.mlkit:text-recognition:16.0.1`), takže model je součástí aplikace a analýza nevyžaduje odesílání screenshotu na server.

## Hlavní tok

1. Uživatel udělá screenshot přehledu útoků.
2. Screenshot otevře přes **Vybrat screenshot** nebo použije Android **Sdílet → DK Alarm**.
3. `ScreenshotAnalyzer` provede OCR, detekci řádků, barvy, korunky a kontrolu času.
4. Uživatel výsledek opraví nebo potvrdí.
5. Nejasná korunka nebo čas blokují vytvoření alarmů.
6. Důležité útoky se plánují přes `AlarmManager.setExactAndAllowWhileIdle()`.

## Výchozí pravidla

- korunka = **ŠLECHTA**, bez ohledu na barvu,
- nejistá korunka = **MOŽNÁ ŠLECHTA – ZKONTROLOVAT**,
- bez korunky + červená → alarm,
- bez korunky + hnědá → alarm,
- bez korunky + zelená → bez minutového alarmu.

Předstih lze nastavit na 30 / 45 / 60 / 90 / 120 sekund nebo vlastní hodnotu.

## Build

GitHub Actions automaticky spouští unit testy a sestaví debug APK. Hotový soubor je v artifactu `DKAlarm-debug-apk`.
