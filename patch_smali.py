"""Applies the floating-button hooks to an apktool-decoded TikTok tree.

Usage: python -I patch_smali.py <decoded apk dir>
Prints the dex file names whose smali was changed (one per line), so the build
can rebuild only those and copy every other dex raw.

Each patched file is saved once as <file>.orig and every run starts from that
copy, so running the build again never stacks hooks.
"""
import os
import re
import sys
from collections import OrderedDict

HOOK = "Lcom/rigaz/tgbtn/Ttg;"

# (class, method declaration exactly as baksmali writes it, lines inserted at the start of the method)
PATCHES = [
    (
        "com/ss/android/ugc/aweme/app/host/AwemeHostApplication",
        ".method public final onCreate()V",
        [f"invoke-static/range {{p0 .. p0}}, {HOOK}->init(Landroid/app/Application;)V"],
    ),
]

HEADER = re.compile(r"\.(locals|registers|param|end param|prologue)\b")


def find_smali(root, cls):
    for d in sorted(os.listdir(root)):
        if d == "smali" or d.startswith("smali_"):
            path = os.path.join(root, d, cls + ".smali")
            if os.path.isfile(path):
                dex = "classes.dex" if d == "smali" else d[len("smali_"):] + ".dex"
                return path, dex
    raise SystemExit(f"class not found: {cls}")


def insert_at_method_start(lines, decl, code):
    """Inserts code before the first instruction/label/.line of the method."""
    matches = [i for i, l in enumerate(lines) if l.rstrip() == decl]
    if len(matches) != 1:
        raise SystemExit(f"expected one '{decl}', found {len(matches)}")
    i = matches[0] + 1
    in_annotation = False
    while i < len(lines):
        s = lines[i].strip()
        if s.startswith(".end method"):
            raise SystemExit(f"no code in {decl}")
        if in_annotation:
            in_annotation = not s.startswith(".end annotation")
        elif s.startswith(".annotation"):
            in_annotation = True
        elif s and not HEADER.match(s):
            break
        i += 1
    block = ["    # tgbtn hook\n"] + [f"    {c}\n" for c in code] + ["\n"]
    return lines[:i] + block + lines[i:]


def main():
    root = sys.argv[1]
    by_class = OrderedDict()
    for cls, decl, code in PATCHES:
        by_class.setdefault(cls, []).append((decl, code))

    touched = []
    for cls, patches in by_class.items():
        path, dex = find_smali(root, cls)
        orig = path + ".orig"
        if not os.path.exists(orig):
            os.replace(path, orig)
        with open(orig, encoding="utf-8") as f:
            lines = f.readlines()
        for decl, code in patches:
            lines = insert_at_method_start(lines, decl, code)
            print(f"{cls} {decl.split()[-1]} -> {dex}", file=sys.stderr)
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.writelines(lines)
        if dex not in touched:
            touched.append(dex)
    print("\n".join(touched))


if __name__ == "__main__":
    main()
