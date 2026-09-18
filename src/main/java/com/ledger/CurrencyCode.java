package com.ledger;

public enum CurrencyCode {

    AED(2),
    BHD(3);

    private final int scale;
    private final long minorUnitsPerUnit;

    CurrencyCode(int scale) {
        long factor = 1L;
        for (int i = 0; i < scale; i++) {
            factor *= 10L;
        }
        this.scale = scale;
        this.minorUnitsPerUnit = factor;
    }

    public int scale() {
        return scale;
    }

    public long minorUnitsPerUnit() {
        return minorUnitsPerUnit;
    }
}
