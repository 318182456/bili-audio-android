package com.biliaudio.app;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class BilibiliLoginActivity extends AudioActivity {
    private static final String LOGIN_URL = "https://passport.bilibili.com/h5-app/passport/login"
            + "?gourl=https%3A%2F%2Fm.bilibili.com%2F";

    private WebView loginPage;
    private TextView status;
    private Button verifyButton;
    private boolean checking;
    private boolean pageFailed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFitsSystemWindows(true);
        content.setBackgroundColor(getColor(R.color.page_background));

        TextView origin = new TextView(this);
        origin.setText(R.string.bilibili_login_origin);
        origin.setGravity(Gravity.CENTER);
        content.addView(origin);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        Button closeButton = new Button(this);
        closeButton.setText(R.string.bilibili_close_login);
        closeButton.setOnClickListener(view -> finish());
        actions.addView(closeButton);

        verifyButton = new Button(this);
        verifyButton.setText(R.string.bilibili_check_login);
        verifyButton.setOnClickListener(view -> {
            if (pageFailed) {
                loadLoginPage();
            } else {
                checkLogin(true);
            }
        });
        actions.addView(verifyButton);
        content.addView(actions);

        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        content.addView(status);

        loginPage = new WebView(this);
        loginPage.getSettings().setJavaScriptEnabled(true);
        loginPage.getSettings().setDomStorageEnabled(true);
        loginPage.getSettings().setAllowFileAccess(false);
        loginPage.getSettings().setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= 33) {
            loginPage.getSettings().setAlgorithmicDarkeningAllowed(AudioUi.isNight(this));
        }
        loginPage.setWebViewClient(new LoginPageClient());
        content.addView(loginPage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(content);
        Bundle page = savedInstanceState == null ? null : savedInstanceState.getBundle("login_page");
        if (page == null || loginPage.restoreState(page) == null) {
            loadLoginPage();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        Bundle page = new Bundle();
        loginPage.saveState(page);
        state.putBundle("login_page", page);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkLogin(false);
    }

    @Override
    protected void onDestroy() {
        ((LinearLayout) loginPage.getParent()).removeView(loginPage);
        loginPage.destroy();
        super.onDestroy();
    }

    private void loadLoginPage() {
        pageFailed = false;
        verifyButton.setText(R.string.bilibili_check_login);
        status.setVisibility(View.GONE);
        loginPage.loadUrl(LOGIN_URL);
    }

    private void checkLogin(boolean showFeedback) {
        if (checking || isFinishing() || isDestroyed()) {
            return;
        }
        checking = true;
        if (showFeedback) {
            verifyButton.setEnabled(false);
            showStatus(R.string.bilibili_checking);
        }
        BilibiliSession.check((accountName, failed) -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            checking = false;
            verifyButton.setEnabled(true);
            if (accountName != null) {
                finish();
            } else if (showFeedback && !pageFailed) {
                showStatus(failed ? R.string.bilibili_check_failed :
                        R.string.bilibili_login_pending);
            }
        });
    }

    private void showStatus(int message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }

    private void showPageError() {
        pageFailed = true;
        verifyButton.setText(R.string.bilibili_reload_page);
        showStatus(R.string.bilibili_page_failed);
    }

    private static boolean isBilibiliPage(Uri uri) {
        if (!"https".equals(uri.getScheme())) {
            return false;
        }
        String authority = uri.getEncodedAuthority();
        return "passport.bilibili.com".equals(authority)
                || "account.bilibili.com".equals(authority)
                || "m.bilibili.com".equals(authority)
                || "www.bilibili.com".equals(authority);
    }

    private final class LoginPageClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request.isForMainFrame() && !isBilibiliPage(request.getUrl())) {
                checkLogin(false);
                return true;
            }
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!isBilibiliPage(Uri.parse(url))) {
                view.stopLoading();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (isBilibiliPage(Uri.parse(url))) {
                checkLogin(false);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request.isForMainFrame()) {
                showPageError();
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse response) {
            if (request.isForMainFrame()) {
                showPageError();
            }
        }
    }
}
