#!/usr/bin/env python3
"""Verify actual APKs before exposing an update download. No signing keys needed."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile


PACKAGE = "com.localai.linuxagent.desktop"
# Public certificate independently verified in owner-signed v0.18 and v0.22.
# Never replace this pin with a newly generated key to make a build pass.
OWNER_CERT = "feb1a0c42c340e655632e57cd66620369f4a4c4ccdb0c0f0a64f8b34bf906451"


def run(command):
    result = subprocess.run(command, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=90)
    if result.returncode:
        raise ValueError(f"{Path(command[0]).name} failed (exit {result.returncode})")
    return result.stdout


def inspect(apk, sdk, java):
    build_tools = sdk / "build-tools" / "36.0.0"
    aapt = build_tools / ("aapt.exe" if os.name == "nt" else "aapt")
    signer = build_tools / "lib" / "apksigner.jar"
    if not aapt.is_file() or not signer.is_file():
        raise ValueError("Android SDK build-tools 36.0.0 are required")
    if not apk.is_file():
        raise ValueError(f"APK does not exist: {apk.name}")
    badging = run([str(aapt), "dump", "badging", str(apk)])
    package_line = next((line for line in badging.splitlines()
                         if line.startswith("package: ")), "")
    fields = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    if not fields.get("name") or not fields.get("versionCode", "").isdigit():
        raise ValueError("APK package/versionCode cannot be read")
    cert_output = run([java, "-jar", str(signer), "verify", "--print-certs", str(apk)])
    certs = sorted({value.lower() for value in re.findall(
        r"(?m)^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$",
        cert_output, flags=re.IGNORECASE)})
    if not certs:
        raise ValueError("APK has no verified signer certificate")
    with zipfile.ZipFile(apk) as archive:
        abis = sorted({name.split("/")[1] for name in archive.namelist()
                       if name.startswith("lib/") and name.count("/") >= 2})
    return {
        "file": apk.name,
        "package": fields["name"],
        "version_code": int(fields["versionCode"]),
        "version_name": fields.get("versionName", ""),
        "certificates_sha256": certs,
        "abis": abis,
        "debuggable": "application-debuggable" in badging.splitlines(),
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    args = parser.parse_args()
    java = shutil.which("java")
    if not java:
        print(json.dumps({"compatible": False, "errors": ["Java is required"]}))
        return 1
    report = {"compatible": False, "errors": []}
    for label, apk in (("previous", args.previous), ("candidate", args.candidate)):
        try:
            metadata = inspect(apk, args.sdk, java)
            report[label] = metadata
            if metadata["package"] != PACKAGE:
                report["errors"].append(f"{label}: package must be {PACKAGE}")
            if metadata["certificates_sha256"] != [OWNER_CERT]:
                report["errors"].append(f"{label}: signing certificate differs from the owner certificate")
            if metadata["abis"] != ["arm64-v8a"]:
                report["errors"].append(f"{label}: expected ARM64-only APK")
            if metadata["debuggable"]:
                report["errors"].append(f"{label}: debug APK cannot be distributed as an update")
        except (ValueError, OSError, zipfile.BadZipFile, subprocess.TimeoutExpired) as exc:
            report["errors"].append(f"{label}: {exc}")
    if "previous" in report and "candidate" in report:
        if report["candidate"]["version_code"] <= report["previous"]["version_code"]:
            report["errors"].append("candidate: versionCode must increase")
    report["compatible"] = not report["errors"]
    print(json.dumps(report, indent=2))
    return 0 if report["compatible"] else 1


if __name__ == "__main__":
    sys.exit(main())
