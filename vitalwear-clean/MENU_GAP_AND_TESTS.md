# VitalWear clean upstream rebuild — menu audit

Sources inspected:
- [cfogrady/VitalWear](https://github.com/cfogrady/VitalWear), main `bb148e1a600691ac37fe1b1c4fa7ad56478315fa` (2025-03-19)
- [Bandai's Vital Bracelet Digital Monster manual](https://p-bandai.com/us/contents/div/2030/images/VITAL%20BRACE_normal_manual_EN_letter.pdf), originally pictured by the user.
- [Vital Bracelet BE reference](https://humulos.com/digimon/vbbe/manual/)

**Important: The uploaded manual is the original 2021 Vital Bracelet Digital Monster. The cfogrady engine reimplements the later Vital Bracelet BE. Their menu and sleep systems differ.**

## Original VB Digital Monster physical screen / cfogrady engine
| Physical device menu | cfogrady source equivalent | Gap |
|---|---|---|
| Home (time/VITAL/MILE/Digimon) | `character/PartnerScreen.kt`, `main/MainScreen.kt` | Original engine uses steps and BE vitals; native watch HR reading can be shown |
| Status (VITAL/heart rate/MILE) | `stats/StatsMenuActivity.kt` and `heartrate/HeartRateService.kt` | BE engine stats are organized differently |
| DIGIMON (basic info, combat record, trophies) | `stats/StatsMenuActivity.kt`, `character/activity/CharacterSelection.kt` | Entries are spread across game menus; do not fake physical device UI |
| Mission (Normal/Hard/Special/Adventure) | `training/TrainingMenuActivity.kt`, `adventure/AdventureMenuActivity.kt` | Mission taxonomy differs; some physical-device categories have no exact game engine equivalent |
| Backup | `character/activity/CharacterSelection.kt` / save system | Not an exact in-bracelet slot-by-slot flow |
| App (NFC transfer) | Companion import and watch transfer | Remove mandatory physical bracelet verification for import. Watch/phone still pair using Wear OS Data Layer |
| Settings (wallpaper/brightness/sound/reset) | `settings/SettingsComposableFactory.kt`, `background/BackgroundSelectionActivity.kt` | Background selection exists; other physical settings are not exact device controls |
| Items | **Absent on original VB Digital Monster** | Do not add fake item menu |

## What this rebuild deliberately preserves
- Original firmware-background selection and imported card animation/sprites.
- Original Wear OS step-counter event listener.
- Original heart-rate game integration, with a bounded read and real value display.
- Original character database, battle/training/mission/evolution engine.
- Original multi-screen navigation; **no** fabricated Pixel City scene, forest watch face, or rendered mock UI.

## Physical-bracelet independence
- Phone imports a valid local DIM/BEM image directly to the paired Wear OS device; no physical Vital Bracelet verification prompt.
- The original compatible firmware file must still be supplied by the owner: the repository does not include Bandai firmware or card assets.
- An actual paired Galaxy Watch and phone may still be needed to transfer the file. 
- Existing VB Helper storage/transport is not replicated in this fresh upstream source; **VB Helper interoperability needs a separate on-device round-trip test before claiming it works**.

## Energy and sensor behavior
- `TYPE_STEP_COUNTER` is event-driven, not GPS or an accelerometer polling loop.
- Check and request `ACTIVITY_RECOGNITION` runtime permission; re-register the counter when it becomes available.
- HR request uses the existing optical sensor at low sampling frequency and stops after a bounded 10-second attempt.
- Failed HR measurements do not become invented BPM or increase virtual-pet Vitals.
- Mood alarm uses an inexact non-wake-up schedule.
- *Battery improvements are architectural, not verified battery-runtime results. Physical device measurement is required.*

## Not implemented in this scope
- A full 1:1 copy of the 2021 physical VB menu (the upstream engine is BE, not the original VB firmware).
- Proprietary audio samples, sprites, or firmware redistribution.
- Removal of firmware-file import dependency (would require replacing upstream sprite and background dependencies).
