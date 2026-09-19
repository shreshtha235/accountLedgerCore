package com.ledger;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ReportPrinter {

    private static final String RULE =
            "================================================================================";
    private static final String FIELD = "    %-38s%s%n";

    private ReportPrinter() {
    }

    public static void print(Ledger ledger, int firstDay, int lastDay, PrintStream out) {
        for (int day = firstDay; day <= lastDay; day++) {
            out.println(RULE);
            out.println("DAY " + day);
            out.println(RULE);
            for (Account account : ledger.accounts()) {
                printAccount(ledger, account, day, lastDay, out);
            }
            printErrors(ledger.errorsOn(day), out);
            out.println();
        }
        printSummary(ledger, firstDay, lastDay, out);
    }

    private static void printAccount(Ledger ledger, Account account, int day, int lastDay,
                                     PrintStream out) {
        String id = account.id();
        Money asAtClose = ledger.balance(id, day, day);
        Money restated = ledger.balance(id, day);

        out.printf("  %s  %s%n", id, account.currency());
        out.printf(FIELD, "closing ledger balance (at close)", asAtClose.format());
        if (!restated.equals(asAtClose)) {
            out.printf(FIELD, "closing ledger balance (restated now)",
                    restated.format() + "   <-- changed by a later backdated entry");
        }
        out.printf(FIELD, "available balance", ledger.available(id, day).format());
        out.printf(FIELD, "active holds", ledger.activeHolds(id, day).format());
        out.printf(FIELD, "overdraft fee assessed", ledger.feePostingOn(id, day)
                .map(fee -> fee.bookingDay() == day
                        ? fee.amount().negated().format()
                        : fee.amount().negated().format() + "   (booked on day "
                                + fee.bookingDay() + ")")
                .orElse("none"));
        out.printf(FIELD, "interest accrued", ledger.accrual(id, day).format());
        if (day == lastDay) {
            out.printf(FIELD, "interest capitalised",
                    ledger.capitalisationFor(id).map(Money::format).orElse("none"));
        }
        out.printf(FIELD, "authorizations", describe(ledger.authorizationsFor(id, day)));
    }

    private static String describe(Map<String, AuthorizationTransition.State> states) {
        if (states.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        states.forEach((ref, state) -> parts.add(ref + " " + state));
        return String.join(", ", parts);
    }

    private static void printErrors(List<LedgerError> errors, PrintStream out) {
        out.println("  errors");
        if (errors.isEmpty()) {
            out.println("    none");
            return;
        }
        for (LedgerError error : errors) {
            out.printf("    %-8s %-30s %s%n", error.sourceEvent().orElse("SYSTEM"), error.code(),
                    error.detail());
        }
    }

    private static void printSummary(Ledger ledger, int firstDay, int lastDay, PrintStream out) {
        out.println(RULE);
        out.println("SUMMARY");
        out.println(RULE);
        for (Account account : ledger.accounts()) {
            String id = account.id();
            CurrencyCode ccy = account.currency();
            List<String> daily = new ArrayList<>();
            for (int day = firstDay; day <= lastDay; day++) {
                daily.add(ledger.accrual(id, day).format());
            }
            Money total = ledger.accrualTotal(id);
            Money capitalised = ledger.capitalisationFor(id).orElse(Money.zero(ccy));

            Money totalCredits = ledger.postings().stream()
                    .filter(p -> p.accountId().equals(id) && p.amount().isPositive())
                    .map(Posting::amount)
                    .reduce(Money.zero(ccy), Money::plus);
            Money totalDebits = ledger.postings().stream()
                    .filter(p -> p.accountId().equals(id) && p.amount().isNegative())
                    .map(p -> p.amount().negated())
                    .reduce(Money.zero(ccy), Money::plus);
            Money totalFees = ledger.postings().stream()
                    .filter(p -> p.accountId().equals(id) && p.type() == Posting.Type.OVERDRAFT_FEE)
                    .map(p -> p.amount().negated())
                    .reduce(Money.zero(ccy), Money::plus);

            out.printf("  %s  %s%n", id, ccy);
            out.printf(FIELD, "final ledger balance", ledger.balance(id, lastDay).format());
            out.printf(FIELD, "total credits", totalCredits.format());
            out.printf(FIELD, "total debits", totalDebits.format());
            out.printf(FIELD, "total fees charged", totalFees.isZero() ? "none" : totalFees.format());
            out.printf(FIELD, "daily accruals", String.join("  ", daily));
            out.printf(FIELD, "sum of daily accruals", total.format());
            out.printf(FIELD, "capitalised credit", capitalised.format());
            out.printf(FIELD, "sum equals capitalised",
                    total.equals(capitalised) ? "yes" : "NO - INVARIANT BROKEN");
        }
        out.printf("  %d error(s) recorded across the window%n", ledger.errors().size());
    }
}
