package com.ledger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class LedgerEngine {

    private final List<Account> accounts;
    private final LedgerPolicy policy;

    public LedgerEngine(List<Account> accounts, LedgerPolicy policy) {
        this.accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
        this.policy = Objects.requireNonNull(policy, "policy");
        if (this.accounts.isEmpty()) {
            throw new IllegalArgumentException("At least one account is required");
        }
    }

    public Ledger replay(List<LedgerEvent> events) {
        Objects.requireNonNull(events, "events");
        if (events.isEmpty()) {
            return replay(events, 1, 1);
        }
        int firstDay = Integer.MAX_VALUE;
        int lastDay = Integer.MIN_VALUE;
        for (LedgerEvent event : events) {
            firstDay = Math.min(firstDay, Math.min(event.bookingDay(), event.valueDay()));
            lastDay = Math.max(lastDay, Math.max(event.bookingDay(), event.valueDay()));
        }
        return replay(events, firstDay, lastDay);
    }

    public Ledger replay(List<LedgerEvent> events, int firstDay, int lastDay) {
        Objects.requireNonNull(events, "events");
        if (firstDay < 1) {
            throw new IllegalArgumentException("First day must be at least 1: " + firstDay);
        }
        if (lastDay < firstDay) {
            throw new IllegalArgumentException(
                    "Window " + firstDay + ".." + lastDay + " is empty");
        }
        for (LedgerEvent event : events) {
            if (event.bookingDay() < firstDay || event.bookingDay() > lastDay) {
                throw new IllegalArgumentException("Event " + event.id() + " is booked on day "
                        + event.bookingDay() + ", outside the window " + firstDay + ".."
                        + lastDay);
            }
        }

        List<LedgerEvent> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparingInt(LedgerEvent::bookingDay));

        Ledger ledger = new Ledger(accounts, firstDay);
        for (int day = firstDay; day <= lastDay; day++) {
            for (LedgerEvent event : ordered) {
                if (event.bookingDay() == day) {
                    apply(ledger, event, day);
                }
            }
            for (LedgerEvent event : ordered) {
                if (event.bookingDay() == day && event.valueDay() < day) {
                    for (int affected = event.valueDay(); affected < day; affected++) {
                        ledger.recordBackdatedView(event.accountId(), affected, day);
                    }
                }
            }
            closeDay(ledger, day, firstDay);
        }
        capitalise(ledger, lastDay);
        return ledger;
    }

    private void apply(Ledger ledger, LedgerEvent event, int day) {
        switch (event) {
            case LedgerEvent.Credit credit -> applyCredit(ledger, credit);
            case LedgerEvent.Debit debit -> applyDebit(ledger, debit);
            case LedgerEvent.Authorize authorize -> applyAuthorize(ledger, authorize, day);
            case LedgerEvent.Settle settle -> applySettle(ledger, settle, day);
            case LedgerEvent.Reversal reversal -> applyReversal(ledger, reversal, day);
        }
    }

    private void applyCredit(Ledger ledger, LedgerEvent.Credit credit) {
        List<Money> parts = new ArrayList<>(credit.amount().divideEqually(credit.instalments()));
        Money residual = credit.amount().minus(sum(parts, credit.amount().currency()));

        if (!residual.isZero()) {
            switch (policy.instalmentResidual()) {
                case DISCARD -> ledger.record(LedgerError.forEvent(credit.bookingDay(),
                        credit.id(), LedgerError.Code.INSTALMENT_RESIDUAL_DISCARDED,
                        "Splitting " + credit.amount().format() + " into " + credit.instalments()
                                + " equal parts leaves " + residual.format()
                                + ", discarded so the account receives "
                                + sum(parts, credit.amount().currency()).format()));
                case FIRST -> parts.set(0, parts.get(0).plus(residual));
                case LAST -> parts.set(parts.size() - 1, parts.getLast().plus(residual));
            }
        }

        for (Money part : parts) {
            ledger.post(credit.id(), credit.accountId(), part, credit.valueDay(),
                    credit.bookingDay(), Posting.Type.CREDIT);
        }
    }

    private void applyDebit(Ledger ledger, LedgerEvent.Debit debit) {
        ledger.post(debit.id(), debit.accountId(), debit.amount().negated(), debit.valueDay(),
                debit.bookingDay(), Posting.Type.DEBIT);
    }

    private void applyAuthorize(Ledger ledger, LedgerEvent.Authorize authorize, int day) {
        Money available = ledger.available(authorize.accountId(), day);
        Money afterHold = available.minus(authorize.amount());

        if (afterHold.isNegative()) {
            ledger.record(new AuthorizationTransition(authorize.authRef(), authorize.accountId(),
                    authorize.amount(), day, AuthorizationTransition.State.DECLINED,
                    "available " + available.format() + " would become " + afterHold.format()));
            ledger.record(LedgerError.forEvent(day, authorize.id(),
                    LedgerError.Code.AUTHORIZATION_DECLINED,
                    authorize.authRef() + " declined: available " + available.format()
                            + " minus hold " + authorize.amount().format() + " is "
                            + afterHold.format()));
            return;
        }

        ledger.record(new AuthorizationTransition(authorize.authRef(), authorize.accountId(),
                authorize.amount(), day, AuthorizationTransition.State.APPROVED,
                "hold placed, available " + available.format() + " becomes "
                        + afterHold.format()));
    }

    private void applySettle(Ledger ledger, LedgerEvent.Settle settle, int day) {
        Optional<AuthorizationTransition.State> state = ledger.stateOf(settle.authRef());

        if (state.isEmpty()) {
            ledger.record(LedgerError.forEvent(day, settle.id(),
                    LedgerError.Code.ORPHAN_SETTLEMENT,
                    "Settlement of " + settle.amount().format() + " references "
                            + settle.authRef() + ", which has no authorization; rejected and "
                            + "funds not moved"));
            return;
        }

        if (state.get() != AuthorizationTransition.State.APPROVED) {
            ledger.record(LedgerError.forEvent(day, settle.id(),
                    LedgerError.Code.AUTHORIZATION_NOT_OPEN,
                    settle.authRef() + " is " + state.get() + " and cannot be settled again"));
            return;
        }

        Money hold = ledger.holdOf(settle.authRef()).orElseThrow();
        if (settle.amount().minus(hold).isPositive()) {
            ledger.record(LedgerError.forEvent(day, settle.id(),
                    LedgerError.Code.OVER_SETTLEMENT,
                    "Settlement " + settle.amount().format() + " exceeds hold " + hold.format()
                            + " on " + settle.authRef() + "; posted in full"));
        }

        ledger.post(settle.id(), settle.accountId(), settle.amount().negated(), settle.valueDay(),
                settle.bookingDay(), Posting.Type.SETTLEMENT);
        ledger.record(new AuthorizationTransition(settle.authRef(), settle.accountId(), hold, day,
                AuthorizationTransition.State.SETTLED,
                "settled for " + settle.amount().format() + ", hold " + hold.format()
                        + " released in full"));
    }

    private void applyReversal(Ledger ledger, LedgerEvent.Reversal reversal, int day) {
        String targetId = reversal.reversesEventId();
        List<Posting> target = ledger.postingsForEvent(targetId);

        if (target.isEmpty()) {
            ledger.record(LedgerError.forEvent(day, reversal.id(),
                    LedgerError.Code.REVERSAL_TARGET_NOT_FOUND,
                    "No postings found for " + targetId));
            return;
        }

        if (ledger.isReversed(targetId)) {
            ledger.record(LedgerError.forEvent(day, reversal.id(),
                    LedgerError.Code.DUPLICATE_REVERSAL,
                    targetId + " has already been reversed; not applied again"));
            return;
        }

        for (Posting posting : target) {
            if (posting.type() == Posting.Type.REVERSAL) {
                ledger.record(LedgerError.forEvent(day, reversal.id(),
                        LedgerError.Code.REVERSAL_OF_REVERSAL,
                        targetId + " is itself a reversal; not applied"));
                return;
            }
            if (!posting.accountId().equals(reversal.accountId())
                    || posting.valueDay() != reversal.valueDay()) {
                ledger.record(LedgerError.forEvent(day, reversal.id(),
                        LedgerError.Code.REVERSAL_MISMATCH,
                        "Reversal of " + targetId + " states account " + reversal.accountId()
                                + " value day " + reversal.valueDay() + " but the target posted "
                                + posting.accountId() + " value day " + posting.valueDay()));
                return;
            }
        }

        for (Posting posting : target) {
            ledger.postReversal(reversal.id(), posting.accountId(), posting.amount().negated(),
                    posting.valueDay(), day, targetId);
        }

        if (policy.refundFeeOnReversal()) {
            refundFeesNoLongerDue(ledger, reversal.accountId(), reversal.valueDay(), day);
        }
    }

    private void refundFeesNoLongerDue(Ledger ledger, String accountId, int fromDay, int today) {
        for (int day = fromDay; day <= today; day++) {
            Optional<Money> fee = ledger.feeOn(accountId, day);
            if (fee.isEmpty() || alreadyRefunded(ledger, accountId, day)) {
                continue;
            }
            if (ledger.balance(accountId, day).isNegative()) {
                continue;
            }
            ledger.post("REFUND-" + accountId + "-D" + day, accountId, fee.get().negated(), day,
                    today, Posting.Type.FEE_REFUND);
        }
    }

    private static boolean alreadyRefunded(Ledger ledger, String accountId, int day) {
        for (Posting posting : ledger.postings()) {
            if (posting.type() == Posting.Type.FEE_REFUND
                    && posting.accountId().equals(accountId)
                    && posting.valueDay() == day) {
                return true;
            }
        }
        return false;
    }

    private void closeDay(Ledger ledger, int today, int firstDay) {
        for (Account account : accounts) {
            int from = policy.reopenClosedDaysForFeesCalculation() ? firstDay : today;
            for (int day = from; day <= today; day++) {
                assessFee(ledger, account, day, today);
            }

            Money afterFee = ledger.balance(account.id(), today);
            Money accrual = afterFee.isPositive()
                    ? afterFee.applyRate(policy.rateNumerator(), policy.rateDenominator())
                    : Money.zero(account.currency());
            ledger.record(new Accrual(account.id(), today, accrual));
        }
    }

    private void assessFee(Ledger ledger, Account account, int valueDay, int bookingDay) {
        if (ledger.feeAssessed(account.id(), valueDay)) {
            return;
        }
        if (!ledger.balance(account.id(), valueDay).isNegative()) {
            return;
        }
        Optional<Money> fee = policy.feeFor(account.currency());
        if (fee.isEmpty()) {
            ledger.record(LedgerError.system(bookingDay, LedgerError.Code.FEE_CURRENCY_UNDEFINED,
                    "No overdraft fee is defined for " + account.currency() + ", so "
                            + account.id() + " was not charged for day " + valueDay));
            return;
        }
        ledger.post("FEE-" + account.id() + "-D" + valueDay, account.id(), fee.get().negated(),
                valueDay, bookingDay, Posting.Type.OVERDRAFT_FEE);
    }

    private void capitalise(Ledger ledger, int lastDay) {
        for (Account account : accounts) {
            Money total = ledger.accrualTotal(account.id());
            if (total.isZero()) {
                continue;
            }
            ledger.post("CAPITALISATION-" + account.id(), account.id(), total, lastDay, lastDay,
                    Posting.Type.INTEREST_CAPITALISATION);
        }
    }

    private static Money sum(List<Money> amounts, CurrencyCode currency) {
        Money total = Money.zero(currency);
        for (Money amount : amounts) {
            total = total.plus(amount);
        }
        return total;
    }
}
