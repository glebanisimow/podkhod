"""Create the Android updater manifest from the signed APK, never from a guessed version."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def create_manifest(apk: Path, aapt2: str, url: str) -> dict:
    badging = subprocess.check_output([aapt2, "dump", "badging", str(apk)], text=True, encoding="utf-8")
    package_line = next(line for line in badging.splitlines() if line.startswith("package:"))
    package = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    min_sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'$", badging, re.MULTILINE)
    if package["name"] != "app.podkhod" or min_sdk is None:
        raise ValueError("Expected the release APK for app.podkhod with a minSdk")
    if not re.fullmatch(
        r"https://github\.com/glebanisimow/podkhod/releases/download/android/podkhod-\d+-\d+-\d+\.apk", url
    ):
        raise ValueError("Expected an immutable APK URL in the Android release")
    with apk.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {
        "versionCode": int(package["versionCode"]),
        "versionName": package["versionName"],
        "applicationId": package["name"],
        "minSdk": int(min_sdk.group(1)),
        "apkUrl": url,
        "sha256": digest,
        "sizeBytes": apk.stat().st_size,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    manifest = create_manifest(args.apk, args.aapt2, args.url)
    args.output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Update manifest: {manifest['versionName']} ({manifest['versionCode']}), {manifest['sizeBytes']} bytes")
