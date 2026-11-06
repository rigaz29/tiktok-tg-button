"""APK steps of build-morphe.sh, done with zipfile so no zip CLI is needed.

  locate <apk> <class>                      print the dex entry that defines a class
                                            (class as a/b/C, without L and ;)
  mini <apk> <dex> <out>                    write a one-dex APK that apktool decodes in seconds
  repack <apk> <dex> <mini> <module> <out>  copy the APK with <dex> replaced by the classes.dex
                                            of <mini>, and <module> added as the next
                                            classesN.dex; print that name
"""
import copy
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I leaves this out
from dexindex import parse_dex

DEX_NAME = re.compile(r"classes(\d*)\.dex")


def dex_entries(z):
    """Dex entry names in the order the runtime loads them."""
    names = [n for n in z.namelist() if DEX_NAME.fullmatch(n)]
    return sorted(names, key=lambda n: int(DEX_NAME.fullmatch(n).group(1) or 1))


def locate(apk, cls):
    descriptor = f"L{cls};"
    needle = descriptor.encode("utf-8")
    hits = []
    with zipfile.ZipFile(apk) as z:
        for name in dex_entries(z):
            buf = z.read(name)
            if needle in buf and any(c == descriptor for c, _ in parse_dex(buf)[0]):
                hits.append(name)
    if not hits:
        raise SystemExit(f"no dex defines {descriptor}")
    if len(hits) > 1:
        # The runtime resolves a class from the first dex that has it.
        print(f"{descriptor} is defined in {hits}, using {hits[0]}", file=sys.stderr)
    print(hits[0])


def mini(apk, dex, out):
    with zipfile.ZipFile(apk) as z, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as o:
        o.writestr("AndroidManifest.xml", z.read("AndroidManifest.xml"))
        o.writestr("resources.arsc", z.read("resources.arsc"))
        o.writestr("classes.dex", z.read(dex))


def repack(apk, dex, mini_apk, module, out):
    with zipfile.ZipFile(mini_apk) as m:
        patched = m.read("classes.dex")
    with open(module, "rb") as f:
        module_dex = f.read()

    with zipfile.ZipFile(apk) as z, zipfile.ZipFile(out, "w") as o:
        names = dex_entries(z)
        # The runtime stops loading at the first gap, so a gap would hide the module.
        if names[-1] != f"classes{len(names)}.dex":
            raise SystemExit(f"dex numbering has gaps: {len(names)} files, last is {names[-1]}")
        for info in z.infolist():
            data = patched if info.filename == dex else z.read(info)
            # Keeps each entry's compression: native libs and resources.arsc must stay stored.
            o.writestr(copy.copy(info), data)
        source = z.getinfo(dex)
        entry = zipfile.ZipInfo(f"classes{len(names) + 1}.dex", source.date_time)
        entry.compress_type = source.compress_type
        o.writestr(entry, module_dex)
    print(entry.filename)


def main():
    commands = {"locate": locate, "mini": mini, "repack": repack}
    if len(sys.argv) < 2 or sys.argv[1] not in commands:
        raise SystemExit(__doc__)
    commands[sys.argv[1]](*sys.argv[2:])


if __name__ == "__main__":
    main()
