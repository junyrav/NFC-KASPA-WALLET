# NFC KASPA WALLET v0.1

Android/Kotlin prototype for the NFC KASPA WALLET project.

## v0.1 scope
- Testnet-only UI shell
- MIFARE Classic NFC detection
- UID, memory size, sectors and blocks display
- Placeholder KAS/KRW/USD balance UI
- No private key, seed, or mainnet funds are handled yet

## Planned security model
- Daily send: 1-of-3 NFC + Android biometric-gated Keystore
- Recovery: 2-of-3 encrypted recovery shares + recovery password
- NFC stores ciphertext only; UID is never treated as a secret
- Mainnet disabled until recovery and attack-path tests are completed

## Build
Open the folder in Android Studio (JDK 17), sync Gradle, then Run on an NFC-capable Android device.
