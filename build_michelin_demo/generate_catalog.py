"""Rebuild the fine-dining archive from its checked-in Spanish-first catalog.

The canonical data and table definitions are in data.json; this wrapper keeps the
historical generator entry point while sharing the deterministic packager.
"""
from pathlib import Path
import runpy

root = Path(__file__).resolve().parent.parent
packager = runpy.run_path(str(root / 'scripts' / 'build_demo_templates.py'))
packager['build']('michelin')
