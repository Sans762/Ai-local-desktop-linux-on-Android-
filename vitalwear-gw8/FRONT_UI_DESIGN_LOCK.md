# VitalWear GW8 — Front UI design lock (user-approved 2026-11-20)

The user explicitly approved three existing reference visuals: images 1–2 are in-app home/menu; image 3 is a **separate watch-face app**. **Preserve these designs in their correct targets.** The imagery in the conversation, not new AI renderings, is the intended visual reference; the descriptions below are to make code reviews unambiguous.

1. **City portrait home (primary)**: Vertical Vital Bracelet-style window centered on circular Galaxy Watch display, bright blue pixel-art skyline and pavement, real active Digimon sprite at the bottom, large digital clock toward top, compact VP/steps and heart-rate counters. Dark frame with neon-lime highlights is visual design (not hardware), and every item must stay within the circular glass safe area. No stretching to circular full-screen.
2. **Main menu (primary)**: The same tall rectangle, dark/navy pixel background, crisp cyan pixel borders, neon-lime selected row, simple pixel icons to the left. User reference labels: STATUS, TRAINING, ADVENTURE, BATTLE, ITEMS, CONNECT, SENSOR. All visible rows should be touch/rotary accessible with correct underlying functions. Do not insert dead/mock actions: ITEMS only if a real inventory/action is implemented, otherwise keep a working CHARACTER action without claiming ITEMS is functional. SETTINGS / RECOVER NFC may be accessible via scroll after front rows without breaking visual reference.
3. **Forest full-circle WATCH FACE (separate watch-face APK, NOT an in-app home theme)**: The user explicitly clarified on 2026-11-20 that reference image 3 is a standalone smartwatch watch face. It should occupy the entire circular watch display and show a pixel landscape, time, real Digimon, and compact WILD/BPM/STEPS/VITALS/FIGHTS chips when corresponding live data is supported. Maintain it in the independent watch-face module (VBClassicXL-Face-EXPERIMENT.apk / WFF), separate from VitalWear's home and menu screens. Do not add a forest home theme to the game app in place of this watch face. Treat the artwork as a target reference, not a claim that all dynamic Digimon data/animation is supported by WFF.

**Approved scope of UI fixes**: fix the inner menus, submenus, status LIMIT/RANK, training, battle, adventure, character selection, connect, sensors and settings. Use scaled-down portrait/safe-area layout, readable pixel labels and real actions. Preserve working game functions, state and NFC transfer code. Do not replace or cosmetically redesign the approved front screens as a side effect of inner-menu repairs.

**Validation requirements**:
- On Galaxy Watch 8, no content or controls outside round-screen safe bounds.
- Screenshot comparisons must distinguish conceptual reference renders from actual device captures.
- GitHub CI build and unit tests alone **do not prove** on-device match or end-to-end VBHelper NFC compatibility.
- Keep an independent NFC investigation; no destructive transfers or optimistic success claims.

**Important**: Existing 0.6.x APK may still differ visually from reference renders; this specification locks the **target designs**, not a claim of pixel-perfect completion.
