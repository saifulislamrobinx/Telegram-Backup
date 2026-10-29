# NothingVault — Private Telegram-Powered Android Backup Engine

NothingVault is a production-grade Android personal backup and synchronization utility built with **Kotlin**, **Jetpack Compose (Material 3)**, **Room**, **DataStore**, **WorkManager**, **OkHttp**, and **Android Keystore**. It features a monochrome, dot-matrix industrial interface inspired by Nothing OS.

---

## 1. Key Capabilities

- **Self-Sovereign Telegram Bot Integration**:
  - Connect your own Telegram Bot API token obtained from `@BotFather`.
  - Live validation via the official `getMe` endpoint.
  - Hardware-backed token storage using **Android Keystore (`AES/GCM/NoPadding`, 256-bit)**.
  - Automatic token masking in the UI and strict credential redaction across all diagnostic logs.
- **Eligible Destination Discovery & Registry**:
  - Discover accessible chats, groups, supergroups, and channels via `getUpdates`.
  - Manually configure numeric Chat IDs (e.g. `-1001234567890`) or `@channelusername` targets.
  - Verify upload permissions via `getChat` and user-confirmed test ping messages (`sendMessage`).
  - Maintain a global default destination while supporting per-job destination overrides without mutating the default.
- **Scoped Backup Categories**:
  - **Photos & Videos**: Zero-permission Android Photo Picker (`PickMultipleVisualMedia`) and authorized SAF folders (`OpenDocumentTree`).
  - **Documents, Audio & App Exports**: Storage Access Framework (`OpenMultipleDocuments` & `OpenDocumentTree`).
  - **Contacts Export**: Opt-in runtime `READ_CONTACTS` permission with VCF 3.0 / JSON export and field exclusion filters (phones, emails, organization, notes).
  - **Calendar Export**: Opt-in runtime `READ_CALENDAR` permission with structured JSON snapshot export.
- **Transfer Engine & Background Sync**:
  - Configurable concurrent upload limits (`1..4`), per-file byte progress, pause/cancel/retry controls.
  - SHA-256 content hashing and local Room manifest index to skip unchanged duplicate files.
  - Automatic handling of Telegram HTTP 429 flood-control responses (`parameters.retry_after`) and exponential backoff.
  - Multi-part `.nvpart` archive splitting for files exceeding the 50 MB standard Telegram Bot API upload limit.
  - Periodic and manual background synchronization via **WorkManager** with Wi-Fi-only and Charging-only constraints.
- **Zero-Trust Local Encryption & Verified Restore**:
  - Optional on-device **AES-256-GCM** authenticated encryption (`PBKDF2WithHmacSHA256`, 16-byte random salt, 96-bit unique nonce per file, `NVLT1` header) before upload.
  - Structured Backup Manifest recording Backup ID, timestamp, destination, original filename, MIME type, SHA-256 hash, encryption status, and Telegram `message_id` / `file_id`.
  - Complete Restore workflow that downloads files via `getFile`, decrypts with AES-256-GCM, and verifies SHA-256 integrity before saving.

---

## 2. Telegram Bot Setup Instructions

1. Open Telegram and start a chat with **[@BotFather](https://t.me/BotFather)**.
2. Send `/newbot` and follow the prompts to name your bot and receive your Bot API token (`123456789:AAH...`).
3. **To back up to a Private Chat with your bot**:
   - Open a direct chat with your newly created bot in Telegram, press **Start**, and send any message (e.g., `hello`).
   - In NothingVault, open **TARGETS -> SCAN RECENT BOT UPDATES** to automatically detect your private chat.
4. **To back up to a Private Group or Channel**:
   - Create a private Group or Channel in Telegram.
   - Add your bot as an **Administrator** with **Post Messages / Send Media** permissions enabled.
   - Send a message in the group/channel, then tap **SCAN RECENT BOT UPDATES** in NothingVault (or enter the `-100...` Chat ID manually).

---

## 3. Build & Test Instructions

- **Compile Debug APK**:
  ```bash
  gradle assembleDebug
  ```
- **Run Unit & Robolectric Tests**:
  ```bash
  gradle :app:testDebugUnitTest
  ```
