package de.niendo.ImapNotes3.Sync;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.mail.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SelectiveExpungeTest {
    @Test public void toleratesAdvertisedUidPlusWithRejectedCommand() throws Exception {
        check(true, "BAD Parse command error", false);
    }

    @Test public void selectivelyExpungesWhenSupported() throws Exception {
        check(true, "OK Expunged", false);
    }

    @Test public void leavesDeletedFlagsWhenUidPlusIsAbsent() throws Exception {
        check(false, "", false);
    }

    @Test public void preservesPermissionRefusalsForRetry() throws Exception {
        check(true, "NO Permission denied", true);
    }

    @Test public void preservesConnectionFailuresForRetry() throws Exception {
        check(true, "disconnect", true);
    }

    private void check(boolean uidPlus, String response, boolean shouldFail) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<List<String>> transcript = executor.submit(() -> {
                List<String> commands = new ArrayList<>();
                boolean deleted = false;
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                    String capability = "IMAP4rev1 UNSELECT" + (uidPlus ? " UIDPLUS" : "");
                    output.print("* OK [CAPABILITY " + capability + "] Fixture ready\r\n"); output.flush();
                    for (String line; (line = input.readLine()) != null;) {
                        String[] fields = line.split(" ", 3);
                        String tag = fields[0], command = fields[1];
                        commands.add(command + (fields.length > 2 ? " " + fields[2] : ""));
                        switch (command) {
                            case "CAPABILITY": output.print("* CAPABILITY " + capability + "\r\n" + tag + " OK Capability complete\r\n"); break;
                            case "LOGIN": output.print(tag + " OK Logged in\r\n"); break;
                            case "SELECT":
                                output.print("* FLAGS (\\Seen \\Deleted)\r\n* 2 EXISTS\r\n* 0 RECENT\r\n* OK [UIDVALIDITY 1]\r\n* OK [UIDNEXT 12]\r\n" + tag + " OK [READ-WRITE] Selected\r\n"); break;
                            case "STORE":
                                assertTrue("Only the selected message may be marked deleted", fields[2].startsWith("1 "));
                                assertTrue(fields[2].contains("\\Deleted"));
                                deleted = true;
                                output.print("* 1 FETCH (FLAGS (\\Deleted))\r\n" + tag + " OK Stored\r\n"); break;
                            case "FETCH":
                                output.print("* 1 FETCH (UID 10 FLAGS (" + (deleted ? "\\Deleted" : "") + "))\r\n" + tag + " OK Fetched\r\n"); break;
                            case "UID":
                                assertTrue(deleted);
                                assertEquals("EXPUNGE 10", fields[2]);
                                if ("disconnect".equals(response)) return commands;
                                if (response.startsWith("OK")) output.print("* 1 EXPUNGE\r\n");
                                output.print(tag + " " + response + "\r\n"); break;
                            case "UNSELECT": output.print(tag + " OK Unselected\r\n"); break;
                            case "LOGOUT": output.print("* BYE Logout\r\n" + tag + " OK Logout complete\r\n"); output.flush(); return commands;
                            case "EXPUNGE": throw new AssertionError("Global EXPUNGE could delete unrelated messages");
                            default: throw new AssertionError("Unexpected fixture command: " + command);
                        }
                        output.flush();
                    }
                }
                return commands;
            });
            try {
                Properties properties = new Properties();
                properties.setProperty("mail.imap.timeout", "5000");
                IMAPStore store = (IMAPStore) Session.getInstance(properties).getStore("imap");
                try {
                    store.connect("127.0.0.1", server.getLocalPort(), "fixture", "fixture-password");
                    IMAPFolder notes = (IMAPFolder) store.getFolder("Notes");
                    notes.open(Folder.READ_WRITE);
                    Message[] selected = {notes.getMessage(1)};
                    notes.setFlags(selected, new Flags(Flags.Flag.DELETED), true);
                    boolean failed = false;
                    try { SelectiveExpunge.expunge(notes, selected); }
                    catch (MessagingException expected) { failed = true; }
                    assertEquals("Only unsupported commands may be tolerated", shouldFail, failed);
                    if (notes.isOpen()) notes.close(false);
                } finally { store.close(); }
                List<String> commands = transcript.get(5, TimeUnit.SECONDS);
                assertEquals(uidPlus, commands.contains("UID EXPUNGE 10"));
                assertTrue(commands.stream().anyMatch(command -> command.startsWith("STORE 1 ")));
                assertFalse(commands.contains("EXPUNGE"));
            } finally { executor.shutdownNow(); }
        }
    }
}
