package com.biliaudio.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EpisodeCuts {
    private static final String EPISODE_NUMBER = "([0-9０-９零〇一二两兩三四五六七八九十百千万萬\\h]{1,16})";
    private static final String CLAUSE_END = "\\p{Punct}，。！？：、；…」』】";
    private static final Pattern ANNOUNCEMENT = Pattern.compile("第\\h*" + EPISODE_NUMBER + "[集章回]");
    private static final Pattern HOMOPHONE = Pattern.compile("第\\h*" + EPISODE_NUMBER + "[级級]");
    private static final Pattern BARE_TITLE = Pattern.compile("^[\\h" + CLAUSE_END + "]*"
            + EPISODE_NUMBER + "[集章回](?=$|[\\h" + CLAUSE_END + "])");
    private static final Pattern CONTINUATION = Pattern.compile("^\\h*" + EPISODE_NUMBER + "[集章回]");
    private static final String DIGITS = "零一二三四五六七八九";

    private EpisodeCuts() {}

    static List<Integer> verifiedChapters(AudioLibrary.Part part) {
        // The first upload part was checked against all five spoken episode titles.
        if (part.cid() == 41274903613L && part.bvid().equals("BV1Tv8Q6KEyw") && part.count() == 5) {
            return Arrays.asList(0, 492300, 946060, 1423940, 1899230);
        }
        return Collections.emptyList();
    }

    static AudioLibrary.Analysis analyze(AudioLibrary.Part part, String source,
                                         List<AudioLibrary.Cue> cues, List<Integer> chapters,
                                         AudioLibrary.Analysis previous) {
        if (previous.confirmed()) {
            return new AudioLibrary.Analysis(source, cues, previous.starts(), true);
        }
        if (part.count() == 1) {
            return new AudioLibrary.Analysis(source, cues, Collections.emptyList(), true);
        }
        if (chapters.size() == part.count() && chapters.get(0) <= 2000) {
            List<Integer> boundaries = chapters.subList(1, chapters.size());
            if (valid(part, boundaries)) {
                return new AudioLibrary.Analysis(source, cues, new ArrayList<>(boundaries), true);
            }
        }
        Map<Integer, Integer> candidates = new HashMap<>();
        Map<Integer, Integer> homophones = new HashMap<>();
        AudioLibrary.Cue preceding = null;
        for (AudioLibrary.Cue cue : cues) {
            String text = cue.text();
            Matcher match = ANNOUNCEMENT.matcher(text);
            while (match.find()) {
                if (match.start() <= 8 || clauseStartsAt(text, match.start()) || match.end() == text.length()) {
                    int offset = (int) ((long) (cue.end() - cue.start()) * match.start() / text.length());
                    addCandidate(part, candidates, match.group(1), cue.start() + offset);
                }
            }
            Matcher homophone = HOMOPHONE.matcher(text);
            if (homophone.find() && clauseStartsAt(text, homophone.start())) {
                int offset = (int) ((long) (cue.end() - cue.start()) * homophone.start() / text.length());
                addCandidate(part, homophones, homophone.group(1), cue.start() + offset);
            }
            if (preceding != null && preceding.text().trim().endsWith("第")
                    && cue.start() - preceding.end() <= 1200) {
                Matcher continuation = CONTINUATION.matcher(text);
                if (continuation.find()) {
                    addCandidate(part, candidates, continuation.group(1), cue.start());
                }
            } else {
                Matcher bare = BARE_TITLE.matcher(text);
                if (bare.find()) {
                    addCandidate(part, candidates, bare.group(1), cue.start());
                }
            }
            preceding = cue;
        }
        for (Map.Entry<Integer, Integer> fallback : homophones.entrySet()) {
            int episode = fallback.getKey();
            int start = fallback.getValue();
            int before = episode == part.first() + 1 ? 0 : candidates.getOrDefault(episode - 1, -1);
            int after = episode == part.last() ? part.duration() : candidates.getOrDefault(episode + 1, -1);
            if (!candidates.containsKey(episode) && before >= 0 && after > before
                    && start > before + (after - before) / 4 && start < after - (after - before) / 4) {
                candidates.put(episode, start);
            }
        }
        List<Integer> starts = new ArrayList<>(candidates.size());
        for (int number = part.first() + 1; number <= part.last(); number++) {
            int candidate = candidates.getOrDefault(number, -1);
            if (candidate > 0) {
                starts.add(candidate);
            }
        }
        boolean complete = valid(part, starts);
        starts.sort(Integer::compare);
        return new AudioLibrary.Analysis(source, cues, starts, complete);
    }

    private static boolean clauseStartsAt(String text, int start) {
        int index = start - 1;
        while (index >= 0 && Character.isSpaceChar(text.charAt(index))) {
            index--;
        }
        return index < 0 || "，。！？：、；,.!?:;\n「『【“".indexOf(text.charAt(index)) >= 0;
    }

    private static void addCandidate(AudioLibrary.Part part, Map<Integer, Integer> candidates,
                                     String text, int start) {
        int episode = number(text);
        int boundary = start - 250;
        if (episode > part.first() && episode <= part.last()
                && boundary >= 1000 && part.duration() - boundary >= 1000) {
            candidates.merge(episode, boundary, (first, next) ->
                    first < 0 || Math.abs(next - first) > 15000 ? -1 : Math.min(first, next));
        }
    }

    static boolean valid(AudioLibrary.Part part, List<Integer> starts) {
        if (starts.size() != part.count() - 1) {
            return false;
        }
        return validManual(part, starts);
    }

    static boolean validManual(AudioLibrary.Part part, List<Integer> starts) {
        if (starts.size() > 499 || (part.count() > 1 && starts.size() != part.count() - 1)) {
            return false;
        }
        int previous = 0;
        for (int start : starts) {
            if (start - previous < 1000 || part.duration() - start < 1000) {
                return false;
            }
            previous = start;
        }
        return true;
    }

    private static int number(String text) {
        int total = 0;
        int section = 0;
        int value = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                continue;
            }
            int digit = Character.digit(ch, 10);
            if (digit < 0) {
                digit = DIGITS.indexOf(ch);
            }
            if (ch == '两' || ch == '兩') {
                digit = 2;
            } else if (ch == '〇') {
                digit = 0;
            }
            if (digit >= 0) {
                value = value * 10 + digit;
                continue;
            }
            int unit = ch == '十' ? 10 : ch == '百' ? 100 : ch == '千' ? 1000 : 10000;
            if (unit == 10000) {
                total += (section + value) * unit;
                section = 0;
            } else {
                section += Math.max(value, 1) * unit;
            }
            value = 0;
        }
        return total + section + value;
    }
}
