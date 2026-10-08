package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

import static eu.faircode.email.ServiceAuthenticator.AUTH_TYPE_PASSWORD;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.textfield.TextInputLayout;

import java.util.Date;

import javax.mail.AuthenticationFailedException;

// Quick setup of Postman mail (mail.i2p) over the embedded I2P router
public class FragmentDialogI2p extends FragmentDialogBase {
    private RadioGroup rgMode;
    private EditText etName;
    private EditText etMailbox;
    private TextInputLayout tilPassword;
    private TextView tvCreateHint;
    private ProgressBar pbWait;
    private TextView tvProgress;
    private TextView tvError;

    private static final long ROUTER_WAIT = 10 * 60 * 1000L; // milliseconds
    private static final long POSTMAN_WAIT = 6 * 60 * 1000L; // milliseconds

    private interface Attempt<T> {
        T run() throws Throwable;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context context = getContext();
        View dview = LayoutInflater.from(context).inflate(R.layout.dialog_i2p, null);
        rgMode = dview.findViewById(R.id.rgMode);
        etName = dview.findViewById(R.id.etName);
        etMailbox = dview.findViewById(R.id.etMailbox);
        tilPassword = dview.findViewById(R.id.tilPassword);
        tvCreateHint = dview.findViewById(R.id.tvCreateHint);
        pbWait = dview.findViewById(R.id.pbWait);
        tvProgress = dview.findViewById(R.id.tvProgress);
        tvError = dview.findViewById(R.id.tvError);

        rgMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                tvCreateHint.setVisibility(checkedId == R.id.rbCreate ? View.VISIBLE : View.GONE);
            }
        });

        tvCreateHint.setVisibility(View.GONE);
        pbWait.setVisibility(View.GONE);
        tvProgress.setVisibility(View.GONE);
        tvError.setVisibility(View.GONE);

        return new AlertDialog.Builder(context)
                .setView(dview)
                .setPositiveButton(android.R.string.ok, null) // set in onStart, so the dialog stays open
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog == null)
            return;
        Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSetup();
            }
        });
    }

    private void onSetup() {
        String password = (tilPassword.getEditText() == null ? "" : tilPassword.getEditText().getText().toString());
        boolean create = (rgMode.getCheckedRadioButtonId() == R.id.rbCreate);

        Bundle args = new Bundle();
        args.putBoolean("create", create);
        args.putString("name", etName.getText().toString().trim());
        args.putString("mailbox", I2pPostman.normalize(etMailbox.getText().toString()));
        args.putString("password", password);

        String error = (create
                ? I2pPostman.check(args.getString("mailbox"), password)
                : TextUtils.isEmpty(args.getString("mailbox")) ? getString(R.string.title_no_user)
                : TextUtils.isEmpty(password) ? getString(R.string.title_no_password) : null);
        if (error != null) {
            tvError.setText(error);
            tvError.setVisibility(View.VISIBLE);
            return;
        }

        new SimpleTask<Void>() {
            @Override
            protected void onPreExecute(Bundle args) {
                setBusy(true);
            }

            @Override
            protected void onPostExecute(Bundle args) {
                setBusy(false);
            }

            @Override
            protected Void onExecute(Context context, Bundle args) throws Throwable {
                boolean create = args.getBoolean("create");
                String name = args.getString("name");
                String mailbox = args.getString("mailbox");
                String password = args.getString("password");

                // Router
                I2pRouter.setEnabled(context, true);
                I2pRouter.start(context);

                long until = new Date().getTime() + ROUTER_WAIT;
                while (true) {
                    I2pRouter.Status status = I2pRouter.getStatus();
                    Log.i("I2P " + status);
                    if (status != null && status.isReady())
                        break;
                    if (new Date().getTime() > until)
                        throw new IllegalStateException(context.getString(R.string.title_setup_i2p_timeout));
                    postProgress(context.getString(R.string.title_setup_i2p_router, status == null
                            ? context.getString(R.string.title_setup_i2p_starting)
                            : context.getString(R.string.title_setup_i2p_connecting, status.routers, status.tunnels)));
                    Thread.sleep(3000L);
                }

                if (create) {
                    postProgress(context.getString(R.string.title_setup_i2p_registering));
                    I2pPostman.Outcome outcome = retry(context, new Attempt<I2pPostman.Outcome>() {
                        @Override
                        public I2pPostman.Outcome run() throws Throwable {
                            return I2pPostman.register(mailbox, password);
                        }
                    });
                    EntityLog.log(context, "I2P register " + mailbox + " " + outcome.result + " " + outcome.message);
                    if (outcome.result != I2pPostman.Result.CREATED)
                        throw new IllegalArgumentException(outcome.message);
                    args.putBoolean("created", true);
                } else {
                    // Postman wants the bare name for POP3 and SMTP AUTH
                    postProgress(context.getString(R.string.title_setup_i2p_checking));
                    retry(context, new Attempt<Void>() {
                        @Override
                        public Void run() throws Throwable {
                            try (EmailService iservice = new EmailService(context,
                                    "pop3", null, EmailService.ENCRYPTION_NONE, false, false, false,
                                    EmailService.PURPOSE_CHECK, true)) {
                                iservice.connect(
                                        false, I2pRouter.HOST, I2pRouter.POP3_PORT,
                                        AUTH_TYPE_PASSWORD, null,
                                        mailbox, password,
                                        null, null);
                            }
                            return null;
                        }
                    });
                }

                save(context, name, mailbox, password);
                return null;
            }

            // A fresh router has tunnels before it can find Postman: the local tunnel then
            // accepts and closes at once (EOF), or the proxy answers with an error page
            private <T> T retry(Context context, Attempt<T> attempt) throws Throwable {
                long until = new Date().getTime() + POSTMAN_WAIT;
                for (int i = 1; ; i++)
                    try {
                        return attempt.run();
                    } catch (AuthenticationFailedException ex) {
                        throw ex;
                    } catch (Throwable ex) {
                        Log.i("I2P attempt=" + i + " " + ex);
                        if (new Date().getTime() > until)
                            throw ex;
                        postProgress(context.getString(R.string.title_setup_i2p_waiting, i + 1));
                        Thread.sleep(10 * 1000L);
                    }
            }

            @Override
            protected void onProgress(CharSequence status, Bundle data) {
                tvProgress.setText(status);
                tvProgress.setVisibility(View.VISIBLE);
            }

            @Override
            protected void onExecuted(Bundle args, Void data) {
                ToastEx.makeText(getContext(), args.getBoolean("created")
                                ? R.string.title_setup_i2p_created : R.string.title_setup_i2p_done,
                        Toast.LENGTH_LONG).show();
                dismissAllowingStateLoss();
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                Log.w(ex);
                tvProgress.setVisibility(View.GONE);
                tvError.setText(new ThrowableWrapper(ex).toSafeString());
                tvError.setVisibility(View.VISIBLE);
            }
        }.execute(this, args, "setup:i2p");
    }

    private void setBusy(boolean busy) {
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
        rgMode.setEnabled(!busy);
        etName.setEnabled(!busy);
        etMailbox.setEnabled(!busy);
        tilPassword.setEnabled(!busy);
        pbWait.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy)
            tvError.setVisibility(View.GONE);
    }

    private static void save(Context context, String name, String mailbox, String password) {
        String email = mailbox + "@" + I2pRouter.DOMAIN;

        DB db = DB.getInstance(context);
        try {
            db.beginTransaction();

            EntityAccount primary = db.account().getPrimaryAccount();

            EntityAccount account = new EntityAccount();
            account.protocol = EntityAccount.TYPE_POP;
            account.host = I2pRouter.HOST;
            account.encryption = EmailService.ENCRYPTION_NONE;
            account.port = I2pRouter.POP3_PORT;
            account.auth_type = AUTH_TYPE_PASSWORD;
            account.user = mailbox;
            account.password = password;
            account.name = email;
            account.synchronize = true;
            account.primary = (primary == null);
            account.leave_on_server = true;
            account.leave_deleted = true;
            account.created = new Date().getTime();
            account.last_connected = account.created;

            account.id = db.account().insertAccount(account);
            EntityLog.log(context, "I2P added account=" + account.name);

            for (EntityFolder folder : EntityFolder.getPopFolders(context)) {
                folder.account = account.id;
                folder.id = db.folder().insertFolder(folder);
                if (folder.synchronize)
                    EntityOperation.sync(context, folder.id, true);
            }

            EntityIdentity identity = new EntityIdentity();
            identity.name = (TextUtils.isEmpty(name) ? mailbox : name);
            identity.email = email;
            identity.account = account.id;
            identity.host = I2pRouter.HOST;
            identity.encryption = EmailService.ENCRYPTION_NONE;
            identity.port = I2pRouter.SMTP_PORT;
            identity.auth_type = AUTH_TYPE_PASSWORD;
            identity.user = mailbox;
            identity.password = password;
            identity.synchronize = true;
            identity.primary = true;

            identity.id = db.identity().insertIdentity(identity);
            EntityLog.log(context, "I2P added identity=" + identity.email);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        ServiceSynchronize.eval(context, "I2P setup");
        FairEmailBackupAgent.dataChanged(context);
    }
}
