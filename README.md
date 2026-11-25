# M30HE Probe (read-only)

Sends only `55 01` and `55 07` read commands to a keyboard with USB vendor ID 0x19F5.
Never writes settings.

Build without a PC (phone browser):
1. Make a free GitHub account, create a new repo.
2. Upload these files, keeping folders (`app/`, `.github/workflows/build.yml`).
3. Open the repo > Actions > "Build APK" > Run workflow.
4. When done, open the run > Artifacts > m30-probe-apk, download, unzip, install the APK.
