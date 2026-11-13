package com.hms.home.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.hms.home.R;
import com.hms.home.data.Net;
import com.hms.home.data.Store;
import com.hms.home.data.Xtream;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.Callable;
import org.json.JSONObject;

public class LoginActivity extends BaseActivity {
    private EditText server, user, pass;
    private TextView msg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_login);
        server = findViewById(R.id.server); user = findViewById(R.id.user); pass = findViewById(R.id.pass); msg = findViewById(R.id.msg);
        if (Store.hasLogin()) { server.setText(Store.server()); user.setText(Store.user()); pass.setText(Store.pass()); }
        LinearLayout btns = findViewById(R.id.btns);
        TextView login = Ui.button(this, "Login"), save = Ui.button(this, "Save Login");
        login.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { doLogin(false); } });
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { doLogin(true); } });
        btns.addView(login); btns.addView(save);
        if (Store.hasLogin()) {
            TextView out = Ui.button(this, "Log out");
            out.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Store.logout(); Xtream.clearCache(); HomeActivity.invalidateMemo();
                    toast("تم تسجيل الخروج"); finish();
                }
            });
            btns.addView(out);
        }
    }

    private void doLogin(final boolean save) {
        final String s = Xtream.norm(server.getText().toString());
        final String u = user.getText().toString().trim();
        final String p = pass.getText().toString();
        if (s.isEmpty() || u.isEmpty() || p.isEmpty()) { msg.setText("املا السيرفر واسم المستخدم وكلمة السر"); return; }
        msg.setText("Connecting…");
        run(new Callable<JSONObject>() {
            @Override public JSONObject call() throws Exception { return Xtream.auth(s, u, p); }
        }, new Net.Ok<JSONObject>() {
            @Override public void ok(JSONObject ui) {
                if (!s.equals(Store.server())) Xtream.clearCache();
                if (save) Store.saveLogin(s, u, p); else Store.setSession(s, u, p);
                HomeActivity.invalidateMemo();
                long exp = ui.optLong("exp_date", 0);
                toast("تم تسجيل الدخول" + (exp > 0 ? " - ينتهي: " + DateFormat.getDateInstance().format(new Date(exp * 1000)) : "")
                        + (save ? "" : " (للجلسة دي بس)"));
                finish();
            }
        }, new Net.Fail() {
            @Override public void fail(Exception e) { msg.setText(Net.msg(e)); }
        });
    }
}
