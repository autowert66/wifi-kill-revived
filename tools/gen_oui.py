#!/usr/bin/env python3
"""Fetch Wireshark's manuf file and produce app/src/main/assets/oui.json.

Output format: {"AA:BB:CC": "Manufacturer", ...} using the short name.
Downloads from wireshark.org at build time.
"""
import json
import re
import sys
import urllib.request

URL = "https://www.wireshark.org/download/automated/data/manuf"
OUT = "app/src/main/assets/oui.json"


def main():
    req = urllib.request.Request(URL, headers={"User-Agent": "oui-generator"})
    oui = {}
    with urllib.request.urlopen(req, timeout=60) as r:
        for raw in r:
            line = raw.decode("utf-8", "replace")
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.split()
            if len(fields) < 2:
                continue
            prefix, short = fields[0], fields[1]
            # Only 24-bit OUIs: AA:BB:CC or AABBCC. Skip 28/36-bit MA-S/MA-M blocks.
            if re.fullmatch(r"(?:[0-9A-F]{2}[:\-]){2}[0-9A-F]{2}", prefix):
                oui[prefix] = short
    with open(OUT, "w") as f:
        json.dump(oui, f, sort_keys=True)
    print(f"wrote {len(oui)} OUIs to {OUT} ({__import__('os').path.getsize(OUT)} bytes)")


if __name__ == "__main__":
    sys.exit(main())