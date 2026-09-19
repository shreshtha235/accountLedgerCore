package com.ledger;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Ledger ledger = new LedgerEngine(EventStream.accounts(), LedgerPolicy.defaults())
                .replay(EventStream.events(), EventStream.FIRST_DAY, EventStream.LAST_DAY);

        ReportPrinter.print(ledger, EventStream.FIRST_DAY, EventStream.LAST_DAY, System.out);
    }
}
