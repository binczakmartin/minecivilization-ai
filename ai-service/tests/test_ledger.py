"""Append-only ledger invariants."""

import pytest

from minecivilization_ai.db.session import session_scope
from minecivilization_ai.economy.ledger import (
    LedgerError,
    balance,
    get_or_create_account,
    statement,
    transfer,
    verify_cache,
)


def test_transfer_moves_funds_and_appends_ledger():
    with session_scope() as s:
        a = get_or_create_account(s, "CITIZEN", "alice")
        b = get_or_create_account(s, "CITIZEN", "bob")
        transfer(s, None, a.id, 50.0, reason="starting grant", allow_mint=True)
        tx = transfer(s, a.id, b.id, 10.0, reason="wage")
        assert tx.amount == 10.0
        assert balance(s, a.id) == 40.0
        assert balance(s, b.id) == 10.0
        assert verify_cache(s, a.id) and verify_cache(s, b.id)
        assert len(statement(s, b.id)) == 1


def test_insufficient_funds_rejected():
    with session_scope() as s:
        a = get_or_create_account(s, "CITIZEN", "pauper")
        b = get_or_create_account(s, "CITIZEN", "rich")
        with pytest.raises(LedgerError):
            transfer(s, a.id, b.id, 1.0, reason="impossible")


def test_mint_requires_opt_in():
    with session_scope() as s:
        treasury = get_or_create_account(s, "TREASURY", "state")
        citizen = get_or_create_account(s, "CITIZEN", "carol")
        with pytest.raises(LedgerError):
            transfer(s, None, citizen.id, 100.0, reason="free money")
        transfer(s, None, citizen.id, 100.0, reason="starting wage",
                 allow_mint=True)
        assert balance(s, citizen.id) == 100.0


def test_negative_amount_rejected():
    with session_scope() as s:
        a = get_or_create_account(s, "CITIZEN", "x")
        b = get_or_create_account(s, "CITIZEN", "y")
        with pytest.raises(LedgerError):
            transfer(s, a.id, b.id, -1.0, reason="refund trick")


def test_cache_stays_consistent_after_many_transfers():
    with session_scope() as s:
        a = get_or_create_account(s, "CITIZEN", "payer")
        b = get_or_create_account(s, "CITIZEN", "payee")
        transfer(s, None, a.id, 50.0, reason="grant", allow_mint=True)
        for i in range(10):
            transfer(s, a.id, b.id, 4.5, reason=f"payment {i}")
        assert verify_cache(s, a.id)
        assert verify_cache(s, b.id)
        assert abs(balance(s, a.id) - 5.0) < 1e-9
        assert abs(balance(s, b.id) - 45.0) < 1e-9
