# Building, testing and installing

This page shows every step from an empty computer to PAPRelay on your phone. It works on
Linux, macOS and Windows. On Windows, write `gradlew.bat` where this page says `./gradlew`.

The tests need no phone and no machine. They use a simulated machine.

## 1. What you need

| Tool | Version | Needed for |
|---|---|---|
| Git | any | getting the source |
| JDK | 17 or later (the automatic checks use 21) | everything |
| Android SDK | platform `android-37.0`, build tools `36.0.0` | the app (not needed for the protocol tests) |
| `adb` (Android platform tools) | any | installing on a phone by USB |
| Python 3 | any | only for drawing the documentation diagrams again |

You do not need to install Gradle. The project downloads the right version by itself the
first time. The first build needs an internet connection and takes some minutes.

### The Android SDK

**The easy way:** install [Android Studio](https://developer.android.com/studio) and open
the `paprelay` folder with it. Android Studio installs the missing parts of the SDK and
writes the file `local.properties`, which says where the SDK is.

**With the command line only:** install the
[command-line tools](https://developer.android.com/studio#command-line-tools-only), then:

```bash
sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"
```

```bash
sdkmanager --licenses
```

Then tell the build where the SDK is. Use the folder of your own SDK:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
```

## 2. Get the source

```bash
git clone https://github.com/hahnfeld/paprelay.git
```

```bash
cd paprelay
```

## 3. Run the tests

All tests:

```bash
./gradlew :core:test :as11:test :app:testDebugUnitTest
```

Only the protocol and model tests. These need **no Android SDK**, only a JDK:

```bash
./gradlew :core:test :as11:test -Ppaprelay.skipAndroid=true
```

Everything that the automatic checks on GitHub run (tests, lint for both versions, and the
release build):

```bash
./gradlew :core:test :as11:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease
```

`BUILD SUCCESSFUL` at the end means that every test passed. If a test fails, Gradle prints
the name of the test and the path of a report that you can open in a browser.

## 4. Make your own signing key

Android installs only signed apps. Make a key once, and keep it. You need the **same key**
for every later update.

```bash
keytool -genkeypair -v -storetype PKCS12 -keystore "$HOME/paprelay-key.p12" -alias paprelay -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` is part of the JDK. It asks for a password and for a name. Remember the password.

Never put the key file into the repository. Never send it to anyone.

## 5. Build the app

```bash
PAPRELAY_KEYSTORE="$HOME/paprelay-key.p12" PAPRELAY_KEYSTORE_PASSWORD="your password" ./gradlew :app:assembleRelease
```

The app is now here:

```
app/build/outputs/apk/release/app-release.apk
```

The build reads three environment variables:

| Variable | Meaning |
|---|---|
| `PAPRELAY_KEYSTORE` | The path of the key file. A relative path starts at the project folder. |
| `PAPRELAY_KEYSTORE_PASSWORD` | The password of the key file. |
| `PAPRELAY_KEY_ALIAS` | The name of the key in the file. Only needed if it is not `paprelay`. |

Without `PAPRELAY_KEYSTORE`, the build makes `app-release-unsigned.apk`. That file can be
checked, but it cannot be installed.

## 6. Install it on the phone

1. On the phone, switch on **Developer options** and **USB debugging**.
2. Connect the phone to the computer by USB. Allow the connection on the phone.
3. Install:

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

Without `adb`: copy the file `app-release.apk` to the phone and open it there.

Then follow [First-time setup](../README.md#first-time-setup).

## The debug build

```bash
./gradlew :app:assembleDebug
```

The debug build is a **test version**. It has the name `com.everysoft.paprelay.debug` and
can be installed next to the real app. It runs the device check, but it **never
synchronises**. The reason: Health Connect files every record under the app that wrote it.
To Health Connect, the debug build is a different app, and every night would be stored
twice.

To use PAPRelay, build the release version (steps 4 and 5).

## Checking a downloaded release

The official releases are signed with a certificate with this SHA-256 fingerprint:

```
8E:1A:FB:37:FF:59:27:4A:5C:8D:C5:21:B8:0D:37:83:3C:0C:1F:0A:51:98:A7:D9:DF:5E:8D:AE:EC:F8:98:06
```

To see the fingerprint of an APK file (`apksigner` is in the SDK folder `build-tools/36.0.0`):

```bash
apksigner verify --print-certs paprelay-0.2.0.apk
```

## If the build fails

| Message | Meaning and solution |
|---|---|
| `SDK location not found` | The build cannot find the Android SDK. Set `ANDROID_HOME` (step 1), or open the project once in Android Studio. |
| `License for package … not accepted` | Run `sdkmanager --licenses` and accept. |
| `PAPRELAY_KEYSTORE is set, so PAPRELAY_KEYSTORE_PASSWORD must be set too` | Give both variables (step 5). |
| `keystore password was incorrect` | The password does not match the key file. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` on install | An app with another signature is already installed, for example the official release. Uninstall it first. The data in Health Connect stays. You must pair the machine again. |
| Lint reports an error | Lint warnings are errors in this project. The message says the file and the line. |

## Drawing the diagrams again

The pictures in `docs/images` are made by a script. Do not change them by hand.

```bash
python3 tools/gen_doc_diagrams.py
```
