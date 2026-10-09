package de.niendo.ImapNotes3.Miscs;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import de.niendo.ImapNotes3.*;
import de.niendo.ImapNotes3.Data.*;
import de.niendo.ImapNotes3.Sync.SyncUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import jp.wasabeef.richeditor.RichEditor;

/** Opt-in device probe: touches only its own newly created note, using an already saved account. */
final class EditorProbe {
    static Bundle run(Instrumentation test, String server) {
        Bundle result = new Bundle();
        int passed = 0;
        NoteDetailActivity activity = null;
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = test.getUiAutomation().getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            test.getUiAutomation().setServiceInfo(info);
            AccountManager accounts = AccountManager.get(test.getTargetContext());
            Account account = Arrays.stream(accounts.getAccountsByType(Utilities.PackageName))
                    .filter(a -> server == null || server.equals(accounts.getUserData(a, ConfigurationFieldNames.Server)))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Requires saved test account"));
            String title = "ImapNotes3 Editor test " + System.currentTimeMillis();
            String markdown = "# " + title + "\n\n**中文与 emoji ✅**\n\n- [x] 创建\n- [ ] 同步\n\n" +
                    "| 格式 | 结果 |\n| --- | --- |\n| Markdown | 正常 |\n\n```html\n<div>source</div>\n```\n";
            activity = openNew(test, account);
            final NoteDetailActivity firstEditor = activity;
            waitUntil(test, () -> enabled(firstEditor, R.id.editorModeButton));
            require(js(test, firstEditor, "RE.editor.contentEditable").equals("\"true\""), "New note editable");
            Rect bounds = new Rect();
            test.waitForIdleSync();
            test.runOnMainSync(() -> firstEditor.findViewById(R.id.bodyView).getGlobalVisibleRect(bounds));
            tap(test, bounds.left + bounds.width() / 3, bounds.top + 70);
            require(js(test, firstEditor, "document.activeElement.id").equals("\"editor\""), "Tap focuses rich editor");
            test.sendStringSync(title);
            require(js(test, firstEditor, "RE.editor.innerText").contains(title), "Tap and keyboard input");
            passed++;
            activity = recreate(test, firstEditor);
            final NoteDetailActivity editor = activity;
            waitUntil(test, () -> enabled(editor, R.id.editorModeButton));
            require(js(test, editor, "RE.editor.innerText").contains(title), "Unsaved rich draft survives recreation");
            passed++;
            click(test, editor, R.id.editorHtml);
            waitUntil(test, () -> visible(editor, R.id.editorSource) && enabled(editor, R.id.editorModeButton));
            require(source(editor).getText().toString().contains(title), "Rich to HTML retains input");
            String html = "<h1>" + title + "</h1><p><b>HTML source saved</b> 中文 ✅</p><input type='checkbox'> ";
            test.runOnMainSync(() -> {
                source(editor).setText(html.trim());
                source(editor).requestFocus();
                source(editor).setSelection(source(editor).length());
            });
            test.sendStringSync(" ");
            require(source(editor).getText().toString().equals(html), "Source accepts keyboard input");
            click(test, editor, R.id.editorPreview);
            waitUntil(test, () -> visible(editor, R.id.bodyView) && enabled(editor, R.id.editorModeButton));
            require(js(test, editor, "RE.editor.querySelector('b').textContent").contains("HTML source saved"), "HTML preview");
            require(js(test, editor, "RE.editor.contentEditable").equals("\"false\""), "Preview read only");
            passed++;
            click(test, editor, R.id.editorPreview);
            waitUntil(test, () -> visible(editor, R.id.editorSource) && enabled(editor, R.id.editorModeButton));
            require(source(editor).getText().toString().equals(html), "HTML source retained");
            saveMenu(test);
            waitUntil(test, editor::isFinishing);
            OneNote htmlNote = find(test, account, title);
            String saved = NoteMime.html(SyncUtils.ReadMailFromFileRootAndNew(htmlNote.GetUid(), ImapNotes3.GetAccountDir(account.name)));
            require(saved.contains("HTML source saved"), "HTML saved locally");
            passed++;
            htmlNote = awaitSynced(test, account, title);
            activity = openExisting(test, account, htmlNote);
            final NoteDetailActivity reopened = activity;
            waitUntil(test, () -> enabled(reopened, R.id.editorModeButton));
            require(js(test, reopened, "RE.editor.contentEditable").equals("\"false\""), "Existing note read only");
            test.runOnMainSync(() -> require(reopened.findViewById(R.id.editorTools).getVisibility() == View.GONE, "Readonly toolbar hidden"));
            String before = js(test, reopened, "RE.editor.innerHTML");
            String rect = js(test, reopened, "JSON.stringify((function(){let r=RE.editor.querySelector('input').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,w:innerWidth}})())");
            org.json.JSONObject point = new org.json.JSONObject((String) new org.json.JSONTokener(rect).nextValue());
            test.runOnMainSync(() -> reopened.findViewById(R.id.bodyView).getGlobalVisibleRect(bounds));
            double scale = bounds.width() / point.getDouble("w");
            tap(test, bounds.left + (int)(point.getDouble("x") * scale), bounds.top + (int)(point.getDouble("y") * scale));
            require(js(test, reopened, "RE.editor.querySelector('input').checked").equals("false"), "Readonly checkbox unchanged");
            require(before.equals(js(test, reopened, "RE.editor.innerHTML")), "Readonly content unchanged");
            passed++;
            click(test, reopened, R.id.editorModeButton);
            waitUntil(test, () -> enabled(reopened, R.id.editorModeButton));
            require(js(test, reopened, "RE.editor.contentEditable").equals("\"true\""), "Edit switch enables input");
            test.runOnMainSync(() -> reopened.findViewById(R.id.bodyView).getGlobalVisibleRect(bounds));
            tap(test, bounds.left + 120, bounds.top + 80);
            test.sendStringSync("typed ");
            require(js(test, reopened, "RE.editor.innerText").contains("typed"), "Reopened note accepts keyboard input");
            passed++;
            click(test, reopened, R.id.editorMarkdown);
            waitUntil(test, () -> visible(reopened, R.id.editorSource) && enabled(reopened, R.id.editorModeButton));
            test.runOnMainSync(() -> source(reopened).setText(markdown));
            click(test, reopened, R.id.editorPreview);
            waitUntil(test, () -> visible(reopened, R.id.bodyView) && enabled(reopened, R.id.editorModeButton));
            require(js(test, reopened, "RE.editor.querySelector('table') !== null && RE.editor.querySelector('code') !== null && RE.editor.querySelector('strong') !== null").equals("true"), "Markdown rendering");
            passed++;
            click(test, reopened, R.id.editorPreview);
            waitUntil(test, () -> visible(reopened, R.id.editorSource) && enabled(reopened, R.id.editorModeButton));
            require(source(reopened).getText().toString().equals(markdown), "Verbatim Markdown after preview");
            click(test, reopened, R.id.editorHtml);
            waitUntil(test, () -> visible(reopened, R.id.editorSource) && enabled(reopened, R.id.editorModeButton));
            click(test, reopened, R.id.editorMarkdown);
            waitUntil(test, () -> visible(reopened, R.id.editorSource) && enabled(reopened, R.id.editorModeButton));
            require(source(reopened).getText().toString().equals(markdown), "Verbatim Markdown after format switch");
            click(test, reopened, R.id.editorModeButton);
            waitUntil(test, () -> enabled(reopened, R.id.editorModeButton));
            test.runOnMainSync(() -> require(source(reopened).getKeyListener() == null, "Readonly source"));
            require(source(reopened).getText().toString().equals(markdown), "Reading retains draft");
            passed++;
            saveMenu(test);
            waitUntil(test, reopened::isFinishing);
            OneNote mdNote = find(test, account, title);
            saved = NoteMime.html(SyncUtils.ReadMailFromFileRootAndNew(mdNote.GetUid(), ImapNotes3.GetAccountDir(account.name)));
            require(new NoteEditorDocument(saved).markdown().equals(markdown), "Markdown saved verbatim");
            passed++;
            mdNote = awaitSynced(test, account, title);
            activity = openExisting(test, account, mdNote);
            final NoteDetailActivity finalView = activity;
            waitUntil(test, () -> enabled(finalView, R.id.editorModeButton));
            click(test, finalView, R.id.editorMarkdown);
            waitUntil(test, () -> visible(finalView, R.id.editorSource) && enabled(finalView, R.id.editorModeButton));
            require(source(finalView).getText().toString().equals(markdown), "Markdown reopened verbatim");
            require(((TextView) finalView.findViewById(R.id.editorStatus)).getText().toString().equals(finalView.getString(R.string.editor_read_only)), "Opening formats does not dirty note");
            passed++;
            click(test, finalView, R.id.editorModeButton);
            waitUntil(test, () -> enabled(finalView, R.id.editorModeButton));
            test.runOnMainSync(() -> source(finalView).append("\nUnsaved lifecycle draft"));
            activity = recreate(test, finalView);
            final NoteDetailActivity retained = activity;
            waitUntil(test, () -> visible(retained, R.id.editorSource) && enabled(retained, R.id.editorModeButton));
            require(source(retained).getText().toString().equals(markdown + "\nUnsaved lifecycle draft"), "Unsaved Markdown draft survives recreation");
            require(source(retained).getKeyListener() != null, "Recreated source remains editable");
            passed++;
            test.runOnMainSync(() -> source(retained).setText(markdown));
            saveMenu(test);
            waitUntil(test, retained::isFinishing);
            awaitSynced(test, account, title);
            Bundle synced = de.niendo.ImapNotes3.Sync.NoteSyncProbe.run(test, "editor", server, title);
            require(synced.getBoolean("passed"), "Production editor sync: " + synced.getString("result"));
            result.putString("sync", synced.getString("editorSync"));
            passed++;
            result.putBoolean("passed", true);
            result.putString("fixtureTitle", title);
            result.putString("result", "PASS: real keyboard input, readonly guard, rich/HTML/Markdown preview, save and reopen");
        } catch (Exception | AssertionError error) {
            result.putBoolean("passed", false);
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            if (error.getStackTrace().length > 0) result.putString("failureLocation", error.getStackTrace()[0].toString());
        } finally {
            result.putInt("checksPassed", passed);
            if (activity != null) { NoteDetailActivity closing = activity; test.runOnMainSync(closing::finish); }
        }
        return result;
    }
    private static NoteDetailActivity openNew(Instrumentation test, Account account) {
        return (NoteDetailActivity) test.startActivitySync(new Intent(test.getTargetContext(), NoteDetailActivity.class)
                .putExtra(NoteDetailActivity.ActivityType, NoteDetailActivity.ActivityTypeAdd)
                .putExtra(ListActivity.EDIT_ITEM_ACCOUNTNAME, account.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private static NoteDetailActivity recreate(Instrumentation test, NoteDetailActivity original) throws Exception {
        CountDownLatch resumed = new CountDownLatch(1);
        AtomicReference<NoteDetailActivity> replacement = new AtomicReference<>();
        android.app.Application app = (android.app.Application) test.getTargetContext().getApplicationContext();
        android.app.Application.ActivityLifecycleCallbacks callbacks = new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(android.app.Activity a) {
                if (a instanceof NoteDetailActivity && a != original) { replacement.set((NoteDetailActivity) a); resumed.countDown(); }
            }
            @Override public void onActivityCreated(android.app.Activity a, Bundle state) { }
            @Override public void onActivityStarted(android.app.Activity a) { }
            @Override public void onActivityPaused(android.app.Activity a) { }
            @Override public void onActivityStopped(android.app.Activity a) { }
            @Override public void onActivitySaveInstanceState(android.app.Activity a, Bundle state) { }
            @Override public void onActivityDestroyed(android.app.Activity a) { }
        };
        test.runOnMainSync(() -> { app.registerActivityLifecycleCallbacks(callbacks); original.recreate(); });
        try { require(resumed.await(30, TimeUnit.SECONDS), "Activity recreation"); }
        finally { test.runOnMainSync(() -> app.unregisterActivityLifecycleCallbacks(callbacks)); }
        return replacement.get();
    }
    private static NoteDetailActivity openExisting(Instrumentation test, Account account, OneNote note) {
        HashMap<String, String> selected = new HashMap<>();
        selected.put(OneNote.UID, note.GetUid()); selected.put(OneNote.ACCOUNT, account.name);
        return (NoteDetailActivity) test.startActivitySync(new Intent(test.getTargetContext(), NoteDetailActivity.class)
                .putExtra(NoteDetailActivity.ActivityType, NoteDetailActivity.ActivityTypeEdit)
                .putExtra(NoteDetailActivity.selectedNote, selected).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private static OneNote find(Instrumentation test, Account account, String title) {
        ArrayList<OneNote> notes = new ArrayList<>();
        NotesDb.getInstance(test.getTargetContext()).GetStoredNotes(notes, account.name, "date DESC", null);
        return notes.stream().filter(n -> title.equals(n.GetTitle())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Saved fixture missing"));
    }
    private static OneNote awaitSynced(Instrumentation test, Account account, String title) {
        de.niendo.ImapNotes3.Sync.NoteSyncProbe.sync(test.getTargetContext(), account);
        long deadline = SystemClock.uptimeMillis() + 60000;
        do {
            try {
                OneNote note = find(test, account, title);
                if (!note.GetUid().startsWith("-") && OneNote.SAVE_STATE_OK.equals(note.GetState())) return note;
            } catch (IllegalStateException ignored) { }
            SystemClock.sleep(250);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException("Editor fixture upload timeout");
    }
    private static EditText source(NoteDetailActivity activity) { return activity.findViewById(R.id.editorSource); }
    private static boolean visible(NoteDetailActivity a, int id) { return a.findViewById(id).getVisibility() == View.VISIBLE; }
    private static boolean enabled(NoteDetailActivity a, int id) { return a.findViewById(id).isEnabled(); }
    private static void click(Instrumentation test, NoteDetailActivity a, int id) { test.runOnMainSync(() -> a.findViewById(id).performClick()); }
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static void waitUntil(Instrumentation test, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 30000;
        do {
            AtomicBoolean ready = new AtomicBoolean(); test.runOnMainSync(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException("Editor transition timeout");
    }
    private static String js(Instrumentation test, NoteDetailActivity a, String code) throws Exception {
        AtomicReference<String> result = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
        test.runOnMainSync(() -> ((RichEditor) a.findViewById(R.id.bodyView)).evaluateJavascript(code, value -> { result.set(value); done.countDown(); }));
        if (!done.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("DOM callback timeout");
        return result.get();
    }
    private static void tap(Instrumentation test, int x, int y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        test.getUiAutomation().injectInputEvent(down, true); test.getUiAutomation().injectInputEvent(up, true);
        down.recycle(); up.recycle(); test.waitForIdleSync();
    }
    private static void saveMenu(Instrumentation test) {
        test.waitForIdleSync();
        android.view.accessibility.AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
        require(root != null, "Editor accessibility window");
        java.util.List<android.view.accessibility.AccessibilityNodeInfo> matches =
                root.findAccessibilityNodeInfosByViewId(Utilities.PackageName + ":id/save");
        require(!matches.isEmpty() && matches.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK), "Click save action");
    }
}
