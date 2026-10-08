package de.niendo.ImapNotes3.Miscs;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class GoogleAuthErrorTest {
    @Test public void wrappedRegistrationFailureIsNotShownAsNetworkFailure() {
        assertEquals(GoogleAuthError.Kind.REGISTRATION, GoogleAuthError.classify(
                new IOException("Token request failed", new Exception("UNREGISTERED_ON_API_CONSOLE"))));
    }
    @Test public void missingConsentIsNotShownAsNetworkFailure() {
        assertEquals(GoogleAuthError.Kind.CONSENT, GoogleAuthError.classify(
                new IOException("Google authorization required; reopen account settings")));
    }
    @Test public void unsupportedProviderIsNotShownAsNetworkFailure() {
        assertEquals(GoogleAuthError.Kind.UNSUPPORTED_PROVIDER, GoogleAuthError.classify(
                new IOException("Unsupported Google authenticator")));
    }
    @Test public void networkFailureIsRecognizedThroughAuthenticatorWrapper() {
        assertEquals(GoogleAuthError.Kind.NETWORK, GoogleAuthError.classify(
                new Exception("Authenticator failed", new Exception("NetworkError"))));
    }
    @Test public void ioFailureIsNetworkFailure() {
        assertEquals(GoogleAuthError.Kind.NETWORK, GoogleAuthError.classify(new IOException("Timed out")));
    }
    @Test public void emptyFailureNeedsConsent() {
        assertEquals(GoogleAuthError.Kind.CONSENT, GoogleAuthError.classify(new Exception()));
    }
}
