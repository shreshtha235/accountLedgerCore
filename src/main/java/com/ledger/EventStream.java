package com.ledger;

import java.util.List;

public final class EventStream {

    public static final String ACC_001 = "ACC-001";
    public static final String ACC_002 = "ACC-002";

    public static final int FIRST_DAY = 1;
    public static final int LAST_DAY = 6;

    private EventStream() {
    }

    public static List<Account> accounts() {
        return List.of(
                new Account(ACC_001, CurrencyCode.AED, aed("0.00")),
                new Account(ACC_002, CurrencyCode.BHD, bhd("0.000")));
    }

    public static List<LedgerEvent> events() {
        return List.of(
                new LedgerEvent.Credit("E1", 1, ACC_001, aed("1,200.00"), 1, 1),
                new LedgerEvent.Debit("E2", 1, ACC_001, aed("950.00"), 1),
                new LedgerEvent.Authorize("E3", 2, ACC_001, "Auth-A", aed("200.00"), 2),
                new LedgerEvent.Credit("E4", 3, ACC_001, aed("400.00"), 3, 1),
                new LedgerEvent.Settle("E5", 4, ACC_001, "Auth-A", aed("185.00"), 4),
                new LedgerEvent.Settle("E6", 4, ACC_001, "Auth-Z", aed("180.00"), 4),
                new LedgerEvent.Debit("E7", 5, ACC_001, aed("620.00"), 2),
                new LedgerEvent.Authorize("E8", 5, ACC_001, "Auth-B", aed("90.00"), 5),
                new LedgerEvent.Reversal("E9", 6, ACC_001, "E7", 2),
                new LedgerEvent.Credit("E10", 5, ACC_002, bhd("10.000"), 5, 3));
    }

    private static Money aed(String amount) {
        return Money.of(amount, CurrencyCode.AED);
    }

    private static Money bhd(String amount) {
        return Money.of(amount, CurrencyCode.BHD);
    }
}
