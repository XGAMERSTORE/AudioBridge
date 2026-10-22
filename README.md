# AudioBridge – USB / Bluetooth to Speaker or Headphone

Android native aplikace (Java, bez dalších knihoven). Výběr mikrofonu/USB audio vstupu, výběr vestavěného, USB, kabelového nebo Bluetooth audio výstupu. Živý 16-bit PCM, zesílení 0–200 %, notifikace pro zastavení.

**Pozor:** nejde o Bluetooth A2DP sink a nepřijímá hudbu z jiného telefonu přes Bluetooth. Bluetooth mikrofony používající HFP nemusí fungovat s jiným výstupem, protože směrování řídí Android. USB musí být systémem rozpoznané vstupní audio zařízení (mikrofon/line-in USB zvukovka).

Android minSdk 26, target/compileSdk 36, versionCode 1, versionName 1.0.0.

## Build
Actions → **Build APK and AAB** → run or počkej na push. V úspěšném běhu otevři **Artifacts → AudioBridge-v1.0.0-APK-AAB**. Obsažený debug APK lze instalovat; release AAB je nepodepsané a pro Google Play vyžaduje podpis.

Lokální build: Android SDK 36, JDK 17, Gradle 8.13: `gradle assembleDebug bundleRelease`.

**Oprávnění:** RECORD_AUDIO; BLUETOOTH_CONNECT pro Bluetooth od Android 12; foreground service microphone; notifications od Android 13. Aplikace nepotřebuje internet a audio nikam neodesílá.
