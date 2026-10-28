#!/usr/bin/env python3
# Copyright 2021-present StarRocks, Inc. All rights reserved.
# Licensed under the Apache License, Version 2.0

"""After building both reactors, test their effective FIPS dependency bans offline."""

import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET


root = Path(__file__).resolve().parents[2]
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
for reactor in ("fe", "java-extensions"):
    parent_path = root / reactor / "pom.xml"
    parent = ET.parse(parent_path).getroot()
    with tempfile.TemporaryDirectory(prefix="starrocks-conflicting-bc-") as directory:
        project = ET.Element("project", xmlns=namespace["m"])
        ET.SubElement(project, "modelVersion").text = "4.0.0"
        parent_ref = ET.SubElement(project, "parent")
        for field in ("groupId", "artifactId", "version"):
            ET.SubElement(parent_ref, field).text = parent.find(f"m:{field}", namespace).text
        ET.SubElement(parent_ref, "relativePath").text = os.path.relpath(parent_path, directory)
        ET.SubElement(project, "artifactId").text = "conflicting-bc-test"
        dependency = ET.SubElement(ET.SubElement(project, "dependencies"), "dependency")
        for field, value in (("groupId", "org.bouncycastle"), ("artifactId", "bcprov-jdk18on"), ("version", "1.85")):
            ET.SubElement(dependency, field).text = value
        pom = Path(directory) / "pom.xml"
        ET.ElementTree(project).write(pom, encoding="utf-8", xml_declaration=True)
        result = subprocess.run(["mvn", "-o", "-B", "-f", str(pom), "validate", "-Dcheckstyle.skip"],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if result.returncode == 0 or "org.bouncycastle:bcprov-jdk18on:jar:1.85 <--- banned" not in result.stdout:
            raise RuntimeError(f"{reactor} did not reject the injected dependency as expected:\n{result.stdout}")
        print(f"{reactor}: ordinary BC dependency rejected")
