# AudioBridge v1.1.0

**Dva režimy přenosu zvuku pro Android 8 až 16:**

1. **Bluetooth Peer → jack (NOVINKA)** – na jednom telefonu spusť přijímač, připoj k němu kabelová 3,5mm / USB-C jack sluchátka; na *druhém Android telefonu se stejnou aplikací* vyber první telefon a spusť vysílání mikrofonu. Zvuk jde přímo přes šifrované Bluetooth Classic RFCOMM/SPP spojení do aplikace a následně přes zvolený výstup do sluchátek. Formát 22 050 Hz, mono, PCM16 (hlas).
2. **USB / mikrofon → reproduktor nebo sluchátka** – původní směrování AudioRecord do AudioTrack s volbou zdroje/výstupu a zesílení 0–200 %.

## Výslovné omezení
**AudioBridge není Bluetooth A2DP Sink** a na běžném nerootnutém Androidu nemůže přijmout hudbu z libovolného Bluetooth vysílače, například TV, PC nebo jiného telefonu, pokud na vysílajícím zařízení neběží kompatibilní AudioBridge aplikace. Příjem libovolného standardního A2DP signálu vyžaduje podporu výrobce/firmwaru nebo externí Bluetooth audio přijímač. Bluetooth Peer režim zatím posílá **mikrofon druhého telefonu, nikoliv zvuk jiných aplikací**. Hudbu z aplikací neodposlouchává.

## Jak otestovat
- Nainstaluj stejné APK na oba Android telefony a spáruj je v systémovém nastavení Bluetooth.
- Na přijímacím telefonu připoj jack sluchátka a otevři **Bluetooth → jack (dva telefony)**. Vyber jejich výstup a stiskni **Zapnout Bluetooth přijímač**.
- Na vysílajícím telefonu otevři stejnou obrazovku, vyber ze spárovaných zařízení přijímací telefon a stiskni **Vysílat mikrofon**.
- Oprávnění: BLUETOOTH_CONNECT na Androidu 12+ a RECORD_AUDIO na vysílači. Zastavení funguje i z notifikace. Pokud se zvuk nepřehrává do požadovaných sluchátek, záleží na tom, zda Android dovolí zvolenou audio trasu.

## Build
Verze 1.1.0, versionCode 2, minSdk 26, target/compileSdk 36, Java 17.
GitHub Actions → Build APK and AAB → Artifacts **AudioBridge-v1.1.0-APK-AAB**.
Debug APK lze instalovat. Release AAB v workflow je *nepodepsané* a do Google Play se bez podpisu neodesílá.
Bez internetu, bez reklamy, bez analytics; přenos je mezi zařízeními (šifrované RFCOMM).
