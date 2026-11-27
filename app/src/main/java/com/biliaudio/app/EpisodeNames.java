package com.biliaudio.app;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebSettings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
    private static final int LANREN_PAGE_SIZE = 50;
    private static final int QINGTING_PAGE_SIZE = 30;
    private static final String QINGTING = "https://www.qtfm.cn/channels/";
    private static final String FANQIE = "https://fanqienovel.com/page/";
    private static final Pattern SEARCH_PUNCTUATION = Pattern.compile("[\\p{P}\\p{Z}\\s]+");
    private static final Pattern FANQIE_STATE = Pattern.compile("window\\.__INITIAL_STATE__\\s*=\\s*");

    record Catalog(String source, Map<Integer, String> names) {
        static final Catalog EMPTY = new Catalog("", Collections.emptyMap());

        String label(int episode) {
            String title = names.get(episode);
            return "第 " + episode + " 集" + (title == null ? "" : " · " + title);
        }
    }

    private EpisodeNames() {}

    record DirectoryPage(List<String> titles, int totalPages, String marker) {}
    record Album(String url, String title, String narrator, int episodes, String platform) {}

    static List<Album> searchAlbums(Context context, String name) throws IOException {
        String title = searchKey(name);
        if (title.isEmpty()) {
            return Collections.emptyList();
        }
        String keyword = Uri.encode(name);
        String agent = WebSettings.getDefaultUserAgent(context);
        List<Album> results = new ArrayList<>();
        boolean failed = false;
        for (CatalogLoginActivity.Provider provider : CatalogLoginActivity.Provider.values()) {
            boolean lanren = provider == CatalogLoginActivity.Provider.LANREN;
            String referer = lanren ? "https://m.lrts.me/search?keyword=" + keyword + "&tabIndex=1"
                    : "https://www.ximalaya.com/search/" + keyword;
            String address = lanren
                    ? "https://m.lrts.me/ajax/searchBook?keyWord=" + keyword + "&pageNum=1&pageSize=40"
                    : "https://www.ximalaya.com/revision/search?core=album&kw=" + keyword
                            + "&page=1&rows=20&spellchecker=true";
            try {
                results.addAll(parseAlbums(read(address, referer, provider.cookies(address), agent), provider));
            } catch (IOException | JSONException error) {
                failed = true;
            }
        }
        try {
            String address = "https://search.qtfm.cn/v3/search?categoryid=0&k=" + keyword
                    + "&page=1&pagesize=20&include=channel_ondemand";
            results.addAll(parseQingtingAlbums(read(address, "https://www.qtfm.cn/", "", agent)));
        } catch (IOException | JSONException error) {
            failed = true;
        }
        results.removeIf(album -> !searchKey(album.title()).contains(title));
        if (results.isEmpty() && failed) {
            throw new IOException("暂时无法查找专辑，可重试或直接粘贴专辑链接。");
        }
        return results;
    }

    private static String searchKey(String title) {
        return SEARCH_PUNCTUATION.matcher(title.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    static List<Album> parseQingtingAlbums(String response) throws IOException, JSONException {
        JSONObject result = new JSONObject(response);
        if (result.optInt("errcode", -1) != 0) {
            throw new IOException("蜻蜓 FM 未返回专辑搜索结果。");
        }
        JSONArray entries = result.getJSONObject("data").getJSONObject("data").getJSONArray("docs");
        List<Album> albums = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < entries.length(); index++) {
            JSONObject row = entries.getJSONObject(index);
            String id = row.optString("id");
            String title = row.optString("title").trim();
            if ("channel_ondemand".equals(row.optString("type")) && id.matches("[0-9]{1,12}")
                    && !title.isEmpty() && seen.add(id)) {
                albums.add(new Album(QINGTING + id, title, row.optString("podcaster").trim(),
                        row.optInt("program_count"), "蜻蜓 FM"));
            }
        }
        return albums;
    }

    static List<Album> parseAlbums(String response, CatalogLoginActivity.Provider provider)
            throws IOException, JSONException {
        JSONObject result = new JSONObject(response);
        boolean lanren = provider == CatalogLoginActivity.Provider.LANREN;
        JSONArray entries = lanren ? result.getJSONArray("list")
                : result.getJSONObject("data").getJSONObject("result").getJSONObject("response")
                        .getJSONArray("docs");
        if (lanren ? result.optInt("status", -1) != 0 : result.optInt("ret", -1) != 200) {
            throw new IOException("平台未返回专辑搜索结果。");
        }
        String base = lanren ? "https://m.lrts.me/book/" : "https://www.ximalaya.com/album/";
        List<Album> albums = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < entries.length(); index++) {
            JSONObject row = entries.optJSONObject(index);
            if (row == null) {
                continue;
            }
            String id = row.optString("id");
            String title = row.optString(lanren ? "name" : "title").trim();
            if (id.matches("[0-9]{1,12}") && !title.isEmpty() && seen.add(id)) {
                albums.add(new Album(base + id, title, row.optString(lanren ? "announcer" : "nickname").trim(),
                        row.optInt(lanren ? "sections" : "tracks"), provider.label));
            }
        }
        return albums;
    }

    static String source(String input) throws IOException {
        URI link;
        try {
            link = URI.create(input.trim());
        } catch (IllegalArgumentException error) {
            throw new IOException("请输入完整的专辑链接。", error);
        }
        if (!"https".equalsIgnoreCase(link.getScheme()) || link.getHost() == null || link.getUserInfo() != null) {
            throw new IOException("请输入完整的 HTTPS 专辑或原著链接。");
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
        if (host.matches("(?:[a-z]+\\.)?(?:qtfm\\.cn|qingting\\.fm)")
                && path.matches("/v?channels/[0-9]{1,12}/?")) {
            return QINGTING + path.split("/")[2];
        }
        if (host.matches("(?:www\\.)?fanqienovel\\.com") && path.matches("/page/[0-9]{1,22}/?")) {
            return FANQIE + path.split("/")[2];
        }
        throw new IOException("支持喜马拉雅、懒人听书、蜻蜓 FM 专辑或番茄小说作品链接。");
    }

    static boolean originalChapters(String source) {
        return source.startsWith(FANQIE);
    }

    static Catalog fromText(String text, int lastEpisode) throws IOException {
        Titles titles = new Titles(lastEpisode);
        for (String line : text.split("\\R")) {
            titles.add(line);
        }
        return titles.catalog("");
    }

    static DirectoryPage page(Context context, String source, int page) throws IOException, JSONException {
        String id = source.substring(source.lastIndexOf('/') + 1);
        String agent = WebSettings.getDefaultUserAgent(context);
        if (source.startsWith(QINGTING)) {
            String base = "https://i.qtfm.cn/capi/";
            JSONObject channel = new JSONObject(read(base + "v3/channel/" + id + "?user_id=0", source, "", agent))
                    .getJSONObject("data");
            String version = channel.optString("v");
            if (!id.equals(channel.optString("id")) || !version.matches("[0-9a-fA-F]{32}")) {
                throw new IOException("蜻蜓 FM 未返回此书的目录版本。");
            }
            String address = base + "channel/" + id + "/programs/" + version + "?curpage=" + page
                    + "&pagesize=" + QINGTING_PAGE_SIZE + "&order=asc";
            return parseQingtingPage(read(address, source, "", agent), page);
        }
        if (originalChapters(source)) {
            return parseFanqiePage(read(source, source, "", agent), id);
        }
        if (source.startsWith("https://m.lrts.me/")) {
            String address = "https://m.lrts.me/ajax/getBookMenu?bookId=" + id + "&pageNum=" + page
                    + "&pageSize=" + LANREN_PAGE_SIZE + "&sortType=0&isUp=0";
            return parseLanren(read(address, source, CatalogLoginActivity.Provider.LANREN.cookies(address), agent), id, page);
        }
        String address = "https://www.ximalaya.com/revision/album/getTracksList?albumId=" + id
                + "&pageNum=" + page + "&pageSize=30&sort=1";
        return parseXimalaya(read(address, source, CatalogLoginActivity.Provider.XIMALAYA.cookies(address), agent), page);
    }

    static DirectoryPage parseQingtingPage(String response, int page) throws IOException, JSONException {
        JSONObject result = new JSONObject(response);
        JSONObject data = result.getJSONObject("data");
        if (result.optInt("errorno", -1) != 0 || data.optInt("curpage") != page) {
            throw new IOException("蜻蜓 FM 返回的目录页不匹配。");
        }
        JSONArray entries = data.getJSONArray("programs");
        int total = data.optInt("total");
        int size = data.optInt("pagesize");
        int pages = total > 0 && size > 0 ? 1 + (total - 1) / size : 0;
        if (entries.length() == 0 && (page == 1 || page <= pages)) {
            throw new IOException("蜻蜓 FM 返回空目录页，请稍后重试。");
        }
        List<String> titles = new ArrayList<>(entries.length());
        for (int index = 0; index < entries.length(); index++) {
            titles.add(entries.getJSONObject(index).getString("title"));
        }
        String marker = entries.length() == 0 ? "" : entries.getJSONObject(0).getString("id") + "\n"
                + entries.getJSONObject(entries.length() - 1).getString("id");
        return new DirectoryPage(titles, pages, marker);
    }

    static DirectoryPage parseFanqiePage(String html, String id) throws IOException, JSONException {
        Matcher state = FANQIE_STATE.matcher(html);
        if (!state.find()) {
            throw new IOException("番茄小说未返回公开章节目录。");
        }
        JSONObject book = new JSONObject(html.substring(state.end())).getJSONObject("page");
        if (!id.equals(book.optString("bookId"))) {
            throw new IOException("番茄小说返回的目录与作品不匹配。");
        }
        JSONArray volumes = book.getJSONArray("chapterListWithVolume");
        List<String> titles = new ArrayList<>();
        for (int volume = 0; volume < volumes.length(); volume++) {
            JSONArray chapters = volumes.getJSONArray(volume);
            for (int chapter = 0; chapter < chapters.length(); chapter++) {
                titles.add(chapters.getJSONObject(chapter).getString("title"));
            }
        }
        if (titles.isEmpty()) {
            throw new IOException("番茄小说未返回公开章节目录。");
        }
        return new DirectoryPage(titles, 1, id);
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

    static DirectoryPage parseLanren(String response, String bookId, int page)
            throws IOException, JSONException {
        JSONObject result = new JSONObject(response);
        int status = result.optInt("status", -1);
        if (status == 483) {
            throw new IOException("懒人听书要求网页验证。请在账号页完成验证后重试，或粘贴目录文本。");
        }
        JSONArray entries = result.optJSONArray("list");
        if (status != 0 || entries == null || !bookId.equals(result.optString("bookId"))) {
            throw new IOException("懒人听书未返回此书的目录，请稍后重试。");
        }
        int total = result.optInt("sections", 0);
        int pages = total > 0 ? 1 + (total - 1) / LANREN_PAGE_SIZE : 0;
        List<String> titles = new ArrayList<>(entries.length());
        String first = "";
        String last = "";
        for (int row = 0; row < entries.length(); row++) {
            JSONObject entry = entries.optJSONObject(row);
            if (entry == null) {
                continue;
            }
            String title = entry.optString("name");
            last = entry.optString("id", title);
            if (titles.isEmpty()) {
                first = last;
            }
            titles.add(title);
        }
        if (titles.isEmpty() && (page == 1 || total <= 0 || page <= pages)) {
            throw new IOException("懒人听书返回空目录页，请稍后重试。");
        }
        return new DirectoryPage(titles, pages, first + "\n" + last);
    }

    private static String read(String address, String referer, String cookies, String agent) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", agent == null ? "Mozilla/5.0" : agent);
            connection.setRequestProperty("Referer", referer);
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
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
