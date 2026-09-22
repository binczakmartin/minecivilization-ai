"""Memory store: kinds, text search relevance, working-memory replacement."""

from minecivilization_ai.db.session import session_scope
from minecivilization_ai.memory import recent, remember, search, set_working, working_set


def test_remember_and_recent():
    with session_scope() as s:
        remember(s, "c1", "Found iron deposit near x=820 z=-312", kind="EPISODIC",
                 importance=0.9)
        remember(s, "c1", "Warehouse failed: stone supply insufficient", kind="EPISODIC")
        remember(s, "c2", "other citizen memory", kind="EPISODIC")
        rows = recent(s, "c1")
        assert len(rows) == 2
        assert all(m.citizen_id == "c1" for m in rows)


def test_search_ranks_relevant_first():
    with session_scope() as s:
        remember(s, "c1", "Found iron deposit near the east mine", importance=0.9)
        remember(s, "c1", "Ate bread at noon", importance=0.2)
        remember(s, "c1", "Learned stone smelting recipe", importance=0.5)
        results = search(s, "c1", "iron deposit")
        assert results, "search returned nothing"
        top_mem, top_score = results[0]
        assert "iron" in top_mem.content
        assert top_score > results[-1][1]


def test_search_filters_by_kind():
    with session_scope() as s:
        remember(s, "c1", "Bob reliably delivers iron", kind="SOCIAL", importance=0.8)
        remember(s, "c1", "iron ore found", kind="EPISODIC")
        soc = search(s, "c1", "iron", kinds=("SOCIAL",))
        assert len(soc) == 1
        assert soc[0][0].kind == "SOCIAL"


def test_working_memory_is_replaced():
    with session_scope() as s:
        set_working(s, "c1", "goal: gather oak")
        set_working(s, "c1", "goal: build warehouse")
        ws = working_set(s, "c1")
        assert ws == ["goal: build warehouse"]


def test_unknown_kind_rejected():
    import pytest
    with session_scope() as s:
        with pytest.raises(ValueError):
            remember(s, "c1", "x", kind="TELEPATHIC")
