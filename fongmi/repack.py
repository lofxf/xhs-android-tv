#!/usr/bin/env python3
"""Turns an apktool-decoded official FongMi/TV APK into a side-by-side "小红书TV".

- package -> com.fongmi.android.tv.xhs (via apktool.yml renameManifestPackage; class names untouched)
- provider authorities / app-defined permissions renamed so both apps can be installed together
- app name -> 小红书TV, own launcher icon + Fire TV banner
Usage: repack.py <decoded_dir> <res_dir>
"""
import pathlib
import re
import shutil
import sys

OLD = "com.fongmi.android.tv"
NEW = "com.fongmi.android.tv.xhs"
APP_NAME = "小红书TV"

root = pathlib.Path(sys.argv[1])
res_src = pathlib.Path(sys.argv[2])

# 1. Package rename (aapt2 --rename-manifest-package keeps component class names resolvable).
yml = root / "apktool.yml"
y = yml.read_text(encoding="utf-8")
if re.search(r"^\s*renameManifestPackage:", y, re.M):
    y = re.sub(r"^(\s*renameManifestPackage:).*$", rf"\1 {NEW}", y, flags=re.M)
elif re.search(r"^packageInfo:\s*$", y, re.M):
    y = re.sub(r"^packageInfo:\s*$", f"packageInfo:\n  renameManifestPackage: {NEW}", y, count=1, flags=re.M)
else:
    y += f"\npackageInfo:\n  renameManifestPackage: {NEW}\n"
yml.write_text(y, encoding="utf-8")
print("apktool.yml: renameManifestPackage =", NEW)

# 2. Manifest: authorities, app-defined permissions, icon, banner.
mf = root / "AndroidManifest.xml"
m = mf.read_text(encoding="utf-8")


def rename_authorities(match):
    parts = [NEW + a[len(OLD):] if a == OLD or a.startswith(OLD + ".") else a for a in match.group(2).split(";")]
    return match.group(1) + ";".join(parts) + '"'


m, n_auth = re.subn(r'(android:authorities=")([^"]*)"', rename_authorities, m)
defined = set(re.findall(r'<permission\b[^>]*android:name="(' + re.escape(OLD) + r'\.[^"]+)"', m))
for perm in defined:
    m = m.replace(f'"{perm}"', f'"{NEW}{perm[len(OLD):]}"')
m, n_icon = re.subn(r'android:(icon|roundIcon)="@(mipmap|drawable)/[^"]+"', r'android:\1="@drawable/xhs_icon"', m)
m, n_banner = re.subn(r'android:banner="@(mipmap|drawable)/[^"]+"', 'android:banner="@drawable/xhs_banner"', m)
mf.write_text(m, encoding="utf-8")
print(f"manifest: authorities={n_auth} permissions={sorted(defined)} icon={n_icon} banner={n_banner}")
if n_banner == 0:
    raise SystemExit("no android:banner found in manifest (not a leanback APK?)")

# 3. App name in every locale.
for strings in root.glob("res/values*/strings.xml"):
    s = strings.read_text(encoding="utf-8")
    t = re.sub(r'(<string name="app_name"[^>]*>)[^<]*(</string>)', rf"\g<1>{APP_NAME}\g<2>", s)
    if t != s:
        strings.write_text(t, encoding="utf-8")
        print("app_name:", strings.relative_to(root))

# 4. Restore attrs stripped by R8 resource shrinking but still referenced from XML
#    (e.g. app:state_liftable / state_dragged), otherwise aapt2 fails to relink.
RES_AUTO = "http://schemas.android.com/apk/res-auto"
attrs_file = root / "res" / "values" / "attrs.xml"
defined_attrs = set(re.findall(r'<attr name="([^"]+)"', attrs_file.read_text(encoding="utf-8"))) if attrs_file.exists() else set()
used = set()
for xml in (root / "res").rglob("*.xml"):
    if "/values" in str(xml.parent):
        continue
    text = xml.read_text(encoding="utf-8", errors="ignore")
    for prefix in re.findall(r'xmlns:(\w+)="' + re.escape(RES_AUTO) + '"', text):
        used.update(re.findall(r"\b" + prefix + r":(state_\w+)=", text))
missing = sorted(used - defined_attrs)
if missing:
    if attrs_file.exists():
        a = attrs_file.read_text(encoding="utf-8")
    else:
        a = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
    extra = "".join(f'    <attr name="{n}" format="boolean" />\n' for n in missing)
    attrs_file.write_text(a.replace("</resources>", extra + "</resources>", 1), encoding="utf-8")
print("restored attrs:", missing)

# 5. Icon / banner drawables.
dst = root / "res" / "drawable-nodpi"
dst.mkdir(parents=True, exist_ok=True)
for name in ("xhs_icon.png", "xhs_banner.png"):
    shutil.copyfile(res_src / name, dst / name)
print("drawables added")
