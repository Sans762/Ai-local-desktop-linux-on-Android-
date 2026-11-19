# VitalWear Galaxy Watch 8 — experimental build

งานทดลองนี้ต่อยอดจากซอร์ส [457R0/VitalWear](https://github.com/457R0/VitalWear) และแยกจากแอป VitalWear/VBHelper เดิมของผู้ใช้ เพื่อให้ทดสอบโดยไม่ถอนแอปที่ใช้ได้อยู่แล้ว

## ไฟล์เมื่อ GitHub Actions Build ผ่าน

- `VitalWear-GW8-Watch-EXPERIMENT.apk` — แอปเกมสำหรับ Galaxy Watch 8
- `VitalWear-GW8-Phone-EXPERIMENT.apk` — แอปนำเข้าข้อมูล DIM/BEM บนมือถือ
- `VBClassicXL-Face-EXPERIMENT.apk` — หน้าปัด WFF v4 แยกต่างหาก พร้อมมาสคอตพิกเซลเคลื่อนไหว (ยังไม่ใช่สไปรต์ดิจิมอนจากไฟล์ของผู้ใช้)

เปิดแท็บ **Actions** > เลือกเวิร์กโฟลว์ VitalWear Galaxy Watch 8 experimental APKs > Run ที่สำเร็จ > Artifacts

## ฟีเจอร์ในชุดทดลองนี้

1. โค้ดนำเข้าไฟล์ DIM/BEM ในแอปมือถือไม่ขอเชื่อมเครื่อง VB จริง
2. โค้ด HCE/NFC จาก Fork (VBHelper ต้องเป็นรุ่นที่รองรับ HCE รูปแบบเดียวกัน) และตั้งต้นทางเป็น COPY ONLY; การส่งสำเร็จ **จะไม่ลบดิจิมอนบน Watch** ในโหมดนี้
3. เอฟเฟกต์เสียงต้นฉบับสังเคราะห์สำหรับเปิดเมนู ฝึก ต่อสู้ วิวัฒนาการ และผลการต่อสู้บางกรณี (ปิดช่วง 22:00–07:00 โดยค่าเริ่มต้น)
4. หน้าปัด VB Classic XL แสดงเวลา, มาสคอตพิกเซลขนาดใหญ่ที่เดินไปมาเป็น AGIF, ภาพนิ่งขณะ AOD และช่อง Partner Complication สำหรับภาพตัวละครที่เล่นจริง
5. ใช้ packageId ทดสอบ `dev.mikeki.vitalwear.gw8` บน Phone และ Watch เพื่อไม่ทับแอปเดิม; หน้าปัด `dev.mikeki.vbclassicxl` แยกต่างหาก

## ข้อจำกัดสำคัญ

- ยัง **ต้องใช้ไฟล์เฟิร์มแวร์ VB BE 1.0.B ที่คุณมีสิทธิ์ใช้** เพื่อให้เอนจินเดิมแสดงผลได้
- ไม่รวมไฟล์เกม, เสียง หรือภาพของ Bandai
- หน้าปัดที่สร้างจาก WFF ไม่สามารถดึงแอนิเมชันตัวละครจากไฟล์ .bin แล้วเดินเองแบบไดนามิกได้; ภาพจริงจาก Partner Complication เป็นภาพนิ่งที่อัปเดตโดยแอปและ Wear OS
- NFC HCE อาศัยอุปกรณ์จริง, รุ่น VBHelper ที่ตรงกัน และการแตะในจุดที่ NFC รับสัญญาณได้ จึง **ยังต้องทดสอบจริง**
- หลีกเลี่ยงใช้ดิจิมอนตัวหลักทดสอบ ส่งทดสอบด้วยตัวที่สำรองข้อมูลแล้วเท่านั้น
- Debug APK จาก GitHub runner อาจใช้คีย์ที่เปลี่ยนใน Build ใหม่ จึงไม่ควรใช้เป็นการอัปเดตระยะยาว และไม่รับประกันการรักษาข้อมูลข้ามเวอร์ชัน
- ไม่แก้/ติดตั้งทับ VBHelper และไม่ลดข้อจำกัดสิทธิ์ ContentProvider แบบ signature-level; การซิงก์การ์ดไป VBHelper อัตโนมัติไม่รองรับใน APK ต่างลายเซ็น

## ขั้นตอนติดตั้งและทดสอบ

ติดตั้ง Phone APK ในมือถือ, Watch APK ผ่าน Wear Installer 2 หรือ ADB บน Galaxy Watch 8, และ Face APK บนนาฬิกาอีกไฟล์ เลือกหน้าปัด VB Classic XL จากรายการหน้าปัด (หากระบบยอมรับ WFF 4) แล้วเลือก Partner Complication ได้เมื่อแอปมีตัวละครถูกตั้งค่าอยู่

เปิดแอปมือถือทดลอง นำเข้าเฟิร์มแวร์ 1.0.B ที่ถูกต้องและไฟล์ DIM/BEM ของคุณ แล้วตรวจว่าแอปนาฬิกาได้รับข้อมูลและเลี้ยงได้ จากนั้นทดสอบเสียงในเวลาที่ไม่ใช่ Quiet Hours และทดสอบ NFC แบบคัดลอกกับดิจิมอนที่สำรองไว้

### การรายงานปัญหา

ส่งภาพ Error และแจ้งว่าเกิดขั้นตอนไหน: Phone install, Watch install, Firmware, Card Import, NoVB, SFX, Watch Face หรือ NFC; พร้อมรุ่น VBHelper ที่ใช้อยู่ หลีกเลี่ยงส่งข้อมูลลับหรือไฟล์ที่ไม่มีสิทธิ์เผยแพร่

## ที่มาซอร์ส

- https://github.com/457R0/VitalWear
- https://github.com/nacabaro/vbhelper
- https://developer.android.com/training/wearables/wff
