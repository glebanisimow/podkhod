"""Split `dumpsys gfxinfo <pkg> framestats` into UI-thread and render-thread medians.

Usage: adb shell dumpsys gfxinfo app.podkhod framestats | python tools/framestats.py
"""
import statistics
import sys


def main() -> None:
    header = None
    rows = []
    for line in sys.stdin:
        line = line.strip().rstrip(",")
        if line.startswith("Flags,"):
            header = line.split(",")
            continue
        if header and line and line[0].isdigit():
            values = line.split(",")
            if len(values) >= len(header):
                row = dict(zip(header, map(int, values)))
                if row["Flags"] == 0:
                    rows.append(row)
    if not rows:
        print("no frames")
        return

    def ms(a: str, b: str) -> list[float]:
        return [(r[b] - r[a]) / 1e6 for r in rows]

    phases = {
        "ui (vsync -> sync queued)": ms("IntendedVsync", "SyncQueued"),
        "draw record (DrawStart -> SyncQueued)": ms("DrawStart", "SyncQueued"),
        "render (SyncStart -> FrameCompleted)": ms("SyncStart", "FrameCompleted"),
        "total (vsync -> completed)": ms("IntendedVsync", "FrameCompleted"),
    }
    print(f"frames: {len(rows)}")
    for name, values in phases.items():
        print(f"{name}: median {statistics.median(values):.1f} ms, max {max(values):.1f} ms")


if __name__ == "__main__":
    main()
