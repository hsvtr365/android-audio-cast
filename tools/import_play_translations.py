"""Import Play Console app-string translations into Android resources.

Input is a JSON array exported from the Play Console translation table,
with one object per app string and language_<tag> columns.
"""

import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path
from xml.sax.saxutils import escape


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/res/values/strings.xml"
RES = ROOT / "app/src/main/res"
PLACEHOLDER = re.compile(r"%(?:\d+\$)?(?:\.\d+)?[sdif]")


def resource_dir(tag):
    if tag == "zh-CN":
        return "values-zh-rCN"
    if tag == "he":
        return "values-iw"  # Android's legacy resource qualifier for Hebrew.
    return f"values-{tag}"


def android_text(value):
    # Play sometimes wraps translations containing apostrophes in Android quotes.
    if len(value) >= 2 and value[0] == value[-1] == '"':
        value = value[1:-1]
    value = value.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    return escape(value.replace("\n", "\\n"))


def main():
    rows = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    overrides = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
    source = {item.attrib["name"]: item.text or "" for item in ET.parse(SOURCE).getroot()}
    by_key = {row["key"]: row for row in rows}
    if set(by_key) != set(source):
        raise ValueError(f"Keys differ: missing={set(source)-set(by_key)}, extra={set(by_key)-set(source)}")
    languages = sorted(key[9:] for key in rows[0] if key.startswith("language_") and key not in {"language_en", "language_ko"})
    for key, original in source.items():
        if " ".join(by_key[key]["default"].split()) != " ".join(original.replace("\\n", " ").split()):
            raise ValueError(f"Source changed since Play translation: {key}")
    for language in languages:
        column = f"language_{language}"
        lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
        for key, original in source.items():
            translated = overrides.get(language, {}).get(key, by_key[key].get(column))
            if not translated or translated == "-":
                raise ValueError(f"Missing {language}/{key}")
            if Counter(PLACEHOLDER.findall(original)) != Counter(PLACEHOLDER.findall(translated)):
                raise ValueError(f"Placeholder mismatch: {language}/{key}")
            lines.append(f'    <string name="{key}">{android_text(translated)}</string>')
        lines.append("</resources>")
        directory = RES / resource_dir(language)
        directory.mkdir(exist_ok=True)
        (directory / "strings.xml").write_text("\n".join(lines) + "\n", encoding="utf-8")
    locale_tags = ["en", "ko", *languages]
    locale_xml = ['<?xml version="1.0" encoding="utf-8"?>', '<locale-config xmlns:android="http://schemas.android.com/apk/res/android">']
    locale_xml.extend(f'    <locale android:name="{tag}"/>' for tag in locale_tags)
    locale_xml.append("</locale-config>")
    (RES / "xml/locales_config.xml").write_text("\n".join(locale_xml) + "\n", encoding="utf-8")
    print(f"Imported {len(source)} strings for {len(languages)} languages")


if __name__ == "__main__":
    main()
