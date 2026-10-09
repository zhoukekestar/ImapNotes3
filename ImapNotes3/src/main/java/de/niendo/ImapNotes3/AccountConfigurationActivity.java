/*
 * Copyright (C) 2022-2025 - Peter Korf <peter@niendo.de>
 * Copyright (C)           - kwhitefoot
 * and Contributors.
 *
 * This file is part of ImapNotes3.
 *
 * ImapNotes3 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.niendo.ImapNotes3;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorActivity;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;

import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import de.niendo.ImapNotes3.Data.ConfigurationFieldNames;
import de.niendo.ImapNotes3.Data.ImapNotesAccount;
import de.niendo.ImapNotes3.Data.Security;
import de.niendo.ImapNotes3.Data.SyncInterval;
import de.niendo.ImapNotes3.Miscs.LoginThread;
import de.niendo.ImapNotes3.Miscs.MailProviderPreset;
import de.niendo.ImapNotes3.Miscs.Result;
import de.niendo.ImapNotes3.Miscs.SmtpServerNameFinder;
import de.niendo.ImapNotes3.Miscs.Utilities;
import eltos.simpledialogfragment.SimpleDialog;

import java.util.List;

public class AccountConfigurationActivity extends AccountAuthenticatorActivity implements OnItemSelectedListener, SimpleDialog.OnDialogResultListener, LoginThread.FinishListener {
    /**
     * Cannot be final or NonNull because it needs the application context which is not available
     * until onCreate.
     */

    public static final String ACTION = "ACTION";
    public static final String ACCOUNTNAME = "ACCOUNTNAME";
    public static final int TO_REFRESH = 999;
    public static final String AUTHORITY = Utilities.PackageName + ".provider";
    private static final String TAG = "IN_AccountConfActivity";


    @Nullable
    private Account myAccount = null;
    private AccountManager accountManager;
    private boolean googleLogin;
    private static final int GOOGLE_PICKER = 81;
    private static final int GOOGLE_CONSENT = 82;
    private boolean googleAuthorized;
    private boolean googleAuthorizationPending;
    private String googleAccountType;
    private boolean imapLoginPending;
    private LoginThread loginTask;
    private Button actionButton;
    private Button chooseGoogle;
    private Button deleteButton;
    private String lastDetectedDomain = "";
    private String lastAutoServer = "";
    private String lastAutoName = "";
    private final OnClickListener clickListenerRemove = v ->
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.account_remove_title)
                    .setMessage(R.string.account_remove_body)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.account_remove_button, (dialog, which) -> removeAccount())
                    .show();

    private void removeAccount() {
        accountManager.removeAccount(myAccount, null, null, null);
        ImapNotes3.ShowMessage(R.string.account_removed, accountnameTextView, 3);
        Intent intent = new Intent();
        intent.putExtra(ListActivity.EDIT_ITEM_ACCOUNTNAME, GetTextViewText(accountnameTextView));
        setResult(ListActivity.ResultCodeRemoveAccount, intent);
        finish();
    }
    private AppCompatDelegate mDelegate;
    private TextView accountnameTextView;
    private TextView usernameTextView;
    private TextView passwordTextView;
    private TextView serverTextView;
    private TextView portnumTextView;
    private Spinner syncIntervalSpinner;
    private TextView folderTextView;
    private TextView copyImapFolderNameTextView;
    private View expandMoreSettings;

    private void setAdvancedSettingsVisible(boolean expanded) {
        findViewById(R.id.ViewExtendedAccountSettings).setVisibility(expanded ? View.VISIBLE : View.GONE);
        findViewById(R.id.accountSettingsChevron).setRotation(expanded ? 180 : 0);
        androidx.core.view.ViewCompat.setStateDescription(expandMoreSettings,
                getString(expanded ? R.string.account_settings_expanded : R.string.account_settings_collapsed));
    }
    private final CheckBox.OnCheckedChangeListener FinishCopyFolderCheckBox = (v, r) -> {
        copyImapFolderNameTextView.setEnabled(v.isChecked());
        if (GetTextViewText(copyImapFolderNameTextView).isEmpty())
            copyImapFolderNameTextView.setText("Trash");
    };
    private Spinner securitySpinner;
    @NonNull
    private SyncInterval syncInterval = SyncInterval.t6h;
    @NonNull
    private Security security = Security.None;
    @Nullable
    private String accountname;

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        getDelegate().onPostCreate(savedInstanceState);
    }

    @Override
    public boolean onResult(@NonNull String dialogTag, int which, @NonNull Bundle extras) {
        return false;
    }

    public ActionBar getSupportActionBar() {
        return getDelegate().getSupportActionBar();
    }

    /* is this important?
    @Override
    @NonNull
    public MenuInflater getMenuInflater() {
        return getDelegate().getMenuInflater();
    }
  */
    //@Override
    public void onFinishPerformed(@NonNull Result<String> result) {
        if (isFinishing() || isDestroyed()) return;
        imapLoginPending = false;
        loginTask = null;
        setLoginLoading(0);
        if (result.succeeded) {
            Intent intent = new Intent();
            intent.putExtra(ListActivity.EDIT_ITEM_ACCOUNTNAME, GetTextViewText(accountnameTextView));
            setResult(ListActivity.ResultCodeSuccess, intent);
            Clear();
            finish();
        } else {
            setResult(ListActivity.ResultCodeError);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.IMAP_operation_failed)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(result.result)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        // Do nothing
                    })
                    .setNeutralButton(R.string.help, (dialog, which) -> {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.help)
                                .setIcon(android.R.drawable.ic_dialog_info)
                                .setMessage(R.string.imap_help_text)
                                .setPositiveButton(android.R.string.ok, (dialog1, which1) -> {
                                }).show();
                    })
                    .show();
            /*
            SimpleDialog.build()
                    .title("R.string.hello")
                    .msg("R.string.hello_world")
                    .show(ListActivity);

             */
        }

    }

    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        getDelegate().setSupportActionBar(toolbar);
    }

    @Override
    public void setContentView(@LayoutRes int layoutResID) {
        getDelegate().setContentView(layoutResID);
    }

    @Override
    public void setContentView(View view) {
        getDelegate().setContentView(view);
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        getDelegate().setContentView(view, params);
    }

    @Override
    public void addContentView(View view, ViewGroup.LayoutParams params) {
        getDelegate().addContentView(view, params);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        getDelegate().onPostResume();
    }

    @Override
    protected void onTitleChanged(CharSequence title, int color) {
        super.onTitleChanged(title, color);
        getDelegate().setTitle(title);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        getDelegate().onConfigurationChanged(newConfig);
    }

    @Override
    protected void onStop() {
        super.onStop();
        getDelegate().onStop();
    }

    @Override
    protected void onDestroy() {
        if (loginTask != null) loginTask.cancel(true);
        super.onDestroy();
        getDelegate().onDestroy();
    }

    public void invalidateOptionsMenu() {
        getDelegate().invalidateOptionsMenu();
    }

    private AppCompatDelegate getDelegate() {
        if (mDelegate == null) {
            mDelegate = AppCompatDelegate.create(this, null);
        }
        return mDelegate;
    }

    @Nullable
    private Actions action;

    private final OnClickListener clickListenerLogin = v -> {
        // Click on Login Button
        Log.d(TAG, "clickListenerLogin  onClick");
        CheckNameAndLogIn();
    };
    private final OnClickListener clickListenerSave = v -> {
        // Click on Save Button
        Log.d(TAG, "clickListenerSave onClick");
        CheckNameAndLogIn();
    };
    private final OnClickListener clickListenerAbort = v -> {
        // Click on Abort Button
        Log.d(TAG, "clickListenerAbort onClick");
        finish();
    };
    private CheckBox copyImapFolderCheckBox;

    private void CheckNameAndLogIn() {
        if (googleAuthorizationPending || imapLoginPending) return;
        String name = accountnameTextView.getText().toString();
        if (name.isEmpty()) {
            name = GetTextViewText(usernameTextView);
            accountnameTextView.setText(name);
            lastAutoName = name;
        }
        if (name.contains("'") || name.contains("\""))
            ImapNotes3.ShowMessage(R.string.quotation_marks_not_allowed, accountnameTextView, 3);
        else
            DoLogin();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        getDelegate().installViewFactory();
        getDelegate().onCreate(savedInstanceState);
        super.onCreate(savedInstanceState);
        setResult(ListActivity.ResultCodeNeutral);
        setContentView(R.layout.account_setup);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        accountnameTextView = findTextViewById(R.id.accountnameEdit);
        usernameTextView = findTextViewById(R.id.usernameEdit);
        passwordTextView = findTextViewById(R.id.passwordEdit);
        serverTextView = findTextViewById(R.id.serverEdit);
        portnumTextView = findTextViewById(R.id.portnumEdit);
        security = Security.SSL_TLS;
        portnumTextView.setText(security.defaultPort);
        syncIntervalSpinner = findViewById(R.id.syncintervalSpinner);
        List<String> listInterval = SyncInterval.Printables(getResources());
        ArrayAdapter<String> dataAdapterInterval = new ArrayAdapter<>
                (this, R.layout.ssl_spinner_item, listInterval);
        syncIntervalSpinner.setAdapter(dataAdapterInterval);
        syncIntervalSpinner.setSelection(SyncInterval.t6h.ordinal());
        syncIntervalSpinner.setOnItemSelectedListener(this);

        folderTextView = findTextViewById(R.id.folderEdit);
        folderTextView.setText("Notes");
        copyImapFolderNameTextView = findTextViewById(R.id.copyImapFolderName);
        copyImapFolderCheckBox = (CheckBox) findTextViewById(R.id.copyImapFolder);
        copyImapFolderCheckBox.setOnCheckedChangeListener(FinishCopyFolderCheckBox);
        copyImapFolderNameTextView.setEnabled(copyImapFolderCheckBox.isChecked());
        securitySpinner = findViewById(R.id.securitySpinner);
        List<String> list = Security.Printables(getResources());
        ArrayAdapter<String> dataAdapter = new ArrayAdapter<>
                (this, R.layout.ssl_spinner_item, list);
        securitySpinner.setAdapter(dataAdapter);
        securitySpinner.setOnItemSelectedListener(this);
        securitySpinner.setSelection(Security.SSL_TLS.ordinal());

        expandMoreSettings = findViewById(R.id.BtnExpandAccountSettings);
        expandMoreSettings.setOnClickListener(v -> setAdvancedSettingsVisible(
                findViewById(R.id.ViewExtendedAccountSettings).getVisibility() != View.VISIBLE));
        setAdvancedSettingsVisible(false);

        Bundle extras = getIntent().getExtras();
        // TODO: find out if extras can be null.
        if (extras != null) {
            if (extras.containsKey(ACTION)) {
                action = (Actions) (extras.getSerializable(ACTION));
            }
            if (extras.containsKey(ACCOUNTNAME)) {
                accountname = extras.getString(ACCOUNTNAME);
            }
        }

        googleAccountType = de.niendo.ImapNotes3.Miscs.GoogleAccountAuth.preferredAccountType(this);
        chooseGoogle = findViewById(R.id.googleChooseAccount);
        chooseGoogle.setOnClickListener(v -> {
            try {
                startActivityForResult(AccountManager.newChooseAccountIntent(null, null,
                        new String[]{de.niendo.ImapNotes3.Miscs.GoogleAccountAuth.preferredAccountType(this)},
                        getString(R.string.account_google_choose_body), null, null, null), GOOGLE_PICKER);
            } catch (Exception e) {
                showGoogleError();
            }
        });
        accountManager = AccountManager.get(getApplicationContext());
        Account[] accounts = accountManager.getAccountsByType(Utilities.PackageName);
        for (Account account : accounts) {
            if (account.name.equals(accountname)) {
                myAccount = account;
                break;
            }
        }

        // action can never be null
        if (myAccount == null) {
            action = Actions.CREATE_ACCOUNT;
        }

        if (action == Actions.EDIT_ACCOUNT) {
            // Here we have to edit an existing account
            accountnameTextView.setText(accountname);
            accountnameTextView.setEnabled(false);
            usernameTextView.setText(GetConfigValue(ConfigurationFieldNames.UserName));
            setGoogleMode("google".equals(GetConfigValue(ConfigurationFieldNames.Authentication)));
            String savedGoogleType = GetConfigValue(ConfigurationFieldNames.GoogleAccountType);
            googleAccountType = savedGoogleType == null || savedGoogleType.isEmpty() ?
                    de.niendo.ImapNotes3.Miscs.GoogleAccountAuth.ACCOUNT_TYPE : savedGoogleType;
            //passwordTextView.setText(accountManager.getPassword(myAccount));
            serverTextView.setText(GetConfigValue(ConfigurationFieldNames.Server));
            portnumTextView.setText(GetConfigValue(ConfigurationFieldNames.PortNumber));
            //Log.d(TAG, "Security: " + GetConfigValue(ConfigurationFieldNames.Security));
            security = Security.from(GetConfigValue(ConfigurationFieldNames.Security));
            securitySpinner.setSelection(security.ordinal());
            syncInterval = SyncInterval.from(GetConfigValue(ConfigurationFieldNames.SyncInterval));
            syncIntervalSpinner.setSelection(syncInterval.ordinal());
            folderTextView.setText(GetConfigValue(ConfigurationFieldNames.ImapFolder));
            copyImapFolderNameTextView.setText(GetConfigValue(ConfigurationFieldNames.copyImapFolderName));

            String myTempStr = GetConfigValue(ConfigurationFieldNames.copyImapFolder);
            copyImapFolderCheckBox.setChecked(myTempStr != null && myTempStr.equals("true") && !(GetConfigValue(ConfigurationFieldNames.copyImapFolderName).isEmpty()));

        }
        getSupportActionBar().setTitle(action == Actions.EDIT_ACCOUNT
                ? R.string.account_settings_title : R.string.account_add_title);
        actionButton = findViewById(R.id.accountLoginButton);
        actionButton.setText(action == Actions.EDIT_ACCOUNT ? R.string.account_save_button : R.string.account_connect_button);
        actionButton.setOnClickListener(action == Actions.EDIT_ACCOUNT ? clickListenerSave : clickListenerLogin);
        findViewById(R.id.accountCancelButton).setOnClickListener(clickListenerAbort);
        deleteButton = findViewById(R.id.removeAccountButton);
        deleteButton.setOnClickListener(clickListenerRemove);
        findViewById(R.id.removeAccountSection).setVisibility(action == Actions.EDIT_ACCOUNT ? View.VISIBLE : View.GONE);
        findViewById(R.id.useEmailLogin).setOnClickListener(v -> setGoogleMode(false));
        passwordTextView.setOnEditorActionListener((view, id, event) -> {
            if (id == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                CheckNameAndLogIn();
                return true;
            }
            return false;
        });
        if (savedInstanceState != null) {
            googleAccountType = savedInstanceState.getString("googleAccountType", googleAccountType);
            setGoogleMode(savedInstanceState.getBoolean("googleLogin", googleLogin));
            lastAutoServer = savedInstanceState.getString("lastAutoServer", "");
            lastDetectedDomain = savedInstanceState.getString("lastDetectedDomain", "");
            lastAutoName = savedInstanceState.getString("lastAutoName", "");
            setAdvancedSettingsVisible(savedInstanceState.getBoolean("advancedSettings", false));
        } else {
            lastDetectedDomain = MailProviderPreset.domainOf(GetTextViewText(usernameTextView));
            MailProviderPreset preset = MailProviderPreset.forEmail(GetTextViewText(usernameTextView));
            if (preset != null && preset.server.equals(GetTextViewText(serverTextView))) lastAutoServer = preset.server;
        }
        usernameTextView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) { updateEmailDefaults(); }
        });
        serverTextView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) { updateProviderHints(); }
        });
        folderTextView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) { updateSettingsSummary(); }
        });
        findViewById(R.id.authorizationHelp).setOnClickListener(v -> showAuthorizationHelp());
        updateEmailDefaults();

        // Don't display keyboard when on note detail, only if user touches the screen
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        );
    }

    private TextView findTextViewById(int id) {
        return findViewById(id);
    }

    private String GetConfigValue(@NonNull String name) {
        return accountManager.getUserData(myAccount, name);
    }

    private String GetTextViewText(@NonNull TextView textView) {
        return textView.getText().toString().trim();
    }

    private String GetCheckBoxValue(@NonNull CheckBox checkBox) {
        return checkBox.isChecked() ? "true" : "false";
    }

    private com.google.android.material.textfield.TextInputLayout inputLayout(int id) {
        return findViewById(id);
    }

    private void setGoogleMode(boolean enabled) {
        googleLogin = enabled;
        googleAuthorized = false;
        passwordTextView.setEnabled(!enabled);
        usernameTextView.setEnabled(!enabled);
        serverTextView.setEnabled(!enabled);
        securitySpinner.setEnabled(!enabled);
        portnumTextView.setEnabled(!enabled);
        if (enabled) {
            serverTextView.setText("imap.gmail.com");
            lastAutoServer = "imap.gmail.com";
            portnumTextView.setText("993");
            security = Security.SSL_TLS;
            securitySpinner.setSelection(security.ordinal());
        }
        lastDetectedDomain = "";
        updateEmailDefaults();
    }

    private void updateEmailDefaults() {
        String email = GetTextViewText(usernameTextView);
        MailProviderPreset preset = MailProviderPreset.forEmail(email);
        String domain = MailProviderPreset.domainOf(email);
        if (action == Actions.CREATE_ACCOUNT && (GetTextViewText(accountnameTextView).isEmpty()
                || GetTextViewText(accountnameTextView).equals(lastAutoName))) {
            lastAutoName = email;
            accountnameTextView.setText(email);
        }
        if (!googleLogin && !domain.isEmpty() && !domain.equals(lastDetectedDomain)) {
            String server = GetTextViewText(serverTextView);
            if (server.isEmpty() || server.equals(lastAutoServer)) {
                lastAutoServer = preset == null ? SmtpServerNameFinder.getSmtpServerName(email) : preset.server;
                serverTextView.setText(lastAutoServer);
                security = Security.SSL_TLS;
                securitySpinner.setSelection(security.ordinal());
                portnumTextView.setText(security.defaultPort);
            }
        }
        lastDetectedDomain = domain;
        updateProviderHints();
    }

    private void updateProviderHints() {
        String email = GetTextViewText(usernameTextView);
        String domain = MailProviderPreset.domainOf(email);
        MailProviderPreset preset = MailProviderPreset.forEmail(email);
        inputLayout(R.id.passwordInput).setHint(getString(preset == null ? R.string.password : R.string.authorization_code));
        inputLayout(R.id.passwordInput).setHelperText(action == Actions.EDIT_ACCOUNT ? getString(R.string.account_keep_password)
                : preset == null ? null : getString(R.string.authorization_code_hint));
        findViewById(R.id.authorizationHelp).setVisibility(preset != null && !googleLogin ? View.VISIBLE : View.GONE);
        TextView hint = findViewById(R.id.providerHint);
        hint.setVisibility(!domain.isEmpty() && !googleLogin ? View.VISIBLE : View.GONE);
        updateAccountPresentation(preset);
        String server = GetTextViewText(serverTextView);
        hint.setText(preset == MailProviderPreset.QQ && preset.server.equals(server) ? getString(R.string.qq_notes_storage_hint) : preset != null && preset.server.equals(server) ? getString(R.string.provider_configured, preset.label)
                : getString(server.equals(lastAutoServer) ? R.string.server_configured : R.string.server_custom_configured));
    }

    private void updateAccountPresentation(MailProviderPreset preset) {
        boolean google = googleLogin;
        boolean editing = action == Actions.EDIT_ACCOUNT;
        boolean canChooseGoogle = !editing || google || GetTextViewText(serverTextView).equals("imap.gmail.com");
        findViewById(R.id.googleAuthControls).setVisibility(canChooseGoogle ? View.VISIBLE : View.GONE);
        findViewById(R.id.emailDivider).setVisibility(!editing && !google ? View.VISIBLE : View.GONE);
        findViewById(R.id.usernameInput).setVisibility(google ? View.GONE : View.VISIBLE);
        findViewById(R.id.passwordInput).setVisibility(google ? View.GONE : View.VISIBLE);
        findViewById(R.id.googleSelectedAccount).setVisibility(google ? View.VISIBLE : View.GONE);
        findViewById(R.id.useEmailLogin).setVisibility(google ? View.VISIBLE : View.GONE);
        chooseGoogle.setText(google ? R.string.account_google_change : R.string.account_google_continue);
        ((TextView) findViewById(R.id.googleSelectedEmail)).setText(GetTextViewText(usernameTextView));
        ((TextView) findViewById(R.id.credentialsTitle)).setText(google ? R.string.account_google_title
                : editing ? R.string.account_login_details : R.string.account_email_login);
        TextView subtitle = findViewById(R.id.credentialsSubtitle);
        subtitle.setText(google ? R.string.account_google_no_password : R.string.account_email_support);
        subtitle.setVisibility(google || !editing ? View.VISIBLE : View.GONE);
        if (editing) {
            String label = google ? "Google" : preset != null ? preset.label : "";
            ((TextView) findViewById(R.id.heading)).setText(google ? getString(R.string.account_google_title)
                    : label.isEmpty() ? getString(R.string.account_generic_provider)
                    : getString(R.string.account_mail_provider, label));
            ((TextView) findViewById(R.id.accountSubtitle)).setText(GetTextViewText(usernameTextView));
            android.widget.ImageView icon = findViewById(R.id.accountHeaderIcon);
            icon.setImageResource(google ? R.drawable.ic_google : R.drawable.ic_account_mail);
            icon.setBackgroundResource(R.drawable.account_icon_background);
            int padding = Math.round(14 * getResources().getDisplayMetrics().density);
            icon.setPadding(padding, padding, padding, padding);
        }
        updateSettingsSummary();
    }

    private void updateSettingsSummary() {
        if (action == Actions.EDIT_ACCOUNT) {
            ((TextView) findViewById(R.id.accountSettingsSummary)).setText(getString(R.string.account_sync_summary,
                    GetTextViewText(folderTextView), getString(syncInterval.textID)));
        }
    }

    private void showAuthorizationHelp() {
        MailProviderPreset preset = MailProviderPreset.forEmail(GetTextViewText(usernameTextView));
        if (preset == null) return;
        new AlertDialog.Builder(this).setTitle(R.string.authorization_code_help)
                .setMessage(R.string.authorization_code_steps)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.open_mail_help, (dialog, which) ->
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(preset.helpUrl))))
                .show();
    }

    /** Zero clears loading; other values describe the current login stage. */
    private void setLoginLoading(int stage) {
        boolean busy = stage != 0;
        findViewById(R.id.loginProgressPanel).setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            ((TextView) findViewById(R.id.loginProgressText)).setText(stage);
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(usernameTextView.getWindowToken(), 0);
            findViewById(R.id.loginProgressPanel).post(() -> {
                View progress = findViewById(R.id.loginProgressPanel);
                progress.requestRectangleOnScreen(new android.graphics.Rect(0, 0, progress.getWidth(), progress.getHeight()), false);
            });
        }
        actionButton.setText(busy ? (stage == R.string.login_google ? R.string.login_authorizing_button : R.string.login_connecting_button)
                : (action == Actions.EDIT_ACCOUNT ? R.string.account_save_button : R.string.account_connect_button));
        actionButton.setEnabled(!busy);
        if (deleteButton != null) deleteButton.setEnabled(!busy);
        chooseGoogle.setEnabled(!busy);
        expandMoreSettings.setEnabled(!busy);
        accountnameTextView.setEnabled(!busy && action != Actions.EDIT_ACCOUNT);
        usernameTextView.setEnabled(!busy && !googleLogin);
        passwordTextView.setEnabled(!busy && !googleLogin);
        serverTextView.setEnabled(!busy && !googleLogin);
        portnumTextView.setEnabled(!busy && !googleLogin);
        securitySpinner.setEnabled(!busy && !googleLogin);
        syncIntervalSpinner.setEnabled(!busy);
        folderTextView.setEnabled(!busy);
        copyImapFolderCheckBox.setEnabled(!busy);
        copyImapFolderNameTextView.setEnabled(!busy && copyImapFolderCheckBox.isChecked());
        findViewById(R.id.authorizationHelp).setEnabled(!busy);
        findViewById(R.id.useEmailLogin).setEnabled(!busy);
    }

    // DoLogin method is defined in account_selection.xml (account_selection layout)
    private void DoLogin() {
        if (isFinishing() || isDestroyed() || googleAuthorizationPending || imapLoginPending) return;
        Log.d(TAG, "DoLogin");
        inputLayout(R.id.usernameInput).setError(null);
        inputLayout(R.id.passwordInput).setError(null);
        if (GetTextViewText(usernameTextView).isEmpty()) {
            inputLayout(R.id.usernameInput).setError(getString(R.string.login_email_required));
            return;
        }

        if (googleLogin && !googleAuthorized) {
            if (googleAuthorizationPending) return;
            googleAuthorizationPending = true;
            setLoginLoading(R.string.login_google);
            de.niendo.ImapNotes3.Miscs.GoogleAccountAuth.authorize(this,
                    GetTextViewText(usernameTextView), googleAccountType, GOOGLE_CONSENT, () -> {
                        if (isFinishing() || isDestroyed()) return;
                        googleAuthorizationPending = false;
                        googleAuthorized = true;
                        CheckNameAndLogIn();
                    }, message -> {
                        if (isFinishing() || isDestroyed()) return;
                        googleAuthorizationPending = false;
                        showGoogleError(message);
                    });
            return;
        }

        if (GetTextViewText(serverTextView).isEmpty()) {
            setAdvancedSettingsVisible(true);
            serverTextView.setError(getString(R.string.login_server_required));
            return;
        }
        int port;
        try { port = Integer.parseInt(GetTextViewText(portnumTextView)); }
        catch (NumberFormatException error) { port = 0; }
        if (port < 1 || port > 65535) {
            setAdvancedSettingsVisible(true);
            portnumTextView.setError(getString(R.string.login_port_invalid));
            return;
        }
        //password will not shown if account is edit and have to be loaded;
        String password = GetTextViewText(passwordTextView);
        if (!googleLogin && (action == Actions.EDIT_ACCOUNT) && (password.isEmpty())) {
            // Server name edited: new password required (avoid password spoofing)
            if (GetTextViewText(serverTextView).equals(GetConfigValue(ConfigurationFieldNames.Server))) {
                password = accountManager.getPassword(myAccount);
            } else {
                ImapNotes3.ShowMessage(R.string.imap_server_changed_new_password, accountnameTextView, 3);
                return;
            }
        }
        if (!googleLogin && (password == null || password.isEmpty())) {
            inputLayout(R.id.passwordInput).setError(getString(R.string.login_password_required));
            return;
        }

        final ImapNotesAccount ImapNotesAccount = new ImapNotesAccount(
                GetTextViewText(accountnameTextView),
                GetTextViewText(usernameTextView),
                password,
                GetTextViewText(serverTextView),
                GetTextViewText(portnumTextView),
                security,
                syncInterval,
                GetTextViewText(folderTextView),
                GetTextViewText(copyImapFolderNameTextView),
                GetCheckBoxValue(copyImapFolderCheckBox));
        ImapNotesAccount.googleOAuth = googleLogin;
        ImapNotesAccount.googleAccountType = googleAccountType;
        // No need to check for valid numbers because the field only allows digits.  But it is
        // possible to remove all characters which causes the program to crash.  The easiest fix is
        // to add a zero at the beginning so that we are guaranteed to be able to parse it but that
        // leaves us with a zero sync. interval.

        imapLoginPending = true;
        setLoginLoading(R.string.login_connecting);
        loginTask = new LoginThread(
                ImapNotesAccount,
                this,
                action);
        loginTask.execute();
    }

    private void showGoogleError() {
        showGoogleError(getString(R.string.google_auth_required));
    }

    private void showGoogleError(String message) {
        if (isFinishing() || isDestroyed()) return;
        googleAuthorizationPending = false;
        setLoginLoading(0);
        new AlertDialog.Builder(this).setMessage(message)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("googleAccountType", googleAccountType);
        state.putBoolean("googleLogin", googleLogin);
        state.putString("lastAutoServer", lastAutoServer);
        state.putString("lastDetectedDomain", lastDetectedDomain);
        state.putString("lastAutoName", lastAutoName);
        state.putBoolean("advancedSettings", findViewById(R.id.ViewExtendedAccountSettings).getVisibility() == View.VISIBLE);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == GOOGLE_PICKER && resultCode == RESULT_OK && data != null) {
            String email = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            String type = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE);
            if (email != null && de.niendo.ImapNotes3.Miscs.GoogleAccountAuth.supportedAccountType(type)) {
                googleAccountType = type;
                usernameTextView.setText(email);
                setGoogleMode(true);
                googleAuthorized = false;
            }
        } else if (requestCode == GOOGLE_CONSENT) {
            googleAuthorizationPending = false;
            setLoginLoading(0);
            if (resultCode == RESULT_OK) {
                setGoogleMode(true);
                googleAuthorized = false;
                DoLogin();
            }
        }
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (parent.getId() == R.id.syncintervalSpinner) {
            syncInterval = SyncInterval.from(position);
            updateSettingsSummary();
        } else if ( (parent.getId() == R.id.securitySpinner)) {
            if (!security.equals(Security.from(position))) {
                security = Security.from(position);
                portnumTextView.setText(security.defaultPort);
            }
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        // TODO Auto-generated method stub
    }

    public void Clear() {

        accountnameTextView.setText("");
        usernameTextView.setText("");
        passwordTextView.setText("");
        serverTextView.setText("");
        portnumTextView.setText("");
        syncIntervalSpinner.setSelection(0);
        securitySpinner.setSelection(0);
        folderTextView.setText("");
        copyImapFolderNameTextView.setText("");
        copyImapFolderCheckBox.setChecked(false);
    }


    /**
     *
     */
    public enum Actions {
        CREATE_ACCOUNT,
        EDIT_ACCOUNT
    }

}
