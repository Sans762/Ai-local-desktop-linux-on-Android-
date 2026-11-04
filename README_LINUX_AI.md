# Local AI Native v0.4 — AI installer + Built-in Linux

This builds directly as an Android ARM64 application, without Termux, without root.

## Built-in Linux

During GitHub Actions build, `tools/fetch-embedded-linux.sh` bundles native Android PRoot binaries from a **pinned upstream commit** and an **official Alpine Linux 3.23.7 ARM64 mini-rootfs** in the APK. This can be unpacked from **Control Center → Linux built-in → Install** entirely offline. The Alpine userland shares Android's kernel; it is not a VM or an independent Linux kernel. It runs through the app's `nativeLibraryDir` executables for Android W^X compatibility. The console is local-only, not accessible on the remote web endpoint. Linux files live in `filesDir/embedded_linux/`, separate from model files and original user content. Full interactive desktop/GUI has not been added.

`PRoot` runtime code: https://github.com/oonid/pr , pinned `85764d46f9c36b2ddd5bc6f1cdb45b4bcf4f851d`.
Original PRoot upstream and Android patches use GPL-2.0; review notices at the upstream source before redistribution. Alpine packages have their respective licenses: https://www.alpinelinux.org/about/.

## Local AI GGUF

The APK includes the Android-native GGUF inference *engine*, **not** hundreds of MB of model weights. The user explicitly chooses either Qwen2.5 0.5B Q4_K_M (~491 MB) or Qwen2.5 1.5B Q4_K_M (~1.12 GB) from Control Center. Android downloads in a foreground data-sync service (cancel with notification), verifies the manufacturer's published SHA-256, then atomically activates the model. Interrupted downloads retain `.part` for resuming, and an imported existing model remains untouched. Internet is needed only for initial model download. Inference uses no external AI API. Downloading while the app is running requires sufficient storage and battery; device manufacturers may still terminate foreground services.

Model publisher: https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF and https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF (Apache-2.0). This small model is **not** comparable to the quality or autonomy of ChatGPT. Runtime inference, rootfs compatibility, and device support require testing on the user's Android phone.

## Security and limitations

Linux console runs commands locally when the user explicitly presses Run; no remote HTTP or model action exposes unrestricted shell execution. Phone screen sharing requires MediaProjection consent. Input control separately requires Accessibility and explicit grant. No SSH server or publicly exposed rootfs console is started. Chrome/remote UI is **LAN/VPN only**, with unencrypted HTTP on local networks, and is not a secure public-internet access method. The assistant in this chat cannot connect directly to the phone unless a separately authorized supported connector is installed and online. No Termux service or Termux files are touched.