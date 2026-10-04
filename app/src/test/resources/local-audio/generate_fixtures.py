"""Regenerates the local audio SQLite fixtures used by LocalAudioSqliteReaderTest.

Run from this directory with any Python 3 build that includes sqlite3:

    python generate_fixtures.py

The row formulas here are mirrored by LocalAudioFixture in the unit tests, so keep
both in sync when changing them.
"""

import os
import sqlite3

SOURCES = ["nhk16", "daijisen", "forvo", "jpod", "shinmeikai8"]
EXTENSIONS = ["mp3", "opus", "ogg", "MP3"]

ENTRIES_SQL = """CREATE TABLE entries (
                id integer PRIMARY KEY NOT NULL,
                expression text NOT NULL,
                reading text,
                source text NOT NULL,
                speaker text,
                display text,
                file text NOT NULL
           )"""
ANDROID_SQL = """CREATE TABLE android (
            id integer PRIMARY KEY NOT NULL,
            file text NOT NULL,
            source text NOT NULL,
            data blob NOT NULL
        )"""
INDEX_SQL = [
    "CREATE INDEX idx_reading ON entries(reading)",
    "CREATE INDEX idx_speaker ON entries(speaker)",
    "CREATE INDEX idx_expr_reading ON entries(expression, reading)",
    "CREATE INDEX idx_reading_speaker ON entries(expression, reading, speaker)",
    "CREATE INDEX idx_all ON entries(expression, reading, source)",
    "CREATE INDEX idx_android ON android(file, source)",
]


def blob(seed, size):
    return bytes((seed * 31 + k * 7) % 256 for k in range(size))


def entry(i):
    return (
        f"語{i:04d}",
        None if i % 10 == 0 else f"よみ{i % 100:02d}",
        SOURCES[i % 5],
        None if i % 3 == 0 else f"表示{i}",
        f"{i:04d}.{EXTENSIONS[i % 4]}",
    )


SPECIAL_ENTRIES = [
    ("𠮟る", "しかる", "nhk16", "叱る", "shikaru.opus"),
    ("画像", "がぞう", "images", None, "picture.png"),
    ("語0005", "よみ05", "taas", "重複", "dup.mp3"),
    ("長" * 300, "ながい", "forvo", None, "long.mp3"),
]


def build(path, count, page_size, encoding, with_indexes):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    db.execute(f"PRAGMA page_size={page_size}")
    db.execute(f"PRAGMA encoding='{encoding}'")
    db.execute("PRAGMA journal_mode=DELETE")
    db.execute(ENTRIES_SQL)
    db.execute(ANDROID_SQL)
    if with_indexes:
        for sql in INDEX_SQL:
            db.execute(sql)
    rows = [entry(i) for i in range(count)] + SPECIAL_ENTRIES
    db.executemany(
        "INSERT INTO entries (expression, reading, source, display, file) VALUES (?, ?, ?, ?, ?)",
        rows,
    )
    audio = [
        (f"{i:04d}.{EXTENSIONS[i % 4]}", SOURCES[i % 5], blob(i, 50 + (i * 37) % 3000))
        for i in range(0, count, 25)
    ]
    audio += [
        ("shikaru.opus", "nhk16", blob(9001, 20000)),
        ("dup.mp3", "taas", blob(9002, 64)),
    ]
    db.executemany("INSERT INTO android (file, source, data) VALUES (?, ?, ?)", audio)
    db.commit()
    db.execute("VACUUM")
    db.close()


if __name__ == "__main__":
    build("android-small-pages.db", 1500, 512, "UTF-8", True)
    build("android-utf16.db", 200, 4096, "UTF-16le", True)
    build("android-no-indexes.db", 120, 1024, "UTF-8", False)
