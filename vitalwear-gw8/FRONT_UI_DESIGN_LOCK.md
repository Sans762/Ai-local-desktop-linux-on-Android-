# VitalWear GW8 — Front UI design lock (user-approved 2026-11-20)

The user explicitly approved three existing reference visuals. **Preserve these front-facing designs.** The imagery in the conversation, not new AI renderings, is the intended visual reference; the descriptions below are to make code reviews unambiguous.

1. **City portrait home (primary)**: Vertical Vital Bracelet-style window centered on circular Galaxy Watch display, bright blue pixel-art skyline and pavement, real active Digimon sprite at the bottom, large digital clock toward top, compact VP/steps and heart-rate counters. Dark frame with neon-lime highlights is visual design (not hardware), and every item must stay within the circular glass safe area. No stretching to circular full-screen.
2. **Main menu (primary)**: The same tall rectangle, dark/navy pixel background, crisp cyan pixel borders, neon-lime selected row, simple pixel icons to the left. User reference labels: STATUS, TRAINING, ADVENTURE, BATTLE, ITEMS, CONNECT, SENSOR. All visible rows should be touch/rotary accessible with correct underlying functions. Do not insert dead/mock actions: ITEMS only if a real inventory/action is implemented, otherwise keep a working CHARACTER action without claiming ITEMS is functional. SETTINGS / RECOVER NFC may be accessible via scroll after front rows without breaking visual reference.
3. **Forest full-circle home (optional alternate theme)**: Rich landscape pixel scene, large clock, Digimon centered, compact WILD/BPM/STEPS/VITALS/FIGHTS chips. Do not replace primary city portrait home with forest theme by default. This is an alternate appearance, not evidence of an implemented gameplay screen.

**Approved scope of UI fixes**: fix the inner menus, submenus, status LIMIT/RANK, training, battle, adventure, character selection, connect, sensors and settings. Use scaled-down portrait/safe-area layout, readable pixel labels and real actions. Preserve working game functions, state and NFC transfer code. Do not replace or cosmetically redesign the approved front screens as a side effect of inner-menu repairs.

**Validation requirements**:
- On Galaxy Watch 8, no content or controls outside round-screen safe bounds.
- Screenshot comparisons must distinguish conceptual reference renders from actual device captures.
- GitHub CI build and unit tests alone **do not prove** on-device match or end-to-end VBHelper NFC compatibility.
- Keep an independent NFC investigation; no destructive transfers or optimistic success claims.

**Important**: Existing 0.6.x APK may still differ visually from reference renders; this specification locks the **target designs**, not a claim of pixel-perfect completion.
