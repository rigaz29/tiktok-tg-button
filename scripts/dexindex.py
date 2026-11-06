"""Index class definitions across dex files.

Usage: python dexindex.py <dex_dir> <out_tsv>
Writes one line per defined class: dex_name, class_descriptor, superclass.
Also prints per-dex method_ids/field_ids/type_ids counts (64K limit check).
"""
import os
import struct
import sys


def uleb(buf, off):
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, off
        shift += 7


def read_string(buf, off):
    _, off = uleb(buf, off)
    end = buf.index(b"\x00", off)
    return buf[off:end].decode("utf-8", "replace")


def parse_dex(buf):
    (string_ids_size, string_ids_off, type_ids_size, type_ids_off,
     proto_ids_size, proto_ids_off, field_ids_size, field_ids_off,
     method_ids_size, method_ids_off, class_defs_size, class_defs_off) = struct.unpack_from("<12I", buf, 0x38)

    def string(idx):
        (soff,) = struct.unpack_from("<I", buf, string_ids_off + idx * 4)
        return read_string(buf, soff)

    def type_name(tidx):
        if tidx == 0xFFFFFFFF:
            return ""
        (sidx,) = struct.unpack_from("<I", buf, type_ids_off + tidx * 4)
        return string(sidx)

    classes = []
    for i in range(class_defs_size):
        cls_idx, _flags, super_idx = struct.unpack_from("<III", buf, class_defs_off + i * 32)
        classes.append((type_name(cls_idx), type_name(super_idx)))
    counts = dict(methods=method_ids_size, fields=field_ids_size, types=type_ids_size)
    return classes, counts


def index_dex(path):
    with open(path, "rb") as f:
        return parse_dex(f.read())


def main():
    dex_dir, out = sys.argv[1], sys.argv[2]
    names = sorted((n for n in os.listdir(dex_dir) if n.endswith(".dex")),
                   key=lambda n: int(n[7:-4] or 1))
    with open(out, "w", encoding="utf-8") as fo:
        for n in names:
            classes, counts = index_dex(os.path.join(dex_dir, n))
            print(f"{n}\tclasses={len(classes)}\tmethods={counts['methods']}\tfields={counts['fields']}\ttypes={counts['types']}")
            for c, s in classes:
                fo.write(f"{n}\t{c}\t{s}\n")


if __name__ == "__main__":
    main()
