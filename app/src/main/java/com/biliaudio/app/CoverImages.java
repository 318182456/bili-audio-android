package com.biliaudio.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import javax.net.ssl.HttpsURLConnection;

final class CoverImages {
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, List<Consumer<Bitmap>>> PENDING = new HashMap<>();
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
        WeakReference<ImageView> target = new WeakReference<>(view);
        load(view.getContext(), address, image -> {
            ImageView current = target.get();
            if (current != null && address.equals(current.getTag())) {
                display(current, image);
            }
        });
    }

    static void load(Context context, String address, Consumer<Bitmap> callback) {
        if (address == null || address.isEmpty()) {
            return;
        }
        Bitmap cached = CACHE.get(address);
        if (cached != null) {
            callback.accept(cached);
            return;
        }
        List<Consumer<Bitmap>> waiting = PENDING.get(address);
        if (waiting != null) {
            waiting.add(callback);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(callback);
        PENDING.put(address, waiting);
        File directory = new File(context.getCacheDir(), "covers");
        File local = new File(directory, UUID.nameUUIDFromBytes(address.getBytes(StandardCharsets.UTF_8)) + ".jpg");
        WORKER.execute(() -> {
            Bitmap image = null;
            try {
                image = local.isFile() ? BitmapFactory.decodeFile(local.getPath()) : null;
                if (image == null) {
                    image = read(address);
                    if (image != null) {
                        cache(local, image);
                    }
                }
            } catch (IOException ignored) {
                // The cover placeholder remains available offline.
            }
            Bitmap result = image;
            MAIN.post(() -> {
                List<Consumer<Bitmap>> listeners = PENDING.remove(address);
                if (result != null) {
                    CACHE.put(address, result);
                    for (Consumer<Bitmap> listener : listeners) {
                        listener.accept(result);
                    }
                }
            });
        });
    }

    private static void cache(File local, Bitmap image) {
        AtomicFile file = new AtomicFile(local);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            if (image.compress(Bitmap.CompressFormat.JPEG, 88, output)) {
                file.finishWrite(output);
            } else {
                file.failWrite(output);
            }
        } catch (IOException ignored) {
            file.failWrite(output);
        }
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
