package com.biliaudio.app;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CaptionText {
    private static final int MAX_LENGTH = 36;
    private static final int MIN_LINE_MILLIS = 700;
    private static final String SENTENCE_END = "。！？!?；;…";
    private static final String CLAUSE_END = SENTENCE_END + "，、,：:";
    private static final String CLOSING_QUOTES = "”’」』）》】\"'";
    private static final Pattern ANNOUNCEMENT = Pattern.compile(
            "^[\\s第]*[0-9０-９零〇一二两兩三四五六七八九十百千万萬]+[集章回]");

    private CaptionText() {}

    record Pause(int start, int end) {}

    static int closestCue(List<AudioLibrary.Cue> cues, int position) {
        int lower = 0;
        int upper = cues.size() - 1;
        while (lower <= upper) {
            int middle = (lower + upper) >>> 1;
            if (cues.get(middle).start() > position) {
                upper = middle - 1;
            } else {
                lower = middle + 1;
            }
        }
        return Math.max(0, upper);
    }

    static List<AudioLibrary.Cue> format(List<AudioLibrary.Cue> source) {
        return format(source, Collections.emptyList());
    }

    static List<AudioLibrary.Cue> format(List<AudioLibrary.Cue> source, List<Pause> pauses) {
        List<AudioLibrary.Cue> pieces = new ArrayList<>(source.size());
        for (AudioLibrary.Cue cue : source) {
            String text = cue.text().replaceAll("\\s+", " ").trim();
            if (!text.isEmpty()) {
                splitAtPauses(pieces, cue, text, pauses);
            }
        }
        List<AudioLibrary.Cue> result = new ArrayList<>(source.size());
        for (AudioLibrary.Cue cue : pieces) {
            String text = cue.text();
            if (!result.isEmpty()) {
                AudioLibrary.Cue previous = result.get(result.size() - 1);
                int gap = cue.start() - previous.end();
                if (gap >= -180 && gap <= 400 && cue.start() >= previous.start()
                        && !pauseBetween(pauses, previous.end(), cue.start())
                        && !endsWith(previous.text(), SENTENCE_END)
                        && (previous.text().length() <= 15 || !endsWith(previous.text(), CLAUSE_END))
                        && previous.text().length() + text.length() < MAX_LENGTH
                        && cue.end() - previous.start() <= 6500
                        && !ANNOUNCEMENT.matcher(previous.text()).find() && !ANNOUNCEMENT.matcher(text).find()) {
                    char left = previous.text().charAt(previous.text().length() - 1);
                    char right = text.charAt(0);
                    String space = asciiWord(left) && asciiWord(right) ? " " : "";
                    result.set(result.size() - 1, new AudioLibrary.Cue(previous.start(),
                            Math.max(previous.end(), cue.end()), previous.text() + space + text));
                    continue;
                }
            }
            splitLong(result, cue.start(), cue.end(), text);
        }
        return result;
    }

    private static void splitAtPauses(List<AudioLibrary.Cue> result, AudioLibrary.Cue cue,
                                      String text, List<Pause> pauses) {
        int first = firstPauseAfter(pauses, cue.start());
        int last = first;
        int speech = cue.end() - cue.start();
        while (last < pauses.size() && pauses.get(last).start() < cue.end()) {
            Pause pause = pauses.get(last++);
            speech -= Math.min(cue.end(), pause.end()) - Math.max(cue.start(), pause.start());
        }
        int start = 0;
        int time = cue.start();
        if (speech > 0 && last > first && text.length() >= 8) {
            BreakIterator words = BreakIterator.getWordInstance(Locale.SIMPLIFIED_CHINESE);
            words.setText(text);
            Matcher announcement = ANNOUNCEMENT.matcher(text);
            int announcementEnd = announcement.find() ? announcement.end() : 0;
            int quiet = 0;
            for (int index = first; index < last; index++) {
                Pause pause = pauses.get(index);
                int onset = Math.max(cue.start(), pause.start());
                int spoken = onset - cue.start() - quiet;
                quiet += Math.min(cue.end(), pause.end()) - onset;
                if (onset - time < MIN_LINE_MILLIS || cue.end() - pause.end() < MIN_LINE_MILLIS) {
                    continue;
                }
                // shortcut: cue text has no word timestamps, so map speech time to nearby word
                // boundaries. Use token alignment if the source starts supplying word timestamps.
                int target = (int) Math.round((double) spoken * text.length() / speech);
                int end = nearbyBoundary(words, text, Math.max(start + 4, announcementEnd), target);
                if (end > start) {
                    result.add(new AudioLibrary.Cue(time, onset, text.substring(start, end).trim()));
                    time = pause.end();
                    start = end;
                }
            }
        }
        result.add(new AudioLibrary.Cue(time, cue.end(), text.substring(start).trim()));
    }

    private static int nearbyBoundary(BreakIterator words, String text, int minimum, int target) {
        int selected = -1;
        int best = Integer.MAX_VALUE;
        int limit = Math.min(text.length() - 4, target + 4);
        for (int end = words.following(Math.max(minimum - 1, target - 5));
                end != BreakIterator.DONE && end <= limit; end = words.next()) {
            char next = text.charAt(end);
            if (CLAUSE_END.indexOf(next) >= 0 || CLOSING_QUOTES.indexOf(next) >= 0) {
                continue;
            }
            int score = Math.abs(end - target) - (endsAt(text, end, CLAUSE_END) ? 3 : 0);
            if (score < best) {
                selected = end;
                best = score;
            }
        }
        return selected;
    }

    private static boolean pauseBetween(List<Pause> pauses, int end, int start) {
        int index = firstPauseAfter(pauses, Math.min(end, start) - 80);
        return index < pauses.size() && pauses.get(index).start() <= Math.max(end, start) + 80;
    }

    private static int firstPauseAfter(List<Pause> pauses, int time) {
        int low = 0;
        int high = pauses.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (pauses.get(middle).end() <= time) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static void splitLong(List<AudioLibrary.Cue> result, int cueStart, int cueEnd, String text) {
        int start = 0;
        int time = cueStart;
        int duration = cueEnd - time;
        while (text.length() - start > MAX_LENGTH) {
            int end = splitPoint(text, start);
            int next = cueStart + (int) ((long) duration * end / text.length());
            if (end == text.length() || next - time < MIN_LINE_MILLIS || cueEnd - next < MIN_LINE_MILLIS) {
                break;
            }
            result.add(new AudioLibrary.Cue(time, next, text.substring(start, end).trim()));
            time = next;
            start = end;
        }
        result.add(new AudioLibrary.Cue(time, cueEnd, text.substring(start).trim()));
    }

    private static int splitPoint(String text, int start) {
        int clause = -1;
        int limit = Math.min(start + MAX_LENGTH, text.length() - 8);
        for (int index = start + 12; index < limit; index++) {
            char mark = text.charAt(index);
            if (CLAUSE_END.indexOf(mark) >= 0) {
                int end = index + 1;
                while (end < text.length() && CLOSING_QUOTES.indexOf(text.charAt(end)) >= 0) {
                    end++;
                }
                if (SENTENCE_END.indexOf(mark) >= 0) {
                    return end;
                }
                clause = end;
            }
        }
        return clause < 0 ? text.length() : clause;
    }

    private static boolean endsWith(String text, String punctuation) {
        return endsAt(text, text.length(), punctuation);
    }

    private static boolean endsAt(String text, int end, String punctuation) {
        int index = end - 1;
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
            index--;
        }
        while (index >= 0 && CLOSING_QUOTES.indexOf(text.charAt(index)) >= 0) {
            index--;
        }
        return index >= 0 && punctuation.indexOf(text.charAt(index)) >= 0;
    }

    private static boolean asciiWord(char character) {
        return character < 128 && Character.isLetterOrDigit(character);
    }
}
