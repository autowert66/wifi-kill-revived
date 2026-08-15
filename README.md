# WiFiKill MVP

Deauth a device from your own Wi-Fi network via ARP spoofing, then restore it.

```
wifi-kill-revived/
├── app/                 Android app (Kotlin)
│   └── src/main/
│       ├── kotlin/dev/a99/wifikill/
│       │   ├── MainActivity.kt        UI + lifecycle
│       │   ├── MainViewModel.kt       scan / kill / hostname state
│       │   ├── RootExecutor.kt        su-based process launcher
│       │   ├── NetworkScanner.kt      arpscan deploy + output parsing
│       │   ├── HostnameResolver.kt    reverse-DNS / mDNS / NBNS racers
│       │   ├── OuiLookup.kt           MAC prefix -> manufacturer
│       │   ├── ArpSpoofer.kt          arpspoof lifecycle (kill/unkill)
│       │   ├── model/Host.kt
│       │   └── ui/HostListAdapter.kt
│       └── assets/                    arpscan, arpspoof, oui.json
├── native/              C sources + cross-compile script
├── tools/gen_oui.py     regenerates assets/oui.json from wireshark.org
└── bin/                 host-built ARM64 binaries (build artifacts)
```

## Requirements

- Android device (ARM64) **rooted** with working `su` (`adb shell su -c id`)
- Android NDK (e.g. `ndk;27.2.12479018`), JDK 21, Gradle wrapper (8.9)
- Wi-Fi must be in station/client mode; the phone's chipset must pass raw frame
  injection (many don't — test early).

## Build

```bash
# 1. Native binaries
./native/build.sh                # requires NDK under ~/Library/Android/sdk/ndk

# 2. OUI database (once, or whenever you want fresh vendors)
python3 tools/gen_oui.py         # writes app/src/main/assets/oui.json

# 3. Copy binaries into the app (build.sh outputs to bin/)
cp bin/arpscan bin/arpspoof app/src/main/assets/

# 4. APK + tests + lint
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# 5. Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Manual on-device verification (before using the UI)

```bash
adb push bin/arpscan /data/local/tmp/arpscan
adb shell su -c "chmod 755 /data/local/tmp/arpscan"
adb shell su -c "/data/local/tmp/arpscan wlan0 192.168.1.1 24"
# expect "IP MAC" lines for responding hosts

adb push bin/arpspoof /data/local/tmp/arpspoof
adb shell su -c "chmod 755 /data/local/tmp/arpspoof"
adb shell su -c "/data/local/tmp/arpspoof wlan0 <victim> <victim_mac> <gateway_ip> <gateway_mac> &"
# victim loses internet; `killall arpspoof` (SIGTERM) restores it
```

## App flow

1. FAB triggers an ARP scan of the current SSID's subnet.
2. Each host gets OUI manufacturer + a hostname (
   reverse DNS / mDNS / NetBIOS racers) once found.
3. Flipping a switch spawns `arpspoof` claiming the gateway's IP, refreshes
   every 1s, and restores the correct mapping on SIGTERM.
4. `unkillAll()` on `onDestroy`/`onCleared` cleans up all targets.

## Known failure modes

| Situation | Handling |
|---|---|
| No root | Dialogs on launch, switches no-op |
| Gateway MAC missing from `/proc/net/arp` | Kill toggle shows a toast; re-scan to refresh ARP table |
| SELinux blocks raw sockets | Some ROMs need `setenforce 0`; error surfaces as scan failure |
| Chipset drops crafted frames | Nothing to fix; test on real hardware early |
| Target re-ARPs (some OSes) | 1s resend interval beats most |
| IPv6 targets | Unaffected (ARP is IPv4-only; NDP is out of scope) |

**Only use on networks and devices you own.**

## Tests

Unit tests cover the DNS/NBNS wire-format parsing (`HostnameResolverTest`).
Run with `./gradlew :app:testDebugUnitTest`.