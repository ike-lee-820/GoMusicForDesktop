#!/usr/bin/env python3
"""把 compose 生成的 app 目录打成 zip。

Windows / Linux / macOS 三个 Runner 都自带 python3，
用它打包可以避开 zip 命令与 pwsh 的差异。
用法: python3 ci/package.py <src_dir> <out.zip>
"""
import os
import sys
import zipfile


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: package.py <src_dir> <out.zip>")
        return 2
    src, out = sys.argv[1], sys.argv[2]
    if not os.path.isdir(src):
        print("src dir not found:", src)
        return 1
    if os.path.exists(out):
        os.remove(out)
    n = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for base, _dirs, files in os.walk(src):
            for f in files:
                p = os.path.join(base, f)
                rel = os.path.relpath(p, src)
                try:
                    z.write(p, rel)
                    n += 1
                except Exception as e:  # noqa: BLE001
                    print("skip", rel, e)
    print("packed files:", n)
    if n == 0:
        print("nothing packed")
        return 1
    print("out:", out, os.path.getsize(out), "bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
