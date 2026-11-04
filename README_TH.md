# Local AI Native — โปรเจกต์ Android (Phase 1)

สถานะ: **มีซอร์สโค้ดต้นแบบ แต่ยังไม่ได้ Build APK และทดสอบบนมือถือจริง** เนื่องจากรีโมท Termux เดิม Offline และสภาพแวดล้อมสำหรับรัน Gradle/Android SDK ไม่พร้อมในรอบนี้

## โครงสร้างที่สร้างให้แล้ว

- **แอป Android Native** เขียน Kotlin, Min SDK 26, Target SDK 36, ARM64
- **Foreground Service** ประเภท `specialUse` พร้อมการแจ้งเตือนตลอดที่เปิด (ผู้ใช้ต้องกด Start ในแอป) และปุ่ม Stop
- **เว็บ Dashboard** ที่ `http://127.0.0.1:18788` รองรับมือถือ/แท็บเล็ต/คอมพิวเตอร์ในมุมมอง Responsive แต่ **ฟังเฉพาะ Loopback มือถือ** ยังเปิดจากอุปกรณ์อื่นไม่ได้
- **AI ออฟไลน์** ผ่าน `llama-android` 0.1.1 (llama.cpp CPU) โหลดโมเดล GGUF ที่เลือกด้วย Storage Access Framework; ไม่ใช้ API ของบริษัทภายนอกในการอนุมาน
- **ความจำ SQLite:** เก็บประวัติแชท, เรียกบริบท, นำเข้า `memory.db` เดิมจาก Termux โดยไม่ลบต้นฉบับ
- **ควบคุม Android แบบจำกัด:** `AccessibilityService` เมื่อผู้ใช้เปิดใน Settings และอนุญาตอีกชั้นในแอป (`tap`, `type`, `back`, `home`, อ่าน text tree โดยปกปิด password field) ยังไม่ใช่ระบบควบคุมทั้งหมด
- **ความปลอดภัย:** บังคับตั้งรหัสผ่านใหม่ (>=8 ตัวอักษร), PBKDF2 เก็บ hash, HttpOnly SameSite Cookie, CSRF token, ตรวจ Origin, จำกัดจำนวนครั้งการล็อกอิน และ localhost เท่านั้น

## สิ่งที่ยังไม่มี (อย่าเข้าใจว่าพร้อมใช้งานแล้ว)

- APK ที่ Build แล้วและทดสอบติดตั้งได้จริงบน Xiaomi Android 17
- จอสด MediaProjection, ลากหน้าจอหลายจุด, เสียงโทรแบบเรียลไทม์, LINE OA
- AI Agent อัตโนมัติหลายขั้นตอนที่พิสูจน์แล้วว่าเสร็จทุกงาน (ต้นแบบรองรับเพียงหนึ่ง action ต่อคำสั่ง และเช็กการยอมรับ action)
- เปิดเว็บจากภายนอกข้ามเครือข่ายที่มี HTTPS และการยืนยันตัวตนอย่างเหมาะสม
- การย้ายไฟล์ Termux เดิมในเครื่องจริง (ต้องเปิดรีโมทกลับมาออนไลน์ หรือผู้ใช้ export ด้วยสคริปต์)
- การรับประกันบริการไม่ถูก Android ปิด — ไม่มี Foreground Service ที่รับประกันเช่นนั้น

## วิธี Build แบบ Android Studio บนคอมพิวเตอร์

1. เปิดโฟลเดอร์ `native-ai-controller` ด้วย Android Studio (JDK 17)
2. Sync Gradle ครั้งแรก (ต้องใช้อินเทอร์เน็ตเพื่อโหลดไลบรารีและ SDK)
3. ติดตั้ง SDK Platform 36 + Build Tools 36
4. Build > Build APK(s) หรือรัน `gradle :app:assembleDebug`
5. ไฟล์ APK จะอยู่ที่ `app/build/outputs/apk/debug/app-debug.apk`

> มี Workflow `.github/workflows/build-apk.yml` สำหรับ Build บน GitHub Actions จากมือถือเพียงเครื่องเดียว แต่ต้องอัปโหลดซอร์สไป repository ที่ผู้ใช้ควบคุมก่อน และไม่มีการอัปโหลดให้ในรอบนี้

## วิธีใช้งานหลัง Build APK สำเร็จ

1. ติดตั้ง APK แล้วเปิดแอป `Local AI Native`
2. ตั้งรหัสผ่านเว็บเองอย่างน้อย 8 ตัวอักษร
3. เลือก `Import a GGUF model` และเลือกไฟล์ `.gguf` ที่มีอยู่จริง
4. กด `Start foreground AI service` และอนุญาตการแจ้งเตือน
5. กด `Open web dashboard` > Login ด้วยรหัสที่ตั้งไว้
6. หากต้องการแตะ/พิมพ์ในแอปอื่น ค่อยเปิดสวิตช์ `Allow requested actions` และ Accessibility ใน Settings

## ส่งออกของเดิมจาก Termux โดย **ไม่ลบไฟล์ต้นฉบับ**

ใช้ไฟล์ `tools/export_from_termux.py` หลังนำไฟล์นี้ไปไว้ใน Termux:

```bash
termux-setup-storage
python3 export_from_termux.py
# คัดลอกโมเดล Ollama เดิมไป Download เฉพาะโมเดลที่ต้องการ (กินพื้นที่เพิ่ม):
python3 export_from_termux.py gemma3:1b
```

สคริปต์สร้างโฟลเดอร์ `Download/LocalAI-Migration/` ซึ่งอาจมี:

- `memory.db`: สำเนาฐานข้อมูลเดิมผ่าน SQLite Backup API (ไม่ลบฐานข้อมูลเดิม)
- `safe-config.json`: เฉพาะชื่อ AI / ภาษา / ตัวเลือกความจำ ไม่ส่งออก PIN, token, password หรือ URL
- `gemma3-1b.gguf`: สำเนาโมเดลเฉพาะถ้า Ollama มีไฟล์ raw GGUF และพื้นที่พอ

จากนั้นเข้าแอป Native ใหม่ > Import legacy memory.db / Import GGUF / Import safe AI name

**ข้อมูล Android app ใหม่ไม่สามารถเปิดไฟล์ภายใน** `/data/data/com.termux/...` **โดยตรงได้** เนื่องจาก Android sandbox ต้อง export ไปที่พื้นที่ที่ผู้ใช้เลือกก่อน จึงจะ import ได้

## เหตุผลที่ใช้ Foreground Service ประเภท `specialUse`

`dataSync` มีข้อจำกัดเวลาใน Android 15+ และไม่ใช่กรณีใช้ AI ภายในเครื่องระยะยาว ส่วน `specialUse` ต้องมี use-case ที่สมเหตุสมผลและเมื่อส่งขึ้น Google Play จะถูกตรวจสอบแยก แม้เป็น Foreground Service ก็ยังไม่ป้องกัน Android/OEM ฆ่าบริการเมื่อแรมไม่พอ

อ้างอิง Android: https://developer.android.com/develop/background-work/services/fgs/service-types

ไลบรารีโมเดล: https://central.sonatype.com/artifact/dev.ffmpegkit-maintained/llama-android

## ข้อควรทราบเรื่อง RAM

โมเดล 1B–3B อาจใช้ RAM 1–4+ GB รวม KV Cache/Runtime โมเดล 3B ที่เคยใช้งานบน Termux อาจทำให้ Android ปิดแอปได้อีก แอปนี้โหลดโมเดลเฉพาะขณะส่งคำสั่ง แล้วคืนหน่วยความจำหลังสร้างข้อความ เพื่อลด RAM เมื่อไม่ได้ใช้งาน แนะนำเริ่มจากโมเดลไม่เกิน ~1B ก่อน และมีระบบเช็ก RAM เทียบกับขนาดโมเดลโดยประมาณ