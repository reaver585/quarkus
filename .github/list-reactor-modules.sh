#!/bin/bash

set -e -u -o pipefail

python3 - <<'PY'
import os
import xml.etree.ElementTree as ET
from collections import deque


def local_name(tag: str) -> str:
    if "}" in tag:
        return tag.split("}", 1)[1]
    return tag


root_dir = os.getcwd()
visited = set()
modules = set()
queue = deque(["."])

while queue:
    module_dir = queue.popleft()
    module_dir = os.path.normpath(module_dir)
    if module_dir in visited:
        continue
    visited.add(module_dir)

    pom_path = os.path.join(root_dir, module_dir, "pom.xml")
    if not os.path.isfile(pom_path):
        continue

    if module_dir != ".":
        modules.add(module_dir)

    try:
        tree = ET.parse(pom_path)
    except ET.ParseError:
        continue

    root = tree.getroot()
    for elem in root.iter():
        if local_name(elem.tag) != "module":
            continue
        if elem.text is None:
            continue
        child = elem.text.strip()
        if not child:
            continue
        child_dir = os.path.normpath(os.path.join(module_dir, child))
        queue.append(child_dir)

for module in sorted(modules):
    print(module)
PY
