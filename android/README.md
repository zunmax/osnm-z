# OSNM-Z Multi-Job — Android

A native Android port (Kotlin + View framework, no Compose) of the Python CLI `zunmax/osnm-z`
(OpenSea SeaDrop ERC-721, one wallet per job), extended with multi-job support as
requested: a **+** button adds a job, each job has its own configuration,
and all jobs are managed by a single ForegroundService with notifications.

The upstream reference (read-only, not a build dependency) is in
`upstream-osnm-z-reference/` in the local workspace. On GitHub this code lives
in the fork `alawymuhsin/osnm-z`, branch `android-multijob`, under `android/`,
forked from `zunmax/osnm-z` — the upstream Python files at the repo root are
untouched by this branch.

## Security — read this first, it matters most

- **A private key never leaves the phone.** A key is entered only directly
  on the user's phone, stored as AES-256-GCM ciphertext whose key is
  created and held in the Android Keystore (the key never leaves the
  Keystore), and is never written in plaintext, never put in `jobs.json`,
  never put in logs, never put in the repo, and never sent to any server.
- The only things sent to RPC/OpenSea are: the wallet address (public), a
  transaction **already signed locally**, and a SIWE signature for
  OpenSea sign-in. RPC/OpenSea never receive the private key.
- Addresses in the UI/notifications are shown masked only (`0x1234…abcd`).
- Deleting a job deletes that job's key (`SecureKeyStore.deleteKey`).
- Backup: `res/xml/backup_rules.xml` (full backup) and
  `res/xml/data_extraction_rules.xml` (Android 12+) exclude
  `osnmz_keys.xml` (the key ciphertext file) and `jobs.json` from cloud
  backup and device transfer, so a backup cannot be used to restore a wallet.
- **Never paste a real private key in chat, in this VM, or in a
  `.env` file.** Builds and testing in this VM use a DUMMY key only
  (see "Testing" below). No real `.env` exists in this tree, and
  `.gitignore` covers `.env`, `*.key`, and secret patterns.
- Test first with a small wallet. A mint transaction that has already been
  sent cannot be cancelled.

## How to use (on the phone)

1. Install the debug APK and allow notifications when prompted.
2. Tap **+** and fill in: job name, OpenSea slug/URL/contract address, RPC URL
   (HTTPS), quantity, gas limit, and fee (automatic = 1.25x once the phase
   is active, 2.5x while the phase is still scheduled — same as osnm-z;
   manual = enter max fee and priority fee in gwei).
3. Paste that job's private key and tap **Save Key (encrypted on phone)**.
   The key will not be shown again. The wallet address is derived and shown
   masked.
4. Tap **Lookup** to fetch the stage list from OpenSea, then pick one stage.
   The stage list now mirrors the osnm-z terminal:
   `name | Public/Allowlist | active/upcoming/ended | eligible/ineligible |
   available/unavailable`, plus a `start/end` line, `max/wallet`,
   `already minted`, and price. Ineligible/unavailable stages are shown
   but their buttons are disabled and cannot be started — exactly like
   the terminal, which only accepts an eligible phase number. To check
   allowlist eligibility, the job's key must be saved first (Lookup
   uses SIWE sign-in + OpenSea eligibility); without a key, only PUBLIC
   is marked `eligible (public)`.
5. Tap **Doctor** (optional, like `osnm-z doctor`) to check
   RPC, balance, nonce, and fee.
6. Tap **Start Mint**. The job waits for the phase if it is still scheduled,
   then sends at (or with the offset from) the start time.
7. Monitor from the job card (per-job log) and notifications. **Stop**
   cancels a job that is still waiting/preparing. A transaction that
   has already been sent is not cancelled by Stop — only receipt
   polling stops; check its hash in an explorer.

## Background & notifications

- One `MintService` (ForegroundService, `dataSync`) manages all jobs.
  A persistent notification shows the running jobs; separate event
  notifications appear for: Lookup finished/failed, Doctor finished/failed,
  job started, tx sent, mint confirmed, mint unconfirmed,
  and job failed (the final status is also recorded in the job log). The
  screen being off alone does not stop the service.
- **Eligibility fix (2026-11-03):** the SIWE nonce at OpenSea must be
  requested with a POST with no body, exactly like the CLI (`_send()` in
  osnm-z defaults to POST). The earlier app version used GET and
  OpenSea answered 405, so eligibility always failed and Lookup
  fell back to public-only labels. This is fixed, plus a
  `osnm-z/0.1.0` user-agent like the CLI, a `.opensea.io` cookie hint, and
  eligibility now uses the CLI-style GET persisted query first, falling
  back to POST if GET fails. **Verified on the user's phone on the evening
  of 2026-11-03: the eligibility check succeeded (previously 405). A real
  mint execution had not been tested when this note was written.**
- **If notifications do not appear, check the permission first, not the log:**
  the app has a "Open Notification Settings" button that opens the app's
  notification page in the phone's Settings. Make sure the "Mint events" and
  "Mint running" channels are ON, and battery optimization is off. If
  notification permission was ever denied, Android will not show the
  permission dialog again — only that Settings page can turn notifications
  back on. The old version only sent an event notification on failure, so
  Lookup/Doctor success/failure and confirmed mints never appeared
  as notifications; that is fixed in this version.
- **Honest limits:** force-stopping the app, rebooting without reopening the
  app, or an aggressive battery killer built into some phones (e.g.
  Xiaomi/Samsung) can still stop the service. For the best results: turn
  off battery optimization for this app, lock the app in Recent Apps, and
  allow autostart if the phone offers it. Jobs do **not** auto-resume
  after process death — deliberately, so no transaction is ever sent
  silently after a phone restart. Restart manually.
- For phase timing that is very time-critical, a foreground service is
  the approach used here (not WorkManager, which the system may defer,
  and not exact alarms alone).

## Multi-job & nonce

- Different wallets may run in parallel.
- Jobs with the same wallet have their **sending serialised** via
  `NonceLocks` (a Mutex per address): nonce snapshot → sign → send
  never happens concurrently for one address. Receipt polling
  happens outside the lock so other jobs keep running.
- This replaces the manual warning in the osnm-z README ("no other
  transactions from the same wallet while a mint is running") with
  a guard inside the app.

## What has actually been ported (real, not placeholders)

From upstream `src/osnm_z/`, ported to Kotlin in `app/src/main/java/com/osnmz/android/`:

- `config.py` → a per-job `Job` model (not one global `.env`):
  RPC, gas limit, automatic/manual fee, broadcast offset, receipt timeout.
- `chain.py` + `rpc_protocol.py` → `RpcClient`: `eth_chainId`,
  `eth_getBalance`, `eth_getTransactionCount` (pending),
  `eth_getBlockByNumber` + `eth_maxPriorityFeePerGas`
  (max fee = baseFee*2 + priority, exactly like osnm-z), `eth_call`,
  `eth_sendRawTransaction`, `eth_getTransactionReceipt`.
- `signing.py` + `transaction.py` → `Crypto.kt` (BouncyCastle):
  secp256k1 address derivation, EIP-1559 type 2 (empty accessList,
  same as the CLI), and SIWE EIP-191 personal message signatures,
  same as `encode_defunct` in the CLI.
- `opensea.py` + `opensea_protocol.py` → `OpenSeaClient`: queries
  `MintCollectionMetadata`, `MintCollectionSearch`,
  `DropConfigChangesQuery`, `MintActionTimelineQuery`, and
  `DropEligibilityQuery`; SIWE nonce → sign → verify; verified
  address must match the wallet.
- `public_mint.py` + `seadrop.py` → `SeaDrop`: SeaDrop discovery from
  receipt log `PublicDropUpdated`, fallback to the canonical address
  `0x00005EA00Ac477B1030CE78506496e8C2dE24bf5`, public drop decode
  (6 words), fee recipient, mint stats, and public mint calldata
  (`161ac21f` + collection + feeRecipient + wallet + quantity).
- `submission.py` + `nft.py` → send once, **never resend**;
  receipts are verified from ERC-721 `Transfer` logs (from = zero address,
  to = wallet, empty data, tokenId count == quantity, unique tokenIds).
  If no receipt arrives within the timeout, the status is `UNCONFIRMED` with
  its hash — not a success claim.
- `command.py` → UI flow: Doctor, Lookup (pick a stage), Start Mint.

## What is not yet / not exactly like the CLI

- Pre-phase connection warm-up and periodic SIWE re-auth (T-20 s to T-2
  in the CLI) are not fully ported: the app does SIWE once before
  the mint action and retries the mint action 15x with simple re-auth.
  For a private phase with very tight timing, the CLI is still more aggressive.
- Per-stage eligibility is used to display stages, but the final limit
  validation relies on the OpenSea mint action plus balance and
  on-chain supply checks for public mints. If OpenSea rejects the action,
  its error is shown as-is in the job log.
- The app's default receipt timeout is 120 seconds (not 20 seconds like the CLI),
  so polling on a phone does not give up too quickly; this value can
  be changed per job in code (`receiptTimeoutSec`, default in the model).
- No automatic resume after reboot/force-stop (deliberate, see above). No
  sell, swap, or approval features outside minting.

## Build

There are two build paths for the same source:

### Main path in this VM — direct toolchain (no Gradle daemon)

A standard Gradle build cannot run in this VM: the loopback socket
used by the Gradle daemon is intercepted in this environment, so the Gradle
client never receives the daemon's reply (error: `Could not receive a
message from the daemon`, with the first byte received not being
daemon protocol). So the APK here is built directly:

```bash
./manual-build/build-apk.sh
# APK: osnm-z-multijob-debug.apk
# When sending an update, only the APK filename gets a patch suffix
# (e.g. …-0.1.1.apk) so the download does not overwrite the old file on the phone.
```

That script runs: kotlinc (JVM 1.8) -> d8 (min API 26) ->
aapt2 compile/link -> zipalign -> apksigner (local debug key, created
in `manual-build/debug.keystore`, testing only, not a release).
Manual build dependencies are in `manual-build/libs/` (kotlin-stdlib,
BouncyCastle, kotlinx-coroutines) and were downloaded once from Maven Central.

### Gradle path (normal machine / CI, not this VM)

Prerequisites: JDK 17, Android SDK (Android 34 platform, build-tools 34.0.0),
Gradle 8.7.

```bash
export ANDROID_HOME=$HOME/Android/Sdk
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

On a normal machine this path should work; in this VM it fails at the
daemon as explained above, so do not treat a Gradle failure in this VM
as proof the source is broken — the kotlinc compilation and the
direct-toolchain APK are the proof.

## Testing

- A build only verifies compilation and APK structure (see
  "Verification" below). No real transaction is sent from the VM.
- When testing Doctor/Lookup on a phone: use a dummy/small-value wallet,
  a public RPC, and a collection that has already passed — never use
  the main wallet for a first test.
- Crypto verification uses public test vectors, not a real key:
  test private key `1` (64 hex, value 1) must produce address
  `0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf` and empty Keccak
  `0xc5d246...85a470` — both matched after one Y-coordinate offset
  bug was fixed. This test key is not stored in the repo/APK
  (grep `strings` on the APK does not find it).

## Verification (done at build time)

- `grep` the source tree: no real `.env` file, no stored 64-character
  hex key in the source (other than public contract address constants
  for SeaDrop/fee recipient, which are not secrets).
- `unzip -l` the APK: manifest, dex, and resources are present; no
  `.env` file inside the APK.
- `aapt2 dump badging`: package `com.osnmz.android`, versionCode 1,
  versionName 0.1.0, minSdk 26, targetSdk 34, only the requested permissions
  (INTERNET, POST_NOTIFICATIONS, FOREGROUND_SERVICE,
  FOREGROUND_SERVICE_DATA_SYNC, WAKE_LOCK, RECEIVE_BOOT_COMPLETED),
  launcher activity `MainActivity`, service `MintService` with
  `foregroundServiceType=dataSync`.
- `dexdump classes.dex`: all app classes are present (`MainActivity`,
  `MintService`, `JobManager`, `MintEngine`, `OpenSeaClient`,
  `RpcClient`, `SeaDrop`, `Crypto`, `SecureKeyStore`).
- `apksigner verify`: passes for this debug APK.
- The debug APK uses a local AGP debug key — **not** for
  Play Store distribution. For a release, create your own signing key on
  a secure machine and never put it in the repo.
