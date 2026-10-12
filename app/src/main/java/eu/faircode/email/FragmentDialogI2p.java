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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.textfield.TextInputLayout;

import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.mail.AuthenticationFailedException;

// Quick setup of Postman mail (mail.i2p) over an I2P router of one's own.
// A new mailbox is created on that server with i2p-server/register.py.
public class FragmentDialogI2p extends FragmentDialogBase {
    private EditText etServer;
    private Button btnCertificate;
    private TextView tvCertificate;
    private EditText etName;
    private EditText etMailbox;
    private TextInputLayout tilPassword;
    private ProgressBar pbWait;
    private TextView tvProgress;
    private TextView tvError;

    // The server certificate the user was shown, trusted on the next OK
    private String shownServer = null;
    private String shownFingerprint = null;

    // The client certificate the server lets in, an alias in the Android key chain
    private String certificate = null;

    private static final long POSTMAN_WAIT = 6 * 60 * 1000L; // milliseconds

    private static class ConfirmException extends IllegalStateException {
        final String server;
        final String fingerprint;

        ConfirmException(String message, String server, String fingerprint) {
            super(message);
            this.server = server;
            this.fingerprint = fingerprint;
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context context = getContext();
        View dview = LayoutInflater.from(context).inflate(R.layout.dialog_i2p, null);
        etServer = dview.findViewById(R.id.etServer);
        btnCertificate = dview.findViewById(R.id.btnCertificate);
        tvCertificate = dview.findViewById(R.id.tvCertificate);
        etName = dview.findViewById(R.id.etName);
        etMailbox = dview.findViewById(R.id.etMailbox);
        tilPassword = dview.findViewById(R.id.tilPassword);
        pbWait = dview.findViewById(R.id.pbWait);
        tvProgress = dview.findViewById(R.id.tvProgress);
        tvError = dview.findViewById(R.id.tvError);

        btnCertificate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Helper.selectKeyAlias(getActivity(), FragmentDialogI2p.this, null, new Helper.IKeyAlias() {
                    @Override
                    public void onSelected(String alias) {
                        setCertificate(alias);
                    }

                    @Override
                    public void onNothingSelected() {
                        setCertificate(null);
                    }
                });
            }
        });
        setCertificate(I2pRouter.getRemoteCertificateAlias(context));
        etServer.setText(I2pRouter.getRemote(context));

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
        String server = etServer.getText().toString().trim().toLowerCase(Locale.ROOT);
        String mailbox = etMailbox.getText().toString().trim().toLowerCase(Locale.ROOT);
        String password = (tilPassword.getEditText() == null ? "" : tilPassword.getEditText().getText().toString());

        String error = (TextUtils.isEmpty(server) ? getString(R.string.title_setup_i2p_no_server)
                : TextUtils.isEmpty(mailbox) ? getString(R.string.title_no_user)
                : TextUtils.isEmpty(password) ? getString(R.string.title_no_password) : null);
        if (error != null) {
            tvError.setText(error);
            tvError.setVisibility(View.VISIBLE);
            return;
        }

        Bundle args = new Bundle();
        args.putString("server", server);
        args.putString("fingerprint", server.equals(shownServer) ? shownFingerprint : null);
        args.putString("certificate", certificate);
        args.putString("name", etName.getText().toString().trim());
        args.putString("mailbox", mailbox);
        args.putString("password", password);

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
                String server = args.getString("server");
                String fingerprint = args.getString("fingerprint");
                String certificate = args.getString("certificate");
                String name = args.getString("name");
                String mailbox = args.getString("mailbox");
                String password = args.getString("password");

                // Its self-signed certificate is pinned once the user confirmed it
                postProgress(context.getString(R.string.title_setup_i2p_server_connecting, server));
                X509Certificate pop = I2pRouter.getRemoteCertificate(context, server, I2pRouter.REMOTE_POP3_PORT, certificate);
                X509Certificate smtp = I2pRouter.getRemoteCertificate(context, server, I2pRouter.REMOTE_SMTP_PORT, certificate);
                String fp = EntityCertificate.getKeyFingerprint(pop);
                if (fp == null || !fp.equals(EntityCertificate.getKeyFingerprint(smtp)))
                    throw new IllegalStateException(context.getString(R.string.title_setup_i2p_server_mismatch));
                if (!fp.equals(fingerprint))
                    throw new ConfirmException(context.getString(R.string.title_setup_i2p_server_confirm,
                            EntityCertificate.getFingerprintSha1(pop)), server, fp);

                // Known as I2P from now on, for the long timeouts Postman needs
                I2pRouter.setRemote(context, server, certificate);

                // Postman wants the bare name for POP3 and SMTP AUTH.
                // A fresh router has tunnels before it can find Postman: the stream then closes at once
                postProgress(context.getString(R.string.title_setup_i2p_checking));
                long until = new Date().getTime() + POSTMAN_WAIT;
                for (int i = 1; ; i++)
                    try (EmailService iservice = new EmailService(context,
                            "pop3s", null, EmailService.ENCRYPTION_SSL, false, false, false,
                            EmailService.PURPOSE_CHECK, true)) {
                        iservice.connect(
                                false, server, I2pRouter.REMOTE_POP3_PORT,
                                AUTH_TYPE_PASSWORD, null,
                                mailbox, password,
                                certificate, fp);
                        break;
                    } catch (AuthenticationFailedException ex) {
                        throw ex;
                    } catch (Throwable ex) {
                        Log.i("I2P attempt=" + i + " " + ex);
                        if (new Date().getTime() > until)
                            throw ex;
                        postProgress(context.getString(R.string.title_setup_i2p_waiting, i + 1));
                        Thread.sleep(10 * 1000L);
                    }

                save(context, name, mailbox, password, server, fp, certificate);
                return null;
            }

            @Override
            protected void onProgress(CharSequence status, Bundle data) {
                tvProgress.setText(status);
                tvProgress.setVisibility(View.VISIBLE);
            }

            @Override
            protected void onExecuted(Bundle args, Void data) {
                ToastEx.makeText(getContext(), R.string.title_setup_i2p_done, Toast.LENGTH_LONG).show();
                dismissAllowingStateLoss();
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                tvProgress.setVisibility(View.GONE);
                if (ex instanceof ConfirmException) {
                    shownServer = ((ConfirmException) ex).server;
                    shownFingerprint = ((ConfirmException) ex).fingerprint;
                    tvError.setText(ex.getMessage());
                    tvError.setVisibility(View.VISIBLE);
                    return;
                }
                Log.w(ex);
                tvError.setText(new ThrowableWrapper(ex).toSafeString());
                tvError.setVisibility(View.VISIBLE);
            }
        }.execute(this, args, "setup:i2p");
    }

    private void setBusy(boolean busy) {
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
        etServer.setEnabled(!busy);
        btnCertificate.setEnabled(!busy);
        etName.setEnabled(!busy);
        etMailbox.setEnabled(!busy);
        tilPassword.setEnabled(!busy);
        pbWait.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy)
            tvError.setVisibility(View.GONE);
    }

    private void setCertificate(String alias) {
        certificate = alias;
        tvCertificate.setText(alias == null
                ? getString(R.string.title_setup_i2p_certificate_none)
                : getString(R.string.title_setup_i2p_certificate, alias));
    }

    // The same mailbox set up before, over the router once built into the app or another server:
    // switch it over, keeping its messages, instead of adding a second account
    private static boolean move(Context context, String mailbox, String password,
                                String server, String fingerprint, String certificate) {
        DB db = DB.getInstance(context);
        try {
            db.beginTransaction();
            List<EntityAccount> existing = db.account().getAccounts(mailbox, EntityAccount.TYPE_POP);
            if (existing != null)
                for (EntityAccount account : existing) {
                    if (!I2pRouter.isLegacy(account.host, account.port) &&
                            !I2pRouter.isI2p(context, account.host, account.port))
                        continue;
                    account.host = server;
                    account.encryption = EmailService.ENCRYPTION_SSL;
                    account.port = I2pRouter.REMOTE_POP3_PORT;
                    account.fingerprint = fingerprint;
                    account.certificate_alias = certificate;
                    account.password = password;
                    account.synchronize = true;
                    db.account().updateAccount(account);
                    EntityLog.log(context, "I2P moved account=" + account.name + " to " + account.host);

                    for (EntityIdentity identity : db.identity().getIdentities(account.id)) {
                        if (!I2pRouter.isLegacy(identity.host, identity.port) &&
                                !I2pRouter.isI2p(context, identity.host, identity.port))
                            continue;
                        identity.host = server;
                        identity.encryption = EmailService.ENCRYPTION_SSL;
                        identity.port = I2pRouter.REMOTE_SMTP_PORT;
                        identity.fingerprint = fingerprint;
                        identity.certificate_alias = certificate;
                        identity.password = password;
                        db.identity().updateIdentity(identity);
                    }

                    db.setTransactionSuccessful();
                    return true;
                }
        } finally {
            db.endTransaction();
        }
        return false;
    }

    private static void save(Context context, String name, String mailbox, String password,
                             String server, String fingerprint, String certificate) {
        String email = mailbox + "@" + I2pRouter.DOMAIN;

        if (move(context, mailbox, password, server, fingerprint, certificate)) {
            ServiceSynchronize.eval(context, "I2P moved");
            FairEmailBackupAgent.dataChanged(context);
            return;
        }

        DB db = DB.getInstance(context);
        try {
            db.beginTransaction();

            EntityAccount primary = db.account().getPrimaryAccount();

            EntityAccount account = new EntityAccount();
            account.protocol = EntityAccount.TYPE_POP;
            account.host = server;
            account.encryption = EmailService.ENCRYPTION_SSL;
            account.port = I2pRouter.REMOTE_POP3_PORT;
            account.fingerprint = fingerprint;
            account.certificate_alias = certificate;
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
            identity.host = server;
            identity.encryption = EmailService.ENCRYPTION_SSL;
            identity.port = I2pRouter.REMOTE_SMTP_PORT;
            identity.fingerprint = fingerprint;
            identity.certificate_alias = certificate;
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
