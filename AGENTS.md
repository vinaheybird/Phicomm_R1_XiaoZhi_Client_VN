# Phicomm R1 XiaoZhi Client (VN Fork) — Workspace Rules

## 1. Canonical Source Locations
- **Device package (`com.phicomm.speaker.device`)**: Active Java source compiled by `build_rel_V206.sh` is in `04-build-scripts/device/v2src/cn/workbuddy/r1client/` (do **not** edit legacy `01-parasite-src/device-v2src/`).
- **Player / Web Console (`com.phicomm.speaker.player`)**:
  - Active Web Console UI served on port `:8080` is `04-build-scripts/player/ui/player.html` (compiled into `04-build-scripts/player/plsrc/cn/workbuddy/r1web/Ui.java` via `ui/gen_java.py`).
  - `04-build-scripts/device/ui/ui.html` is **obsolete/unused**; port `:8088` is loopback-only API proxied by `player`.

## 2. Build Rules (Windows + Git Bash)
- Always run build scripts via Git Bash (`C:\Program Files\Git\bin\bash.exe`), never raw `cmd`/PowerShell:
  ```bash
  cd 04-build-scripts/device && bash build_rel_V206.sh   # -> XiaoZhi_Unisound_V2.0.6.apk
  cd 04-build-scripts/player && bash build_rel_V206.sh   # -> XiaoZhi_EchoService_V2.0.6.apk
  ```
- Toolchain lives in `04-build-scripts/tools/` (gitignored): `android.jar`, `r8.jar` (Android SDK `d8.jar` includes both `R8` and `D8`), and `smali/` (8 jars of `smali`/`baksmali` `2.5.2`). Verify with `bash 04-build-scripts/build_check.sh`.
- Keep `04-build-scripts/player/_uichk.py` present — `player/build_rel_V206.sh` uses it for `chkUI` assertions across chunked string literals in `Ui.java`.
- If `node` is unavailable in PATH, validate `04-build-scripts/player/ui/_preview.html` JS syntax & runtime errors using headless Chrome before shipping `player` APK.
- Never commit generated build artifacts (`XiaoZhi_*.apk`, `_stale/`, `release/`, `v22*`, `smali_xz*`, `ui/_preview.html`).

## 3. Installing & Deploying to Phicomm R1 (Android 5.1.1 / FW 3448, Non-Rooted)
- **Target speaker**: `192.168.88.66:5555` (note: device system clock runs in CST / UTC+8, and `md5sum` is not available in `/system/bin`).
- **NEVER use `adb install -r` directly**: both APKs are **Janus** packages (DEX prefix before ZIP header) which `adb install` rejects.
- **NEVER use scrcpy's `adb.exe` (`37.0.0`) with `pm install`**: calling `adb shell "pm install ..."` via scrcpy's `adb` fails with `error: closed` on R1's Android 5.1.1 `adbd`.
- **Always use Android SDK `adb.exe`** (`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`), push to `/data/local/tmp/`, install via **`sh -c 'pm install -r -d ...'`** (confirm `Success` in output), clean up temp APKs, and start both services:
  ```powershell
  $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
  & $adb connect 192.168.88.66:5555
  & $adb -s 192.168.88.66:5555 push "04-build-scripts\device\XiaoZhi_Unisound_V2.0.6.apk" /data/local/tmp/jp2.apk
  & $adb -s 192.168.88.66:5555 push "04-build-scripts\player\XiaoZhi_EchoService_V2.0.6.apk" /data/local/tmp/echo.apk
  & $adb -s 192.168.88.66:5555 shell "sh -c 'pm install -r -d /data/local/tmp/jp2.apk'"
  & $adb -s 192.168.88.66:5555 shell "sh -c 'pm install -r -d /data/local/tmp/echo.apk'"
  & $adb -s 192.168.88.66:5555 shell "rm -f /data/local/tmp/jp2.apk /data/local/tmp/echo.apk"
  & $adb -s 192.168.88.66:5555 shell "am startservice -n com.phicomm.speaker.device/.ui.service.WindowsService"
  & $adb -s 192.168.88.66:5555 shell "am startservice -n com.phicomm.speaker.player/.EchoService"
  ```
- **Each shell command runs in a fresh session**: always chain `adb connect 192.168.88.66:5555` in the same command block before `adb -s ...`.
- **No speaker reboot required**: wait ~5–10s after `am startservice`, then verify live against `http://192.168.88.66:8080/` (`<html lang="vi">`) and `http://192.168.88.66:8080/api/status` (verify `uptimeSec` reset and expected JSON fields). Remind the user to hard-refresh (`Ctrl+F5`) if the browser still shows cached Chinese UI.

## 4. VN Fork Flag & Code Conventions
- **Defaults**: `gain=4`, `alc=false`, `ttsVol=50` (`files/tts_vol`), `auto_disconnect=true` (disabled only when `files/auto_disconnect_off` exists; idle timeout `5..600s` via `files/idle<N>`, default `10s`), `myMusic=false` (enabled only when `files/my_music_on` exists; `PlayerBridge.init()` skips `loadIndex()` on boot when `myMusic=false` and no custom `files/music_index`).
- Whenever changing flag defaults or polarity, keep `XzBootstrap.java`, `SessionController.java`, and `WebConsole.java` in sync.
- When deleting old `files/idle<N>` files, only match numeric `idle[0-9]+` so `files/idle_alert_off` is never deleted.
- **Bluetooth / AVRCP**: `BLUETOOTH_PRIVILEGED` and `Bt.java` live in `com.phicomm.speaker.player`; on R1 FW 3448, `getProfileProxy(11)` returns `BluetoothAvrcpController` (not `BluetoothA2dpSink`).
- **Vietnamese STT quit matching (`XiaoZhiWs.isQuitCommand`)**: upstream strips spaces (`Character.isLetterOrDigit`) and caps length at 16 chars (sized for CJK); any Vietnamese quit-command additions must preserve/normalize word spaces and allow longer Latin strings.
