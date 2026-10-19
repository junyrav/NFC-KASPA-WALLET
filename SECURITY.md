# Security review follow-up: 0.10.0-mainnet

This document supersedes the 0.9.x behavior descriptions. Baseline reviewed by the external auditor: `acdd1c44230826d42c0cb213cc326ae898dba25c`. This is an implementation response, not an independent security certification. Review the final commit and CI results together.

## Audit disposition

| Finding | Software change / remaining boundary |
| --- | --- |
| F01 unspendable P2PK recipients | Recipient validation checks mainnet encoding, checksum, version, 32-byte x-coordinate, x < secp256k1 field prime and lift to a valid curve point. Form feedback, clipboard, QR, prepare, confirmation and signing enforce it. A valid address does not prove recipient ownership. The generic encoding parser retains official encoding-vector compatibility. |
| F02 previous bank/password fallback | Recovery decrypts only the newest structurally committed record. V3 authenticates a random registration generation in both AEAD ciphertexts. Send/Receive require three current-generation cards, independent two-card recovery and read-back-confirmed cleanup of the inactive bank on all three cards. Old data is preserved until that recovery test succeeds. |
| F03 Classic card access / cloning | NOT eliminated. Existing Classic sector access remains compatible; no untested trailer/key rotation is performed. The app warns that these cards are clonable and destructively writable. AEAD protects ciphertext integrity, not card availability or physical authenticity. Secure-card hardware requires a separate implementation and physical validation. |
| F04 interrupted password rotation | Each recovery tag can use its own password, so mixed-generation/password cards can recover the same authenticated wallet. Mixed cards do not satisfy current-generation backup verification. Resuming registration proves the proposed password against an already written current-generation card before freezing it for the rest of that generation. Registration can explicitly restart with a new generation after two-card unlock. |
| F05 damaged / stuck pending payment | Before POST, signed bytes and intent are durably saved in a checksummed preference plus AtomicFile mirror. One valid copy can recover the other; conflicting or doubly corrupt copies remain locked. Explicit manual abandonment archives the record before clearing, requires an acknowledgement and typing the wallet's last eight characters, and is bound to the displayed TXID. This is NOT cancellation; duplicate payment remains possible if the owner overrides the lock. |
| F06 HTTP rejection unlock | HTTP 400/422, timeout, not-found, unspent inputs and mere submission acknowledgement never automatically clear the lock. Reconciliation requires a positively accepted original TXID or positively accepted competing transaction spending an original outpoint. Otherwise only the identical stored request may be retried, after local TXID validation. |
| F07 storage-priority overpricing | At the supported 100 sompi/gram relay floor, fee admission uses max(compute, normalized transient) mass, as in Rust node policy. Storage mass still applies to standard-transaction limits and higher-rate priority pricing. Absolute 0.01 KAS and max(0.0001 KAS, 1% recipient amount) fee caps remain. The confirmation explains possible congestion delay. Some very small splits still exceed standard mass; no amount is silently changed. |
| F08 debug artifacts | Debug uses a separate `.debug` application ID. It cannot create actual mainnet keys, access real mainnet NFC records or POST transfers through the broadcast helper. CI no longer distributes debug APKs. The delivered `.mainnet` package remains non-debuggable, backup-disabled and signed offline with the existing delivery certificate. |
| F09 build dependencies | Actions are pinned to commit SHAs. Gradle 8.9 wrapper JAR and distribution are pinned to official SHA-256 values. Dependency SHA-256 metadata is generated from the configured official repositories, committed, then enforced in strict verification builds. This initial checksum enrollment is trust-on-first-use, not independent publisher authentication. AndroidX biometric 1.1.0 remains the stable channel; an alpha is not substituted merely for a higher version number. |
| F10 wall-clock sequence | Each new card sequence is the latest committed per-card sequence plus one, with checked overflow. Wall-clock changes cannot roll it backwards. |
| F11 overwrite consent scope | Consent binds UID, old snapshot digest, target slot, new record digest and a 60-second elapsed-time lifetime. It is consumed on a write attempt. Changing the card or prepared record requires new consent. UID is not an anti-cloning proof. |

## Upgrade / card migration

1. Install as an update of `com.nfckaspa.wallet.mainnet`, retaining app data and Android Keystore. Do not uninstall to update. Keep all old cards, all passwords needed for them, and the original wallet address.
2. Unlock in Card Management using two distinct slot cards. Enter the appropriate password before each tag. Legacy cards on a fresh phone require the original mainnet address for identity validation.
3. Choose and confirm a strong new recovery password; provision A/B/C into a new V3 generation. First tag reads the target, then strong biometric authorization prepares its record; re-tag the same card to write. Occupied cards require explicit overwrite consent and another matching tag. This is intentional multi-step behavior.
4. After all three are read-back verified, secrets lock. Re-enter the new password and independently recover using two current-generation cards.
5. Enter the new password and choose **이전 데이터 정리**. Tag A/B/C to authenticate the current record, erase only its inactive bank and read back both regions. Only then does the backup gate enable Send/Receive. Cleanup is retryable after RF interruption; the active bank is preserved.
6. If provisioning was interrupted, recover with two available cards using each card's password. Resume with the same new password and a current-generation card, or explicitly restart registration after unlock. Do not discard old passwords until all three new cards and cleanup have been verified.

Prior to the independent recovery test the journal deliberately retains a previous committed bank. It must not be advertised as old-password revocation during migration. On Classic, an attacker can corrupt a newer bank or rewrite the card regardless of this app's policy. After cleanup the previous ciphertext on these banks is absent, but copies made earlier are unaffected. Re-encrypting shares of the same wallet is not private-key rotation. If old weak-password ciphertext may have leaked, moving funds to a fresh wallet is the effective response; the app never moves funds automatically.

## Controls retained

- Daily ciphertext uses Android Keystore AES-GCM with per-operation strong biometric CryptoObject authorization and enrollment invalidation; recovery uses Argon2id v1.3 (64 MiB, 3 passes, parallelism 1) plus AES-GCM.
- Version, slot, public identity, sequence and V3 generation are AEAD-bound. Recovery checks the reconstructed private key against the expected wallet identity.
- Partial journal writes invalidate the destination header first and commit its header last. A previous active bank remains intact until explicit verified cleanup.
- Full recipient, amount, fee and change are checked before signing. Inputs must belong to the wallet; duplicate outpoints, overflow, excessive mass and fee estimates fail closed. Every BIP-340 signature is locally verified before submission.
- Secrets are kept in memory only, with array clearing on lock/background/disposal/timeout; passwords are never persisted. FLAG_SECURE is the default; the 0.10.1 temporary troubleshooting exception below is owner-controlled. Backup/device-transfer exclusions remain enabled. JVM strings, BigInteger and provider/GC copies prevent a promise of physical memory erasure.

## Residual risks and verification limits

- **MIFARE Classic is not a secure element.** Read/copy/erase/rollback and UID cloning remain hardware risks. User consent governs only this app. Independent card copies are not enforceable by software.
- Previously copied encrypted shares cannot be recalled. A strong password increases offline attack cost but manual password policy does not prove entropy. Loss of two independent cards or needed passwords can cause permanent loss.
- The signing implementation still uses Bouncy Castle/BigInteger and is not claimed constant-time. A separately reviewed native constant-time signer is future work. A compromised/rooted phone is not a trusted signing environment.
- A single REST source supplies balance, fee estimates and acceptance evidence. Local intent/fee/mass checks constrain it but do not independently establish chain truth. Malicious acceptance evidence, censorship, stale history and availability remain risks. HTTPS platform certificate validation remains enabled.
- Pending checksums protect against accidental corruption, not an attacker controlling app storage/OS. Explicit abandonment deliberately accepts duplicate-payment risk; old signed transactions cannot be revoked by deleting local records.
- Native v0 P2PK only. Unsupported address/protocol variants fail closed. Large-input/compound-transaction coin selection is not implemented.
- Automated tests use JVM/Robolectric and fault injection. They do not establish actual NFC reliability, secure hardware behavior, enrollment invalidation, timing resistance or real mainnet propagation. No funded transfer or physical card write was performed by the agent. Test migration and RF interruptions on spare cards before trusting this release with funds.

## Review entry points

`KaspaAddress.kt`, `KaspaTransaction.kt`, `KaspaTx.kt`, `KaspaMass.kt`: recipient, transaction intent, signing and fee controls.

`CardRecord.kt`, `WalletSecurity.kt`, `NfcCards.kt`, `MainActivity.kt`: latest-only recovery, authenticated generation, journal/cleanup, biometrics and lifecycle state transitions.

`WalletStore.kt`, `KaspaBroadcast.kt`: redundant durable pending state, reconciliation, exact retry and explicit abandonment.

`.github/workflows/build-apk.yml`, `gradle/wrapper/*`, `gradle/verification-metadata.xml`: build integrity. New regression suites: `AuditRemediationTest` and `PendingRecoveryTest`. Re-run `bash ./gradlew --dependency-verification strict :app:testDebugUnitTest :app:lintDebug :app:lintMainnet :app:assembleMainnet` and independently inspect the delivery APK certificate/manifest.


## 0.10.1 interrupted initial registration / owner-requested usability changes

- Root cause: cancellation/backgrounding/timeout cleared the volatile initial key after just A was written, while the only unlock path required two cards. Initial unfinished V3 generations now have a separate continuation path: the latest current-generation card, its recovery password, and the same phone's per-operation biometric Keystore decryption. Both the full daily key and password-opened share must authenticate before recreating the original degree-one sharing polynomial. Remaining cards keep the same wallet, generation and polynomial; no key/password/share is persisted. This path is unavailable with three registered cards or verified backups and never enables spending before the normal backup gate. A completed write whose metadata update was interrupted can restore its public registration progress after authentication.
- One card alone cannot recover after uninstall/app-data deletion, phone replacement or Keystore invalidation. Two independent shares remain required then. Keep the app/data, card and its password until the remaining cards are registered. Zero committed cards plus a lost volatile key cannot be restored by this feature.
- At the owner's explicit request, new passwords may be eight characters or longer, with trivial repeated/common patterns rejected. Long random generation and Argon2id parameters remain unchanged. This weakens resistance to offline guessing compared with the earlier 16-character policy; eight characters do not imply adequate entropy, and no online attempt counter can protect copied ciphertext.
- At the owner's explicit request, Settings offers temporary screen-capture permission for troubleshooting. It defaults to blocked, is not persisted, and resets on backgrounding. Generated plaintext-password dialogs always use SecureOn and force the activity secure as well. Permitted captures can expose wallet addresses, balances and history; do not share them indiscriminately. This is distinct from Android debuggable builds, which remain prohibited from mainnet key creation/NFC/broadcast.

The UI distinguishes registration, two-card recovery verification, and three-card inactive-bank cleanup. A verified pair is labelled complete while cleanup remains pending. Each attempted pair is evaluated independently of an earlier persisted verification flag, and mixed-generation pairs report the exact unmet condition instead of a generic recovery-success message.
