# manifestpatcher.py
import os
import zipfile
import re
import xml.etree.ElementTree as ET
from datetime import datetime
import tempfile
import json

from axml import AXML  # ton décoder AXML
from pyaxmlparser import APK

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')

NAME_PATTERNS = [
    r'^com\.google\.android\.gms\.permission\.AD_ID$',
    r'^com\.google\.android\.gms\.ads\..*',
    r'^com\.facebook\.ads\..*',
    r'^com\.applovin\..*',
    r'^com\.unity3d\.ads\..*',
    r'^com\.chartboost\..*',
    r'^com\.ironsource\..*',
    r'^com\.vungle\..*',
    r'^com\.mopub\..*',
    r'^com\.startapp\..*',
    r'^com\.tapjoy\..*',
    r'^com\.adcolony\..*',
    r'^com\.onesignal\..*',
    r'^com\.firebase\.analytics\..*',
    r'^com\.firebase\.installations\..*',
]

LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO")

def log(msg, level='INFO'):
    levels = ['DEBUG', 'INFO', 'WARN', 'ERROR']
    if levels.index(level) >= levels.index(LOG_LEVEL):
        prefix = {'ERROR': '❌', 'WARN': '⚠️', 'INFO': 'ℹ️', 'DEBUG': '🐞'}.get(level, '')
        print(f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {prefix} {msg}")

def postprocess_xml(xml_str: str) -> str:
    """Fix des espaces de noms afin que ElementTree ne supprime pas xmlns:android."""
    return xml_str.replace(
        'xmlns="{http://schemas.android.com/apk/res/android}"',
        'xmlns:android="http://schemas.android.com/apk/res/android"'
    )

def extract_manifest(apk_path: str, dest: str):
    log("1/4  Extracting AndroidManifest.xml…")
    try:
        with zipfile.ZipFile(apk_path) as zf:
            data = zf.read("AndroidManifest.xml")
            if len(data) < 8:
                raise ValueError("Manifest too small (truncated?)")
        with open(dest, 'wb') as f:
            f.write(data)
        log(f"   ✅ Extracted {len(data):,} bytes")
    except KeyError:
        raise FileNotFoundError("AndroidManifest.xml not found in APK")

def decode_axml(axml_path: str, xml_path: str):
    log("2/4  Decoding AXML → XML…")
    axml = AXML()
    axml.parse_file(axml_path)
    xml_str = axml.get_xml()
    if isinstance(xml_str, bytes):
        xml_str = xml_str.decode("utf-8", errors="replace")
    xml_str = postprocess_xml(xml_str)
    with open(xml_path, 'w', encoding='utf-8') as f:
        f.write(xml_str)
    log("   ✅ Decoded to plain XML")

def patch_xml(input_xml_path: str, output_xml_path: str):
    log("3/4  Patching XML…")
    tree = ET.parse(input_xml_path)
    root = tree.getroot()
    counts = {'onesignal': 0, 'trackers': 0, 'empty_intents': 0}

    # Neutraliser OneSignal
    for tag in ('activity', 'service', 'receiver', 'provider'):
        for elem in root.iter(tag):
            name = elem.get(f'{ANDROID_NS}name')
            if name and name.startswith('com.onesignal.'):
                generic = {
                    'activity': 'android.app.Activity',
                    'service': 'android.app.Service',
                    'receiver': 'android.content.BroadcastReceiver',
                    'provider': 'android.content.ContentProvider',
                }[tag]
                elem.set(f'{ANDROID_NS}name', generic)
                elem.set(f'{ANDROID_NS}exported', 'false')
                for intent in elem.findall('intent-filter'):
                    intent.clear()
                counts['onesignal'] += 1

    # Retirer trackers
    parent_map = {c: p for p in root.iter() for c in p}
    to_remove = []
    for elem in root.iter():
        name_attr = elem.get(f'{ANDROID_NS}name') or elem.get('name')
        if name_attr and any(re.match(pat, name_attr, re.I) for pat in NAME_PATTERNS):
            parent = parent_map.get(elem)
            if parent is not None:
                to_remove.append((parent, elem))
    for parent, elem in to_remove:
        parent.remove(elem)
        counts['trackers'] += 1

    # Supprimer <intent> vides dans <queries>
    for queries in root.iter('queries'):
        for intent in list(queries.findall('intent')):
            if not intent.attrib and not list(intent):
                queries.remove(intent)
                counts['empty_intents'] += 1

    # Écriture finale et log
    tree.write(output_xml_path, encoding='utf-8', xml_declaration=True)
    log(f"   ✅ {counts['onesignal']} OneSignal neutralisés, "
        f"{counts['trackers']} trackers retirés, "
        f"{counts['empty_intents']} intents vides supprimés")
    return counts

def process_apk(apk_path: str) -> str:
    try:
        log(f"Starting processing for APK: {apk_path}")

        tmpdir = tempfile.mkdtemp()
        manifest_axml = os.path.join(tmpdir, "AndroidManifest.axml")
        manifest_xml = os.path.join(tmpdir, "AndroidManifest.xml")
        patched_xml_path = os.path.join(tmpdir, "AndroidManifest_patched.xml")

        # Step 1
        extract_manifest(apk_path, manifest_axml)
        # Step 2
        decode_axml(manifest_axml, manifest_xml)
        # Step 3 : patch_xml renvoie un dict counts
        counts = patch_xml(manifest_xml, patched_xml_path)

        log("ℹ️  Returning patched XML path for Java-side encoding.")

        result = {
            "status": "success",
            "patchedXmlPath": patched_xml_path,
            "tmpDirPath": tmpdir,                   # <-- Ici le tmpdir est renvoyé avec la clé tmpDirPath
            "removed_trackers": counts['trackers'],
            "neutralized_onesignal": counts['onesignal']
        }
        return json.dumps(result)

    except Exception as e:
        log(f"❌ Erreur: {e}")
        return json.dumps({"status": "error", "message": str(e)})