package com.biliaudio.app;

import android.text.Html;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class EpisodeNames {
    private static final Pattern NUMBERED = Pattern.compile("(?:第\\h*)?([0-9０-９]{1,6})\\h*[集章回]\\h*[：:、.．·－—-]?\\h*(.+)");
    private static final Pattern SEPARATED = Pattern.compile("(?:^|\\h)([0-9０-９]{1,6})(?:\\h*[-－—：:、.．·]\\h*|\\h+)(.+)");
    private static final Pattern LANREN_MENU = Pattern.compile("menuList:\\[(.*?)\\],", Pattern.DOTALL);
    private static final Pattern LANREN_NAME = Pattern.compile("\\bname:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")");
    private static final Pattern LANREN_VISIBLE = Pattern.compile(
            "class=\"chapter-copy\"[^>]*>\\s*<strong[^>]*>([^<]+)</strong>");

    record Catalog(String source, Map<Integer, String> names) {
        static final Catalog EMPTY = new Catalog("", Collections.emptyMap());

        String label(int episode) {
            String title = names.get(episode);
            return "第 " + episode + " 集" + (title == null ? "" : " · " + title);
        }
    }

    private EpisodeNames() {}

    record DirectoryPage(List<String> titles, int totalPages, String marker) {}

    static String source(String input) throws IOException {
        URI link;
        try {
            link = URI.create(input.trim());
        } catch (IllegalArgumentException error) {
            throw new IOException("请输入完整的专辑链接。", error);
        }
        if (!"https".equalsIgnoreCase(link.getScheme()) || link.getHost() == null || link.getUserInfo() != null) {
            throw new IOException("请输入喜马拉雅或懒人听书的 HTTPS 专辑链接。");
        }
        String host = link.getHost().toLowerCase(Locale.ROOT);
        String path = link.getPath();
        if ((host.equals("ximalaya.com") || host.endsWith(".ximalaya.com")) && path.matches("/album/[0-9]{1,12}/?")) {
            String id = path.split("/")[2];
            return "https://www.ximalaya.com/album/" + id;
        }
        if (host.matches("(?:[a-z]+\\.)?(?:lrts\\.me|lanrentingshu\\.com)") && path.matches("/book/[0-9]{1,12}/?")) {
            String id = path.split("/")[2];
            return "https://m.lrts.me/book/" + id;
        }
        throw new IOException("请粘贴喜马拉雅或懒人听书的专辑页面链接。");
    }

    static Catalog fromText(String text, int lastEpisode) throws IOException {
        Titles titles = new Titles(lastEpisode);
        for (String line : text.split("\\R")) {
            titles.add(line);
        }
        return titles.catalog("");
    }

    static DirectoryPage page(Context context, String source, int page) throws IOException, JSONException {
        if (source.startsWith("https://m.lrts.me/")) {
            String address = source + "?pageNum=" + page;
            List<String> titles = parseLanren(read(address, source, CatalogLoginActivity.Provider.LANREN.cookies(address),
                    android.webkit.WebSettings.getDefaultUserAgent(context)));
            if (titles.isEmpty()) {
                throw new IOException("懒人听书未返回目录。");
            }
            return new DirectoryPage(titles, 0, titles.get(0) + "\n" + titles.get(titles.size() - 1));
        }
        String id = source.substring(source.lastIndexOf('/') + 1);
        String address = "https://www.ximalaya.com/revision/album/v1/getTracksList?albumId=" + id
                + "&pageNum=" + page + "&pageSize=30&sort=1";
        return parseXimalaya(read(address, source, CatalogLoginActivity.Provider.XIMALAYA.cookies(address),
                android.webkit.WebSettings.getDefaultUserAgent(context)), page);
    }

    static DirectoryPage parseXimalaya(String response, int page) throws IOException, JSONException {
        JSONObject result = new JSONObject(response);
        JSONObject data = result.optJSONObject("data");
        if (data == null || result.optInt("ret", 200) >= 400) {
            throw new IOException("喜马拉雅未开放这次目录请求，请检查登录或网页验证。");
        }
        JSONArray entries = data.optJSONArray("tracks");
        if (entries == null || data.optInt("pageNum", page) != page) {
            throw new IOException("喜马拉雅返回的目录页不匹配。");
        }
        int total = data.optInt("trackTotalCount", data.optInt("totalCount", -1));
        int size = data.optInt("pageSize", 0);
        int pages = data.optInt("totalPage", total >= 0 && size > 0 ? (total + size - 1) / size : 0);
        List<String> titles = new ArrayList<>(entries.length());
        String first = "";
        String last = "";
        for (int row = 0; row < entries.length(); row++) {
            JSONObject track = entries.getJSONObject(row);
            String title = track.optString("title");
            String key = track.optString("trackId", title);
            if (row == 0) {
                first = key;
            }
            last = key;
            titles.add(title);
        }
        if (titles.isEmpty() && page <= pages) {
            throw new IOException("目录中途返回空页，稍后重试。");
        }
        return new DirectoryPage(titles, pages, first + "\n" + last);
    }

    static Catalog mergePage(String source, Map<Integer, String> prior, List<String> rows) {
        Titles parsed = new Titles(999999);
        for (String title : rows) {
            parsed.add(title);
        }
        Map<Integer, String> names = new TreeMap<>(prior);
        names.putAll(parsed.names);
        return new Catalog(source, names);
    }

    private static List<String> parseLanren(String html) throws JSONException {
        List<String> titles = new ArrayList<>();
        Matcher menu = LANREN_MENU.matcher(html);
        if (menu.find()) {
            Matcher entries = LANREN_NAME.matcher(menu.group(1));
            while (entries.find()) {
                titles.add(String.valueOf(new JSONTokener(entries.group(1)).nextValue()));
            }
        }
        if (titles.isEmpty()) {
            Matcher visible = LANREN_VISIBLE.matcher(html);
            while (visible.find()) {
                titles.add(Html.fromHtml(visible.group(1), Html.FROM_HTML_MODE_LEGACY).toString());
            }
        }
        return titles;
    }

    private static String read(String address, String referer, String cookies, String agent) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", agent == null ? "Mozilla/5.0" : agent);
            connection.setRequestProperty("Referer", referer);
            if (cookies != null && !cookies.isEmpty()) {
                connection.setRequestProperty("Cookie", cookies);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("目录获取失败（HTTP " + status + "）。可粘贴目录文本导入。");
            }
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (output.size() + length > 4 * 1024 * 1024) {
                        throw new IOException("目录数据过大。");
                    }
                    output.write(buffer, 0, length);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static final class Titles {
        final Map<Integer, String> names = new TreeMap<>();
        final Set<Integer> ambiguous = new HashSet<>();
        final int last;

        Titles(int last) { this.last = last; }

        void add(String line) {
            Matcher match = NUMBERED.matcher(line.trim());
            if (!match.find()) {
                match = SEPARATED.matcher(line.trim());
                if (!match.find()) {
                    return;
                }
            }
            int episode = Integer.parseInt(match.group(1));
            if (episode < 1 || episode > last || ambiguous.contains(episode)) {
                return;
            }
            String name = match.group(2).trim();
            if (name.isEmpty() || name.length() > 150 || name.matches("^(?:到|至|[0-9０-９]).*[集章回].*$")) {
                return;
            }
            String other = names.putIfAbsent(episode, name);
            if (other != null && !other.equals(name)) {
                names.remove(episode);
                ambiguous.add(episode);
            }
        }

        Catalog catalog(String source) throws IOException {
            if (names.isEmpty()) {
                throw new IOException("没有找到带集号的名称。可粘贴如「第 1 集 开始」的目录文本。");
            }
            return new Catalog(source, Collections.unmodifiableMap(names));
        }
    }
}
