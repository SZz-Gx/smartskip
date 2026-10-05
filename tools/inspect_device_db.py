"""Read the SmartSkip database pulled from the device and summarise what it recorded.

Run with the bundled Python. The database plus its WAL must both be present; SQLite
replays the WAL on open.
"""

from __future__ import annotations

import sqlite3
import sys
from collections import Counter
from pathlib import Path

DB_DIR = Path(r"D:\codeTest\smartskip\.tooling\device-db")
DB = DB_DIR / "smartskip.db"


def main() -> int:
    if not DB.exists():
        print(f"missing {DB}")
        return 1

    # read-only so we never modify the pulled artefact
    connection = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    cursor = connection.cursor()

    def scalar(sql: str, default: int = 0) -> int:
        try:
            row = cursor.execute(sql).fetchone()
            return int(row[0]) if row and row[0] is not None else default
        except sqlite3.Error as exc:
            print(f"  query failed: {exc}")
            return default

    print("=== table sizes ===")
    for table in ("advertisement_event", "app_rule", "package_state"):
        print(f"  {table:24} {scalar(f'SELECT COUNT(*) FROM {table}')}")

    print("\n=== events per package (top 15) ===")
    try:
        rows = cursor.execute(
            """
            SELECT package_name,
                   COUNT(*)              AS events,
                   MAX(score)            AS best_score,
                   MAX(action)           AS any_action,
                   COUNT(DISTINCT structure_signature) AS signatures,
                   COUNT(DISTINCT activity_name)       AS activities
            FROM advertisement_event
            GROUP BY package_name
            ORDER BY events DESC
            LIMIT 15
            """
        ).fetchall()
        if not rows:
            print("  (no events recorded)")
        for row in rows:
            print(
                f"  {row['package_name']:38} events={row['events']:5} "
                f"best={row['best_score']:3} sigs={row['signatures']:3} acts={row['activities']:2}"
            )
    except sqlite3.Error as exc:
        print(f"  failed: {exc}")

    print("\n=== action / result distribution ===")
    try:
        for row in cursor.execute(
            "SELECT action, result, COUNT(*) AS n FROM advertisement_event GROUP BY action, result ORDER BY n DESC"
        ):
            print(f"  {row['action']:10} {row['result']:24} {row['n']}")
    except sqlite3.Error as exc:
        print(f"  failed: {exc}")

    print("\n=== text-feature vocabulary actually observed ===")
    try:
        counter: Counter[str] = Counter()
        for row in cursor.execute("SELECT text_features FROM advertisement_event"):
            for token in (row["text_features"] or "").split(","):
                token = token.strip()
                if token:
                    counter[token] += 1
        for token, count in counter.most_common(30):
            print(f"  {token:34} {count}")
    except sqlite3.Error as exc:
        print(f"  failed: {exc}")

    print("\n=== veto reasons seen ===")
    try:
        counter = Counter()
        for row in cursor.execute("SELECT veto_reasons FROM advertisement_event"):
            for token in (row["veto_reasons"] or "").split(","):
                token = token.strip()
                if token:
                    counter[token] += 1
        if not counter:
            print("  (none)")
        for token, count in counter.most_common():
            print(f"  {token:34} {count}")
    except sqlite3.Error as exc:
        print(f"  failed: {exc}")

    print("\n=== best-scoring candidate rows (limit 12) ===")
    try:
        rows = cursor.execute(
            """
            SELECT package_name, activity_name, score, band, action, result,
                   structure_signature, top_features, resource_id, class_name,
                   bounds_left, bounds_top, bounds_right, bounds_bottom,
                   screen_width, screen_height, text_features
            FROM advertisement_event
            ORDER BY score DESC
            LIMIT 12
            """
        ).fetchall()
        if not rows:
            print("  (none)")
        for row in rows:
            print(f"\n  {row['package_name']} / {row['activity_name']}")
            print(f"    score={row['score']} band={row['band']} action={row['action']} result={row['result']}")
            print(f"    class={row['class_name']}  id={row['resource_id']}")
            print(
                f"    bounds=({row['bounds_left']},{row['bounds_top']})-"
                f"({row['bounds_right']},{row['bounds_bottom']}) "
                f"screen={row['screen_width']}x{row['screen_height']}"
            )
            print(f"    text_features={row['text_features']}")
            print(f"    sig={row['structure_signature']}")
            print(f"    top={row['top_features']}")
    except sqlite3.Error as exc:
        print(f"  failed: {exc}")

    print("\n=== learned rules ===")
    try:
        rows = cursor.execute(
            """
            SELECT package_name, activity_name, structure_signature, confidence,
                   success_count, failure_count, dangerous_failure_count,
                   user_confirmed, user_rejected, is_enabled, text_observation
            FROM app_rule
            ORDER BY confidence DESC
            LIMIT 20
            """
        ).fetchall()
        if not rows:
            print("  (no rules learned)")
        for row in rows:
            print(
                f"  {row['package_name']:38} conf={row['confidence']:.2f} "
                f"ok={row['success_count']} fail={row['failure_count']} "
                f"danger={row['dangerous_failure_count']} "
                f"confirmed={row['user_confirmed']} enabled={row['is_enabled']}"
            )
            print(f"      sig={row['structure_signature']}  text={row['text_observation']}")
    except sqlite3.Error as exc:
        print(f"  failed: {exc}")

    print("\n=== packages tracked ===")
    try:
        rows = cursor.execute(
            """
            SELECT package_name, dangerous_misclick_count, consecutive_failure_count,
                   is_auto_skip_suspended, suspension_reason, last_seen_ms
            FROM package_state
            ORDER BY last_seen_ms DESC
            LIMIT 25
            """
        ).fetchall()
        if not rows:
            print("  (none)")
        for row in rows:
            flag = " SUSPENDED" if row["is_auto_skip_suspended"] else ""
            print(
                f"  {row['package_name']:38} danger={row['dangerous_misclick_count']} "
                f"fail={row['consecutive_failure_count']}{flag}"
            )
    except sqlite3.Error as exc:
        print(f"  failed: {exc}")

    connection.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
