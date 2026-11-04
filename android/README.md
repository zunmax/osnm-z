# OSNM-Z Multi-Job — Android

Port Android native (Kotlin + framework View, tanpa Compose) dari CLI Python `zunmax/osnm-z`
(OpenSea SeaDrop ERC-721, satu wallet per job), ditambah multi-job seperti
yang diminta: tombol **+** menambah job, tiap job punya konfigurasi sendiri,
dan semua job dikelola satu ForegroundService dengan notifikasi.

Referensi upstream (hanya untuk dibaca, bukan dependensi build) ada di
`upstream-osnm-z-reference/`. Fork via GitHub MCP gagal 403
(`Resource not accessible by integration`), jadi build ini dibuat dari
clone lokal — tidak ada fork yang dibuat ulang.

## Keamanan — baca dulu, ini yang paling penting

- **Private key tidak pernah keluar dari HP.** Key hanya diinput langsung
  di HP user, disimpan sebagai ciphertext AES-256-GCM yang kuncinya
  dibuat dan disimpan di Android Keystore (kunci tidak pernah keluar
  dari Keystore), dan tidak pernah ditulis plaintext, tidak pernah
  masuk `jobs.json`, tidak pernah masuk log, tidak pernah masuk repo,
  dan tidak pernah dikirim ke server mana pun.
- Yang dikirim ke RPC/OpenSea hanyalah: alamat wallet (publik), transaksi
  yang **sudah ditandatangani secara lokal**, dan signature SIWE untuk
  sign-in OpenSea. RPC/OpenSea tidak pernah menerima private key.
- Alamat di UI/notifikasi hanya ditampilkan ter-mask (`0x1234…abcd`).
- Hapus job = hapus key job itu (`SecureKeyStore.deleteKey`).
- Backup: `res/xml/backup_rules.xml` (full backup) dan
  `res/xml/data_extraction_rules.xml` (Android 12+) mengecualikan
  `osnmz_keys.xml` (file ciphertext key) dan `jobs.json` dari cloud
  backup maupun device transfer, sehingga backup tidak bisa dipakai
  memulihkan wallet.
- **Jangan pernah menempel private key asli di chat, di VM ini, atau di
  file `.env`.** Build dan testing di VM ini memakai DUMMY key saja
  (lihat "Testing" di bawah). `.env` asli tidak ada di tree ini dan
  `.gitignore` menutupi `.env`, `*.key`, dan pola secret.
- Uji dulu dengan wallet kecil. Transaksi mint yang sudah terkirim
  tidak bisa dibatalkan.

## Cara pakai (di HP)

1. Install APK debug, izinkan notifikasi saat diminta.
2. Tekan **+**, isi: nama job, slug/URL OpenSea/alamat kontrak, RPC URL
   (HTTPS), quantity, gas limit, dan fee (otomatis = 1,25x saat phase
   sudah aktif, 2,5x saat phase masih terjadwal — sama seperti osnm-z;
   manual = isi max fee & priority fee dalam gwei).
3. Tempel private key job itu, tekan **Simpan Key (terenkripsi di HP)**.
   Key tidak akan ditampilkan lagi. Alamat wallet ter-derive dan tampil
   ter-mask.
4. Tekan **Lookup** untuk mengambil daftar stage dari OpenSea, lalu
   pilih satu stage. Daftar stage sekarang meniru terminal osnm-z:
   `nama | Public/Allowlist | active/upcoming/ended | eligible/ineligible |
   available/unavailable`, plus baris `start/end`, `maks/wallet`,
   `sudah mint`, dan harga. Stage yang ineligible/unavailable tampil
   tapi tombolnya nonaktif dan tidak bisa di-Start — persis seperti
   terminal yang hanya menerima nomor phase yang eligible. Untuk cek
   eligibility allowlist, key job harus sudah disimpan dulu (Lookup
   memakai SIWE sign-in + eligibility OpenSea); tanpa key, hanya PUBLIC
   yang ditandai `eligible (public)`.
5. Tekan **Doctor** (opsional, seperti `osnm-z doctor`) untuk memeriksa
   RPC, saldo, nonce, dan fee.
6. Tekan **Start Mint**. Job menunggu phase bila masih terjadwal,
   lalu mengirim tepat di (atau dengan offset) waktu mulai.
7. Pantau dari kartu job (log per job) dan notifikasi. **Stop** untuk
   membatalkan job yang masih menunggu/menyiapkan. Transaksi yang
   sudah terkirim tidak dibatalkan oleh Stop — hanya polling receipt
   yang berhenti; cek hash-nya di explorer.

## Background & notifikasi

- Satu `MintService` (ForegroundService, `dataSync`) mengelola semua job.
  Notifikasi persisten menampilkan job yang sedang berjalan; notifikasi
  event terpisah muncul untuk: Lookup selesai/gagal, Doctor selesai/gagal,
  job dimulai, tx terkirim, mint terkonfirmasi, mint belum terkonfirmasi,
  dan job gagal (status akhir juga tercatat di log job). Layar mati saja
  tidak menghentikan service.
- **Perbaikan eligibility (2026-11-03):** SIWE nonce di OpenSea harus
  diminta dengan POST tanpa body, persis seperti CLI (`_send()` di
  osnm-z default-nya POST). Versi app sebelumnya memakai GET dan
  OpenSea menjawab 405, sehingga eligibility selalu gagal dan Lookup
  jatuh ke label public-only. Sudah diperbaiki, plus user-agent
  `osnm-z/0.1.0` seperti CLI, cookie hint `.opensea.io`, dan eligibility
  sekarang memakai GET persisted query seperti CLI dulu, baru fallback
  ke POST bila GET gagal. **Terverifikasi di HP user 2026-11-03 malam:
  cek eligibility berhasil (sebelumnya 405). Eksekusi mint sungguhan
  belum dites saat catatan ini ditulis.**
- **Kalau notifikasi tidak muncul, cek dulu izinnya, bukan log-nya:**
  di app ada tombol “Buka Pengaturan Notifikasi” yang membuka halaman
  notifikasi app di Pengaturan HP. Pastikan channel “Event mint” dan
  “Mint berjalan” ON, dan optimasi baterai dimatikan. Kalau izin
  notifikasi pernah ditolak, Android tidak akan menampilkan dialog izin
  lagi — hanya lewat halaman Pengaturan itu notifikasi bisa dihidupkan
  kembali. Versi lama hanya mengirim notifikasi event saat gagal, jadi
  sukses/gagal Lookup/Doctor dan mint terkonfirmasi tidak pernah muncul
  sebagai notifikasi; itu sudah diperbaiki di versi ini.
- **Batasan yang jujur:** force-stop app, reboot tanpa membuka app lagi,
  atau battery killer agresif bawaan HP tertentu (mis. Xiaomi/Samsung)
  tetap bisa menghentikan service. Untuk hasil terbaik: matikan
  optimasi baterai untuk app ini, kunci app di Recent Apps, dan
  izinkan autostart bila HP menyediakannya. Job **tidak** auto-resume
  setelah process death — sengaja, supaya tidak ada transaksi yang
  terkirim diam-diam setelah HP restart. Start ulang manual.
- Untuk timing phase yang sangat kritis, foreground service adalah
  pendekatan yang dipakai di sini (bukan WorkManager, yang boleh
  ditunda sistem, dan bukan exact alarm saja).

## Multi-job & nonce

- Wallet berbeda boleh jalan paralel.
- Job dengan wallet yang sama **diantrikan pengirimannya** lewat
  `NonceLocks` (Mutex per alamat): snapshot nonce → sign → kirim
  tidak pernah terjadi bersamaan untuk satu alamat. Polling receipt
  dilakukan di luar lock supaya job lain tetap bisa jalan.
- Ini menggantikan peringatan manual di README osnm-z ("jangan ada
  transaksi lain dari wallet yang sama saat mint berjalan") dengan
  pengaman di dalam app.

## Apa yang sudah di-port (nyata, bukan placeholder)

Dari `src/osnm_z/` upstream, di-port ke Kotlin di `app/src/main/java/com/osnmz/android/`:

- `config.py` → model `Job` per job (bukan satu `.env` global):
  RPC, gas limit, fee otomatis/manual, broadcast offset, receipt timeout.
- `chain.py` + `rpc_protocol.py` → `RpcClient`: `eth_chainId`,
  `eth_getBalance`, `eth_getTransactionCount` (pending),
  `eth_getBlockByNumber` + `eth_maxPriorityFeePerGas`
  (max fee = baseFee*2 + priority, persis osnm-z), `eth_call`,
  `eth_sendRawTransaction`, `eth_getTransactionReceipt`.
- `signing.py` + `transaction.py` → `Crypto.kt` (BouncyCastle):
  derivasi alamat secp256k1, EIP-1559 type 2 (accessList kosong,
  sama seperti CLI), dan signature SIWE EIP-191 personal message,
  sama seperti `encode_defunct` di CLI.
- `opensea.py` + `opensea_protocol.py` → `OpenSeaClient`: query
  `MintCollectionMetadata`, `MintCollectionSearch`,
  `DropConfigChangesQuery`, `MintActionTimelineQuery`, dan
  `DropEligibilityQuery`; SIWE nonce → sign → verify; validasi
  alamat terverifikasi cocok dengan wallet.
- `public_mint.py` + `seadrop.py` → `SeaDrop`: discovery SeaDrop dari
  receipt log `PublicDropUpdated`, fallback ke alamat canonical
  `0x00005EA00Ac477B1030CE78506496e8C2dE24bf5`, decode public drop
  (6 word), fee recipient, mint stats, dan build calldata public mint
  (`161ac21f` + collection + feeRecipient + wallet + quantity).
- `submission.py` + `nft.py` → kirim sekali, **tidak pernah resend**;
  receipt diverifikasi dari log `Transfer` ERC-721 (from = zero address,
  to = wallet, data kosong, jumlah tokenId == quantity, tokenId unik).
  Bila receipt tidak datang dalam timeout, status `UNCONFIRMED` dengan
  hash-nya — bukan klaim sukses.
- `command.py` → alur UI: Doctor, Lookup (pilih stage), Start Mint.

## Yang belum / tidak sama persis dengan CLI

- Warm-up koneksi pra-phase dan re-auth SIWE berkala (T-20 s/d T-2
  di CLI) belum di-port penuh: app melakukan SIWE sekali sebelum
  mint action dan retry mint action 15x dengan re-auth sederhana.
  Untuk phase private yang sangat ketat waktunya, CLI masih lebih agresif.
- Eligibility per-stage dipakai untuk menampilkan stage, tetapi
  validasi limit final mengandalkan OpenSea mint action + cek saldo
  dan supply on-chain untuk public mint. Bila OpenSea menolak action,
  error-nya ditampilkan apa adanya di log job.
- Receipt timeout default app 120 detik (bukan 20 detik seperti CLI),
  supaya polling di HP tidak terlalu cepat menyerah; nilai ini bisa
  diubah per job di kode (`receiptTimeoutSec`, default di model).
- Tidak ada resume otomatis setelah reboot/force-stop (sengaja, lihat
  di atas). Tidak ada fitur jual, swap, atau approval di luar mint.

## Build

Ada dua jalur build untuk source yang sama:

### Jalur utama di VM ini — direct toolchain (tanpa Gradle daemon)

Build Gradle standar tidak bisa jalan di VM ini: socket loopback yang
dipakai Gradle daemon dicegat di lingkungan ini, sehingga client Gradle
tidak pernah menerima balasan daemon (error: `Could not receive a
message from the daemon`, dengan byte pertama yang diterima bukan
protokol daemon). Karena itu APK di sini di-build langsung:

```bash
cd osnm-z-android
./manual-build/build-apk.sh
# APK: osnm-z-multijob-debug.apk
# Saat mengirim update, nama file APK-nya saja yang diberi suffix patch
# (mis. …-0.1.1.apk) supaya download tidak menimpa file lama di HP.
```

Skrip itu menjalankan: kotlinc (JVM 1.8) -> d8 (min API 26) ->
aapt2 compile/link -> zipalign -> apksigner (debug key lokal, dibuat
di `manual-build/debug.keystore`, hanya untuk testing, bukan rilis).
Dependensi build manual ada di `manual-build/libs/` (kotlin-stdlib,
BouncyCastle, kotlinx-coroutines) dan diunduh sekali dari Maven Central.

### Jalur Gradle (mesin normal / CI, bukan VM ini)

Prasyarat: JDK 17, Android SDK (platform Android 34, build-tools 34.0.0),
Gradle 8.7.

```bash
export ANDROID_HOME=$HOME/Android/Sdk
cd osnm-z-android
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Di mesin normal jalur ini seharusnya jalan; di VM ini ia gagal di
daemon seperti dijelaskan di atas, jadi jangan jadikan kegagalan Gradle
di VM ini sebagai bukti source rusak — kompilasi kotlinc dan APK
direct-toolchain adalah buktinya.

## Testing

- Build hanya memverifikasi kompilasi dan struktur APK (lihat
  "Verifikasi" di bawah). Tidak ada transaksi nyata yang dikirim dari VM.
- Bila menguji Doctor/Lookup di HP: pakai wallet dummy/bernilai kecil,
  RPC publik, dan koleksi yang sudah lewat — jangan pernah memakai
  wallet utama untuk pengujian pertama.
- Verifikasi kripto dilakukan dengan vektor uji publik, bukan key asli:
  private key uji `1` (64 hex, nilai 1) harus menghasilkan alamat
  `0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf` dan Keccak kosong
  `0xc5d246...85a470` — keduanya cocok setelah satu bug offset
  koordinat Y diperbaiki. Key uji ini tidak disimpan di repo/APK
  (grep `strings` pada APK tidak menemukannya).

## Verifikasi (dilakukan saat build)

- `grep` tree sumber: tidak ada file `.env` asli, tidak ada key hex
  64 karakter yang disimpan di sumber (selain konstanta alamat kontrak
  publik SeaDrop/fee recipient, yang bukan secret).
- `unzip -l` APK: manifest, dex, dan resource ada; tidak ada file
  `.env` di dalam APK.
- `aapt2 dump badging`: package `com.osnmz.android`, versionCode 1,
  versionName 0.1.0, minSdk 26, targetSdk 34, hanya permission yang
  diminta (INTERNET, POST_NOTIFICATIONS, FOREGROUND_SERVICE,
  FOREGROUND_SERVICE_DATA_SYNC, WAKE_LOCK, RECEIVE_BOOT_COMPLETED),
  activity launcher `MainActivity`, service `MintService` dengan
  `foregroundServiceType=dataSync`.
- `dexdump classes.dex`: semua kelas app ada (`MainActivity`,
  `MintService`, `JobManager`, `MintEngine`, `OpenSeaClient`,
  `RpcClient`, `SeaDrop`, `Crypto`, `SecureKeyStore`).
- `apksigner verify`: lolos untuk APK debug ini.
- APK debug memakai debug key AGP (lokal saja) — **bukan** untuk
  distribusi Play Store. Untuk rilis, buat signing key sendiri di
  mesin yang aman dan jangan pernah memasukkannya ke repo.
