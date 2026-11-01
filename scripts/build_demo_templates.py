"""Package the checked-in Spanish-first demo sources into both deployment paths.

Edit build_<id>_demo/data.json, template.json and media/ as the source of truth.
Run from any directory: python scripts/build_demo_templates.py [kebab|cafe|michelin].
"""
from pathlib import Path
import argparse
import json
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent.parent
IDS = ('kebab', 'cafe', 'michelin')

def build(template_id):
    source = ROOT / ('build_' + template_id + '_demo')
    for name in ('data.json', 'template.json'):
        with (source / name).open(encoding='utf-8') as stream:
            json.load(stream)
    output = ROOT / 'demo-templates' / (template_id + '_demo.zip')
    resource = ROOT / 'fastbite/adapter-in/web/src/main/resources' / output.name
    paths = [source / 'template.json', source / 'data.json']
    paths.extend(sorted(p for p in (source / 'media').rglob('*') if p.is_file()))
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in paths:
            # Stable timestamps make repeat builds and the two deployment copies reproducible.
            entry = zipfile.ZipInfo(path.relative_to(source).as_posix(), (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, path.read_bytes(), compresslevel=9)
    shutil.copyfile(output, resource)
    print(f'{template_id}: {output.relative_to(ROOT)} and classpath copy')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('template', nargs='?', choices=IDS)
    args = parser.parse_args()
    for selected in (args.template,) if args.template else IDS:
        build(selected)
