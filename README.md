# Kaspa NFC Wallet · v0.8.2 TN10

Android/Kotlin wallet for Kaspa **TN10 testnet only**. All network clients are fixed to the TN10 API.

## Wallet
- A public wallet address is saved on creation/recovery and loaded on later launches.
- Opening the wallet and refreshing its balance do not require an NFC tag.
- Reference-matched teal wallet: dark gradient balance header, four action tiles, address controls and dated A/B/C card status.
- Dedicated Send, confirmation, submission-complete and Receive screens, plus grouped Settings and fixed bottom navigation.
- QR camera scanner, clipboard paste, fee-aware maximum send, TXID copy and TN10 Explorer links.
- Public market quotes provide indicative KRW/USD conversions only; TN10 coins have no cash value. Currency selection is saved locally.
- Receive address/QR, exact 8-decimal amount validation, fee preview and send confirmation.
- Sending requires one registered NFC card followed by strong Android biometric authentication.
- Transaction history contains submissions made by this app on this device; submission is not a confirmation count or complete network history.

## Cards and recovery
- Register three distinct MIFARE Classic cards before using a new wallet. At least two registered cards are required to enable Send/Receive.
- Recovery uses two different card slots plus the recovery password.
- Recovery reconstructs the **original sharing polynomial**. Re-registering one card therefore preserves compatibility with unchanged cards.
- After an app restart, card registration requires unlocking with two cards and the recovery password. The public wallet remains available without this step.
- Writes are read back and compared before registration is reported complete. NFC I/O runs off the UI thread and retries up to five times.
- The app persists public address, registration metadata and local transfer history only. Private keys, plaintext recovery shares and passwords are not stored in app preferences.
- A newly generated key exists only in memory until cards are registered. Do not close the app during initial card registration.
- Android Keystore encryption is bound to the phone. After reinstalling/changing phones, recover with two cards and re-register cards to refresh daily payloads.

## Build and checks
JDK 17, Gradle 8.9, Android SDK 35:

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Automated checks cover all recovery pairs and unchanged-card compatibility, invalid shares, amount precision/overflow, address round trips, persistence across new contexts, and simulated activity launch/recreation/navigation/Send/Receive screens.

Native Compose render images for the reference screens are attached to CI as `wallet-visual-review`. Screenshot fixtures are deterministic test data, not a real wallet.

Physical NFC reliability, Android Keystore/biometric behavior and a complete TN10 transfer require a real NFC-capable Android phone. No mainnet support is enabled.

Install updates in place without uninstalling. CI restores a stable debug signing key to preserve app data and device Keystore access between builds.

## Preview package and signing migration
The `preview` build installs alongside the existing app as `com.nfckaspa.wallet.preview`, with the same wallet functionality. It does not read or delete the existing app's data. Recover with two NFC cards and the recovery password to load the wallet in this package. Signing in a different package requires re-registering its daily card payloads after recovery. Recovery shares are preserved; an old app's daily payload is not shared across Android app UIDs.

Earlier CI builds cached `~/.android/debug.keystore`, but AGP writes the key under `~/.config/.android/debug.keystore` on this runner. Those older signing keys were not retained. The workflow now caches both paths and reports its certificate. A legacy APK signed with an unretained key cannot receive a normal same-package update from a different certificate; do not uninstall it just to review the new design. Use the side-by-side preview instead.
