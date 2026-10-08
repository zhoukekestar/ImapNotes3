# ImapNotes3 1.4.8-microg.1

- Gmail sign-in through the system Google authenticator, including standard microG, without the Google sign-in SDK.
- Preserve MIME attachments, content IDs and alternative bodies when editing. Open attachments from the note menu.
- Queue local edits durably before closing the editor. Retain edits when local saving fails.
- Retry uploads using a persistent operation ID. Retire the old message only after acknowledgement and matching base content in the same UID namespace.
- Keep both versions if another device changed the original. Preserve local queued notes when the server changes UIDVALIDITY.
- Restrict account removal to that account's cache. Do not cancel active uploads on manual refresh.
- Try syncing every minute while the list is in the foreground. Background sync remains subject to Android scheduling.

Android 7.1 or newer. Package: `io.github.zhoukekestar.imapnotes3`.

System microG consent, Gmail sign-in and Apple Notes round trips require device testing. ReVanced GmsCore is not supported. Native iCloud-only Apple Notes features cannot be added by this IMAP client.

This rebuild uses a newly generated signing certificate because the previous environment's signing key was unavailable. Installing over an older APK requires the same certificate; back up your notes before removing an incompatible older installation.
