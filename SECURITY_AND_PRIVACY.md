# NothingVault — Privacy & Security Architecture

## 1. Credential Protection & Redaction
- **Android Keystore Encryption**: Telegram Bot tokens and master passphrases are encrypted using a 256-bit `AES/GCM/NoPadding` key generated inside `AndroidKeyStore`.
- **Backup Exclusion**: `backup_rules.xml` and `data_extraction_rules.xml` explicitly exclude `nothing_vault_secure_prefs.xml` and `nothing_vault.db` from Android Auto Backup and cloud device-to-device extraction.
- **Zero Plaintext Logging**: `VaultLogger` runs regex sanitization across all log messages and URLs to redact any token matching `\d{6,14}:[A-Za-z0-9_-]{25,60}` or `/bot<token>/`.

## 2. Local File Encryption Specification (`NVLT1`)
Standard Telegram Bot API chats are cloud-stored by Telegram and are not end-to-end encrypted. When local encryption is enabled in NothingVault:
- **Algorithm**: Authenticated `AES/GCM/NoPadding` (256-bit key, 128-bit authentication tag).
- **Key Derivation**: `PBKDF2WithHmacSHA256` with 32,768 iterations and a fresh 16-byte cryptographic random salt per file.
- **Nonce**: Unique 12-byte (96-bit) `SecureRandom` IV per file.
- **Binary Layout**:
  - Bytes `0..4`: ASCII Magic Header `"NVLT1"`
  - Bytes `5..20`: 16-byte PBKDF2 Salt
  - Bytes `21..32`: 12-byte GCM IV / Nonce
  - Bytes `33..N`: AES-256-GCM Ciphertext + 16-byte GCM Authentication Tag
- **Key Recovery**: Encryption keys are never transmitted to Telegram. If you lose your master passphrase and reset your Android device, encrypted `.nvenc` archives cannot be decrypted.

## 3. Scoped Android Permissions & Sandboxing
- **Media & Files**: Uses zero-permission Android Photo Picker (`PickMultipleVisualMedia`) and Storage Access Framework (`OpenMultipleDocuments` / `OpenDocumentTree`). Broad storage permissions (`READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`) are never requested.
- **Contacts & Calendar**: Strictly opt-in; permissions (`READ_CONTACTS`, `READ_CALENDAR`) are requested at runtime only when the user explicitly triggers an export in those categories.
- **App Sandboxing**: Android OS sandboxing prevents ordinary apps from reading another app's private `/data/data/` directory. NothingVault only backs up user-accessible files and authorized exports.
