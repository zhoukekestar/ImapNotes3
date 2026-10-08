package de.niendo.ImapNotes3.Sync;

import com.sun.mail.imap.IMAPStore;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.*;
import javax.mail.Folder;
import javax.mail.Session;
import org.junit.Test;
import static org.junit.Assert.*;

public class ImapClientIdentityTest {
    @Test public void identifiesAfterLoginBeforeSelectingANotesFolder() throws Exception {
        checkConversation(true);
    }

    @Test public void doesNotSendIdToServersWithoutThatCapability() throws Exception {
        checkConversation(false);
    }

    private void checkConversation(boolean supportsId) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> transcript = executor.submit(() -> {
                boolean identified = false;
                boolean loggedIn = false;
                String idCommand = "";
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                    String capability = "IMAP4rev1" + (supportsId ? " ID" : "");
                    output.print("* OK [CAPABILITY " + capability + "] Fixture ready\r\n"); output.flush();
                    for (String line; (line = input.readLine()) != null;) {
                        String[] fields = line.split(" ", 3);
                        String tag = fields[0], command = fields[1];
                        switch (command) {
                            case "CAPABILITY": output.print("* CAPABILITY " + capability + "\r\n" + tag + " OK Capability complete\r\n"); break;
                            case "LOGIN": loggedIn = true; output.print(tag + " OK Logged in\r\n"); break;
                            case "ID":
                                assertTrue("ID must follow login", loggedIn);
                                assertTrue("ID must be advertised", supportsId);
                                identified = true; idCommand = fields[2];
                                output.print("* ID NIL\r\n" + tag + " OK ID complete\r\n"); break;
                            case "SELECT":
                            case "EXAMINE":
                                assertTrue(loggedIn);
                                assertEquals("NetEase-style server requires ID before folder access", supportsId, identified);
                                output.print("* FLAGS (\\Seen)\r\n* 0 EXISTS\r\n* 0 RECENT\r\n* OK [UIDVALIDITY 1]\r\n* OK [UIDNEXT 1]\r\n" + tag + " OK [READ-WRITE] Selected\r\n"); break;
                            case "CLOSE": output.print(tag + " OK Closed\r\n"); break;
                            case "LOGOUT": output.print("* BYE Logout\r\n" + tag + " OK Logout complete\r\n"); output.flush(); return idCommand;
                            default: throw new AssertionError("Unexpected fixture command: " + command);
                        }
                        output.flush();
                    }
                    return idCommand;
                }
            });
            try {
                Properties properties = new Properties();
                properties.setProperty("mail.imap.timeout", "5000");
                IMAPStore store = (IMAPStore) Session.getInstance(properties).getStore("imap");
                try {
                    store.connect("127.0.0.1", server.getLocalPort(), "fixture", "fixture-password");
                    ImapClientIdentity.send(store, "test-version");
                    Folder notes = store.getFolder("Notes");
                    notes.open(Folder.READ_WRITE);
                    notes.close(false);
                } finally { store.close(); }
                String id = transcript.get(5, TimeUnit.SECONDS);
                if (supportsId) {
                    assertTrue(id.contains("ImapNotes3"));
                    assertTrue(id.contains("test-version"));
                    assertFalse(id.contains("fixture-password"));
                    assertFalse(id.contains("fixture\""));
                } else assertEquals("", id);
            } finally { executor.shutdownNow(); }
        }
    }
}
