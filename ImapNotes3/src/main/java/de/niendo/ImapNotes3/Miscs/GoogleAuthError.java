package de.niendo.ImapNotes3.Miscs;

import java.io.IOException;

/** Categorizes authenticator failures without displaying credentials or raw responses. */
final class GoogleAuthError {
    enum Kind { REGISTRATION, UNSUPPORTED_PROVIDER, SERVICE_UNAVAILABLE, NETWORK, CONSENT }

    static Kind classify(Throwable error) {
        boolean network = false;
        boolean unsupported = false;
        boolean consent = false;
        boolean serviceUnavailable = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage() == null ? "" : cause.getMessage();
            if (message.contains("UNREGISTERED_ON_API_CONSOLE")) return Kind.REGISTRATION;
            unsupported |= message.contains("Unsupported Google authenticator");
            consent |= message.contains("Google authorization required");
            serviceUnavailable |= message.equals("bind failure");
            network |= cause instanceof IOException || message.contains("NetworkError");
        }
        if (unsupported) return Kind.UNSUPPORTED_PROVIDER;
        if (serviceUnavailable) return Kind.SERVICE_UNAVAILABLE;
        if (consent) return Kind.CONSENT;
        return network ? Kind.NETWORK : Kind.CONSENT;
    }
}
