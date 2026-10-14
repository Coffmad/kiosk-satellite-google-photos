#!/usr/bin/env python3
"""Verify that a Kiosk Satellite checkout vendors this exact SDK."""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
if len(sys.argv) != 2:
    raise SystemExit('Usage: python3 tools/check-sdk.py /path/to/kiosk-satellite')
app = Path(sys.argv[1]) / 'app/android/app/src/main/java/me/jxl/kiosk/plugins'
for source in sorted((root / 'sdk/src/me/jxl/kiosk/plugins').glob('*.java')):
    target = app / source.name
    if not target.exists() or source.read_bytes() != target.read_bytes():
        raise SystemExit(f'SDK mismatch: {source.name}')
if (root / 'LICENSE').read_bytes() != (app / 'LICENSE').read_bytes():
    raise SystemExit('SDK license mismatch')
print('SDK interfaces and license match the application.')
