package com.biliaudio.app;

import android.net.Uri;
import android.text.Html;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class BilibiliClient {
    private static final String API = "https://api.bilibili.com";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final Pattern LINK = Pattern.compile("https?://[^\\s]+");
    private static final Pattern PART = Pattern.compile("[?&]p=(\\d{1,6})(?:[&#\\s]|$)");
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final Pattern UNSATISFIED_RANGE = Pattern.compile("bytes \\*/(\\d+)");
    private static final int NETWORK_ATTEMPTS = 3;

    private static final class HttpFailure extends IOException {
        final int status;
        final long retryAfterMillis;

        HttpFailure(HttpsURLConnection connection, int status, String message) {
            super(message + "（HTTP " + status + "）。");
            this.status = status;
            String header = connection.getHeaderField("Retry-After");
            retryAfterMillis = header != null && header.matches("[0-9]{1,5}")
                    ? Long.parseLong(header) * 1000 : 0;
        }
    }

    record Selection(String bvid, int page) {}
    record SubtitleData(String source, List<AudioLibrary.Cue> cues, List<Integer> chapters) {}
    record Video(String bvid, long aid, String title, String author, String duration, String cover) {}
    record VideoPage(List<Video> entries, boolean hasMore) {}
    record Folder(long id, String title, int count) {}

    Selection selection(String input) throws IOException {
        Matcher url = LINK.matcher(input);
        if (url.find()) {
            Uri uri = Uri.parse(url.group());
            if ("b23.tv".equalsIgnoreCase(uri.getHost())) {
                HttpsURLConnection redirect = connect(asHttps(uri.toString()), null);
                try {
                    int code = redirect.getResponseCode();
                    String location = redirect.getHeaderField("Location");
                    if (code >= 300 && code < 400 && location != null) {
                        Uri redirected = Uri.parse(location);
                        if (isBilibili(redirected.getHost())) {
                            input = location;
                        }
                    }
                } finally {
                    redirect.disconnect();
                }
            }
        }
        Matcher bvid = AudioLibrary.BVID.matcher(input);
        if (!bvid.find()) {
            throw new IOException("请输入 B 站视频链接或 BV 号。");
        }
        Matcher page = PART.matcher(input);
        return new Selection(bvid.group(), page.find() ? Math.max(1, Integer.parseInt(page.group(1))) : 1);
    }

    JSONObject metadata(String bvid) throws IOException, JSONException {
        return api("/x/web-interface/view?bvid=" + bvid);
    }

    VideoPage search(String keyword, int page) throws IOException, JSONException {
        JSONObject data = api("/x/web-interface/wbi/search/type?search_type=video&keyword="
                + Uri.encode(keyword) + "&page=" + page);
        return new VideoPage(videos(data.optJSONArray("result"), false),
                page < data.optInt("numPages", page));
    }

    List<Folder> favoriteFolders() throws IOException, JSONException {
        JSONObject account = api("/x/web-interface/nav");
        long mid = account.getLong("mid");
        JSONObject data = api("/x/v3/fav/folder/created/list-all?type=2&up_mid=" + mid);
        List<Folder> folders = new ArrayList<>();
        JSONArray list = data.optJSONArray("list");
        if (list != null) {
            for (int index = 0; index < list.length(); index++) {
                JSONObject folder = list.getJSONObject(index);
                folders.add(new Folder(folder.getLong("id"), folder.getString("title"),
                        folder.optInt("media_count")));
            }
        }
        return folders;
    }

    VideoPage favorites(Folder folder, String keyword, int page) throws IOException, JSONException {
        JSONObject data = api("/x/v3/fav/resource/list?media_id=" + folder.id()
                + "&pn=" + page + "&ps=20&order=mtime&type=0&tid=0&platform=web&keyword="
                + Uri.encode(keyword));
        return new VideoPage(videos(data.optJSONArray("medias"), true), data.optBoolean("has_more"));
    }

    void addFavorite(Video video, Folder folder) throws IOException, JSONException {
        long aid = video.aid() > 0 ? video.aid() : metadata(video.bvid()).getLong("aid");
        URL url = new URL(API + "/x/v3/fav/resource/deal");
        String cookie = CookieManager.getInstance().getCookie(url.toString());
        Matcher csrf = Pattern.compile("(?:^|;\\s*)bili_jct=([a-fA-F0-9]{32})(?:;|$)")
                .matcher(cookie == null ? "" : cookie);
        if (!csrf.find()) {
            throw loginRequired();
        }
        byte[] body = ("rid=" + aid + "&type=2&add_media_ids=" + folder.id()
                + "&del_media_ids=&csrf=" + csrf.group(1)).getBytes(StandardCharsets.UTF_8);
        HttpsURLConnection connection = connect(url, cookie);
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Origin", "https://www.bilibili.com");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            checkResult(readJson(connection));
        } finally {
            connection.disconnect();
        }
    }

    private static List<Video> videos(JSONArray items, boolean fromFavorites) throws JSONException {
        List<Video> videos = new ArrayList<>();
        if (items == null) {
            return videos;
        }
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            String bvid = item.optString("bvid", item.optString("bv_id"));
            String title = Html.fromHtml(item.optString("title"), Html.FROM_HTML_MODE_LEGACY)
                    .toString().trim();
            if (!AudioLibrary.BVID.matcher(bvid).matches() || title.isEmpty()
                    || "已失效视频".equals(title) || (fromFavorites && item.optInt("type", 2) != 2)) {
                continue;
            }
            JSONObject owner = item.optJSONObject("upper");
            String author = owner == null ? item.optString("author") : owner.optString("name");
            long aid = item.optLong(fromFavorites ? "id" : "aid");
            String duration = fromFavorites
                    ? AudioLibrary.time((int) Math.min(Integer.MAX_VALUE, item.optLong("duration") * 1000L))
                    : item.optString("duration");
            String cover = item.optString(fromFavorites ? "cover" : "pic");
            videos.add(new Video(bvid, aid, title, author, duration, cover));
        }
        return videos;
    }

    void downloadAudio(AudioLibrary.Part part, File destination, IntConsumer progress)
            throws IOException, JSONException {
        if (destination.isFile()) {
            progress.accept(100);
            return;
        }
        JSONObject play = audioInfo(part);
        JSONObject dash = play.optJSONObject("dash");
        JSONArray audio = dash == null ? null : dash.optJSONArray("audio");
        if (audio == null || audio.length() == 0) {
            throw new IOException("B 站暂未提供独立音轨。");
        }
        List<JSONObject> tracks = new ArrayList<>();
        for (int index = 0; index < audio.length(); index++) {
            JSONObject track = audio.getJSONObject(index);
            if (track.optString("codecs").startsWith("mp4a")) {
                tracks.add(track);
            }
        }
        tracks.sort(Comparator.comparingInt(track -> track.optInt("bandwidth")));
        IOException failure = new IOException("音源暂不可用，请重试。");
        for (JSONObject track : tracks) {
            File partial = new File(destination.getPath() + "." + track.getInt("id") + ".part");
            List<String> urls = new ArrayList<>();
            JSONArray backup = track.optJSONArray("backupUrl");
            if (backup == null) {
                backup = track.optJSONArray("backup_url");
            }
            urls.add(track.optString("baseUrl", track.optString("base_url")));
            if (backup != null) {
                for (int index = 0; index < backup.length(); index++) {
                    urls.add(backup.optString(index));
                }
            }
            for (String url : urls) {
                checkInterrupted();
                if (url.isEmpty()) {
                    continue;
                }
                try {
                    download(url, partial, progress);
                    try {
                        AudioFiles.validateAudio(partial, part.duration());
                    } catch (IOException error) {
                        partial.delete();
                        throw error;
                    }
                    if (!partial.renameTo(destination)) {
                        throw new IOException("音轨保存失败。");
                    }
                    progress.accept(100);
                    return;
                } catch (IOException error) {
                    failure = error;
                }
            }
        }
        throw failure;
    }

    private static JSONObject audioInfo(AudioLibrary.Part part) throws IOException, JSONException {
        for (int attempt = 1; ; attempt++) {
            checkInterrupted();
            try {
                return api("/x/player/playurl?fnval=16&" + mediaQuery(part));
            } catch (IOException error) {
                retry(error, attempt);
            }
        }
    }

    SubtitleData subtitles(AudioLibrary.Part part) throws IOException, JSONException {
        JSONObject player = api("/x/player/wbi/v2?" + mediaQuery(part));
        List<Integer> chapters = new ArrayList<>();
        JSONArray points = player.optJSONArray("view_points");
        if (points != null) {
            for (int index = 0; index < points.length(); index++) {
                chapters.add(milliseconds(points.getJSONObject(index).getDouble("from")));
            }
            Collections.sort(chapters);
        }
        JSONObject subtitle = player.optJSONObject("subtitle");
        JSONArray tracks = subtitle == null ? null : subtitle.optJSONArray("subtitles");
        if (tracks == null) {
            return new SubtitleData("", Collections.emptyList(), chapters);
        }
        List<JSONObject> chinese = new ArrayList<>();
        for (int index = 0; index < tracks.length(); index++) {
            JSONObject track = tracks.getJSONObject(index);
            String language = track.optString("lan");
            if (language.startsWith("zh") || language.startsWith("ai-zh")) {
                chinese.add(track);
            }
        }
        chinese.sort(Comparator.comparing(track -> !track.optString("lan").startsWith("ai-")));
        for (JSONObject track : chinese) {
            String address = track.optString("subtitle_url");
            if (address.isEmpty()) {
                continue;
            }
            JSONArray body = json(asHttps(address), null).getJSONArray("body");
            List<AudioLibrary.Cue> cues = new ArrayList<>(body.length());
            for (int index = 0; index < body.length(); index++) {
                JSONObject item = body.getJSONObject(index);
                int start = Math.max(0, milliseconds(item.getDouble("from")));
                int end = Math.min(part.duration(), milliseconds(item.getDouble("to")));
                if (end > start) {
                    cues.add(new AudioLibrary.Cue(start, end, item.getString("content")));
                }
            }
            cues.sort(Comparator.comparingInt(AudioLibrary.Cue::start));
            if (!cues.isEmpty()) {
                String source = track.optString("lan").startsWith("ai-") ? "B 站 AI" : "B 站字幕";
                return new SubtitleData(source, cues, chapters);
            }
        }
        return new SubtitleData("", Collections.emptyList(), chapters);
    }

    static void download(String address, File partial, IntConsumer progress) throws IOException {
        URL url = asHttps(address);
        int failures = 0;
        boolean resetRange = false;
        while (true) {
            checkInterrupted();
            long previous = partial.length();
            HttpsURLConnection request = connect(url, null);
            try {
                request.setInstanceFollowRedirects(true);
                if (previous > 0) {
                    request.setRequestProperty("Range", "bytes=" + previous + "-");
                }
                int status = request.getResponseCode();
                long total;
                long responseEnd;
                if (status == 206) {
                    String header = request.getHeaderField("Content-Range");
                    Matcher range = CONTENT_RANGE.matcher(header == null ? "" : header);
                    if (!range.matches() || Long.parseLong(range.group(1)) != previous) {
                        throw new IOException("下载返回的字节范围错误。");
                    }
                    total = Long.parseLong(range.group(3));
                    responseEnd = Long.parseLong(range.group(2)) + 1;
                    if (responseEnd <= previous || responseEnd > total) {
                        throw new IOException("下载返回的字节范围错误。");
                    }
                } else if (status == 200) {
                    previous = 0;
                    total = request.getContentLengthLong();
                    responseEnd = total;
                } else if (status == 416 && previous > 0) {
                    String header = request.getHeaderField("Content-Range");
                    Matcher range = UNSATISFIED_RANGE.matcher(header == null ? "" : header);
                    if (range.matches() && Long.parseLong(range.group(1)) == previous) {
                        return;
                    }
                    if (resetRange || !partial.delete()) {
                        throw new IOException("无法重置部分文件。");
                    }
                    resetRange = true;
                    continue;
                } else {
                    throw new HttpFailure(request, status, "下载失败");
                }
                try (InputStream stream = new BufferedInputStream(request.getInputStream());
                     FileOutputStream output = new FileOutputStream(partial, previous > 0)) {
                    byte[] buffer = new byte[64 * 1024];
                    long received = previous;
                    int lastProgress = -1;
                    int size;
                    while ((size = stream.read(buffer)) != -1) {
                        checkInterrupted();
                        output.write(buffer, 0, size);
                        received += size;
                        int percent = total > 0 ? (int) Math.min(99, received * 100 / total) : 0;
                        if (percent != lastProgress) {
                            progress.accept(percent);
                            lastProgress = percent;
                        }
                    }
                    if (received <= previous || (responseEnd > 0 && received != responseEnd)) {
                        throw new EOFException("文件未下载完整。");
                    }
                    output.getFD().sync();
                }
                if (responseEnd == total) {
                    return;
                }
            } catch (IOException error) {
                retry(error, ++failures);
            } finally {
                request.disconnect();
            }
        }
    }

    private static void retry(IOException error, int attempt) throws IOException {
        checkInterrupted();
        boolean transientError = error instanceof EOFException || error instanceof SocketTimeoutException
                || error instanceof SocketException || error instanceof UnknownHostException;
        long delay = attempt * 1200L;
        if (error instanceof HttpFailure http) {
            transientError = http.status == 408 || http.status == 429 || (http.status >= 500 && http.status <= 504);
            delay = Math.max(delay, http.retryAfterMillis);
        }
        if (!transientError || attempt >= NETWORK_ATTEMPTS || delay > 15_000) {
            throw error;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException paused) {
            Thread.currentThread().interrupt();
            checkInterrupted();
        }
    }

    static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("任务已暂停。");
        }
    }

    private static int milliseconds(double seconds) {
        return (int) Math.round(seconds * 1000);
    }

    private static String mediaQuery(AudioLibrary.Part part) {
        return "bvid=" + part.bvid() + "&cid=" + part.cid();
    }

    private static boolean isBilibili(String host) {
        if (host == null) {
            return false;
        }
        String domain = host.toLowerCase(Locale.ROOT);
        return domain.equals("bilibili.com") || domain.endsWith(".bilibili.com");
    }

    private static URL asHttps(String address) throws IOException {
        if (address.startsWith("//")) {
            address = "https:" + address;
        } else if (address.startsWith("http://")) {
            address = "https://" + address.substring(7);
        }
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)) {
            throw new IOException("无效的 HTTPS 地址。");
        }
        return url;
    }

    private static JSONObject api(String path) throws IOException, JSONException {
        URL url = new URL(API + path);
        String cookie = CookieManager.getInstance().getCookie(url.toString());
        JSONObject result = json(url, cookie);
        checkResult(result);
        return result.getJSONObject("data");
    }

    private static void checkResult(JSONObject result) throws IOException, JSONException {
        int code = result.getInt("code");
        if (code == -101 || code == -111) {
            throw loginRequired();
        }
        if (code != 0) {
            throw new IOException("B 站请求失败（" + code + "）"
                    + result.optString("message", "请稍后重试"));
        }
    }

    private static IOException loginRequired() {
        return new IOException("请先在「B 站账号」登录，或重新登录后再试。");
    }

    private static JSONObject json(URL url, String cookie) throws IOException, JSONException {
        HttpsURLConnection connection = connect(url, cookie);
        try {
            return readJson(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject readJson(HttpsURLConnection connection) throws IOException, JSONException {
        int status = connection.getResponseCode();
        if (status != 200) {
            throw new HttpFailure(connection, status, "B 站接口响应异常");
        }
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 12 * 1024 * 1024) {
                    throw new IOException("接口数据太大。");
                }
                output.write(buffer, 0, count);
            }
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static HttpsURLConnection connect(URL url, String cookie) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Referer", REFERER);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (cookie != null && !cookie.isEmpty()) {
            connection.setRequestProperty("Cookie", cookie);
        }
        return connection;
    }
}
