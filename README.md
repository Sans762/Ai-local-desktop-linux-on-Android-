# Local AI Native for Android — v0.3

Offline-first AI on Android. No Termux is required for installation or runtime.

Features: in-app AI workspace, on-device GGUF runtime, SQLite chat history, native control center, explicit Accessibility gestures, user-approved MediaProjection screen sharing and a private-network browser Remote Desktop viewer.

Remote Desktop: share from the phone's control center, copy the listed private-network URL, enter the visible one-time session PIN in a browser. Viewing is the default. Remote input must be separately enabled on the phone and requires Accessibility access. Sharing can be stopped from the app or notification; all remote tokens are invalidated then.

**Important:** The remote viewer currently uses HTTP and restricts clients to private LAN/CGNAT addresses. Only use on trusted Wi-Fi or private VPN, never expose port 18789 publicly. This is not a secure public internet relay, and does not automatically connect ChatGPT to your Android device. Android may stop background services. Screen sharing requires renewed Android consent each session.

Build via `.github/workflows/build-apk.yml`. A signed debug build from different GitHub runners may use different keys, so back up any original data before replacing an existing installation. There is no app data deletion or migration away from existing files.