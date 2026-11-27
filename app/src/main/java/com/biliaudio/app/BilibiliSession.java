package com.biliaudio.app;

import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

final class BilibiliSession {
    private static final String ACCOUNT_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final String SESSION_COOKIE = "SESSDATA=";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface AccountCallback {
        void onResult(String accountName, boolean failed);
    }

    private BilibiliSession() {
    }

    static void check(AccountCallback callback) {
        WORKER.execute(() -> {
            try {
                String accountName = readAccount();
                if (accountName != null) {
                    CookieManager.getInstance().flush();
                }
                MAIN.post(() -> callback.onResult(accountName, false));
            } catch (IOException | JSONException exception) {
                MAIN.post(() -> callback.onResult(null, true));
            }
        });
    }

    static void signOut(Runnable finished) {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(removed -> WORKER.execute(() -> {
            cookieManager.flush();
            MAIN.post(finished);
        }));
    }

    private static String readAccount() throws IOException, JSONException {
        String cookieHeader = CookieManager.getInstance().getCookie(ACCOUNT_URL);
        if (!hasSessionCookie(cookieHeader)) {
            return null;
        }

        HttpsURLConnection connection = (HttpsURLConnection) new URL(ACCOUNT_URL).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Cookie", cookieHeader);
            connection.setRequestProperty("Referer", "https://www.bilibili.com/");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                throw new IOException("Bilibili account request failed");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                JSONObject result = new JSONObject(response.toString());
                int code = result.getInt("code");
                if (code == -101) {
                    return null;
                }
                if (code != 0) {
                    throw new IOException("Bilibili account check failed");
                }
                JSONObject account = result.getJSONObject("data");
                return account.getBoolean("isLogin") ? account.getString("uname") : null;
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean hasSessionCookie(String cookieHeader) {
        if (cookieHeader == null) {
            return false;
        }
        for (String cookie : cookieHeader.split(";")) {
            String value = cookie.trim();
            if (value.startsWith(SESSION_COOKIE) && value.length() > SESSION_COOKIE.length()) {
                return true;
            }
        }
        return false;
    }
}
