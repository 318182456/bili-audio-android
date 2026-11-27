package com.biliaudio.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AudioLibrary {
    static final Pattern BVID = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern RANGE = Pattern.compile(
            "(?:^|_|第|\\s)([0-9]{1,6})\\s*(?:集?\\s*[_–—~～-]|至|到)\\s*"
                    + "第?([0-9]{1,6})(?:集|章|wan)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEDIA_FILE = Pattern.compile(
            "^(\\d+)\\.m4a(?:\\.\\d+\\.part|\\.asr(?:\\.new|\\.bak)?|\\.compact)?$");

    record Cue(int start, int end, String text) {}

    record Part(String bvid, long cid, int page, String name, int duration, int first, int last) {
        static Part fromJson(String bvid, JSONObject source) throws JSONException {
            String name = source.optString("part");
            int page = source.getInt("page");
            int first = page;
            int last = page;
            Matcher match = RANGE.matcher(name);
            if (match.find()) {
                int start = Integer.parseInt(match.group(1));
                int end = Integer.parseInt(match.group(2));
                if (start > 0 && end >= start && end - start < 500) {
                    first = start;
                    last = end;
                }
            }
            return new Part(bvid, source.getLong("cid"), page, name,
                    Math.toIntExact(source.getLong("duration") * 1000), first, last);
        }

        int count() {
            return last - first + 1;
        }

        String label() {
            return first == last ? "第 " + first + " 集 · P" + page
                    : "第 " + first + "–" + last + " 集 · P" + page;
        }
    }

    record Title(String bvid, String name, String cover, List<Part> parts) {
        static Title fromJson(JSONObject source) throws JSONException {
            String bvid = source.getString("bvid");
            if (!BVID.matcher(bvid).matches()) {
                throw new JSONException("无效的 BV 号");
            }
            JSONArray pages = source.getJSONArray("pages");
            List<Part> parts = new ArrayList<>(pages.length());
            for (int index = 0; index < pages.length(); index++) {
                parts.add(Part.fromJson(bvid, pages.getJSONObject(index)));
            }
            if (parts.isEmpty()) {
                throw new JSONException("作品没有可用分 P");
            }
            return new Title(bvid, source.getString("title"), source.optString("pic"),
                    Collections.unmodifiableList(parts));
        }
    }

    record Segment(int number, int start, int end) {}
    record Analysis(String source, List<Cue> cues, List<Integer> starts, boolean confirmed) {
        static Analysis empty() {
            return new Analysis("", Collections.emptyList(), Collections.emptyList(), false);
        }
    }

    private final File directory;
    private final SharedPreferences bookNames;
    private final Map<String, SavedNames> episodeNames = new HashMap<>();
    private record SavedNames(long version, EpisodeNames.Catalog catalog) {}

    AudioLibrary(Context context) {
        directory = new File(context.getFilesDir(), "library");
        bookNames = context.getSharedPreferences("book_names", Context.MODE_PRIVATE);
    }

    String displayName(Title title) {
        return bookNames.getString(title.bvid(), title.name());
    }

    boolean rename(Title title, String name) {
        SharedPreferences.Editor edit = bookNames.edit();
        if (name.equals(title.name())) {
            edit.remove(title.bvid());
        } else {
            edit.putString(title.bvid(), name);
        }
        return edit.commit();
    }

    synchronized EpisodeNames.Catalog episodeNames(String bvid) {
        File file = new File(titleDir(bvid), "episode-titles.json");
        long version = file.lastModified();
        SavedNames saved = episodeNames.get(bvid);
        if (saved != null && saved.version() == version) {
            return saved.catalog();
        }
        EpisodeNames.Catalog result = EpisodeNames.Catalog.EMPTY;
        if (version != 0) {
            try {
                JSONObject data = read(file);
                JSONObject names = data.getJSONObject("names");
                Map<Integer, String> loaded = new java.util.TreeMap<>();
                java.util.Iterator<String> keys = names.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    loaded.put(Integer.parseInt(key), names.getString(key));
                }
                result = new EpisodeNames.Catalog(data.optString("source"), Collections.unmodifiableMap(loaded));
            } catch (IOException | JSONException | NumberFormatException ignored) {
                // Missing or damaged chapter names leave the original episode numbers available.
            }
        }
        episodeNames.put(bvid, new SavedNames(version, result));
        return result;
    }

    synchronized void saveEpisodeNames(String bvid, EpisodeNames.Catalog catalog) throws IOException, JSONException {
        JSONObject names = new JSONObject();
        for (Map.Entry<Integer, String> entry : catalog.names().entrySet()) {
            names.put(Integer.toString(entry.getKey()), entry.getValue());
        }
        write(new File(titleDir(bvid), "episode-titles.json"),
                new JSONObject().put("source", catalog.source()).put("names", names));
        episodeNames.remove(bvid);
    }

    Title saveTitle(JSONObject data) throws IOException, JSONException {
        Title title = Title.fromJson(data);
        write(new File(titleDir(title.bvid()), "source.json"), data);
        return title;
    }

    Title title(String bvid) throws IOException, JSONException {
        return Title.fromJson(read(new File(titleDir(bvid), "source.json")));
    }

    List<Title> titles() {
        File[] items = directory.listFiles(File::isDirectory);
        if (items == null) {
            return Collections.emptyList();
        }
        Arrays.sort(items, Comparator.comparingLong(File::lastModified).reversed());
        List<Title> titles = new ArrayList<>();
        for (File item : items) {
            try {
                titles.add(title(item.getName()));
            } catch (IOException | JSONException | IllegalArgumentException ignored) {
                // A damaged entry should not hide the rest of the library.
            }
        }
        return titles;
    }

    File audio(Part part) {
        return new File(titleDir(part.bvid()), part.cid() + ".m4a");
    }

    boolean hasAudio(String bvid, long cid) {
        return BVID.matcher(bvid).matches() && new File(titleDir(bvid), cid + ".m4a").isFile();
    }

    boolean hasPendingTranscription(Part part) {
        return new File(audio(part).getPath() + ".asr").isFile();
    }

    Map<Long, Long> mediaSizes(Title title) {
        Map<Long, Long> sizes = new HashMap<>();
        File[] files = titleDir(title.bvid()).listFiles(File::isFile);
        if (files != null) {
            for (File file : files) {
                Matcher name = MEDIA_FILE.matcher(file.getName());
                if (name.matches()) {
                    sizes.merge(Long.parseLong(name.group(1)), file.length(), Long::sum);
                }
            }
        }
        return sizes;
    }

    void deleteMedia(Part part) throws IOException, JSONException {
        updateAnalysis(part, old -> new Analysis("", Collections.emptyList(), old.starts(), old.confirmed()));
        String cid = String.valueOf(part.cid());
        File[] files = titleDir(part.bvid()).listFiles(File::isFile);
        if (files != null) {
            boolean failed = false;
            for (File file : files) {
                Matcher name = MEDIA_FILE.matcher(file.getName());
                if (name.matches() && cid.equals(name.group(1)) && !file.delete() && file.exists()) {
                    failed = true;
                }
            }
            if (failed) {
                throw new IOException("部分本地文件无法删除，请重试。");
            }
        }
    }

    Analysis analysis(Part part) throws IOException, JSONException {
        File file = analysisFile(part);
        if (!file.isFile()) {
            return Analysis.empty();
        }
        JSONObject data = read(file);
        JSONArray entries = data.getJSONArray("cues");
        List<Cue> cues = new ArrayList<>(entries.length());
        for (int index = 0; index < entries.length(); index++) {
            JSONArray cue = entries.getJSONArray(index);
            cues.add(new Cue(cue.getInt(0), cue.getInt(1), cue.getString(2)));
        }
        JSONArray entriesStarts = data.getJSONArray("starts");
        List<Integer> starts = new ArrayList<>(entriesStarts.length());
        for (int index = 0; index < entriesStarts.length(); index++) {
            starts.add(entriesStarts.getInt(index));
        }
        return new Analysis(data.optString("source"), cues, starts, data.optBoolean("confirmed"));
    }

    void saveAnalysis(Part part, Analysis analysis) throws IOException, JSONException {
        JSONArray cues = new JSONArray();
        for (Cue cue : analysis.cues()) {
            cues.put(new JSONArray().put(cue.start()).put(cue.end()).put(cue.text()));
        }
        write(analysisFile(part), new JSONObject().put("source", analysis.source())
                .put("cues", cues).put("starts", new JSONArray(analysis.starts()))
                .put("confirmed", analysis.confirmed()));
    }

    long analysisVersion(Part part) {
        return analysisFile(part).lastModified();
    }

    Analysis resolveCuts(Part part) throws IOException, JSONException {
        List<Integer> verified = EpisodeCuts.verifiedChapters(part);
        return updateAnalysis(part, old -> {
            if (old.confirmed() || (part.count() > 1 && old.cues().isEmpty() && verified.isEmpty())) {
                return old;
            }
            Analysis detected = EpisodeCuts.analyze(part, old.source(), old.cues(), verified, old);
            return detected.confirmed() || detected.starts().size() >= old.starts().size() ? detected : old;
        });
    }

    Analysis updateAnalysis(Part part, UnaryOperator<Analysis> change) throws IOException, JSONException {
        synchronized (AudioLibrary.class) {
            Analysis previous = analysis(part);
            Analysis updated = change.apply(previous);
            if (!updated.equals(previous)) {
                saveAnalysis(part, updated);
            }
            return updated;
        }
    }

    static List<Segment> segments(Part part, Analysis result) {
        if (!result.confirmed()) {
            int episode = part.count() == 1 ? part.first() : 0;
            return Collections.singletonList(new Segment(episode, 0, part.duration()));
        }
        List<Segment> segments = new ArrayList<>(result.starts().size() + 1);
        int start = 0;
        int number = part.first();
        for (int next : result.starts()) {
            segments.add(new Segment(number++, start, next));
            start = next;
        }
        segments.add(new Segment(number, start, part.duration()));
        return segments;
    }

    static String time(int millis) {
        long seconds = millis / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d",
                seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private File titleDir(String bvid) {
        if (!BVID.matcher(bvid).matches()) {
            throw new IllegalArgumentException("无效的 BV 号");
        }
        return new File(directory, bvid);
    }

    private File analysisFile(Part part) {
        return new File(titleDir(part.bvid()), part.cid() + ".json");
    }

    static synchronized JSONObject read(File file) throws IOException, JSONException {
        return new JSONObject(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
    }

    static synchronized void write(File file, JSONObject data) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = atomic.startWrite();
        try {
            stream.write(data.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(stream);
        } catch (IOException exception) {
            atomic.failWrite(stream);
            throw exception;
        }
    }
}
