#!/usr/bin/env python3
"""Validate UT reports and record framework skips"""

import argparse
import glob
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def check_reports(pattern, label, output):
    paths = sorted(glob.glob(pattern, recursive=True))
    summary = dict(label=label, reports=len(paths), tests=0, executed=0, failures=0,
                   errors=0, disabled=[], skipped=[], invalid=[])
    for path in paths:
        try:
            root = ET.parse(path).getroot()
            if root.tag not in ("testsuite", "testsuites"):
                raise ValueError("Expected testsuite or testsuites")
            cases = list(root.iter("testcase"))
            if root.get("tests") is not None and int(root.get("tests")) != len(cases):
                raise ValueError("Declared test count does not match testcase entries")
            summary["tests"] += len(cases)
            for kind in ("failures", "errors"):
                # Parent and child suites can report the same failure
                # Reject either report without counting that failure twice
                counters = [int(suite.get(kind, "0")) for suite in root.iter()
                            if suite.tag in ("testsuite", "testsuites")]
                if any(count < 0 for count in counters):
                    raise ValueError("Negative result count")
                reported = max(counters, default=0)
                actual = sum(case.find(kind[:-1]) is not None for case in cases)
                summary[kind] += max(reported, actual)
            for case in cases:
                identifier = f"{case.get('classname', '')}.{case.get('name', '')}"
                if case.get("status") == "notrun" or case.get("result") == "suppressed":
                    summary["disabled"].append(identifier)
                elif case.find("skipped") is not None or case.get("result") == "skipped":
                    skipped = case.find("skipped")
                    summary["skipped"].append(dict(test=identifier,
                                                   reason=(skipped.get("message") or skipped.text or "")
                                                   if skipped is not None else ""))
                else:
                    summary["executed"] += 1
        except (ET.ParseError, OSError, ValueError) as exc:
            summary["invalid"].append(dict(report=path, error=str(exc)))

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, indent=2) + "\n")
    message = (f"{label}: reports={len(paths)}, tests={summary['tests']}, executed={summary['executed']}, "
               f"failures={summary['failures']}, errors={summary['errors']}, "
               f"disabled={len(summary['disabled'])}, skipped={len(summary['skipped'])}")
    print(message)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
            stream.write("- " + message + "\n")
    if not paths or not summary["executed"] or summary["invalid"] or summary["failures"] or summary["errors"]:
        raise RuntimeError(f"{label}: UT reports did not establish a successful run; inspect {output}")
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pattern", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    check_reports(args.pattern, args.label, args.output)
