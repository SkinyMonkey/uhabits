# Build the project

This page describes how to download and build the app from the source code. If you are having trouble building the project, please do not hesitate to open a new issue.

## Contents

* [Build using Android Studio](#build-using-android-studio)
* [Build from the command line](#build-from-the-command-line)
* [Build for iPhone](#build-for-iphone)

## Build using Android Studio

### Step 1: Install git

The package `git` is required for downloading the source code of the app and submitting changes to GitHub. Please see [the git book](https://git-scm.com/book/en/v2/Getting-Started-Installing-Git) for further instructions. If you are planning to submit pull requests in the future, it is recommended to [generate and configure your SSH keys](https://help.github.com/en/github/authenticating-to-github/generating-a-new-ssh-key-and-adding-it-to-the-ssh-agent).

### Step 2: Download and install Android Studio

Although Android Studio can be downloaded [from their official website](https://developer.android.com/studio/), a much better option is to install it through [JetBrains Toolbox](https://www.jetbrains.com/toolbox-app/). This tool, developed by the same developers of Android Studio, allows you to easily upgrade and downgrade the IDE, or switch between stable, beta and canary versions. After downloading and installing JetBrains Toolbox, simply click the install button near Android Studio to install the newest stable version of IDE. Beta and canary versions have not been tested and may not work correctly.

After installation, launch Android Studio. If this is the first time you launch it, you will need to go through a wizard to setup the IDE. The default options should work fine. The wizard will download all additional components necessary for development, including the emulator, so it may take a while.

### Step 3: Download the source code

To create a complete copy of the source code repository, open the terminal (Linux/macOS) or Git Bash (Windows), navigate to the desired folder, then run:
```bash
git clone https://github.com/iSoron/uhabits.git
```
The repository will be downloaded to the directory `uhabits`.

### Step 4: Open and run the project on Android Studio

1. Launch Android Studio and select "Open an existing Android Studio project".
2. When the IDE asks you for the project location, select `uhabits` and click "Ok".
3. Android Studio will spend some time indexing the project. When this is complete, click the toolbar icon "Sync Project with Gradle File", located near the right corner of the top toolbar.
4. The operation will likely fail several times due to missing Android SDK components. Each time it fails, click the link "Install missing platforms", "Install build tools", etc, and try again.
5. To test the application, create a virtual Android device using the menu "Tools" and "AVD Manager". The default options should work fine, but feel free to customize the device.
6. Click the menu "Run" and "uhabits-android". The application should launch.


## Build from the command line

The following instructions were tested on **Ubuntu Linux 24.04 LTS** and may need to be modified for other operating systems.

### Step 1: Install basic packages

To build the application, some basic packages are required. The package `git` is required to download the source code, while `openjdk-17-jdk-headless` is required for compiling Java and Kotlin files.

```bash
sudo apt-get update
sudo apt-get install -y git openjdk-17-jdk-headless
```

**IMPORTANT:** Newer JDK versions have not been tested and may not work correctly.

### Step 2: Install Android SDK tools

The Android SDK tools contain many of the necessary tools for developing and debugging Android applications. They can be obtained as part of Android Studio, but, for simple command line usage, it is recommended to install the **command-line tools** directly, since that is what `build.sh` and the CI pipeline expect.

1. Download the latest `commandlinetools-linux-*.zip` from <https://developer.android.com/studio#command-line-tools-only>, and extract it to `/opt/android-sdk/cmdline-tools/latest` so that the script `/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager` exists.

2. Append the following lines to `~/.profile`, so that other tools can locate your Android SDK installation. It is necessary to restart your terminal for these changes to take effect.
```bash
export ANDROID_HOME="/opt/android-sdk"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin"
export PATH="$PATH:$ANDROID_HOME/platform-tools"
```

3. Accept all Android SDK licenses by running:
```bash
yes | sdkmanager --licenses
```

### Step 3: Download the source code

To create a complete copy of the source code repository, navigate to your home directory and run:
```bash
git clone https://github.com/iSoron/uhabits.git
```
The repository will be downloaded to the directory `uhabits`.

### Step 4: Compile the source code

1. Navigate to the directory `uhabits`
2. Run `./gradlew assembleDebug --stacktrace`

If the compilation is successful, a debug APK will be generated somewhere inside the folder `uhabits-android/build/`. Currently, the full path is `./uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk`, but it may change in the future.

The APK can be installed using the tool `adb`, which should have been automatically installed at `/opt/android-sdk/platform-tools/adb` during compilation of the project.

## Build for iPhone

The iPhone app (`uhabits-ios`) is a native SwiftUI app built on the same Kotlin Multiplatform core (`uhabits-core`) as the Android app. It requires **macOS** with **Xcode 26** or newer and **JDK 17**, since Xcode runs Gradle to compile the core into the `UhabitsCore` framework.

### Run in the simulator

1. Open `uhabits-ios/uhabits-ios.xcodeproj` in Xcode.
2. Select the `uhabits-ios` scheme and an iPhone simulator, then click "Run". The first build takes a few minutes while Gradle compiles the core. If Xcode cannot find Java, make sure `java -version` reports JDK 17 in the environment Xcode is launched from (for example by setting `JAVA_HOME` in `~/.zprofile`).

From the command line:
```bash
xcodebuild -project uhabits-ios/uhabits-ios.xcodeproj -scheme uhabits-ios \
    -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

### Run on a device

The app and its widget extension share data through the app group `group.org.isoron.uhabits.ios`. To run on a real device, select a development team for both the `uhabits-ios` and `uhabits-iosWidgets` targets, change the bundle identifiers and the app group to ones registered with that team, and update the identifiers in `uhabits-ios.entitlements`, `uhabits-iosWidgets.entitlements` and `IosWidgets.GROUP_ID`.

### Run the tests

```bash
# Shared core, on the iOS simulator
./gradlew :uhabits-core:iosSimulatorArm64Test

# App UI tests
xcodebuild test -project uhabits-ios/uhabits-ios.xcodeproj -scheme uhabits-ios \
    -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

In debug builds, the app accepts a few launch arguments that are useful for screenshots and testing: `-resetData` starts from an empty database, `-seed` and `-seedFull` create sample habits, and `-showHabit <id>`, `-editHabit <id>`, `-newHabit` and `-openSettings` open a screen directly.
