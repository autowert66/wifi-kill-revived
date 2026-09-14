# WiFiKill

WiFiKill is a rooted Android utility for inspecting devices on the local IPv4
Wi-Fi network and temporarily interrupting a selected device's connection with
ARP spoofing. Turning the switch off sends corrective ARP announcements and
ends the spoofing session.

The app is intended for network troubleshooting, lab work, and authorized
testing. It does not perform Wi-Fi deauthentication, and it does not affect
IPv6 traffic.

[![Build](https://github.com/autowert66/wifi-kill-revived/actions/workflows/build.yml/badge.svg)](https://github.com/autowert66/wifi-kill-revived/actions/workflows/build.yml)

## Requirements

- A rooted ARM64 Android device with a working `su` binary
- Android 8.0 or newer, because the app's minimum SDK is 26
- A Wi-Fi chipset and ROM that permit the required raw packet operations
- Android SDK, Android NDK, JDK 21, and Python 3 for building from source

Check root access before installing:

```bash
adb shell su -c id
```

The command should return a root user ID. Root access alone does not guarantee
that the device's Wi-Fi driver will pass crafted packets.

## Build From Source

The native tools are compiled for Android ARM64 and bundled into the APK as
assets.

```bash
# Build arpscan and arpspoof. Pass an NDK path if it is not auto-detected.
./native/build.sh [/path/to/android-ndk]

# Refresh the MAC-address vendor database when needed.
python3 tools/gen_oui.py

# Copy the native build outputs into the app assets.
cp bin/arpscan bin/arpspoof app/src/main/assets/

# Build, test, and lint the debug variant.
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`native/build.sh` looks for the NDK in its argument, `ANDROID_NDK_HOME`,
`ANDROID_SDK_ROOT`, `ANDROID_HOME`, and common Android SDK locations.

## Use The App

1. Connect the phone to the Wi-Fi network you are authorized to test.
2. Open WiFiKill and grant root access when prompted.
3. Scan the local network. Hosts are listed with their IP address, MAC address,
   hostname when available, and hardware vendor.
4. Toggle a host to start or stop its ARP spoofing session.
5. Use the foreground-service notification to restore all active sessions if
   the app is no longer visible.

The gateway cannot be selected as a target. Restoring a host depends on the
spoofing process receiving its cleanup signal; the app also monitors active
sessions and restores targets when a blocker exits unexpectedly.

## Verify Native Tools

You can test the compiled tools before using the UI. Replace the interface,
network, and target values with values from your own network.

```bash
adb push bin/arpscan /data/local/tmp/arpscan
adb shell su -c "chmod 755 /data/local/tmp/arpscan"
adb shell su -c "/data/local/tmp/arpscan wlan0 192.168.1.1 24"

adb push bin/arpspoof /data/local/tmp/arpspoof
adb shell su -c "chmod 755 /data/local/tmp/arpspoof"
```

The scanner prints responding hosts as `IP MAC` pairs. Do not start a spoofing
session against a device or network without explicit authorization.

## Project Layout

```text
app/                 Android application written in Kotlin
app/src/main/assets/ Native binaries and the OUI vendor database
native/              C sources and the Android NDK build script
tools/gen_oui.py     Generates the bundled OUI database
bin/                 Locally built ARM64 native binaries
```

The GitHub Actions workflow builds the native tools, debug and release APKs,
runs unit tests, and uploads the debug APK as an artifact. Tagged commits also
create a GitHub release with both APK variants.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Scan fails immediately | Root is unavailable, or raw sockets are blocked by the ROM or SELinux policy. |
| No hosts appear | The phone is not on Wi-Fi, the interface name is unusual, or the network blocks ARP responses. |
| A target reconnects | Some clients refresh their ARP cache faster than the spoofing interval. |
| IPv6 traffic continues | ARP only covers IPv4. IPv6 neighbor discovery is not implemented. |
| The app cannot block a device | The Wi-Fi chipset or driver does not transmit the required crafted frames. |

## Tests

Run the unit tests with:

```bash
./gradlew :app:testDebugUnitTest
```

The tests include hostname-resolution wire-format parsing and OUI lookup
behavior.

## Safety

Use WiFiKill only on networks and devices you own or have explicit permission
to test. Interrupting another person's network access without authorization may
be unlawful. The authors are not responsible for misuse or for connectivity
that is not restored after a device, ROM, or driver failure.
