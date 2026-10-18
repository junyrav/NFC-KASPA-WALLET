# Security changes in 0.9.1-mainnet

Scope: remediation of the independent ef6fb9c audit. This file distinguishes software controls from physical-device validation and residual risks. It is not an independent security certification.

## Changes

- H1: New v2 Android Keystore alias uses per-operation strong biometrics (zero duration on API 30+, -1 on API 28–29) and enrollment invalidation. Actual Cipher operations are passed through BiometricPrompt.CryptoObject. Legacy daily ciphertext is never used for signing; recover two cards and provision v2 daily data. Hardware enrollment/invalidation behavior still requires a real phone test.
- H2/M3: v2 recovery uses Argon2id v1.3, 64 MiB, three passes, parallelism one; the version fixes these parameters and cannot request arbitrary attacker-supplied work. Creation/provisioning requires confirmed passwords of at least 16 characters and three character classes, with a secure-random 24-character generator available. Manual password policy does not prove entropy.
- H3: Mainnet derives from release, is non-debuggable and unsigned in CI. No keystore cache is used. Deliveries are signed offline with the retained private delivery key; no signing secret is committed. Minification is not treated as a security boundary.
- H4: Mainnet v0 P2PK compute/storage/normalized transient mass is calculated locally and compared with the API components. Fee rates above 10 sompi/mass, absolute fees above 0.01 KAS, or fees above max(0.0001 KAS, 1% of the recipient amount) are refused. Confirmed recipient, amount, fee, input ownership and change script are checked immediately before signing. Arbitrary protocol variants fail closed.
- H5 (user-requested behavior): Existing occupied cards trigger an explicit overwrite warning, including the old slot/wallet when identifiable, target slot and recovery consequences. User acknowledgement and a second tag of the same UID and content snapshot are required. The approval lasts 60 seconds, is single-use and disappears on cancellation/backgrounding. Unreadable cards fail closed. This is consent, not a cryptographic proof of card authenticity.
- H5 interruption safety: Two six-sector banks are used. The inactive bank header is invalidated first, its body is written, then the header is committed last. The active bank is never overwritten during the operation. The first legacy migration uses the second bank. Read-back validation is mandatory. Journal fault-injection tests simulate every interrupted write; real RF-loss testing remains required.
- M2: v2 records carry the full x-only public key, slot, format version and sequence in AEAD authenticated data for both daily and recovery ciphertext. Restored public keys must match that identity. Legacy recovery without a saved wallet requires the expected original mainnet address, because legacy cards cannot authenticate their wallet identity.
- M4: Three verified v2 card registrations and an independent two-card recovery verification gate Send/Receive. Card reprovisioning resets this gate. New registrations retain the original sharing polynomial.
- M5: Secret byte/character arrays and password UI state are cleared after completing three-card registration, on explicit lock, ON_STOP/disposal and after five minutes. Biometric operations are cancelled on backgrounding; stale callbacks cannot submit. FLAG_SECURE and password keyboard options reduce screen/keyboard exposure. Java/Kotlin immutable strings, JCA key copies, BigInteger and GC mean physical memory erasure cannot be guaranteed.
- M6: Native v0 TXID is calculated locally. Signed request bytes and public transfer intent are committed before submission. Uncertain submission blocks new payments across restarts. Recovery checks the original TXID or resends exactly the saved signed bytes. A definitive initial HTTP 400/422 rejection may release the lock; transport uncertainty does not. Corrupt pending state fails closed.
- M7/L6: Confirmation shows the entire recipient and uses prepared transaction outputs. Clipboard checksum is validated immediately. Maximum-send results are revision-checked. Valid malicious clipboard addresses still require human comparison with the recipient.
- L2/L4: NFC dispatch accepts only NFC discovery actions while resumed. Both cloud backup and device-transfer rules exclude app data.
- Signing tests additionally include all official BIP-340 32-byte vectors (0–14), core v0 TXID, local mass, fee attacks, intent mismatch, journal interruption, consent snapshot matching and pending-payment persistence.

## Card migration and overwrite consent

1. Install the delivered APK as an update of the existing `com.nfckaspa.wallet.mainnet` app. Do not uninstall to update.
2. Keep the original three cards and original recovery password. Recover the wallet with two distinct cards. For legacy recovery on a fresh phone, also provide the original mainnet address; never guess a recovered zero-balance wallet is the right wallet.
3. Choose and confirm the new recovery password, then register A/B/C. Existing data is shown for explicit acknowledgement. Read the slot warning carefully, then re-tag the same card. Changing a password midway through a three-card provisioning session is disabled.
4. Read-back verification is performed after every write. An interrupted inactive-bank update leaves the previous active bank readable. Re-read and re-authorize before another attempt.
5. Registration locks secrets after three cards. Re-enter the recovery password and verify recovery with two cards before Send/Receive is enabled.

Do not erase all original recovery copies at once. Cross-slot overwrite is permitted only after the user explicitly acknowledges the loss/duplication risk; consent cannot make a wrong slot selection recoverable. New provisioned backups remain marked incomplete until independently verified.

## Residual / not claimed fixed

- MIFARE Classic remains readable, clonable and destructively writable by an adversary with the sector key. This software update does not turn it into DESFire or a secure element. User overwrite consent only controls this app. Do not rely on UID as an anti-cloning mechanism.
- Previously copied legacy ciphertext cannot be revoked by re-encrypting the same private key. If an old weak-password backup may have been copied, moving funds to a newly generated wallet is the effective key rotation; the app never moves funds automatically.
- A lost recovery password, two destroyed cards, or approved destructive overwrites can still cause permanent loss. The app cannot enforce physical independence of cards against deliberate cloning.
- M8: HTTPS system certificate validation remains enabled. No brittle unverified certificate pin is added. The API remains a single availability/history source; signed input/output checks and local mass/fee policies reduce trust but do not prove balances or transaction acceptance independently.
- L1: Signing still uses Bouncy Castle / BigInteger; this is not a guarantee of constant-time behavior. Rooted/compromised operating systems are not a trusted signing environment. A vetted native constant-time secp256k1 integration remains a separate hardening task.
- L3: Only native v0 P2PK is supported. Protocol upgrades must be reviewed and tested before adding v1. ECDSA/P2SH addresses are rejected.
- L5: Large input sets still require a compound/coin-selection implementation; this update refuses excessive mass rather than silently generating multi-step funded transactions. Small splits such as one 0.19821 KAS input into 0.09 KAS plus change can genuinely exceed KIP-9 limits. Maximum-send is explicit and never silently replaces a partial amount.
- No funded mainnet transaction, real card overwrite, physical biometric enrollment or RF interruption is performed by automated checks. Independent follow-up review and device testing are required.
