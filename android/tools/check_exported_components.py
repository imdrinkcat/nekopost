#!/usr/bin/env python3
"""Fail if the merged manifest exports a component that is not allow-listed.

Usage: check_exported_components.py <merged AndroidManifest.xml> <allowlist>

Allowlist lines: "<component class> <permission or ->", '#' starts a comment.
A component is exported when android:exported="true", or when the attribute is
missing and it has an intent filter (legacy default). Each exported component
must match an allowlist line exactly, including the protecting permission, so
an unprotected copy of a library component is still caught.
"""
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
KINDS = ("activity", "activity-alias", "service", "receiver", "provider")


def main(manifest_path, allowlist_path):
    allowed = {}
    with open(allowlist_path, encoding="utf-8") as f:
        for raw in f:
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            name, perm = line.split()
            allowed[name] = None if perm == "-" else perm

    root = ET.parse(manifest_path).getroot()
    package = root.get("package", "")
    app = root.find("application")
    exported = {}
    for kind in KINDS:
        for el in app.findall(kind):
            name = el.get(A + "name")
            if name.startswith("."):
                name = package + name
            flag = el.get(A + "exported")
            is_exported = flag == "true" or (flag is None and el.find("intent-filter") is not None)
            if is_exported:
                exported[name] = (kind, el.get(A + "permission"))

    ok = True
    for name, (kind, perm) in sorted(exported.items()):
        print(f"exported {kind}: {name} (permission: {perm or '-'})")
        if name not in allowed:
            print(f"::error::{name} is exported but not in {allowlist_path}")
            ok = False
        elif allowed[name] != perm:
            print(f"::error::{name} must be protected by {allowed[name] or 'no permission'}, found {perm or 'none'}")
            ok = False
    for name in sorted(set(allowed) - set(exported)):
        print(f"::warning::allow-listed {name} is no longer exported; remove it from the allowlist")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
