#!/usr/bin/env python3
"""Critical NFC correctness fix:
Don't ACK HCE COMMIT to VBHelper until watch character is actually persisted
and activated. VBHelper deletes its source following COMMIT success, so the
old early ACK could lose a Digimon if the async watch import then failed.
"""
from pathlib import Path
import re,sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")/"app/src/main/java/com/github/cfogrady/vitalwear/transfer/hce/VitalWearHostApduService.kt"
s=p.read_text(encoding="utf-8-sig")
s=s.replace("    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {",
            "    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray? {")
s=s.replace("    private fun handleCommit(): ByteArray {","    private fun handleCommit(): ByteArray? {")
start="            VitalWearHceSessionManager.Mode.RECEIVE_FROM_PHONE -> {"
end="\n            VitalWearHceSessionManager.Mode.IDLE -> {"
idx=s.index(start,s.index("private fun handleCommit"))
last=s.index(end,idx)
replacement='''            VitalWearHceSessionManager.Mode.RECEIVE_FROM_PHONE -> {
                val payload=VitalWearHceSessionManager.takeReceivedPayload()
                    ?: return VitalWearHceProtocol.buildResponse(
                        statusWord=VitalWearHceProtocol.SW_CONDITIONS_NOT_SATISFIED
                    )
                VitalWearHceSessionManager.clear(resetStatus=false)
                VitalWearHceSessionManager.markSyncing()
                bumpApduCount()
                // HCE may respond asynchronously. Do not return 0x9000 until DB import,
                // sprite metadata, and active partner selection actually succeed.
                // VBHelper's old client treats COMMIT=0x9000 as permission to delete.
                serviceScope.launch {
                    val accepted=try {
                        kotlinx.coroutines.withTimeout(8500L) {
                            repository.importCharacter(payload)
                        }
                    } catch(e:Exception) {
                        Log.e(TAG,"NFC return commit import failed",e)
                        VitalWearHceSessionManager.markFailure(
                            "Import failed: " + (e.message ?: e.javaClass.simpleName)
                        )
                        false
                    }
                    val statusWord=if(accepted) {
                        VitalWearHceSessionManager.markSuccess()
                        logAndClearMetrics("success(import_confirmed_before_ack)")
                        VitalWearHceProtocol.SW_OK
                    } else {
                        if(VitalWearHceSessionManager.failureReason.value.isBlank()) {
                            VitalWearHceSessionManager.markFailure(
                                "Watch could not save or activate returned character. Matching card data required."
                            )
                        }
                        logAndClearMetrics("failure(import_not_confirmed)")
                        VitalWearHceProtocol.SW_CONDITIONS_NOT_SATISFIED
                    }
                    // A negative COMMIT makes the old VBHelper client keep its original.
                    runCatching {
                        sendResponseApdu(VitalWearHceProtocol.buildResponse(statusWord=statusWord))
                    }.onFailure {
                        Log.w(TAG,"NFC field lost before commit response",it)
                    }
                }
                null // the delayed response is returned via sendResponseApdu()
            }
'''
s=s[:idx]+replacement+s[last:]
assert s.count("null // the delayed response") == 1
assert "private fun handleCommit(): ByteArray?" in s
p.write_text(s,encoding="utf-8")
print("PATCHED: NFC return COMMIT waits for watch import, max 8.5s, failure safely retains VBHelper source")
