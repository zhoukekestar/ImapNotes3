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

public class UploadIdLookupTest {
    private static final String ID = "fixture-upload-id";

    @Test public void usesAnExactIndexedMatch() throws Exception { check(false, false, false, ID, 102); }
    @Test public void findsAnUploadMissingFromTheSearchIndex() throws Exception { check(true, false, false, ID, 102); }
    @Test public void excludesDeletedUploads() throws Exception { check(true, false, true, ID, -1); }
    @Test public void excludesOrdinaryQqDrafts() throws Exception { check(true, true, false, "other", -1); }
    @Test public void rejectsPrefixMatches() throws Exception { check(false, false, false, ID + "-other", -1); }

    private void check(boolean missingIndex, boolean notesOnly, boolean deleted,
                       String secondId, long expected) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<List<String>> transcript = executor.submit(() -> {
                List<String> commands = new ArrayList<>();
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    OutputStream output = socket.getOutputStream();
                    write(output, "* OK [CAPABILITY IMAP4rev1 UNSELECT] Fixture ready\r\n");
                    for (String line; (line = input.readLine()) != null;) {
                        String[] fields = line.split(" ", 3);
                        String tag = fields[0], command = fields[1];
                        commands.add(command);
                        switch (command) {
                            case "CAPABILITY": write(output, "* CAPABILITY IMAP4rev1 UNSELECT\r\n" + tag + " OK Capability complete\r\n"); break;
                            case "LOGIN": write(output, tag + " OK Logged in\r\n"); break;
                            case "SELECT":
                                write(output, "* FLAGS (\\Seen \\Deleted)\r\n* 2 EXISTS\r\n* 0 RECENT\r\n* OK [UIDVALIDITY 1]\r\n* OK [UIDNEXT 103]\r\n" + tag + " OK [READ-WRITE] Selected\r\n"); break;
                            case "SEARCH":
                                assertTrue(fields[2].contains("HEADER"));
                                write(output, "* SEARCH" + (missingIndex ? "" : " 2") + "\r\n" + tag + " OK Search complete\r\n"); break;
                            case "FETCH":
                                assertTrue("Lookup must fetch only header metadata", fields[2].contains("HEADER.FIELDS"));
                                assertFalse(fields[2].contains("BODY.PEEK[]"));
                                assertFalse(fields[2].contains("BODY.PEEK[TEXT]"));
                                String selected = fields[2].split(" ", 2)[0];
                                if (includes(selected, 1)) {
                                    header(output, 1, 101, notesOnly ? ID : "other", false, false, notesOnly);
                                }
                                if (includes(selected, 2)) header(output, 2, 102, secondId, deleted, true, notesOnly);
                                write(output, tag + " OK Fetched\r\n"); break;
                            case "UNSELECT": write(output, tag + " OK Unselected\r\n"); break;
                            case "LOGOUT": write(output, "* BYE Logout\r\n" + tag + " OK Logout complete\r\n"); return commands;
                            default: throw new AssertionError("Unexpected fixture command: " + command);
                        }
                    }
                }
                return commands;
            });
            try {
                Properties properties = new Properties();
                properties.setProperty("mail.imap.timeout", "5000");
                IMAPStore store = (IMAPStore) Session.getInstance(properties).getStore("imap");
                long actual;
                try {
                    store.connect("127.0.0.1", server.getLocalPort(), "fixture", "fixture-password");
                    IMAPFolder notes = (IMAPFolder) store.getFolder("Notes");
                    notes.open(Folder.READ_WRITE);
                    actual = UploadIdLookup.find(notes, ID, notesOnly);
                    notes.close(false);
                } finally { store.close(); }
                List<String> commands = transcript.get(5, TimeUnit.SECONDS);
                assertEquals(expected, actual);
                assertTrue(commands.contains("SEARCH"));
                assertTrue(commands.contains("FETCH"));
                assertFalse(commands.contains("APPEND"));
                assertFalse(commands.contains("STORE"));
            } finally { executor.shutdownNow(); }
        }
    }

    private static void header(OutputStream output, int sequence, long uid, String id,
                               boolean deleted, boolean note, boolean notesOnly) throws IOException {
        String fields = "X-ImapNotes3-Upload-ID" + (notesOnly ? " X-Uniform-Type-Identifier" : "");
        String headers = "X-ImapNotes3-Upload-ID: " + id + "\r\n" +
                (notesOnly && note ? "X-Uniform-Type-Identifier: com.apple.mail-note\r\n" : "") + "\r\n";
        write(output, "* " + sequence + " FETCH (UID " + uid + " FLAGS (" +
                (deleted ? "\\Deleted" : "") + ") BODY[HEADER.FIELDS (" + fields + ")] {" +
                headers.getBytes(StandardCharsets.US_ASCII).length + "}\r\n" + headers + ")\r\n");
    }

    private static void write(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static boolean includes(String selected, int sequence) {
        for (String part : selected.split(",")) {
            String[] range = part.split(":");
            int first = Integer.parseInt(range[0]);
            int last = range.length == 1 ? first : Integer.parseInt(range[1]);
            if (first <= sequence && sequence <= last) return true;
        }
        return false;
    }
}
