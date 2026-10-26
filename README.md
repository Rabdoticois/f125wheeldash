# F1 Dash — F1 25 wheel-style dashboard for an Android tablet

A full-screen dashboard that listens for F1 25's UDP telemetry straight from the Xbox
(no laptop needed). Three screens, tap the tabs at the top:

* **RACE** – gear, speed, shift lights, throttle/brake, DRS, ERS battery + mode, fuel, brake bias,
  engine temp, lap / position, current / last / best lap, sector times coloured purple / green /
  yellow (red = invalid), gaps to the car ahead and the leader, tyre temps + wear, flags, pit status.
  Tap the middle of the screen to flip between RACE and CAR.
* **CAR** – every tyre (surface / inner temp, pressure, wear, brake temp, damage, blisters), damage
  bars (wings, floor, diffuser, sidepods, gearbox, engine), faults, weather and temperatures.
* **SETUP** – shows this tablet's IP address and the exact settings to enter in the game, live
  packet rate, **Demo mode** (a pretend race so you can try every screen) and km/h / mph.

## Connect the game

1. Xbox and tablet on the **same Wi-Fi**.
2. F1 25 → Settings → Telemetry Settings:
   * UDP Telemetry **On**, UDP Format **2025**
   * UDP IP Address = the address shown on the app's SETUP tab
   * UDP Port **20777**, Broadcast Mode **Off**, Send Rate 30 or 60 Hz
3. Start a session. The header changes from NO SIGNAL to LIVE.

(Broadcast Mode **On** sends to every device on the network, so the tablet and a laptop can both
receive at once; unicast to one IP is more reliable on most Wi-Fi.)
Give the tablet a fixed address in your router (DHCP reservation) so the IP in the game never goes stale.

## Build the APK

**A. GitHub (nothing to install).** Create a new GitHub repository, upload everything in this
folder (keep the `.github` folder), open the **Actions** tab → *Build APK* → when it finishes,
download the `F1Dash-apk` artifact and unzip it to get `app-debug.apk`.

**B. Android Studio.** File → Open this folder, let it sync (accept the Gradle version it
suggests, 8.9 or newer), then Build → Build APK(s). The file ends up in
`app/build/outputs/apk/debug/app-debug.apk`.

**C. Command line.** With JDK 17, the Android SDK (platform 34) and Gradle 8.9+ installed:
`gradle assembleDebug`.

## Install on the tablet

Copy `app-debug.apk` to the tablet (USB, Drive, email…), open it, and allow "install unknown apps"
when Android asks. The debug APK is signed automatically, so it installs as is.

## Notes

* The packet parser was checked against the same simulated F1 25 packets used to test the
  laptop dashboard (every field matched). The app code compiles against the Android 34 API.
  It has not been run on a real tablet or with the real game yet.
* Best lap and the purple/green sector colours come from the game's session-history packets,
  so they appear a few seconds after you complete a lap or sector.
* The dashboard shows the player car only (F1 25 sends the telemetry for all cars, the app reads yours).
