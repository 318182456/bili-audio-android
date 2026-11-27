package com.biliaudio.app;

import java.util.List;

final class BookSections {
    private BookSections() {}

    record Entry(AudioLibrary.Part part, int segment, String name, String detail,
                 boolean stored, int start, int end, boolean hasSubtitles) {
        Entry(AudioLibrary.Part part, int segment, String name, String detail, boolean stored) {
            this(part, segment, name, detail, stored, 0, part.duration(), false);
        }

        boolean contains(long cid, int position) {
            int at = Math.min(position, part.duration() - 1);
            return part.cid() == cid && at >= start && at < end;
        }
    }

    record EpisodeGroup(int first, int last, int pending, List<Entry> entries) {
        String range() { return first == last ? String.valueOf(first) : first + "–" + last; }
        String name() { return "第 " + range() + " 集"; }
        String detail() {
            int playable = entries.size() - pending;
            return pending > 0 ? playable + " 集 · " + pending + " 待分" : playable + " 集可播放";
        }

    }

    record Section(AudioLibrary.Title book, String displayName, String summary,
                   List<Entry> entries) {}
}
