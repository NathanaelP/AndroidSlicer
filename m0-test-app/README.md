# M0 test app

A throwaway, single-screen Android app for milestone M0. It checks that CuraEngine runs on the tablet and gives the same result as the desktop build.

It ships the arm64 engine as `libcuraengine.so` in the APK's native library folder, the one place Android lets an app run a binary from. It runs the engine as a separate process, slices the 20 mm test cube with [`engine/test/m0-settings.txt`](../engine/test/m0-settings.txt), and compares the result with [`engine/test/m0-expected.txt`](../engine/test/m0-expected.txt).

## Running the test

1. Install the APK on the tablet (allow installs from unknown sources when asked).
2. Open **MobileSlicer M0 test** and tap **Slice test cube**.
3. The top line reads `RESULT: PASS` when layers, print time, filament and the toolpath hash all match the desktop run exactly. Otherwise it shows each difference.
4. Tap **Copy results** and paste them back into the conversation or an issue.

## Building

After building the engine (see [`engine/README.md`](../engine/README.md)):

```sh
export ANDROID_NDK_HOME=/path/to/android-ndk-r29
m0-test-app/prepare.sh                         # copies the engine and test files in
echo "sdk.dir=/path/to/android/sdk" > m0-test-app/local.properties
cd m0-test-app && ./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the local debug key, so a build from a different machine can't install over this one: uninstall the old copy first.
