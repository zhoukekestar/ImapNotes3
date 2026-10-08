package de.niendo.ImapNotes3.Miscs;

import java.util.concurrent.ConcurrentHashMap;

/** Serializes cache, local save and UID reconciliation for each account. */
public final class AccountLocks {
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    private AccountLocks() {}
    public static Object forAccount(String name) {
        return LOCKS.computeIfAbsent(name, ignored -> new Object());
    }
}
