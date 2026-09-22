"""Append-only economy ledger.

Balances are only ever changed by inserting a Transaction. The cached
Account.balance is a denormalization that can be recomputed from the ledger.
Currency name is configurable ("CIV" default). Currency never materializes
physical Minecraft resources.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlmodel import Session, select

from ..db.models import Account, Transaction


class LedgerError(ValueError):
    pass


def get_or_create_account(
    session: Session, owner_type: str, owner_id: str, currency: str = "CIV"
) -> Account:
    acc = session.exec(
        select(Account).where(Account.owner_type == owner_type,
                              Account.owner_id == owner_id,
                              Account.currency == currency)
    ).first()
    if acc is None:
        acc = Account(id=str(uuid.uuid4()), owner_type=owner_type,
                      owner_id=owner_id, currency=currency, balance=0.0)
        session.add(acc)
        session.commit()
        session.refresh(acc)
    return acc


def _account_or_none(session: Session, account_id: str | None) -> Account | None:
    if account_id is None:
        return None
    return session.get(Account, account_id)


def transfer(
    session: Session,
    from_account: str | None,
    to_account: str | None,
    amount: float,
    reason: str,
    reference_id: str | None = None,
    allow_mint: bool = False,
) -> Transaction:
    """Move funds. from_account=None mints (only when allow_mint). to=None burns."""
    if amount <= 0:
        raise LedgerError("amount must be > 0")
    if from_account is None and to_account is None:
        raise LedgerError("at least one side required")
    if from_account is None and not allow_mint:
        raise LedgerError("minting requires allow_mint")

    src = _account_or_none(session, from_account)
    dst = _account_or_none(session, to_account)

    if src is not None and src.balance < amount:
        raise LedgerError(
            f"insufficient funds: {src.owner_id} has {src.balance}, needs {amount}"
        )

    tx = Transaction(
        id=str(uuid.uuid4()),
        timestamp=datetime.now(timezone.utc),
        from_account=from_account,
        to_account=to_account,
        amount=float(amount),
        reason=reason,
        reference_id=reference_id,
    )
    session.add(tx)
    if src is not None:
        src.balance -= amount
        session.add(src)
    if dst is not None:
        dst.balance += amount
        session.add(dst)
    session.commit()
    session.refresh(tx)
    return tx


def balance(session: Session, account_id: str) -> float:
    """Always derived from the ledger (cache is only a fast path)."""
    rows = session.exec(
        select(Transaction).where(
            (Transaction.from_account == account_id) | (Transaction.to_account == account_id)
        )
    ).all()
    bal = 0.0
    for tx in rows:
        if tx.to_account == account_id:
            bal += tx.amount
        if tx.from_account == account_id:
            bal -= tx.amount
    return bal


def statement(session: Session, account_id: str) -> list[Transaction]:
    rows = session.exec(
        select(Transaction)
        .where(
            (Transaction.from_account == account_id) | (Transaction.to_account == account_id)
        )
        .order_by(Transaction.timestamp)  # type: ignore[attr-defined]
    ).all()
    return list(rows)


def verify_cache(session: Session, account_id: str) -> bool:
    """True when cached balance matches the ledger-derived balance."""
    acc = session.get(Account, account_id)
    if acc is None:
        return False
    return abs(acc.balance - balance(session, account_id)) < 1e-6
