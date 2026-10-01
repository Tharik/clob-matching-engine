package com.trading.clob.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {
    private static final Asset BRL = new Asset("BRL");
    private static final Asset BTC = new Asset("BTC");
    private final Account account = new Account("account");

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private void initialBalance() {
        account.creditAvailable(BRL, decimal("130.00"));
        account.reserve(BRL, decimal("30.00"));
    }

    private void assertBalance(String available, String reserved) {
        Balance balance = account.balanceOf(BRL);
        assertEquals(0, decimal(available).compareTo(balance.available()));
        assertEquals(0, decimal(reserved).compareTo(balance.reserved()));
        assertTrue(balance.available().signum() >= 0);
        assertTrue(balance.reserved().signum() >= 0);
    }

    private List<BiConsumer<Asset, BigDecimal>> operations() {
        return List.of(account::creditAvailable, account::debitAvailable, account::reserve,
                account::release, account::consumeReserved);
    }

    @Test
    void creditIncreasesAvailableAndLeavesReservedUnchanged() {
        initialBalance();
        BigDecimal reserved = account.balanceOf(BRL).reserved();
        account.creditAvailable(BRL, decimal("20.000"));
        assertBalance("120", "30");
        assertEquals(decimal("120.000"), account.balanceOf(BRL).available());
        assertSame(reserved, account.balanceOf(BRL).reserved());
    }

    @Test
    void debitDecreasesAvailableAndLeavesReservedUnchanged() {
        initialBalance();
        BigDecimal reserved = account.balanceOf(BRL).reserved();
        account.debitAvailable(BRL, decimal("20.000"));
        assertBalance("80", "30");
        assertEquals(decimal("80.000"), account.balanceOf(BRL).available());
        assertSame(reserved, account.balanceOf(BRL).reserved());
    }

    @Test
    void reserveMovesAvailableToReservedAndPreservesScale() {
        initialBalance();
        account.reserve(BRL, decimal("30.000"));
        assertBalance("70", "60");
        assertEquals(decimal("70.000"), account.balanceOf(BRL).available());
        assertEquals(decimal("60.000"), account.balanceOf(BRL).reserved());
    }

    @Test
    void releaseMovesReservedToAvailableAndPreservesScale() {
        initialBalance();
        account.release(BRL, decimal("20.000"));
        assertBalance("120", "10");
        assertEquals(decimal("120.000"), account.balanceOf(BRL).available());
        assertEquals(decimal("10.000"), account.balanceOf(BRL).reserved());
    }

    @Test
    void consumeReservedDoesNotIncreaseAvailable() {
        initialBalance();
        BigDecimal available = account.balanceOf(BRL).available();
        account.consumeReserved(BRL, decimal("20.000"));
        assertBalance("100", "10");
        assertSame(available, account.balanceOf(BRL).available());
        assertEquals(decimal("10.000"), account.balanceOf(BRL).reserved());
    }

    @Test
    void absentBalanceIsZeroAndCreditCreatesOnlyRequestedAsset() {
        assertEquals(0, account.balanceOf(BRL).available().signum());
        assertEquals(0, account.balanceOf(BRL).reserved().signum());
        assertTrue(account.balances().isEmpty());
        account.creditAvailable(BRL, decimal("20.000"));
        assertBalance("20", "0");
        assertEquals(decimal("20.000"), account.balanceOf(BRL).available());
        assertEquals(0, account.balanceOf(BTC).available().signum());
        assertEquals(1, account.balances().size());
        assertFalse(account.balances().containsKey(BTC));
    }

    @Test
    void insufficientAvailableRejectsDebitAndReserveWithoutMutation() {
        initialBalance();
        Balance original = account.balanceOf(BRL);
        var snapshot = account.balances();
        assertThrows(IllegalArgumentException.class, () -> account.debitAvailable(BRL, decimal("100.001")));
        assertThrows(IllegalArgumentException.class, () -> account.reserve(BRL, decimal("100.001")));
        assertSame(original, account.balanceOf(BRL));
        assertEquals(snapshot, account.balances());
    }

    @Test
    void insufficientReservedRejectsReleaseAndConsumptionWithoutMutation() {
        initialBalance();
        Balance original = account.balanceOf(BRL);
        var snapshot = account.balances();
        assertThrows(IllegalArgumentException.class, () -> account.release(BRL, decimal("30.001")));
        assertThrows(IllegalArgumentException.class, () -> account.consumeReserved(BRL, decimal("30.001")));
        assertSame(original, account.balanceOf(BRL));
        assertEquals(snapshot, account.balances());
    }

    @Test
    void insufficientAbsentBalancesDoNotCreateEntries() {
        var operations = List.<BiConsumer<Asset, BigDecimal>>of(account::debitAvailable,
                account::reserve, account::release, account::consumeReserved);
        for (var operation : operations) {
            assertThrows(IllegalArgumentException.class, () -> operation.accept(BRL, BigDecimal.ONE));
            assertTrue(account.balances().isEmpty());
        }
    }

    @Test
    void zeroAndNegativeAmountsRejectEveryOperationWithoutMutation() {
        initialBalance();
        Balance original = account.balanceOf(BRL);
        var snapshot = account.balances();
        for (var operation : operations()) {
            for (String invalid : new String[] {"0", "0.000", "-0.01"}) {
                assertThrows(IllegalArgumentException.class, () -> operation.accept(BRL, decimal(invalid)));
                assertThrows(IllegalArgumentException.class, () -> operation.accept(BTC, decimal(invalid)));
                assertSame(original, account.balanceOf(BRL));
                assertEquals(snapshot, account.balances());
            }
        }
    }

    @Test
    void nullInputsRejectEveryOperationWithoutMutation() {
        initialBalance();
        Balance original = account.balanceOf(BRL);
        var snapshot = account.balances();
        for (var operation : operations()) {
            assertThrows(NullPointerException.class, () -> operation.accept(null, BigDecimal.ONE));
            assertThrows(NullPointerException.class, () -> operation.accept(BRL, null));
            assertThrows(NullPointerException.class, () -> operation.accept(BTC, null));
            assertSame(original, account.balanceOf(BRL));
            assertEquals(snapshot, account.balances());
        }
    }

    @Test
    void exactAvailableAndReservedAmountsCanBeConsumedDespiteDifferentScale() {
        initialBalance();
        account.debitAvailable(BRL, decimal("100.000"));
        account.consumeReserved(BRL, decimal("30.0"));
        assertBalance("0", "0");
        account.creditAvailable(BRL, decimal("0.12345678901234567890123456789"));
        account.reserve(BRL, decimal("0.123456789012345678901234567890"));
        assertBalance("0", "0.12345678901234567890123456789");
        assertEquals(decimal("0.123456789012345678901234567890"), account.balanceOf(BRL).reserved());
        account.release(BRL, decimal("0.12345678901234567890123456789"));
        assertBalance("0.12345678901234567890123456789", "0");
    }

    @Test
    void operationsKeepOtherAssetsAndPreviousSnapshotsUnchanged() {
        initialBalance();
        account.creditAvailable(BTC, decimal("1.20"));
        account.reserve(BTC, decimal("0.20"));
        Balance other = account.balanceOf(BTC);
        var snapshot = account.balances();
        account.creditAvailable(BRL, decimal("20"));
        account.reserve(BRL, decimal("30"));
        account.release(BRL, decimal("10"));
        account.consumeReserved(BRL, decimal("10"));
        account.debitAvailable(BRL, decimal("5"));
        assertBalance("95", "40");
        assertSame(other, account.balanceOf(BTC));
        assertEquals(new Balance(decimal("100.00"), decimal("30.00")), snapshot.get(BRL));
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }
}
