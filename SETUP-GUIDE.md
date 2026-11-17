# Doomsday Alert - setup guide

## Part 1: Build the APK (one time, about 15 minutes, needs a PC or laptop)
1. Make a free account at github.com. Click New repository, name it `doomsday-alert`, keep it Private, Create.
2. Unzip `DoomsdayAlert-source.zip`. On the repository page click "uploading an existing file".
   Drag in the CONTENTS of the folder: `app` folder, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, and the `.github` folder. Click Commit.
3. Check the repository shows a `.github/workflows/build.yml` file. If it is missing (hidden folders often do not upload):
   Add file > Create new file, type the name `.github/workflows/build.yml`, paste the text below, Commit.
4. Click the Actions tab. Wait for "Build APK" to finish (3-6 min, green tick).
   If it runs automatically on commit, fine. If not: Build APK > Run workflow.
5. Click the finished run, scroll to Artifacts, download `DoomsdayAlert-APK`, unzip it. You get `app-debug.apk`.
6. A red cross means the build failed. Open the run, copy the red error text and send it to Claude.

build.yml text:
```
name: Build APK
on:
  push:
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'
      - run: gradle assembleDebug --no-daemon --stacktrace
      - uses: actions/upload-artifact@v4
        with:
          name: DoomsdayAlert-APK
          path: app/build/outputs/apk/debug/app-debug.apk
```

## Part 2: Install on your Android phone
1. Send `app-debug.apk` to your phone (WhatsApp to yourself, Telegram, Drive, or USB).
2. Tap it. If asked, allow "Install unknown apps" for that app (file manager / WhatsApp / Chrome).
3. If Play Protect warns, tap More details > Install anyway. (Normal for apps outside the Play Store.)
4. Open Doomsday Alert. Tap Allow on the notification question.

## Part 3: First-time setup inside the app
1. Setup tab: in the BookMyShow app open Avengers: Doomsday > Share > copy. Paste in the app, set 120 seconds, Save settings.
2. Setup tab, press the three phone-settings buttons:
   - Allow notifications (turn everything on)
   - Stop battery optimisation (choose Allow)
   - Open app settings > Battery > Unrestricted (wording differs per phone)
3. Alerts tab: choose your sound, turn alarm volume up, tap Send test alert. Make sure you hear it and see it.
4. Status tab: Start watching. After ~20 seconds it should say WORKING with "Page loaded fine. No booking button yet."
   If it says CANNOT READ PAGE: tap Copy debug info and send it to Claude.

## Part 4: Phone settings that make or break it
- Battery: Unrestricted for this app (Samsung: Settings > Apps > Doomsday Alert > Battery > Unrestricted, and add it to "Never sleeping apps").
- Xiaomi/Redmi/Poco: Autostart ON, Battery saver > No restrictions.
- Oppo/Realme/OnePlus/Vivo: allow Auto-launch / Background activity.
- Open the recent-apps screen and LOCK the app (tap its icon > Lock) so cleaner apps do not kill it.
- Do not swipe it away or Force stop. Keep Wi-Fi on; keep the phone plugged in at night.
- Do not turn on Do Not Disturb with "alarms" blocked. Alerts use the alarm volume.
- Data: the checker loads a real web page every 2 minutes, so use Wi-Fi. On mobile data expect hundreds of MB per day.

## Test every morning
Open the Status tab. It must say WORKING and "Last check" must be within the last 2-3 minutes.
If it says STALLED or NOT WATCHING, tap Start watching.
And still check BookMyShow yourself a few times a day close to release. No app can promise.
