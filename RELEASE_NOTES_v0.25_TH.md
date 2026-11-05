# v0.25 — Desktop lifecycle preview

Stopping Linux previously killed PRoot while leaving Xvnc listening, so the
next start could report that the desktop port was already in use. Desktop
launchers now own separate process groups and stop their own children.
Startup cancellation also cleans up a launcher whose PID is still being read.

The viewer disables Connect until its asynchronous noVNC module has initialized
and reports a visible load error. Rotation keeps the Activity and VNC session.
XTerm explicitly selects the installed DejaVu Sans Mono font, and X11/window
readiness checks use elapsed-time limits instead of six fast failed probes.

Validation: 21 Android runtime checks plus cross-process recovery passed in
the separate native x86_64 PC diagnostic environment. Real noVNC pixels,
pointer/click focus, a unique keyboard output, both orientations, bubble
visibility and stop/restart were verified. Seven Python contract checks and
the JVM suite are also required for this change; see the run report.

Release downloads must use the original production ID
`com.localai.linuxagent.desktop` and the original owner signing certificate.
Before either artifact upload or release publication, an APK verifier checks
the production ID, pinned public certificate, ARM64 ABI, non-debug build and
increasing versionCode against the last owner-signed release. TEST APKs are
diagnostics only and must not be distributed as updates to the main app.

Native ARM64 Linux execution and production v0.22-to-v0.25 installation are
still unverified on physical hardware. The PC diagnostic results above do
not establish that the phone runtime is ready.
