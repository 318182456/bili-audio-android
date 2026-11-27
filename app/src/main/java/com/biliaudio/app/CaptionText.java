package com.biliaudio.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class CaptionText {
    private static final int MAX_LENGTH = 36;
    private static final String SENTENCE_END = "。！？!?；;…";
    private static final String CLAUSE_END = SENTENCE_END + "，、,：:";
    private static final String CLOSING_QUOTES = "”’」』）》】\"'";
    private static final Pattern ANNOUNCEMENT = Pattern.compile(
            "^[\\s第]*[0-9０-９零〇一二两兩三四五六七八九十百千万萬]+[集章回]");

    private CaptionText() {}

    static List<AudioLibrary.Cue> format(List<AudioLibrary.Cue> source) {
        List<AudioLibrary.Cue> result = new ArrayList<>(source.size());
        for (AudioLibrary.Cue cue : source) {
            String text = cue.text().replaceAll("\\s+", " ").trim();
            if (text.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                AudioLibrary.Cue previous = result.get(result.size() - 1);
                int gap = cue.start() - previous.end();
                if (gap >= -180 && gap <= 400 && cue.start() >= previous.start()
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
            split(result, cue, text);
        }
        return result;
    }

    private static void split(List<AudioLibrary.Cue> result, AudioLibrary.Cue cue, String text) {
        int start = 0;
        int time = cue.start();
        int duration = cue.end() - time;
        while (text.length() - start > MAX_LENGTH) {
            int end = splitPoint(text, start);
            int next = cue.start() + (int) ((long) duration * end / text.length());
            if (end == text.length() || next - time < 700 || cue.end() - next < 700) {
                break;
            }
            result.add(new AudioLibrary.Cue(time, next, text.substring(start, end).trim()));
            time = next;
            start = end;
        }
        result.add(new AudioLibrary.Cue(time, cue.end(), text.substring(start).trim()));
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
        int index = text.length() - 1;
        while (index >= 0 && CLOSING_QUOTES.indexOf(text.charAt(index)) >= 0) {
            index--;
        }
        return index >= 0 && punctuation.indexOf(text.charAt(index)) >= 0;
    }

    private static boolean asciiWord(char character) {
        return character < 128 && Character.isLetterOrDigit(character);
    }
}
