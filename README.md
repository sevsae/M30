# M30HE Keyboard Manager

Android USB-OTG utility for M30 Hall-effect keyboards (USB vendor ID `0x19F5`).
The app can read and write trigger settings and key mappings, switch profiles,
and read, apply, and verify per-profile keyboard lighting settings. Lighting
settings use command `0x05` to read and `0x06` to write the profile's 64-byte
configuration bank.

The RGB screen applies a color and brightness using supported lighting effect 1
(mode 0 can leave the LEDs off), while preserving unrelated lighting bytes. The app only shows
physical keys discovered in the connected profile's keymap.

Build without a PC (phone browser):
1. Make a free GitHub account, create a new repo.
2. Upload these files, keeping folders (`app/`, `.github/workflows/build.yml`).
3. Open the repo > Actions > "Build APK" > Run workflow.
4. When done, open the run > Artifacts > m30-probe-apk, download, unzip, install the APK.
