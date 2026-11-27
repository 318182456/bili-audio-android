package com.biliaudio.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class CatalogLoginActivity extends AudioActivity {
    private static final String EXTRA_PROVIDER = "catalog_provider";

    enum Provider {
        XIMALAYA("喜马拉雅", "https://passport.ximalaya.com/page/m/login", "https://www.ximalaya.com/revision/album/"),
        LANREN("懒人听书", "https://m.lrts.me/login", "https://m.lrts.me/");

        final String label;
        final String login;
        final String session;

        Provider(String label, String login, String session) {
            this.label = label;
            this.login = login;
            this.session = session;
        }

        boolean allows(Uri uri) {
            String host = uri.getEncodedAuthority();
            if (!"https".equals(uri.getScheme()) || host == null) {
                return false;
            }
            return switch (this) {
                case XIMALAYA -> host.equals("passport.ximalaya.com") || host.equals("www.ximalaya.com")
                        || host.equals("m.ximalaya.com") || host.equals("ximalaya.com");
                case LANREN -> host.equals("m.lrts.me") || host.equals("www.lrts.me") || host.equals("lrts.me");
            };
        }

        String cookies(String address) {
            return allows(Uri.parse(address)) ? CookieManager.getInstance().getCookie(address) : null;
        }

        boolean hasSession(Context context) {
            String cookies = cookies(session);
            if (cookies == null) {
                return false;
            }
            if (this == LANREN) {
                return context.getSharedPreferences("catalog_accounts", MODE_PRIVATE)
                        .getBoolean("lanren_confirmed", false);
            }
            for (String cookie : cookies.split(";")) {
                int equals = cookie.indexOf('=');
                if (equals == -1) {
                    continue;
                }
                String key = cookie.substring(0, equals).trim();
                String value = cookie.substring(equals + 1).trim();
                if (value.isEmpty() || value.equals("null") || value.equals("undefined") || value.equals("deleted")) {
                    continue;
                }
                if (key.matches("(?:[0-9]+&)?_token")) {
                    return true;
                }
            }
            return false;
        }
    }

    static Intent intent(Context context, Provider provider) {
        return new Intent(context, CatalogLoginActivity.class).putExtra(EXTRA_PROVIDER, provider.name());
    }

    private WebView browser;
    private TextView status;
    private Provider provider;
    private boolean leaving;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        provider = Provider.LANREN.name().equals(getIntent().getStringExtra(EXTRA_PROVIDER))
                ? Provider.LANREN : Provider.XIMALAYA;
        AudioUi.Palette palette = AudioUi.palette(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFitsSystemWindows(true);
        page.setBackgroundColor(palette.page());

        status = new TextView(this);
        status.setText(provider.label + "官网登录");
        status.setTextColor(palette.paper());
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, AudioUi.dp(this, 8), 0, AudioUi.dp(this, 5));
        page.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        addAction(actions, "重新打开", () -> browser.loadUrl(provider.login), false);
        if (provider == Provider.XIMALAYA) {
            addAction(actions, "扫码登录", () -> browser.loadUrl("https://www.ximalaya.com/page/web/login"), false);
        }
        addAction(actions, "返回应用", this::saveAndFinish, true);
        page.addView(actions);

        CookieManager.getInstance().setAcceptCookie(true);
        browser = new WebView(this);
        browser.getSettings().setJavaScriptEnabled(true);
        browser.getSettings().setDomStorageEnabled(true);
        browser.getSettings().setAllowFileAccess(false);
        browser.getSettings().setAllowContentAccess(false);
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request.isForMainFrame() && !provider.allows(request.getUrl());
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (provider.allows(Uri.parse(url))) {
                    inspectSession(() -> {
                        if (provider.hasSession(CatalogLoginActivity.this)) {
                            CookieManager.getInstance().flush();
                            status.setText(provider.label + "会话已保存");
                        }
                    });
                }
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    status.setText("官网暂时无法打开，可点「重新打开」。");
                }
            }
        });
        page.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        if (state == null || browser.restoreState(state) == null) {
            browser.loadUrl(provider.login);
        }
    }

    private void addAction(LinearLayout actions, String label, Runnable action, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        AudioUi.styleButton(button, primary);
        button.setOnClickListener(view -> action.run());
        actions.addView(button);
    }

    private void inspectSession(Runnable finished) {
        String url = browser.getUrl();
        if (provider != Provider.LANREN || url == null || !provider.allows(Uri.parse(url))) {
            finished.run();
            return;
        }
        browser.evaluateJavascript("(() => {const app = window.$nuxt;"
                + "const state = (app && app.$store && app.$store.state) || (window.__NUXT__ && window.__NUXT__.state);"
                + "return state && typeof state.isLogin === 'boolean' ? state.isLogin : null;})()", result -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if ("true".equals(result) || "false".equals(result)) {
                getSharedPreferences("catalog_accounts", MODE_PRIVATE).edit()
                        .putBoolean("lanren_confirmed", "true".equals(result)).apply();
            }
            finished.run();
        });
    }

    private void saveAndFinish() {
        if (leaving) {
            return;
        }
        leaving = true;
        inspectSession(this::finish);
        browser.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                finish();
            }
        }, 1500);
    }

    @Override public void onBackPressed() { saveAndFinish(); }

    @Override protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override public void finish() {
        if (!isFinishing() && provider != null) {
            CookieManager.getInstance().flush();
            EpisodeNameSync.afterLogin(this, provider);
        }
        super.finish();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        browser.saveState(state);
    }

    @Override protected void onDestroy() {
        ((LinearLayout) browser.getParent()).removeView(browser);
        browser.destroy();
        super.onDestroy();
    }
}
