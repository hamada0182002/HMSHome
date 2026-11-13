package com.hms.home.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.hms.home.R;
import com.hms.home.data.Account;
import com.hms.home.data.Net;
import com.hms.home.data.Store;
import com.hms.home.data.Xtream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.json.JSONObject;

/**
 * IPTV accounts, same idea as HMS Player's "Accounts" screen:
 * several Xtream subscriptions, add / edit / delete, one of them is the current one.
 * Saving checks the login first (and offers "Save without checking" if the check fails).
 */
public class LoginActivity extends BaseActivity {
    private TextAdapter ad;
    private TextView hint;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 48), Ui.dp(this, 30), Ui.dp(this, 48), Ui.dp(this, 20));
        root.addView(Ui.label(this, "IPTV Accounts", 30, R.color.white, true));
        hint = Ui.label(this, "Xtream Codes  -  اضغط على حساب لفتحه أو تعديله", 14, R.color.secondary, false);
        hint.setPadding(0, 0, 0, Ui.dp(this, 10));
        root.addView(hint);
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        ad = new TextAdapter(false, new TextAdapter.Listener() {
            @Override public void click(TextAdapter.Row r, int pos) {
                if (r.data instanceof Account) accountMenu((Account) r.data); else showForm(null);
            }
            @Override public void focus(TextAdapter.Row r, int pos) { }
        });
        rv.setAdapter(ad);
        root.addView(rv, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); build(); if (Store.accounts().isEmpty()) showForm(null); }

    private void build() {
        List<TextAdapter.Row> r = new ArrayList<>();
        Account act = Store.active();
        for (Account a : Store.accounts()) {
            boolean cur = act != null && act.id.equals(a.id);
            r.add(new TextAdapter.Row((cur ? "★ " : "") + a.name,
                    a.server.replace("http://", "").replace("https://", "") + "   |   " + a.user + (cur ? "   (current)" : ""), a));
        }
        r.add(new TextAdapter.Row("+  Add account", "Server URL, username and password", "add"));
        ad.set(r);
    }

    private void accountMenu(final Account a) {
        String[] items = {"Use this account", "Edit", "Delete"};
        new AlertDialog.Builder(this).setTitle(a.name).setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if (w == 0) use(a);
                else if (w == 1) showForm(a);
                else confirmDelete(a);
            }
        }).show();
    }

    private void use(Account a) {
        Store.setActive(a);
        Xtream.clearCache(); HomeActivity.invalidateMemo();
        toast("تم الدخول: " + a.name);
        finish();
    }

    private void confirmDelete(final Account a) {
        new AlertDialog.Builder(this).setTitle("Delete account").setMessage("متأكد إنك عايز تحذف \"" + a.name + "\"؟")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Store.deleteAccount(a.id); Xtream.clearCache(); HomeActivity.invalidateMemo(); build();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private EditText field(LinearLayout box, String hintText, String value, int inputType, boolean last) {
        EditText e = new EditText(this);
        e.setHint(hintText);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setTextSize(17);
        e.setImeOptions(last ? android.view.inputmethod.EditorInfo.IME_ACTION_DONE : android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    /** Add (existing == null) or edit an account. */
    private void showForm(final Account existing) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), 0);
        final int T = android.text.InputType.TYPE_CLASS_TEXT;
        final EditText name = field(box, "Account name (optional)", existing == null ? "" : existing.name, T, false);
        final EditText server = field(box, "Server URL  (http://host:port)", existing == null ? "" : existing.server, T | android.text.InputType.TYPE_TEXT_VARIATION_URI, false);
        final EditText user = field(box, "Username", existing == null ? "" : existing.user, T, false);
        final EditText pass = field(box, "Password", existing == null ? "" : existing.pass, T, true);
        final TextView msg = Ui.label(this, "", 14, R.color.beige, false);
        msg.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(msg);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add Xtream account" : "Edit account")
                .setView(sv)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Save without checking", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (server.getText().toString().trim().isEmpty()) { msg.setText("ادخل رابط السيرفر"); return; }
                final Account a = Xtream.buildAccount(existing == null ? null : existing.id, name.getText().toString(),
                        server.getText().toString(), user.getText().toString(), pass.getText().toString());
                if (a.user.isEmpty() || a.pass.isEmpty()) { msg.setText("ادخل اليوزر والباسورد"); return; }
                msg.setText("Connecting…");
                final View save = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
                save.setEnabled(false);
                run(new Callable<JSONObject>() {
                    @Override public JSONObject call() throws Exception { return Xtream.auth(a); }
                }, new Net.Ok<JSONObject>() {
                    @Override public void ok(JSONObject ui) { dlg.dismiss(); commit(a, existing == null); }
                }, new Net.Fail() {
                    @Override public void fail(final Exception e) {
                        save.setEnabled(true);
                        msg.setText(Net.msg(e) + "\n[" + Net.detail(e) + "]");
                        // like HMS Player: let the user save anyway (server may be down right now)
                        View n = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
                        n.setVisibility(View.VISIBLE);
                        n.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v2) { dlg.dismiss(); commit(a, existing == null); }
                        });
                    }
                });
            }
        });
    }

    private void commit(Account a, boolean activate) {
        Store.saveAccount(a, activate);
        Xtream.clearCache(); HomeActivity.invalidateMemo();
        toast("تم الحفظ: " + a.name);
        if (activate) finish(); else build();
    }
}
