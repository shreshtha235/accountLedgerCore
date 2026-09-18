package com.ledger;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        run("READING A: a day is assessed once, on that day, and never reopened (default)",
                LedgerPolicy.defaults());
        run("READING B: a backdated entry reopens earlier days for fee assessment",
                LedgerPolicy.defaults().withReopenClosedDays(true));
    }

    private static void run(String title, LedgerPolicy policy) {
        System.out.println();
        System.out.println("####  " + title);
        System.out.println();

        Ledger ledger = new LedgerEngine(EventStream.accounts(), policy)
                .replay(EventStream.events(), EventStream.FIRST_DAY, EventStream.LAST_DAY);

        ReportPrinter.print(ledger, EventStream.FIRST_DAY, EventStream.LAST_DAY, System.out);
    }
}
