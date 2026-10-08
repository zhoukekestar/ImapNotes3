package de.niendo.ImapNotes3.Miscs;

import java.util.Locale;

/** Defaults for providers that use an IMAP authorization code. */
public enum MailProviderPreset {
    NETEASE_163("163.com", "imap.163.com", "163", "https://mail.163.com/"),
    QQ("qq.com", "imap.qq.com", "QQ", "https://help.mail.qq.com/detail/106/985");

    public final String domain;
    public final String server;
    public final String label;
    public final String helpUrl;

    MailProviderPreset(String domain, String server, String label, String helpUrl) {
        this.domain = domain;
        this.server = server;
        this.label = label;
        this.helpUrl = helpUrl;
    }

    public static MailProviderPreset forEmail(String email) {
        String domain = domainOf(email);
        for (MailProviderPreset preset : values()) if (preset.domain.equals(domain)) return preset;
        return null;
    }

    public static String domainOf(String email) {
        String address = email.trim().toLowerCase(Locale.ROOT);
        int at = address.indexOf('@');
        if (at <= 0 || at != address.lastIndexOf('@') || at == address.length() - 1) return "";
        String domain = address.substring(at + 1);
        return domain.contains(" ") ? "" : domain;
    }
}
