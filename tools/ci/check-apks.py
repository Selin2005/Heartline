#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""
Checks a phone and watch APK pair before it's published, so a build that would install but not
work (as when the shrinker removed the Wear OS capability and the apps couldn't find each other)
never reaches users:

  python3 tools/ci/check-apks.py --phone PHONE.apk --watch WATCH.apk \\
      [--version-name 1.2.0-beta.1] [--version-code 390175] [--root CHECKOUT]

- both apps have the same applicationId, versionName and versionCode (the expected ones, if given),
  and neither is debuggable;
- both are signed with the same certificate, and with the release key when HEARTLINE_KEYSTORE_FILE
  (with HEARTLINE_KEYSTORE_PASSWORD and HEARTLINE_KEY_ALIAS) is set, as in the Build workflow;
- each declares its Wear OS capability (heartline_phone, heartline_watch);
- every activity, service, receiver and provider in each manifest has its class in the APK;
- the classes the apps can't work without are there, unrenamed: all of Heartline's own code, the
  Samsung Health Sensor SDK, ONNX Runtime and the Wear Data Layer;
- every asset and Java resource from the sources, and every resource name the modules define, is
  in the APK, and ONNX Runtime's native libraries are in the phone's.

Needs aapt2 and apksigner from the Android SDK build-tools (ANDROID_HOME) and, for the
release key, keytool.
"""
import argparse
import glob
import os
import re
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

# The repository the APKs were built from (--root; the current directory by default).
ROOT = Path(".")
APP_ID = "io.github.selin2005.heartline"
# The modules whose sources go into each APK.
MODULES = {"phone": ["phone", "datalayer", "shared"], "watch": ["wear", "datalayer", "shared"]}
CAPABILITY = {"phone": "heartline_phone", "watch": "heartline_watch"}
# The Wear Data Layer clients the apps talk through (R8 inlines the static Wearable.get…Client()).
DATA_LAYER = [
    "com.google.android.gms.wearable.MessageClient",
    "com.google.android.gms.wearable.CapabilityClient",
    "com.google.android.gms.wearable.ChannelClient",
    "com.google.android.gms.wearable.NodeClient",
    "com.google.android.gms.wearable.WearableListenerService",
]
CRITICAL = {
    "phone": [
        "ai.onnxruntime.OrtEnvironment",
        "ai.onnxruntime.OrtSession",
        "ai.onnxruntime.OrtSession$Result",
        "ai.onnxruntime.OnnxTensor",
        *DATA_LAYER,
    ],
    "watch": [
        "com.samsung.android.service.health.tracking.HealthTrackingService",
        "com.samsung.android.service.health.tracking.ConnectionListener",
        "com.samsung.android.service.health.tracking.data.HealthTrackerType",
        # The raw sensor recorder finds every value by these sets' fields (RawCapture, SdkKeys).
        "com.samsung.android.service.health.tracking.data.ValueKey$EcgSet",
        "com.samsung.android.service.health.tracking.data.ValueKey$PpgSet",
        "com.samsung.android.service.health.tracking.data.ValueKey$HeartRateSet",
        "com.samsung.android.service.health.tracking.data.ValueKey$SpO2Set",
        "com.samsung.android.service.health.tracking.data.ValueKey$BiaSet",
        "com.samsung.android.service.health.tracking.data.ValueKey$SkinTemperatureSet",
        "com.samsung.android.service.health.tracking.data.ValueKey$EdaSet",
        *DATA_LAYER,
    ],
}
NATIVE = {"phone": ["lib/arm64-v8a/libonnxruntime.so", "lib/armeabi-v7a/libonnxruntime.so"], "watch": []}
COMPONENTS = ("activity", "service", "receiver", "provider")
VALUE_TYPES = {
    "string": "string", "string-array": "array", "integer-array": "array", "array": "array",
    "plurals": "plurals", "color": "color", "dimen": "dimen", "integer": "integer", "bool": "bool",
    "style": "style", "attr": "attr", "fraction": "fraction", "declare-styleable": "styleable",
}

errors: list[str] = []


def fail(message: str) -> None:
    errors.append(message)
    print(f"::error::{message}")


def tool(name: str) -> str:
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    found = sorted(glob.glob(f"{home}/build-tools/*/{name}"), key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", Path(p).parent.name)])
    if not found:
        sys.exit(f"::error::{name} not found in $ANDROID_HOME/build-tools")
    return found[-1]


def run(*args: str) -> str:
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout


def badging(apk: str) -> dict:
    out = run(tool("aapt2"), "dump", "badging", apk)
    m = re.search(r"package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", out)
    return {
        "package": m.group(1) if m else "",
        "code": m.group(2) if m else "",
        "name": m.group(3) if m else "",
        "debuggable": "application-debuggable" in out,
    }


def manifest_components(apk: str) -> list[str]:
    """The class names of the manifest's activities, services, receivers and providers."""
    out = run(tool("aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk)
    names, current = [], None
    for line in out.splitlines():
        element = re.match(r"\s*E: (\S+)", line)
        if element:
            current = element.group(1)
            continue
        attr = re.match(r'\s*A: http://schemas.android.com/apk/res/android:name\(0x01010003\)="([^"]+)"', line)
        if attr and current in COMPONENTS:
            names.append(attr.group(1))
            current = None
    return names


def dex_classes(apk: str) -> set[str]:
    """The classes defined in the APK's dex files, as Java names (a.b.C$D)."""
    classes = set()
    with zipfile.ZipFile(apk) as z:
        for entry in z.namelist():
            if not re.fullmatch(r"classes\d*\.dex", entry):
                continue
            d = z.read(entry)
            string_ids_off, = struct.unpack_from("<I", d, 0x3C)
            type_ids_off, = struct.unpack_from("<I", d, 0x44)
            class_defs_size, class_defs_off = struct.unpack_from("<II", d, 0x60)

            def string(i: int) -> str:
                off, = struct.unpack_from("<I", d, string_ids_off + 4 * i)
                while d[off] & 0x80:  # skip the uleb128 length
                    off += 1
                end = d.index(b"\0", off + 1)
                return d[off + 1:end].decode("utf-8", "replace")

            for c in range(class_defs_size):
                type_idx, = struct.unpack_from("<I", d, class_defs_off + 32 * c)
                descriptor_idx, = struct.unpack_from("<I", d, type_ids_off + 4 * type_idx)
                descriptor = string(descriptor_idx)
                classes.add(descriptor[1:-1].replace("/", "."))
    return classes


def source_classes(modules: list[str]) -> set[str]:
    """Top-level classes Heartline declares in Kotlin (package + name), to check none went missing."""
    found = set()
    declaration = re.compile(r"^(?:(?:public|internal|private|abstract|open|sealed|data|enum|annotation|value|fun)\s+)*(?:class|object|interface)\s+(\w+)", re.M)
    for module in modules:
        for path in (ROOT / module / "src/main").rglob("*.kt"):
            text = path.read_text(encoding="utf-8")
            package = re.search(r"^package\s+([\w.]+)", text, re.M)
            if not package:
                continue
            for name in declaration.findall(text):
                found.add(f"{package.group(1)}.{name}")
    return found


def certificates(apk: str) -> list[str]:
    out = run(tool("apksigner"), "verify", "--print-certs", apk)
    return re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", out)


def release_certificate() -> str | None:
    keystore = os.environ.get("HEARTLINE_KEYSTORE_FILE")
    if not keystore:
        return None
    out = run(
        "keytool", "-list", "-v", "-keystore", keystore,
        "-storepass", os.environ.get("HEARTLINE_KEYSTORE_PASSWORD", ""),
        "-alias", os.environ.get("HEARTLINE_KEY_ALIAS", ""),
    )
    m = re.search(r"SHA256:\s*([0-9A-F:]+)", out)
    return m.group(1).replace(":", "").lower() if m else None


def resource_names(dump: str) -> set[str]:
    return set(re.findall(r"^\s*resource 0x[0-9a-f]+ (\S+/\S+)", dump, re.M))


def defined_resources(modules: list[str]) -> set[str]:
    """type/name of every resource the modules define in src/main/res."""
    names = set()
    for module in modules:
        res = ROOT / module / "src/main/res"
        if not res.is_dir():
            continue
        for folder in res.iterdir():
            kind = folder.name.split("-")[0]
            for f in folder.iterdir():
                if kind == "values":
                    text = f.read_text(encoding="utf-8")
                    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
                    for tag, name in re.findall(r"<(string-array|integer-array|declare-styleable|[a-z]+)\s[^>]*?\bname=\"([^\"]+)\"", text):
                        if tag == "item":
                            typed = re.search(rf'<item\s[^>]*name="{re.escape(name)}"[^>]*type="(\w+)"', text) or re.search(rf'<item\s[^>]*type="(\w+)"[^>]*name="{re.escape(name)}"', text)
                            if typed:
                                names.add(f"{typed.group(1)}/{name}")
                        elif tag in VALUE_TYPES and tag not in ("attr", "declare-styleable"):
                            names.add(f"{VALUE_TYPES[tag]}/{name}")
                else:
                    names.add(f"{kind}/{f.name.split('.')[0]}")
    return names


def check(role: str, apk: str) -> dict:
    print(f"== {role}: {apk} ({os.path.getsize(apk) / 1e6:.1f} MB)")
    info = badging(apk)
    if info["package"] != APP_ID:
        fail(f"{role}: applicationId is '{info['package']}', not {APP_ID}; the Wear Data Layer only connects apps with the same one")
    if info["debuggable"]:
        fail(f"{role}: the APK is debuggable; every channel is published from the release build")

    with zipfile.ZipFile(apk) as z:
        entries = set(z.namelist())

    dump = run(tool("aapt2"), "dump", "resources", apk)
    resources = resource_names(dump)
    if "array/android_wear_capabilities" not in resources:
        fail(f"{role}: no android_wear_capabilities resource: the phone and watch couldn't find each other")
    else:
        block = dump.split("array/android_wear_capabilities", 1)[1].split("resource 0x", 1)[0]
        if CAPABILITY[role] not in block:
            fail(f"{role}: android_wear_capabilities doesn't contain {CAPABILITY[role]}")
    missing_res = sorted(defined_resources(MODULES[role]) - resources)
    if missing_res:
        fail(f"{role}: {len(missing_res)} resources are missing: {', '.join(missing_res[:15])}")

    classes = dex_classes(apk)
    missing_components = [c for c in manifest_components(apk) if c not in classes]
    if missing_components:
        fail(f"{role}: manifest components without their class: {', '.join(missing_components)}")
    missing_critical = [c for c in CRITICAL[role] if c not in classes]
    if missing_critical:
        fail(f"{role}: classes it can't work without are missing or renamed: {', '.join(missing_critical)}")
    missing_own = sorted(source_classes(MODULES[role]) - classes)
    if missing_own:
        fail(f"{role}: {len(missing_own)} of Heartline's classes are missing or renamed: {', '.join(missing_own[:15])}")

    for module in MODULES[role]:
        assets = ROOT / module / "src/main/assets"
        for f in assets.rglob("*") if assets.is_dir() else []:
            if f.is_file() and f"assets/{f.relative_to(assets).as_posix()}" not in entries:
                fail(f"{role}: asset {f.relative_to(ROOT)} is missing")
        java = ROOT / module / "src/main/resources"
        for f in java.rglob("*") if java.is_dir() else []:
            if f.is_file() and f.relative_to(java).as_posix() not in entries:
                fail(f"{role}: Java resource {f.relative_to(ROOT)} is missing")
    for lib in NATIVE[role]:
        if lib not in entries:
            fail(f"{role}: native library {lib} is missing")
    if role == "phone" and "assets/docs/CHANGELOG.md" not in entries:
        fail("phone: assets/docs/CHANGELOG.md is missing (What's new)")

    info["certs"] = certificates(apk)
    print(f"   {info['package']} {info['name']} ({info['code']}), {len(classes)} classes, {len(resources)} resources, certificate {','.join(c[:16] for c in info['certs'])}…")
    return info


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--phone", required=True)
    p.add_argument("--watch", required=True)
    p.add_argument("--version-name")
    p.add_argument("--version-code")
    p.add_argument("--root", default=".", help="the checkout the APKs were built from")
    a = p.parse_args()
    global ROOT
    ROOT = Path(a.root).resolve()
    if not (ROOT / "settings.gradle.kts").exists():
        sys.exit(f"::error::{ROOT} isn't the Heartline repository (use --root)")

    phone, watch = check("phone", a.phone), check("watch", a.watch)
    for key, expected in (("name", a.version_name), ("code", a.version_code)):
        if phone[key] != watch[key]:
            fail(f"version{key.title()} differs: phone {phone[key]}, watch {watch[key]}")
        if expected and phone[key] != expected:
            fail(f"version{key.title()} is {phone[key]}, expected {expected}")
    if not phone["certs"] or phone["certs"] != watch["certs"]:
        fail(f"the phone and watch aren't signed with the same key ({phone['certs']} / {watch['certs']}): the Wear Data Layer won't connect them")
    release = release_certificate()
    if release and phone["certs"] != [release]:
        fail(f"not signed with the release key ({release[:16]}…): users couldn't update between channels")

    if errors:
        sys.exit(f"{len(errors)} problem(s): see above")
    print("All checks passed: the phone and watch APKs are complete, matching and signed alike.")


if __name__ == "__main__":
    main()
