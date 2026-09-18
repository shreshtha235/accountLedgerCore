package com.ledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Ledger {

    private static final String OPENING_EVENT_ID = "OPENING";

    private final Map<String, Account> accounts;
    private final List<Posting> postings = new ArrayList<>();
    private final List<AuthorizationTransition> transitions = new ArrayList<>();
    private final List<Accrual> accruals = new ArrayList<>();
    private final List<LedgerError> errors = new ArrayList<>();
    private long nextSeq;

    public Ledger(List<Account> accounts) {
        this(accounts, 1);
    }

    public Ledger(List<Account> accounts, int openingDay) {
        Objects.requireNonNull(accounts, "accounts");
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("At least one account is required");
        }
        if (openingDay < 1) {
            throw new IllegalArgumentException("Opening day must be at least 1: " + openingDay);
        }

        Map<String, Account> byId = new LinkedHashMap<>();
        for (Account account : accounts) {
            if (byId.put(account.id(), account) != null) {
                throw new IllegalArgumentException("Duplicate account id: " + account.id());
            }
        }
        this.accounts = Collections.unmodifiableMap(byId);

        for (Account account : this.accounts.values()) {
            postings.add(new Posting(nextSeq++, OPENING_EVENT_ID, account.id(),
                    account.openingBalance(), openingDay, openingDay,
                    Posting.Type.OPENING_BALANCE, null));
        }
    }

    public Collection<Account> accounts() {
        return accounts.values();
    }

    public Account account(String accountId) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new IllegalArgumentException("Unknown account: " + accountId);
        }
        return account;
    }

    public CurrencyCode currencyOf(String accountId) {
        return account(accountId).currency();
    }

    public Posting post(String eventId, String accountId, Money amount, int valueDay,
                        int bookingDay, Posting.Type type) {
        return append(new Posting(nextSeq, eventId, accountId, amount, valueDay, bookingDay, type,
                null));
    }

    public Posting postReversal(String eventId, String accountId, Money amount, int valueDay,
                                int bookingDay, String reversesEventId) {
        return append(new Posting(nextSeq, eventId, accountId, amount, valueDay, bookingDay,
                Posting.Type.REVERSAL, reversesEventId));
    }

    private Posting append(Posting posting) {
        requireCurrency(posting.accountId(), posting.amount());
        postings.add(posting);
        nextSeq++;
        return posting;
    }

    public void record(AuthorizationTransition transition) {
        Objects.requireNonNull(transition, "transition");
        requireCurrency(transition.accountId(), transition.amount());
        transitions.add(transition);
    }

    public void record(Accrual accrual) {
        Objects.requireNonNull(accrual, "accrual");
        requireCurrency(accrual.accountId(), accrual.amount());
        accruals.add(accrual);
    }

    public void record(LedgerError error) {
        errors.add(Objects.requireNonNull(error, "error"));
    }

    public List<Posting> postings() {
        return Collections.unmodifiableList(postings);
    }

    public List<AuthorizationTransition> transitions() {
        return Collections.unmodifiableList(transitions);
    }

    public List<Accrual> accruals() {
        return Collections.unmodifiableList(accruals);
    }

    public List<LedgerError> errors() {
        return Collections.unmodifiableList(errors);
    }

    public Money balance(String accountId, int valueDayCutoff) {
        return balance(accountId, valueDayCutoff, Integer.MAX_VALUE);
    }

    // Linear scan per query. Correct and free at this volume; a backdated entry invalidates any
    // cached balance, so nothing is cached. Production fix is a per-day snapshot invalidated from
    // the value date of each arriving entry.
    public Money balance(String accountId, int valueDayCutoff, int knownByBookingDay) {
        Money total = Money.zero(currencyOf(accountId));
        for (Posting posting : postings) {
            if (posting.accountId().equals(accountId)
                    && posting.valueDay() <= valueDayCutoff
                    && posting.bookingDay() <= knownByBookingDay) {
                total = total.plus(posting.amount());
            }
        }
        return total;
    }

    public Money activeHolds(String accountId) {
        return activeHolds(accountId, Integer.MAX_VALUE);
    }

    public Money activeHolds(String accountId, int asAtDay) {
        Money total = Money.zero(currencyOf(accountId));
        for (AuthorizationTransition latest : latestTransitions(asAtDay).values()) {
            if (latest.accountId().equals(accountId)
                    && latest.toState() == AuthorizationTransition.State.APPROVED) {
                total = total.plus(latest.amount());
            }
        }
        return total;
    }

    public Money available(String accountId, int day) {
        return balance(accountId, day, day).minus(activeHolds(accountId, day));
    }

    public Optional<AuthorizationTransition.State> stateOf(String authRef) {
        return Optional.ofNullable(latestTransitions().get(authRef))
                .map(AuthorizationTransition::toState);
    }

    public Optional<Money> holdOf(String authRef) {
        AuthorizationTransition latest = latestTransitions().get(authRef);
        if (latest == null || latest.toState() != AuthorizationTransition.State.APPROVED) {
            return Optional.empty();
        }
        return Optional.of(latest.amount());
    }

    public Map<String, AuthorizationTransition.State> authorizationsFor(String accountId) {
        return authorizationsFor(accountId, Integer.MAX_VALUE);
    }

    public Map<String, AuthorizationTransition.State> authorizationsFor(String accountId,
                                                                        int asAtDay) {
        Map<String, AuthorizationTransition.State> states = new LinkedHashMap<>();
        for (AuthorizationTransition latest : latestTransitions(asAtDay).values()) {
            if (latest.accountId().equals(accountId)) {
                states.put(latest.authRef(), latest.toState());
            }
        }
        return Collections.unmodifiableMap(states);
    }

    private Map<String, AuthorizationTransition> latestTransitions() {
        return latestTransitions(Integer.MAX_VALUE);
    }

    private Map<String, AuthorizationTransition> latestTransitions(int asAtDay) {
        Map<String, AuthorizationTransition> latest = new LinkedHashMap<>();
        for (AuthorizationTransition transition : transitions) {
            if (transition.day() <= asAtDay) {
                latest.put(transition.authRef(), transition);
            }
        }
        return latest;
    }

    public boolean feeAssessed(String accountId, int day) {
        return feeOn(accountId, day).isPresent();
    }

    public Optional<Money> feeOn(String accountId, int day) {
        return feePostingOn(accountId, day).map(Posting::amount);
    }

    public Optional<Posting> feePostingOn(String accountId, int day) {
        for (Posting posting : postings) {
            if (posting.type() == Posting.Type.OVERDRAFT_FEE
                    && posting.accountId().equals(accountId)
                    && posting.valueDay() == day) {
                return Optional.of(posting);
            }
        }
        return Optional.empty();
    }

    public Money accrual(String accountId, int day) {
        Money effective = Money.zero(currencyOf(accountId));
        for (Accrual accrual : accruals) {
            if (accrual.accountId().equals(accountId) && accrual.day() == day) {
                effective = accrual.amount();
            }
        }
        return effective;
    }

    public Money accrualTotal(String accountId) {
        Map<Integer, Money> byDay = new LinkedHashMap<>();
        for (Accrual accrual : accruals) {
            if (accrual.accountId().equals(accountId)) {
                byDay.put(accrual.day(), accrual.amount());
            }
        }
        Money total = Money.zero(currencyOf(accountId));
        for (Money amount : byDay.values()) {
            total = total.plus(amount);
        }
        return total;
    }

    public Optional<Money> capitalisationFor(String accountId) {
        for (Posting posting : postings) {
            if (posting.type() == Posting.Type.INTEREST_CAPITALISATION
                    && posting.accountId().equals(accountId)) {
                return Optional.of(posting.amount());
            }
        }
        return Optional.empty();
    }

    public List<Posting> postingsForEvent(String eventId) {
        List<Posting> result = new ArrayList<>();
        for (Posting posting : postings) {
            if (posting.eventId().equals(eventId)) {
                result.add(posting);
            }
        }
        return List.copyOf(result);
    }

    public boolean isReversed(String eventId) {
        for (Posting posting : postings) {
            if (posting.type() == Posting.Type.REVERSAL
                    && eventId.equals(posting.reversesEventId())) {
                return true;
            }
        }
        return false;
    }

    public List<LedgerError> errorsOn(int day) {
        List<LedgerError> result = new ArrayList<>();
        for (LedgerError error : errors) {
            if (error.day() == day) {
                result.add(error);
            }
        }
        return List.copyOf(result);
    }

    private void requireCurrency(String accountId, Money amount) {
        CurrencyCode expected = account(accountId).currency();
        if (amount.currency() != expected) {
            throw new IllegalArgumentException("Account " + accountId + " is " + expected
                    + " but amount is " + amount.currency());
        }
    }
}
