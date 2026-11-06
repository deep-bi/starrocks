#!/usr/bin/env python3
"""Verify and unpack a release candidate without building StarRocks"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tarfile


def verify_checksum(archive, checksum):
    text = checksum.read_text().strip()
    # Accept one checksum entry in GNU or BSD format
    match = re.fullmatch(r"([0-9a-fA-F]{64})\s+\*?(.+)", text)
    if match:
        expected, filename = match.groups()
    else:
        match = re.fullmatch(r"SHA256\s*\((.+)\)\s*=\s*([0-9a-fA-F]{64})", text)
        if not match:
            raise ValueError("Expected one SHA-256 checksum entry")
        filename, expected = match.groups()
    if Path(filename).name != archive.name:
        raise ValueError("Checksum filename does not match the downloaded archive")
    with archive.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != expected.lower():
        raise ValueError("Release candidate SHA-256 checksum mismatch")
    return actual


def unpack(archive, destination, reserve_bytes=4 * 1024**3):
    destination.mkdir(parents=True, exist_ok=True)
    if any(destination.iterdir()):
        raise ValueError("Extraction directory must be empty")
    with tarfile.open(archive, "r:gz") as source:
        # Omit debug symbols to leave disk space for tests on the hosted runner
        members = [member for member in source.getmembers() if not member.name.endswith(".debuginfo")]
        size = sum(member.size for member in members if member.isfile())
        if size + reserve_bytes > shutil.disk_usage(destination).free:
            raise ValueError(f"Insufficient disk: need {size + reserve_bytes} free bytes to extract and test")
        source.extractall(destination, members=members, filter="data")


def find_package(destination, architecture):
    candidates = [path.parent.parent.parent for path in destination.rglob("fe/bin/start_fe.sh")]
    candidates = [path for path in candidates if (path / "be/bin/start_be.sh").is_file()]
    if len(candidates) != 1:
        raise ValueError("Expected exactly one package containing fe/bin/start_fe.sh and be/bin/start_be.sh")
    package = candidates[0]
    with (package / "be/lib/starrocks_be").open("rb") as stream:
        header = stream.read(20)
    if len(header) != 20 or header[:5] != b"\x7fELF\x02" or header[5] not in (1, 2):
        raise ValueError("RC backend is not a 64-bit ELF binary")
    machine = int.from_bytes(header[18:20], "little" if header[5] == 1 else "big")
    if machine != {"amd64": 62, "arm64": 183}[architecture]:
        raise ValueError(f"RC backend architecture does not match {architecture}")
    return package


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--checksum", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--architecture", choices=("amd64", "arm64"), required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    args = parser.parse_args()
    digest = verify_checksum(args.archive, args.checksum)
    unpack(args.archive, args.destination)
    package = find_package(args.destination, args.architecture)
    args.metadata.write_text(json.dumps({"archive": args.archive.name, "sha256": digest,
                                         "architecture": args.architecture, "package": str(package)}, indent=2) + "\n")
    print(package)


if __name__ == "__main__":
    main()
