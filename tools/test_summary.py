"""Prints a per-suite summary of JUnit XML results: python tools/test_summary.py <dir> [<dir> ...]."""
import glob
import os
import sys
import xml.etree.ElementTree as ET

total = failed = skipped = 0
for root in sys.argv[1:]:
    for path in sorted(glob.glob(os.path.join(root, "**", "*.xml"), recursive=True)):
        suite = ET.parse(path).getroot()
        tests = int(suite.get("tests", 0))
        fails = int(suite.get("failures", 0)) + int(suite.get("errors", 0))
        skips = int(suite.get("skipped", 0))
        total, failed, skipped = total + tests, failed + fails, skipped + skips
        print(f"{suite.get('name')}: {tests} tests, {fails} failed, {skips} skipped")
        for case in suite.iter("testcase"):
            for problem in list(case.findall("failure")) + list(case.findall("error")):
                print(f"  FAIL {case.get('name')}: {(problem.get('message') or '')[:300]}")
print(f"TOTAL {total} tests, {failed} failed, {skipped} skipped")
sys.exit(1 if failed else 0)
