# LiveTGTV Android

Native Android app for phones and tablets. The Windows desktop application remains in [Tv-app](https://github.com/MoonDevil1/Tv-app).

## Features
- Loads the LiveTGTV public M3U playlist
- Channel search and category filtering
- Native Android Media3 / ExoPlayer playback for supported HLS and DASH streams
- Passes playlist-provided stream headers
- Widevine DRM configuration when a channel provides a valid license URL
- Responsive Compose layout for phones and tablets
- LiveTGTV launcher icon and dark theme

## Build APK
The **Android APK** GitHub Actions workflow builds a debug APK on every push to `main`. Download the `LiveTGTV-Android-APK` artifact from the latest successful workflow run.

For local builds, install JDK 17 and Gradle 8.9, then run `gradle assembleDebug`. The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

## DRM notes
The app supports standard Widevine DRM playback when the stream provides a compatible manifest, a valid Widevine license URL, and any required authorization headers. It does not bypass DRM, obtain hidden keys, or guarantee playback for channels whose playlist omits license information or whose provider blocks the device/app.
