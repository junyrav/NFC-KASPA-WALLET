# Kaspa NFC Wallet · v0.12.0 Mainnet

Android/Kotlin NFC wallet for Kaspa mainnet. The launcher label and in-app name are **Kaspa NFC Wallet**.

## Network and installation
- `kaspa:` P2PK mainnet addresses with checksum and spendable secp256k1 public-key validation. Testnet, ECDSA and script-hash recipient addresses are rejected.
- Displayed balance/history, mass calculation and broadcast use `https://api.kaspa.org`. Transfer UTXOs and fee estimates are cross-checked with two distinct public Kaspa wRPC node identities before preparation and again before signing. Pending acceptance also requires both nodes. Explorer links use `https://explorer.kaspa.org`.
- See [SECURITY.md](SECURITY.md) for the audit response, overwrite consent, V3 card migration and remaining limitations.

- The `mainnet` build installs as `com.nfckaspa.wallet.mainnet` alongside the earlier TN10 Preview app. It is not debuggable and does not import, overwrite or delete the Preview app's data.
- Mainnet public metadata and Android Keystore encryption use dedicated namespaces. Testnet coins are not converted into mainnet KAS.
- New wallets require a recovery password and distinct NFC cards. Existing compatible cards can be explicitly recovered with two card slots and the recovery password. Legacy daily payloads are never used for V3 signing: recover two cards and register V3 data. Follow the migration steps in SECURITY.md.
- Re-registering a physical card writes its daily payload. To retain separate independently generated wallets, use separate card sets.

## Wallet and transfers
- Teal wallet UI, square-ended NFC logo, Send/confirmation/submission/Receive screens, QR scanning, copy/paste and grouped settings.
- Public address is persisted; opening the wallet or checking balances/history needs no card.
- Incoming/outgoing network history is merged by wallet and TXID with local submissions, cached for later use, refreshed on opening history and paged with “이전 거래 더 보기”. Network approval is distinguished from submission.
- Exact integer sompi amounts and indicative KRW/USD market conversions.
- Confirmed recipient/amount/change and bounded fees are checked again before signing. Normal and maximum-send fees are calculated against the final outputs, including change-dependent mass. Invalid fee rates, foreign/duplicate UTXOs, insufficient funds and transactions exceeding 100,000 mass are rejected.
- Version-0 native P2PK signing is checked against Kaspa Core signature-hash and BIP-340 vectors. Each generated signature is verified locally. Inputs must belong to the selected wallet.
- Sending requires one wallet card and strong biometric authentication after confirming the destination, amount and fee.

## Cards and recovery
- Register three distinct MIFARE Classic cards. Each registration erases both wallet data banks after overwrite consent, writes the new V3 record and verifies both banks. Three distinct registered cards and an all-three-card verification (checking every two-card combination) are required for Send/Receive; there is no separate cleanup step.
- Recovery uses two different slots and the recovery password, preserving the original Shamir sharing polynomial and compatibility with untouched cards.
- NFC I/O retries up to five times. Writes are read back and compared before registration succeeds.
- Preferences contain public addresses, card metadata and transfer records only. Private keys, plaintext recovery shares and passwords are not persisted there.
- Newly generated keys remain in memory until backed up to cards. Keep the app open during initial registration.
- Android Keystore encryption is bound to the app/phone. After reinstalling or changing phones/packages, recover with two cards and re-register daily payloads.

## Build and validation
JDK 17, Gradle 8.9, Android SDK 35, NDK 28.2.13676358 and CMake 3.22.1. Initialize the pinned native source submodule and build the host JNI library for JVM tests first:

```sh
git submodule update --init --recursive
cmake -S app/src/main/cpp -B native-test -DCMAKE_BUILD_TYPE=Release
cmake --build native-test --parallel 2
bash ./gradlew --dependency-verification strict -PliveNodeChecks=true :app:testDebugUnitTest :app:lintDebug :app:lintMainnet :app:assembleMainnet
```

CI attaches unit test results, lint reports and deterministic native Compose screen renders. Tests cover official address/signing vectors, input ownership, fee convergence, maximum sends, network separation, incoming/outgoing history, storage, recovery and UI navigation.

Automated validation does not broadcast a funded mainnet transaction or substitute for physical NFC/biometric verification. No user funds are moved by CI.

Delivered mainnet APKs are signed after CI using the retained private delivery key, which is not committed. Raw mainnet Actions artifacts are unsigned. Future delivered mainnet APKs retain the same package and private certificate for in-place updates.

Debug builds use a separate package, disable real mainnet creation/NFC/broadcast and are not distributed by CI. See SECURITY.md for important Classic-card, previous-ciphertext, native/JVM memory and public-node trust limitations.

Initial interrupted registration can continue on the original phone using one committed current-generation card, its password and biometric Keystore authorization. This does not replace two-card recovery after reinstall or on another phone. Settings can temporarily allow screenshots; generated plaintext passwords remain protected. New password minimum is 8 characters at the owner's request; longer random passwords remain recommended.
