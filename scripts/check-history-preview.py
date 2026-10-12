#!/usr/bin/env python3
"""Check the bounded history query without Android/device dependencies."""
import sqlite3

connection = sqlite3.connect(":memory:")
connection.execute("CREATE TABLE samples(id INTEGER PRIMARY KEY,session INTEGER,data TEXT)")
connection.executemany(
    "INSERT INTO samples VALUES(?,?,?)",
    [(i, 1, str(i)) for i in range(1, 152)] + [(200, 2, "other")],
)
query = "SELECT data FROM samples WHERE session=? ORDER BY id DESC LIMIT ?"
preview = connection.execute(query, ("1", "120")).fetchall()[::-1]
assert len(preview) == 120
assert preview[0][0] == "32" and preview[-1][0] == "151"
assert connection.execute(query, ("3", "120")).fetchall() == []
full = connection.execute(
    "SELECT data FROM samples WHERE session=? ORDER BY id", ("1",)
).fetchall()
assert len(full) == 151 and full[0][0] == "1"
print("History preview SQL: PASS (limit, order, isolation, empty history).")
