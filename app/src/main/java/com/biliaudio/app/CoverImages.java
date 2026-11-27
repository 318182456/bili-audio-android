package com.biliaudio.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import javax.net.ssl.HttpsURLConnection;

final class CoverImages {
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    private CoverImages() {}

    static void load(ImageView view, String address) {
        if (address == null || address.isEmpty()) {
            return;
        }
        view.setTag(address);
        Bitmap cached = CACHE.get(address);
        if (cached != null) {
            display(view, cached);
            return;
        }
        WeakReference<ImageView> target = new WeakReference<>(view);
        WORKER.execute(() -> {
            if (target.get() == null) {
                return;
            }
            try {
                Bitmap bitmap = read(address);
                if (bitmap == null) {
                    return;
                }
                CACHE.put(address, bitmap);
                MAIN.post(() -> {
                    ImageView current = target.get();
                    if (current != null && address.equals(current.getTag())) {
                        display(current, bitmap);
                    }
                });
            } catch (IOException ignored) {
                // The cover placeholder remains available offline.
            }
        });
    }

    private static void display(ImageView view, Bitmap image) {
        view.setPadding(0, 0, 0, 0);
        view.setImageBitmap(image);
    }

    private static Bitmap read(String address) throws IOException {
        if (address.startsWith("//")) {
            address = "https:" + address;
        } else if (address.startsWith("http://")) {
            address = "https://" + address.substring(7);
        }
        URL url = new URL(address);
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)
                || !host.endsWith(".hdslb.com")) {
            return null;
        }
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(4500);
        connection.setReadTimeout(4500);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Referer", "https://www.bilibili.com/");
        try {
            if (connection.getResponseCode() != 200) {
                return null;
            }
            try (InputStream stream = connection.getInputStream();
                 ByteArrayOutputStream image = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (image.size() + count > 2 * 1024 * 1024) {
                        return null;
                    }
                    image.write(buffer, 0, count);
                }
                byte[] bytes = image.toByteArray();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                options.inJustDecodeBounds = false;
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 480) {
                    options.inSampleSize *= 2;
                }
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            }
        } finally {
            connection.disconnect();
        }
    }
}
