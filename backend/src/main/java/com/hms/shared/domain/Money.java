package com.hms.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount in INR with exactly two decimal places, stored as {@code NUMERIC(14,2)}.
 *
 * <p>Construction never rounds: an amount with more than two decimals is rejected, so precision is
 * only ever lost in {@link #multiply} and {@link #percentage}, which round half-up. Callers round at
 * line level and sum the rounded lines; a total is never re-rounded.
 */
public final class Money implements Comparable<Money> {

    public static final int SCALE = 2;
    /** {@code NUMERIC(14,2)}: at most 12 integer digits. */
    private static final int MAX_INTEGER_DIGITS = 12;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE));

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException("Amount exceeds NUMERIC(14,2)");
        }
        this.amount = amount;
    }

    /** Exact amount; rejects more than two decimal places. */
    public static Money of(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        try {
            return new Money(amount.setScale(SCALE, RoundingMode.UNNECESSARY));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amount must have at most 2 decimal places", e);
        }
    }

    /** Parses a decimal string such as {@code "1250.00"}; rejects more than two decimal places. */
    public static Money of(String amount) {
        Objects.requireNonNull(amount, "amount");
        try {
            return of(new BigDecimal(amount));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a decimal amount", e);
        }
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    /** Line amount, e.g. unit price × quantity, rounded half-up to paise. */
    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor).setScale(SCALE, RoundingMode.HALF_UP));
    }

    /** {@code percent}% of this amount (e.g. tax on a line), rounded half-up to paise. */
    public Money percentage(BigDecimal percent) {
        return new Money(amount.multiply(percent).divide(HUNDRED, SCALE, RoundingMode.HALF_UP));
    }

    public BigDecimal amount() {
        return amount;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money other && amount.equals(other.amount);
    }

    @Override
    public int hashCode() {
        return amount.hashCode();
    }

    /** Plain decimal string with two places, the JSON representation ({@code "1250.00"}). */
    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
