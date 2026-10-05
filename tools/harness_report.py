"""Summarise SmartSkip events from the pulled device database, for the harness runner.

Prints one block per event, newest first, and can be limited to events newer than a given
row id so that each harness scenario reports only its own events. Without that limit every
scenario prints the same most-recent rows and a run looks like it produced nothing.

The `action` column is printed explicitly because "refused by a hard veto" and "refused
because the score was too low" are very different guarantees, and a score alone cannot tell
them apart.

Usage:
    harness_report.py [--since N] [--limit N]
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from pathlib import Path

DB_DIR = Path(r"D:\codeTest\smartskip\.tooling\device-db")
DB = DB_DIR / "smartskip.db"
HARNESS_PACKAGE = "io.github.szzgx.smartskip.debug"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--since", type=int, default=0, help="only events with id > N")
    parser.add_argument("--limit", type=int, default=8)
    args = parser.parse_args()

    if not DB.exists():
        print("(no database pulled yet)")
        return 0

    connection = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    cursor = connection.cursor()

    try:
        rows = cursor.execute(
            """
            SELECT id, activity_name, score, band, action, result,
                   structure_signature, text_features, veto_reasons, top_features,
                   bounds_left, bounds_top, bounds_right, bounds_bottom,
                   screen_width, screen_height
            FROM advertisement_event
            WHERE package_name = ? AND id > ?
            ORDER BY id ASC
            LIMIT ?
            """,
            (HARNESS_PACKAGE, args.since, args.limit),
        ).fetchall()
    except sqlite3.Error as exc:
        print(f"(query failed: {exc})")
        connection.close()
        return 0

    if not rows:
        print("(no new events)")
        connection.close()
        return 0

    for row in rows:
        veto = f"  VETO={row['veto_reasons']}" if row["veto_reasons"] else ""
        print(
            f"#{row['id']} score={row['score']} band={row['band']} "
            f"action={row['action']} result={row['result']}{veto}"
        )

        width = (row["bounds_right"] or 0) - (row["bounds_left"] or 0)
        height = (row["bounds_bottom"] or 0) - (row["bounds_top"] or 0)
        screen_area = (row["screen_width"] or 1) * (row["screen_height"] or 1)
        area_pct = 100.0 * (width * height) / screen_area if screen_area else 0.0

        print(
            f"    size={width}x{height}px ({area_pct:.1f}% of screen)  "
            f"act={row['activity_name']}"
        )
        print(f"    sig={row['structure_signature']}")
        print(f"    text={row['text_features']}")
        if row["top_features"]:
            print(f"    top={row['top_features']}")

    print(f"__MAXID__={rows[-1]['id']}")
    connection.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
