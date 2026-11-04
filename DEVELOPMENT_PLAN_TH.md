# แผนพัฒนา Local AI Linux Desktop (Android ARM64)
อัปเดตเอกสาร: 4 พฤศจิกายน 2026 · อ้างอิงซอร์สล่าสุดในสาขา `main`

## 0. ขอบเขตที่ยืนยันแล้ว (ไม่เปลี่ยนหากไม่ขอ)
- แอป **Android Native** ไม่ต้องใช้ Termux และไม่ต้อง Root มือถือ
- ระบบหลักเป็น **Alpine Linux ARM64 ภายในพื้นที่แอป ผ่าน PRoot** ใช้ kernel ของ Android เดิม ไม่ใช่ VM เต็มรูปแบบ
- AI สั่งงานไฟล์ โปรแกรม เทอร์มินัล และ (ในอนาคต) เว็บเบราว์เซอร์ **ภายใน Linux เท่านั้น**
- ไม่มี Android Accessibility, MediaProjection หรือการสั่งแตะ/ควบคุมมือถือทั้งเครื่อง
- มีเดสก์ท็อป Linux พร้อม VNC/noVNC ให้มือถือและคอมเข้ามาใช้งานได้
- AI ใช้ GGUF บนเครื่องและไม่มีการเรียก AI API ภายนอก; การดาวน์โหลดโมเดล/อัปเดต/ค้นเว็บต้องออนไลน์
- คำสั่ง Linux ที่ AI สร้างต้องเห็นก่อนอนุมัติ; งานอันตรายต้องแจ้งเตือนเป็นพิเศษ

## 1. สถานะปัจจุบัน (แยก Build สำเร็จออกจากทดสอบจริง)

| ระบบ | สถานะจากซอร์ส | สิ่งที่ยังต้องพิสูจน์ |
|---|---|---|
| Android Native + UI | มีโค้ด; มีการแก้ responsive ในสาขา main | UI ทุกความกว้าง, font scale, คีย์บอร์ด, เครื่อง Android 17 |
| Alpine rootfs + PRoot | มีการแนบ binary/rootfs และตัวติดตั้งจาก APK | แตกไฟล์, เปิด shell จริง, ข้ามการรีสตาร์ต/ปิดแอป |
| Linux Desktop | มี Xvnc/Openbox/tint2/PCManFM/XTerm และ setup via apk | ติดตั้งแพ็กเกจจริงและเปิดภาพ/คีย์บอร์ดบนมือถือ |
| noVNC บนมือถือ | มี local WebView + WebSocket bridge | เชื่อมต่อ, การกด/เลื่อน/พิมพ์, การแสดงผลแนวตั้ง/แนวนอน |
| คอมรีโมทเข้า Linux | มี private-IP TCP VNC relay + รหัสผ่าน | เชื่อมบน LAN/VPN จริง, reconnect, เปลี่ยน IP |
| AI Chat | มี llama GGUF, นำเข้าหรือโหลด Qwen, SQLite history | inference จริง, RAM/ความเร็ว/ภาษาไทย, หลุดหรือ OOM |
| AI Linux Agent | มี plan→อนุมัติ→execute พร้อมผลลัพธ์ | งานหลายขั้นตอนจริง, ตรวจผล, retry ที่ปลอดภัย |
| เว็บ AI จากคอม | มี HTTP server บน private-IP และรหัสผ่าน | login/CSRF/การเปิดจากคอมและข้อจำกัดเครือข่าย |
| แจ้งเตือน | มีโมเดลดาวน์โหลด + AI foreground + Desktop foreground | progress Linux install, failure, resume, action buttons และ Android 17 |
| อัปเดตแอป | มีตรวจ GitHub Releases; มีทางเลือก signing keys | **ยังไม่มีหลักฐานว่า release key คงที่ตั้งสำเร็จ** จึงยังอัปเดตทับ/debug ข้าม build ไม่ได้อย่างเชื่อถือได้ |
| Browser Automation โดย AI | **ยังไม่มี** | Linux browser/DOM tools/ภาพเว็บ/agent loop |
| ทดสอบมือถือจริงแบบ E2E | **ยังไม่ผ่าน/ยังไม่มีหลักฐาน** | จำเป็นก่อนเรียกว่าใช้งานได้ 100% |

## 2. แผนพัฒนาและลำดับดำเนินงาน

### P0 — ให้ติดตั้ง/อัปเดต/เปิดระบบได้จริง (บล็อกการใช้งาน)
1. **ทดสอบบน Android 17 จริง**: install→first-run→Alpine install→`uname -a`→`apk update`→Desktop install→Xvnc→noVNC→คอม VNC→ปิด/เปิดใหม่ พร้อม logcat และ error report
2. **ระบบลงนาม APK ถาวร**: ให้เจ้าของสร้าง/เก็บ release keystore ใน GitHub Actions Secrets; ห้ามส่ง private key ลง repo. Build ต้องใช้ key เดิมทุกครั้ง; ตรวจ certificate fingerprint ก่อนเผยแพร่
3. **ย้ายข้อมูลปลอดภัย**: export/import Linux rootfs, โมเดล, SQLite memory, ตั้งค่า; ตรวจ checksum/พื้นที่ว่าง; ห้ามถอนแอปเดิมก่อนสำรอง และต้องมี rollback สำหรับ migration
4. **บริการไม่เด้ง**: lifecycle Foreground Service, notification permissions, process cleanup, notification Stop, battery optimization guidance, crash detection, ไม่ทิ้ง Xvnc/PRoot process ค้าง
5. **เปิด Workspace แบบเข้าใจง่าย**: แก้ไม่ให้เด้ง Chrome ไป `ERR_CONNECTION_REFUSED`; แสดงสถานะบริการและปุ่มแก้ในแอป; UI มี fallback เมื่อ WebView/บริการล้มเหลว

**ผ่านเกณฑ์ P0 เมื่อ:** ติดตั้งและเริ่ม Alpine/AI/VNC บนมือถือจริงได้; เปิดจากคอมได้; ทดสอบ stop/restart/reconnect และข้อมูลไม่สูญ; ลงนามรุ่นถัดไปด้วยคีย์เดิม (การอัปเดตทับจากรุ่น debug ที่เซ็นคนละคีย์ทำไม่ได้)

### P1 — UI/UX และการแจ้งเตือนสำหรับคนทั่วไป
1. **Responsive ทุกหน้า**: Android header/nav/card, AI chat, Linux Agent, noVNC, หน้า login; เปลี่ยนหมุนจอและเปิดแป้นพิมพ์แล้วยังเห็นปุ่ม
2. **หน้า Home แบบนำทางอัตโนมัติ**: ขั้นตอน 1) ติดตั้ง Alpine 2) ติดตั้งโมเดล 3) ติดตั้ง Desktop 4) เริ่มใช้งาน พร้อมสถานะและปุ่มเดียว
3. **Download Center**: ดาวน์โหลดต่อได้, หยุด/ยกเลิก, แสดง MB/%, checksum, พื้นที่ว่าง, ความผิดพลาดและปุ่มลองใหม่
4. **Notification channels**: AI running, model download, Linux package install, VNC running, errors; สถานะจริง, เปิดแอป/หยุด, ไม่แสดงข้อมูลอ่อนไหว
5. **แปลง Error เป็นข้อความคน**: `ERR_CONNECTION_REFUSED`, `LOW_RAM`, `PROOT`, `MODEL_INFERENCE_FAILED`, `VNC connect timeout` พร้อมทางแก้

**ผ่านเกณฑ์ P1 เมื่อ:** ที่ 320/360/390/430/768px และแนวนอน 844×390, font 100/125/200% ไม่มีแถบเลื่อนแนวนอนและไม่มีปุ่มหลุดจอ; เมื่อคีย์บอร์ดขึ้น ปุ่มส่ง/อนุมัติยังเข้าถึงได้; ผู้ใช้ใหม่ทำ setup เองได้

### P2 — ทำ Linux ให้เหมือนเดสก์ท็อปปกติ
1. ให้ Openbox/tint2/PCManFM/XTerm เปิด/ปิดโปรแกรมได้จริง และเพิ่มเมนูเปิด Browser/File Manager/Terminal
2. เพิ่มการตั้งค่า resolution, DPI, keyboard layout ไทย/อังกฤษ, clipboard, mouse wheel/drag, touch gesture, full-screen, reconnect
3. เพิ่ม process supervisor, logs, health checks, หยุดงานอย่างถูกต้อง, แยก home directory, backup/restore
4. ทดสอบการติดตั้ง browser ใน Alpine ARM64/PRoot (Chromium หรือทางเลือก), TLS certificates, DNS, HTTPS; คุม RAM/ความร้อน
5. เพิ่ม VNC session persistence แบบผู้ใช้ยินยอม (เมื่อ Android อนุญาต) และ status health ผ่าน UI

**ผ่านเกณฑ์ P2 เมื่อ:** เปิด XTerm, File Manager, browser จาก desktop แล้วใช้เมาส์/คีย์บอร์ดจากมือถือและคอมได้จริง และ reconnect ได้โดยไม่ทำไฟล์หาย

### P3 — ยกระดับ AI Agent ให้ทำงานจริงภายใน Linux
1. แยก **Chat / Plan / Execute / Observe / Verify**; tool schema ชัดเจน, confirmation, timeout/cancel, audit log
2. ให้ AI อ่านผล shell แล้ววางแผนต่อหลายขั้น, หยุดเมื่อผิด, ถามผู้ใช้เฉพาะเมื่อจำเป็น; สรุปผลตรวจสอบได้ ไม่อ้างว่าทำเสร็จถ้ายังไม่ได้เช็ก
3. เพิ่ม Linux file manager tool (เปิด/ค้น/อ่าน/เขียนภายใน rootfs), package manager, terminal, process viewer
4. เพิ่ม browser tools **ภายใน Linux** (navigate, click, type, screenshot, DOM) โดย user approval และ test browser dependency บน ARM64 จริง
5. ปรับ prompt/model GGUF ให้เหมาะไทย–อังกฤษ: ทดสอบโมเดล 0.5B/1.5B/3B, RAM guard, performance/fallback; ไม่อ้างว่าฉลาดเท่า GPT-6
6. Memory: จดบริบทที่ได้รับอนุญาต, ค้นคืน, แยก session, export/import/ลบข้อมูลตามผู้ใช้, ป้องกันคำสั่งที่มากับหน้าเว็บ (prompt injection)

**ผ่านเกณฑ์ P3 เมื่อ:** สั่ง “เปิดเว็บใน Linux, หาข้อมูล, สร้างไฟล์สรุป” ได้เป็นชุดงานพร้อมหลักฐานขั้นตอน/ผลลัพธ์และไม่กระทบ Android ภายนอก

### P4 — Remote อย่างปลอดภัย
1. VNC ปัจจุบันใช้ auth แบบเดิม (ใช้รหัสได้จริงเพียง 8 อักษรแรก) และไม่ได้เข้ารหัสทราฟฟิก; HTTP AI บน LAN ก็ไม่ได้เข้ารหัส
2. ก่อนเปิดใช้นอกบ้าน ต้องใช้ VPN ส่วนตัว หรือ TLS tunnel พร้อมยืนยันตัวตนจริง; ไม่ forward พอร์ต 5902/18789 ออกอินเทอร์เน็ต
3. เพิ่ม pairing/อนุญาตจากมือถือ, แสดง client ที่เชื่อมต่อ, ตัด session, rotate credentials, reconnect, ป้องกัน brute force
4. จำกัด bind interface/ตรวจการเปลี่ยนเครือข่าย, ตรวจ host/origin/cookie; ห้ามเปิดอินเทอร์เน็ตสาธารณะโดยอัตโนมัติ

**ผ่านเกณฑ์ P4 เมื่อ:** ต่อคอมข้ามเครือข่ายผ่าน VPN ได้พร้อมการยืนยันบนมือถือและตัดสิทธิ์ได้ทันที โดยไม่เปิดพอร์ตสาธารณะ

### P5 — คุณภาพ การทดสอบ และปล่อยเวอร์ชัน
1. GitHub Actions: Kotlin compile, JS syntax, asset/package/permission checks, Android tests, Web UI screenshot tests และ artifact provenance
2. Test matrix: Android 13–17, ARM64, RAM ต่างกัน, แบตเตอรี่ประหยัด, offline/online, Wi‑Fi↔VPN, จอแนวตั้ง/แนวนอน, keyboard
3. Crash logs ที่ไม่เปิดเผยรหัสผ่าน/ข้อความส่วนตัว, diagnostic ZIP (ผู้ใช้เป็นฝ่ายส่ง), health dashboard
4. Release checklist: signing fingerprint, versionCode สูงขึ้น, backup format compatible, installer test, rollback path, changelog
5. หลังผ่าน P0–P4 จึงพิจารณาเสียงสนทนา, LINE, งานตามกำหนดเวลา เป็น optional — **ไม่ใช่ส่วนของ Linux Desktop ขั้นพื้นฐาน**

## 3. สิ่งที่ทำทันทีในรอบ UI นี้
- ลดความกว้างของ header และเปลี่ยนปุ่ม navigation ให้ยืดตามข้อความใน Android Native
- เพิ่มสรุปเริ่มใช้งาน 4 ขั้นตอนหน้า Control Center
- ปรับ Linux Workspace (CSS+JS) รองรับ viewport ที่ย่อเมื่อ keyboard ขึ้น; flex/grid ไม่ล้นแนวนอน
- ปรับ noVNC viewer ให้ความสูงเต็มพื้นที่ที่มองเห็นโดยไม่บังคับขั้นต่ำ 360px; toolbar หักบรรทัดตามจอ
- ตรวจ Build และ JavaScript syntax หลังแก้ทุกครั้ง

## 4. นโยบายไม่ต้องให้ผู้ใช้สั่งทีละรอบ
- ทุกงานต้องมี **Definition of Done** ก่อนเริ่ม: feature + UI + error handling + tests + backup/security + docs
- ไม่รายงานว่า “ใช้ได้ 100%” จากการ Build เพียงอย่างเดียว
- เมื่อเจอข้อผิดพลาดใน CI ให้แก้และทดสอบซ้ำในรอบเดียวกัน
- ไม่เปลี่ยน package ID ซ้ำ; ไม่ถอน/ลบ Linux rootfs/โมเดล/ความจำของผู้ใช้โดยไม่ได้รับคำสั่ง
- ไม่ปล่อย APK เป็นอัปเดตทับโดยไม่มีการยืนยันลายเซ็นเดิม
- ให้ UI บอกตรง ๆ ว่าส่วนใดพร้อมจริง ส่วนใดยังต้องติดตั้ง/ทดสอบ

## 5. บันทึกปัญหาที่ต้องรีบตรวจ
- Installer v0.4 ถูก Play Protect บล็อกเพราะ Android Accessibility; รุ่น Linux-only นำบริการ Accessibility ออกแล้ว แต่ต้องทดสอบจริงว่าไม่ถูกบล็อกด้วยเหตุผลอื่น
- v0.6/v0.7 ใช้ debug signing จาก GitHub Actions จึงมีความเสี่ยง signature mismatch ระหว่าง Build; **ยังไม่มีหลักฐานว่าตั้งค่า persistent release key สำเร็จ**
- การใช้งาน VNC/PRoot บน Android 17 ยังไม่มีหลักฐาน E2E ว่าทำงานครบ
- ไม่มีระบบ Browser Agent สำหรับ AI ควบคุมเว็บ Linux จริง ณ ปัจจุบัน
- Linux install ยังใช้ thread ของ Activity ไม่ใช่งาน foreground ที่ resumable; หาก Android ปิดแอปกลางทาง อาจติดตั้งค้าง
- AI และ Linux desktop ต้องแสดง error ที่อธิบายให้ผู้ใช้แก้ได้ โดยไม่เปิดให้สาธารณะเห็น logs/credentials

> หมายเหตุ: แผนนี้กำหนดขอบเขตและเกณฑ์ผ่าน ไม่ใช่การยืนยันว่าระบบที่ยังไม่ทดสอบผ่านแล้ว
