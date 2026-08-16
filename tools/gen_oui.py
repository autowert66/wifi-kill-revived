#!/usr/bin/env python3
"""Fetch Wireshark's manuf file and produce app/src/main/assets/oui.json.

Output format: {"<hex-prefix>": "<vendor>", ...}

Prefixes are the significant hex digits (no separators), so lookups can do a
longest-prefix match:
  - 24-bit OUI (MA-L): 6 hex digits
  - 28-bit (MA-M):     7 hex digits
  - 36-bit (MA-S):     9 hex digits

The full "long name" is preferred over Wireshark's truncated 13-char short name.
"""
import json
import re
import sys
import urllib.request

URL = "https://www.wireshark.org/download/automated/data/manuf"
OUT = "app/src/main/assets/oui.json"


def pick_name(short, long):
    # The manuf file's 3rd column is the full name; ignore entries whose long
    # name is actually prose about the OUI rather than a vendor name.
    if long and " but " not in long and "Officially" not in long:
        return long.strip().strip(".,")
    return short.strip().strip(".,")


def main():
    req = urllib.request.Request(URL, headers={"User-Agent": "oui-generator"})
    oui = {}
    with urllib.request.urlopen(req, timeout=60) as r:
        for raw in r:
            line = raw.decode("utf-8", "replace")
            if not line.strip() or line.startswith("#"):
                continue
            # The manuf file is tab-separated; the long name itself contains
            # spaces, so splitting on whitespace would truncate it.
            fields = line.split("\t")
            if len(fields) < 2:
                continue

            prefix = fields[0].strip()
            short = fields[1].strip()
            long = fields[2].strip() if len(fields) >= 3 else None

            if prefix.endswith("/28"):
                keep = 7
                prefix = prefix[:-3]
            elif prefix.endswith("/36"):
                keep = 9
                prefix = prefix[:-3]
            elif "/" in prefix:
                continue
            else:
                keep = 6

            hexdigits = re.sub(r"[^0-9A-Fa-f]", "", prefix)
            if len(hexdigits) < keep:
                continue
            key = hexdigits[:keep].upper()

            name = pick_name(short, long)
            if not name:
                continue
            oui[key] = name

    with open(OUT, "w") as f:
        json.dump(oui, f, sort_keys=True)
    print(f"wrote {len(oui)} OUIs to {OUT} ({__import__('os').path.getsize(OUT)} bytes)")


if __name__ == "__main__":
    sys.exit(main())
