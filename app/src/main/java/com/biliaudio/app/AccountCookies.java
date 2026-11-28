package com.biliaudio.app;

import android.net.Uri;
import android.webkit.CookieManager;

import java.util.LinkedHashSet;
import java.util.Set;

final class AccountCookies {
    private AccountCookies() {}

    static void clear(String domain, String... addresses) {
        Set<String> scopes = new LinkedHashSet<>();
        for (String address : addresses) {
            Uri uri = Uri.parse(address);
            String origin = "https://" + uri.getHost();
            String path = uri.getEncodedPath();
            scopes.add(origin + "/");
            if (path == null || path.isEmpty()) {
                continue;
            }
            for (int slash = path.indexOf('/', 1); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                scopes.add(origin + path.substring(0, slash));
                scopes.add(origin + path.substring(0, slash + 1));
            }
            scopes.add(origin + path);
        }
        CookieManager store = CookieManager.getInstance();
        for (String url : scopes) {
            String cookies = store.getCookie(url);
            if (cookies == null) {
                continue;
            }
            Uri uri = Uri.parse(url);
            for (String cookie : cookies.split(";")) {
                int equals = cookie.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String name = cookie.substring(0, equals).trim();
                String expiry = name + "=; Max-Age=0; Secure; Path=" + uri.getEncodedPath();
                store.setCookie(url, expiry);
                store.setCookie(url, expiry + "; Domain=" + uri.getHost());
                if (!domain.equals(uri.getHost())) {
                    store.setCookie(url, expiry + "; Domain=" + domain);
                }
            }
        }
        store.flush();
    }
}
