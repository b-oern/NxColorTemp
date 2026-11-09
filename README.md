# NxColorTemp

Android app that measures the color temperature (2000–8000 K) of the central part of the camera image in real time
(sRGB → CIE XYZ → CIE 1960 uv → Robertson CCT).

## Build

Requirements: JDK 21, Android SDK (compileSdk 36).

```bash
./gradlew assembleRelease
```

The unsigned APK is written to `app/build/outputs/apk/release/`.

## Permissions

- `CAMERA`: required for the measurement. The app has no network access.

## License

MIT, see [LICENSE](LICENSE).
