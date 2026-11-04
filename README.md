# Local AI Linux Desktop for Android (v0.8)

A Linux-only AI workstation running in a native ARM64 Android app. **No Termux. No root. No Android UI automation.**

**Read the development roadmap and missing features:** [DEVELOPMENT_PLAN_TH.md](DEVELOPMENT_PLAN_TH.md) (Thai).

## What is in the source
- Embedded Alpine Linux ARM64 rootfs with PRoot, using Android's kernel and the app's private filesystem.
- Optional graphical Linux desktop: Openbox + TigerVNC/Xvnc + tint2 + PCManFM + XTerm. Install packages on the first launch from Control Center.
- Responsive **in-app noVNC** viewer for the *Linux* desktop, and private LAN/VPN VNC for a desktop PC.
- On-device GGUF inference, model download/import, SQLite chat, Linux command planner with explicit user approval.
- Local AI web dashboard plus a private-LAN web endpoint when the desktop service is running and a password is configured.
- Foreground notifications and user-started Stop controls; a responsive native Control Center and mobile WebView UI.
- **No Accessibility or MediaProjection permissions** in the current Android manifest.

## Getting started
1. Install the app on an ARM64 phone, set the AI web password.
2. Install Alpine Linux from the APK.
3. Download/import a GGUF model if you want local AI chat and Linux task planning.
4. Install graphical desktop packages (internet required for the first setup).
5. Start Linux Desktop and set a VNC password (legacy VncAuth uses only the first 8 ASCII characters).
6. Open Linux Desktop *inside the app*, or connect your PC's VNC Viewer to the private IP and port displayed in the app.
7. To use web AI from a PC, open the private IP and port shown in Control Center and sign in.

## Security and limitations
- Android remains Android; PRoot is not a VM or strong security sandbox.
- Do not expose VNC or HTTP ports to the public internet. They are **not encrypted**: use trusted LAN/private VPN only.
- The app does not control Android. Browser automation **inside Linux** is still on the roadmap and not implemented.
- Build success confirms compilation and packaged assets, **not device-level functionality**. Android 17 PRoot/VNC E2E tests remain outstanding.
- Current GitHub Actions fall back to ephemeral **debug signing** when persistent release signing secrets are absent. APKs signed by different keys **cannot update one another in place**. Do not uninstall a version with valuable app data before backup/migration.
- Model weights and the graphical desktop packages are not preinstalled in the ~11 MB APK.

## Documentation
- [Development plan (Thai)](DEVELOPMENT_PLAN_TH.md)
- [Getting started: Linux desktop and PC VNC (Thai)](README_DESKTOP_TH.md)
- [Build workflow](.github/workflows/build-apk.yml)

This is an evolving prototype, not a verified production-ready remote desktop.
