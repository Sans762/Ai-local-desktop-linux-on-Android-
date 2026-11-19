#!/usr/bin/env python3
"""Bidirectional NFC return diagnostics and safe response confirmation.
The official VBHelper APK is not modified.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
base="app/src/main/java/com/github/cfogrady/vitalwear/"
def patch(rel,before,after):
 p=root/(base+rel);s=p.read_text(encoding="utf-8-sig")
 n=s.count(before)
 if n!=1:raise RuntimeError(f"Unexpected {rel} occurrences={n}; anchor {before[:75]}")
 p.write_text(s.replace(before,after),encoding="utf-8");print("NFC PATCH",rel)
s="transfer/hce/VitalWearHceSessionManager.kt"
patch(s,
'''    val transferStatus: StateFlow<TransferStatus>
        get() = _transferStatus''',
'''    val transferStatus: StateFlow<TransferStatus>
        get() = _transferStatus
    private val _failureReason=MutableStateFlow("")
    val failureReason:StateFlow<String> get()=_failureReason''')
patch(s,'''            _transferStatus.value = TransferStatus.ARMED_SEND''',
'''            _failureReason.value=""
            _transferStatus.value = TransferStatus.ARMED_SEND''')
patch(s,'''            _transferStatus.value = TransferStatus.ARMED_RECEIVE''',
'''            _failureReason.value=""
            _transferStatus.value = TransferStatus.ARMED_RECEIVE''')
patch(s,'''    fun markFailure() {
        synchronized(lock) {
            _transferStatus.value = TransferStatus.FAILURE
        }
    }''',
'''    fun markFailure(reason:String="Transfer failed") {
        synchronized(lock) {
            _failureReason.value=reason.take(250)
            _transferStatus.value = TransferStatus.FAILURE
        }
    }''')
repo="transfer/hce/VitalWearHceTransferRepository.kt"
patch(repo,'''        val validationError = sanitized.validateForImport(app.cardMetaEntityDao, speciesDao, matchedCard)''',
'''        val validationError = sanitized.validateForImport(app.cardMetaEntityDao, speciesDao, matchedCard)
        if(matchedCard==null && !sanitized.hasTransferSpecies()) {
            val why="Matching DIM/BEM is missing on watch; import its card before returning from VBHelper."
            Log.w(TAG,why)
            VitalWearHceSessionManager.markFailure(why)
            return false
        }''')
patch(repo,
'''            if (!(sanitized.hasTransferCard() && sanitized.hasTransferSpecies())) {
                Log.w(TAG, "Import validation failed: $validationError")
                return false
            }''',
'''            if (!(sanitized.hasTransferCard() && sanitized.hasTransferSpecies())) {
                Log.w(TAG,"Import validation failed: $validationError")
                VitalWearHceSessionManager.markFailure(
                    "Return rejected: " + validationError + ". Import the same DIM/BEM into VitalWear on watch first."
                )
                return false
            }''')
patch(repo,
'''        if (!activationSucceeded) {
            Log.w(TAG, "Character import persisted but activation verification failed")
            return false
        }''',
'''        if (!activationSucceeded) {
            Log.w(TAG,"Character imported but activation failed")
            VitalWearHceSessionManager.markFailure(
                "Character was saved but not activated; check card sprites. Keep VBHelper copy."
            )
            return false
        }''')
patch(repo,
'''        // Keep COMMIT fast on HCE: heavy sprite file checks can outlive NFC field and cause TagLost.
        return true''',
'''        app.complicationRefreshService.refreshActivePartnerComplication()
        // Keep COMMIT fast on HCE; confirmation still waits for actual import result.
        return true''')
host="transfer/hce/VitalWearHostApduService.kt"
patch(host,'''                    }.onFailure {
                        Log.e(TAG, "Async import threw exception", it)
                    }.getOrDefault(false)''',
'''                    }.onFailure {
                        Log.e(TAG,"Async import threw exception",it)
                        VitalWearHceSessionManager.markFailure(
                            "Import error: " + (it.message ?: it.javaClass.simpleName) +
                            ". Confirm matching card is installed in VitalWear."
                        )
                    }.getOrDefault(false)''')
patch(host,'''                        VitalWearHceSessionManager.markFailure()
                        logAndClearMetrics("failure(import_failed)")''',
'''                        if(VitalWearHceSessionManager.failureReason.value.isBlank()) {
                            VitalWearHceSessionManager.markFailure(
                                "Watch did not accept character. Check transfer logs or card data."
                            )
                        }
                        logAndClearMetrics("failure(import_failed)")''')
screen="transfer/TransferScreen.kt"
patch(screen,'''    val hceStatus by VitalWearHceSessionManager.transferStatus.collectAsState()''',
'''    val hceStatus by VitalWearHceSessionManager.transferStatus.collectAsState()
    val transferError by VitalWearHceSessionManager.failureReason.collectAsState()''')
patch(screen,'''            TransferResult(controller, sendOrReceive, resultStatus, onComplete = controller::finish)''',
'''            if(resultStatus == HceTransferResult.FAILURE) {
                Box(modifier=Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("TRANSFER FAILED")
                        Text(transferError.ifBlank {
                            "Check NFC connection and matching DIM/BEM card."
                        })
                        CompactButton(onClick={state=TransferState.ENTRY}) { Text("Try again") }
                    }
                }
            } else {
                TransferResult(controller,sendOrReceive,resultStatus,onComplete=controller::finish)
            }''')
print("NFC return error messages and preservation of VBHelper source completed.")
