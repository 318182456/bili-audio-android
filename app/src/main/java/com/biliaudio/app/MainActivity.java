package com.biliaudio.app;

import android.app.AlertDialog;
import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntPredicate;

public final class MainActivity extends AudioActivity {
    private enum Page {
        PLAYER("播放", AudioUi.Icon.HEADPHONES),
        DOWNLOAD("下载", AudioUi.Icon.DOWNLOAD),
        MANAGE("管理", AudioUi.Icon.MANAGE),
        ACCOUNT("账号", AudioUi.Icon.ACCOUNT),
        EPISODES("选集", AudioUi.Icon.LIBRARY);

        final String label;
        final AudioUi.Icon icon;

        Page(String label, AudioUi.Icon icon) {
            this.label = label;
            this.icon = icon;
        }
    }
    private enum ManageFilter {
        ALL("全部"), UNFINISHED("未完成"), RUNNING("进行中"), READY("已完成"),
        CUTS("缺切点"), CAPTIONS("缺字幕");

        final String label;

        ManageFilter(String label) {
            this.label = label;
        }

        boolean includes(boolean complete, TransferService.Status status) {
            boolean unfinished = !complete || (status != null && status.unfinished());
            return switch (this) {
                case ALL, CUTS, CAPTIONS -> true;
                case UNFINISHED -> unfinished;
                case RUNNING -> status != null && status.running();
                case READY -> !unfinished;
            };
        }
    }
    private enum EpisodeFilter {
        ALL("全部"), PENDING("待分集"), CUTS("缺切点"), CAPTIONS("缺字幕");

        final String label;

        EpisodeFilter(String label) { this.label = label; }

        boolean includes(BookSectionList.Entry entry) {
            return switch (this) {
                case ALL -> true;
                case PENDING -> entry.segment() < 0;
                case CUTS -> entry.segment() < 0 && entry.hasSubtitles();
                case CAPTIONS -> !entry.hasSubtitles();
            };
        }
    }
    private static final int EXPORT_REQUEST = 21;
    private static final int DISCOVERY_REQUEST = 23;
    private static final int MODEL_IMPORT_REQUEST = 24;
    private static final int EPISODES_PER_GROUP = 100;
    private static final String SAMPLE = "https://www.bilibili.com/video/BV1Tv8Q6KEyw/?p=1";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService catalog = Executors.newSingleThreadExecutor();
    private final EnumMap<Page, View> pages = new EnumMap<>(Page.class);
    private final EnumMap<Page, LinearLayout> tabs = new EnumMap<>(Page.class);
    private final EnumMap<Page, BookSectionList> bookLists = new EnumMap<>(Page.class);
    private final EnumMap<Page, TextView> bookHeadings = new EnumMap<>(Page.class);
    private final EnumMap<Page, TextView> bookEmpty = new EnumMap<>(Page.class);
    private final Map<AudioLibrary.Part, EpisodeIndex> episodeIndexes = new HashMap<>();

    private AudioUi.Palette palette;
    private Page currentPage = Page.PLAYER;
    private AudioLibrary library;
    private ListeningHistory history;
    private AudioLibrary.Title title;
    private AudioLibrary.Part part;
    private AudioLibrary.Analysis analysis;
    private TransferService.Status transfer;
    private boolean audioReady;
    private long analysisVersion;
    private PlaybackService playing;
    private boolean bound;
    private boolean dragging;
    private boolean showCaptions = true;
    private Bundle exportParts;
    private long lastBookRefresh;
    private boolean loadingBooks;
    private boolean accountChecking;
    private boolean locateEpisodeGroup;
    private EpisodeFilter episodeFilter = EpisodeFilter.ALL;
    private Button episodeFilterButton;
    private boolean batchManage;
    private ManageFilter manageFilter = ManageFilter.ALL;
    private List<BookSectionList.Section> managedBooks = Collections.emptyList();
    private final Set<AudioLibrary.Part> selectedParts = new HashSet<>();

    private TextView pageTitle;
    private ImageButton pageBack;
    private TextView downloadBookName;
    private ImageView downloadCover;
    private ScrollView downloadScroll;
    private EditText link;
    private Button loadLink;
    private TextView accountStatus;
    private TextView ximalayaStatus;
    private Button ximalayaAccount;
    private TextView lanrenStatus;
    private Button lanrenAccount;
    private TextView modelStatus;
    private TextView nameSyncStatus;
    private Button accountButton;
    private LinearLayout workspace;
    private Button resumeButton;
    private LinearLayout playerPanel;
    private TextView playerTitle;
    private ImageView playerCover;
    private TextView playerEpisode;
    private TextView playerTime;
    private TextView playerDuration;
    private CaptionScrubber transcript;
    private ScrollView playerLanding;
    private TextView playerError;
    private SeekBar progress;
    private ImageButton toggle;
    private Button speed;
    private Button captions;
    private Button manageFilterButton;
    private Button manageModeButton;
    private LinearLayout batchBar;
    private Button selectAllFiles;
    private TextView selectedCount;
    private Button batchActions;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            playing = ((PlaybackService.LocalBinder) binder).service();
            updatePlayer();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            playing = null;
            updatePlayer();
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        palette = AudioUi.palette(this);
        library = new AudioLibrary(this);
        history = new ListeningHistory(this);
        if (saved != null) {
            exportParts = saved.getBundle("export_parts");
            showCaptions = saved.getBoolean("captions", true);
            currentPage = Page.valueOf(saved.getString("screen", Page.PLAYER.name()));
            episodeFilter = EpisodeFilter.valueOf(saved.getString("episode_filter", EpisodeFilter.ALL.name()));
            locateEpisodeGroup = currentPage == Page.EPISODES && episodeFilter == EpisodeFilter.ALL;
            manageFilter = ManageFilter.valueOf(saved.getString("manage_filter", ManageFilter.ALL.name()));
        }

        LinearLayout base = column();
        base.setFitsSystemWindows(true);
        base.setBackgroundColor(palette.page());
        LinearLayout toolbar = row();
        toolbar.setPadding(dp(16), dp(4), dp(16), dp(5));
        pageBack = iconAction(toolbar, AudioUi.Icon.BACK, "返回播放", () -> showPage(Page.PLAYER));
        pageBack.setVisibility(View.GONE);
        pageTitle = leadingLabel("", 24, palette.paper());
        pageTitle.setTypeface(null, Typeface.BOLD);
        pageTitle.setPadding(dp(4), dp(6), 0, dp(6));
        pageTitle.setMinHeight(dp(43));
        if (Build.VERSION.SDK_INT >= 28) {
            pageTitle.setAccessibilityHeading(true);
        }
        toolbar.addView(pageTitle, new LinearLayout.LayoutParams(0, -2, 1));
        base.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout stack = new FrameLayout(this);
        base.addView(stack, new LinearLayout.LayoutParams(-1, 0, 1));

        buildHome(addPage(stack, Page.PLAYER));
        buildPlayer((FrameLayout) pages.get(Page.PLAYER));
        buildEpisodePage(addPage(stack, Page.EPISODES));
        buildDownloadPage(addPage(stack, Page.DOWNLOAD));
        buildManagePage(addPage(stack, Page.MANAGE));
        buildManageActions((LinearLayout) pages.get(Page.MANAGE));
        buildAccountPage(addPage(stack, Page.ACCOUNT));

        LinearLayout navigation = row();
        navigation.setPadding(dp(12), dp(4), dp(12), dp(5));
        navigation.setBackgroundColor(palette.surface());
        for (Page page : Page.values()) {
            if (page != Page.EPISODES) {
                tabs.put(page, navTile(navigation, page.icon, page.label, () -> showPage(page)));
            }
        }
        View navigationDivider = new View(this);
        navigationDivider.setBackgroundColor(palette.outline());
        base.addView(navigationDivider, new LinearLayout.LayoutParams(-1, dp(1)));
        base.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        setContentView(base);
        base.setFocusableInTouchMode(true);
        base.requestFocus();
        if (saved != null) {
            link.setText(saved.getString("link", ""));
            for (Map.Entry<Page, BookSectionList> list : bookLists.entrySet()) {
                list.getValue().restoreExpansion(saved.getStringArrayList("books_" + list.getKey()));
            }
        }
        restoreSelection();
        catalog.execute(() -> EpisodeNameSync.restore(this, library));
        scheduleFinishedCleanup();
        showPage(currentPage);
    }

    private LinearLayout addPage(FrameLayout stack, Page page) {
        LinearLayout body = column();
        View pageView;
        if (page == Page.ACCOUNT || page == Page.PLAYER || page == Page.DOWNLOAD) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            body.setPadding(dp(16), dp(8), dp(16), dp(18));
            scroll.addView(body);
            if (page == Page.PLAYER) {
                FrameLayout player = new FrameLayout(this);
                player.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
                playerLanding = scroll;
                pageView = player;
            } else if (page == Page.DOWNLOAD) {
                LinearLayout downloads = column();
                downloads.addView(buildDownloadBookSwitcher(), new LinearLayout.LayoutParams(-1, -2));
                downloads.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
                downloadScroll = scroll;
                pageView = downloads;
            } else {
                pageView = scroll;
            }
        } else {
            body.setPadding(dp(16), 0, dp(16), dp(8));
            BookSectionList list = new BookSectionList(this, palette, page == Page.MANAGE,
                    (book, entry) -> openBookEntry(page, book, entry), this::showEpisodeGroup, this::renameBook);
            bookLists.put(page, list);
            LinearLayout container = column();
            container.addView(body, new LinearLayout.LayoutParams(-1, -2));
            container.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            pageView = container;
        }
        stack.addView(pageView, new FrameLayout.LayoutParams(-1, -1));
        pages.put(page, pageView);
        return body;
    }

    private void showPage(Page page) {
        if (currentPage == Page.DOWNLOAD && page != Page.DOWNLOAD) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(link.getWindowToken(), 0);
            link.clearFocus();
        }
        currentPage = page;
        pageTitle.setText(page == Page.PLAYER ? getString(R.string.app_name) : page.label);
        pageBack.setVisibility(page == Page.EPISODES ? View.VISIBLE : View.GONE);
        for (Page item : Page.values()) {
            boolean selected = item == page;
            pages.get(item).setVisibility(selected ? View.VISIBLE : View.GONE);
            LinearLayout tab = tabs.get(item);
            if (tab != null) {
                boolean activeTab = selected || (item == Page.PLAYER && page == Page.EPISODES);
                tab.setSelected(activeTab);
                ImageView icon = (ImageView) tab.getChildAt(0);
                icon.setImageDrawable(AudioUi.icon(item.icon, activeTab ? palette.accent() : palette.muted()));
                icon.setBackground(activeTab ? AudioUi.card(this, palette.selected(), 16) : null);
                TextView text = (TextView) tab.getChildAt(1);
                text.setTextColor(activeTab ? palette.paper() : palette.muted());
                text.setTypeface(null, activeTab ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
        if (page == Page.ACCOUNT) {
            refreshAccount();
        } else if (page == Page.PLAYER) {
            updatePlayer();
            refreshResume();
        } else if (page == Page.DOWNLOAD) {
            renderPart();
        }
        refreshBooks();
    }

    @Override public void onBackPressed() {
        if (currentPage == Page.PLAYER) {
            super.onBackPressed();
        } else {
            showPage(Page.PLAYER);
        }
    }

    private void buildHome(LinearLayout body) {
        LinearLayout emptyPlayer = panel();
        emptyPlayer.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyPlayer.setPadding(dp(18), dp(24), dp(18), dp(18));
        ImageView artwork = iconTile(AudioUi.Icon.HEADPHONES, 84);
        emptyPlayer.addView(artwork, new LinearLayout.LayoutParams(dp(84), dp(84)));
        TextView title = label("开始收听", 24, palette.paper());
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(18), 0, dp(4));
        emptyPlayer.addView(title, new LinearLayout.LayoutParams(-1, -2));
        emptyPlayer.addView(label("从已下载的剧集继续你的听单", 14, palette.muted()),
                new LinearLayout.LayoutParams(-1, -2));
        resumeButton = button(emptyPlayer, "继续上次播放", this::resumeLast);
        AudioUi.styleButton(resumeButton, true);
        resumeButton.setLayoutParams(spaced());
        resumeButton.setVisibility(View.GONE);
        Button episodes = button(emptyPlayer, "打开选集", this::showEpisodes);
        AudioUi.styleButton(episodes, false);
        episodes.setLayoutParams(spaced());
        body.addView(emptyPlayer, spaced());
    }

    private void buildEpisodePage(LinearLayout body) {
        LinearLayout filters = row();
        TextView downloaded = leadingLabel("已下载剧集", 16, palette.paper());
        downloaded.setTypeface(null, Typeface.BOLD);
        filters.addView(downloaded, new LinearLayout.LayoutParams(0, -2, 1));
        episodeFilterButton = button(filters, "", this::chooseEpisodeFilter);
        updateEpisodeFilter();
        body.addView(filters, new LinearLayout.LayoutParams(-1, -2));
        addBookEmpty(body, Page.EPISODES, "还没有可播放的本地剧集。");
    }

    private void updateEpisodeFilter() {
        episodeFilterButton.setText("筛选 · " + episodeFilter.label);
        AudioUi.styleButton(episodeFilterButton, episodeFilter != EpisodeFilter.ALL);
    }

    private void chooseEpisodeFilter() {
        EpisodeFilter[] filters = EpisodeFilter.values();
        String[] choices = new String[filters.length];
        for (int index = 0; index < filters.length; index++) {
            choices[index] = filters[index].label;
        }
        new AlertDialog.Builder(this).setTitle("筛选剧集")
                .setSingleChoiceItems(choices, episodeFilter.ordinal(), (dialog, index) -> {
                    dialog.dismiss();
                    episodeFilter = filters[index];
                    locateEpisodeGroup = episodeFilter == EpisodeFilter.ALL;
                    updateEpisodeFilter();
                    if (!locateEpisodeGroup) {
                        bookLists.get(Page.EPISODES).openEpisodeGroup(null, 0, 0);
                    }
                    bookLists.get(Page.EPISODES).setSelection(0);
                    refreshBooks();
                }).setNegativeButton("取消", null).show();
    }

    private void showEpisodes() {
        episodeFilter = EpisodeFilter.ALL;
        locateEpisodeGroup = true;
        updateEpisodeFilter();
        showPage(Page.EPISODES);
    }

    private PlaybackService.LastPlayed episodePosition() {
        AudioLibrary.Part source = currentPart();
        if (source != null) {
            return new PlaybackService.LastPlayed(source.bvid(), source.cid(), playing.sourcePosition());
        }
        return PlaybackService.last(this);
    }

    private void showEpisodeGroup(AudioLibrary.Title book, BookSectionList.EpisodeGroup group) {
        PlaybackService.LastPlayed current = episodePosition();
        int active = current != null && book.bvid().equals(current.bvid())
                ? group.episodeAt(current.cid(), current.position()) : -1;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(group.name())
                .setAdapter(bookLists.get(Page.EPISODES).episodeRows(group, active),
                        (ignored, row) -> openBookEntry(Page.EPISODES, book, group.entries().get(row)))
                .setNegativeButton("返回选集", null).show();
        if (active >= 0) {
            dialog.getListView().setSelectionFromTop(Math.max(0, active - 2), 0);
        }
    }

    private void buildDownloadPage(LinearLayout body) {
        LinearLayout discovery = row();
        textIcon(weightedButton(discovery, "搜索 B 站", false, () -> openDiscovery(false)), AudioUi.Icon.SEARCH);
        textIcon(weightedButton(discovery, "收藏夹", false, () -> openDiscovery(true)), AudioUi.Icon.BOOKMARK);
        body.addView(discovery, new LinearLayout.LayoutParams(-1, -2));

        workspace = column();
        body.addView(workspace, spaced());

        LinearLayout importCard = panel();
        TextView importTitle = leadingLabel("从链接导入", 16, palette.paper());
        importTitle.setTypeface(null, Typeface.BOLD);
        importCard.addView(importTitle);
        LinearLayout inputRow = row();
        link = new EditText(this);
        link.setSingleLine(true);
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        link.setHint("视频链接、短链或 BV 号");
        link.setHintTextColor(palette.muted());
        link.setTextColor(palette.paper());
        link.setTextSize(15);
        link.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        link.setBackground(AudioUi.card(this, palette.field(), 13));
        link.setPadding(dp(12), dp(12), dp(12), dp(12));
        android.graphics.drawable.Drawable linkIcon = AudioUi.icon(AudioUi.Icon.LINK, palette.accent());
        linkIcon.setBounds(0, 0, dp(21), dp(21));
        link.setCompoundDrawablesRelative(linkIcon, null, null, null);
        link.setCompoundDrawablePadding(dp(9));
        LinearLayout.LayoutParams field = new LinearLayout.LayoutParams(0, -2, 1);
        field.setMarginEnd(dp(8));
        inputRow.addView(link, field);
        loadLink = button(inputRow, "读取分集", () -> load(link.getText().toString()));
        AudioUi.styleButton(loadLink, false);
        importCard.addView(inputRow, spaced());
        body.addView(importCard, spaced());
    }

    private View buildDownloadBookSwitcher() {
        LinearLayout frame = column();
        frame.setPadding(dp(16), 0, dp(16), dp(4));
        LinearLayout books = row();
        books.setPadding(dp(10), dp(8), dp(8), dp(8));
        books.setBackground(AudioUi.card(this, palette.surface(), 17));
        downloadCover = iconTile(AudioUi.Icon.HEADPHONES, 42);
        books.addView(downloadCover, new LinearLayout.LayoutParams(dp(42), dp(42)));
        downloadBookName = leadingLabel("尚未添加书籍", 15, palette.paper());
        downloadBookName.setTypeface(null, Typeface.BOLD);
        downloadBookName.setMaxLines(2);
        downloadBookName.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams name = new LinearLayout.LayoutParams(0, -2, 1);
        name.setMargins(dp(10), 0, dp(4), 0);
        books.addView(downloadBookName, name);
        AudioUi.styleButton(button(books, "切换书籍", this::chooseDownloadBook), false);
        frame.addView(books, new LinearLayout.LayoutParams(-1, -2));
        return frame;
    }

    private void buildManagePage(LinearLayout body) {
        LinearLayout summary = row();
        TextView storage = leadingLabel("本地文件", 16, palette.paper());
        storage.setTypeface(null, Typeface.BOLD);
        summary.addView(storage, new LinearLayout.LayoutParams(0, -2, 1));
        bookHeadings.put(Page.MANAGE, storage);
        Switch cleanup = new Switch(this);
        cleanup.setText("听完清理");
        cleanup.setTextColor(palette.muted());
        cleanup.setMinHeight(dp(48));
        cleanup.setContentDescription("听完整个分 P 后自动清理本地音频和字幕");
        cleanup.setChecked(history.autoCleanup());
        cleanup.setOnCheckedChangeListener((view, value) -> {
            history.setAutoCleanup(value);
            if (value) {
                scheduleFinishedCleanup();
            }
        });
        summary.addView(cleanup, new LinearLayout.LayoutParams(-2, -2));
        body.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout tools = row();
        manageFilterButton = weightedButton(tools, "筛选 · " + manageFilter.label, false, this::chooseManageFilter);
        manageModeButton = weightedButton(tools, "批量管理", false, () -> {
            batchManage = !batchManage;
            selectedParts.clear();
            refreshSelection();
        });
        body.addView(tools, new LinearLayout.LayoutParams(-1, -2));
        addBookEmpty(body, Page.MANAGE, "还没有本地音频或下载任务。");
    }

    private void buildManageActions(LinearLayout page) {
        batchBar = row();
        batchBar.setPadding(dp(6), dp(5), dp(6), dp(5));
        batchBar.setBackgroundColor(palette.surface());
        selectAllFiles = button(batchBar, "全选", this::toggleAllFiles);
        selectedCount = label("", 14, palette.muted());
        batchBar.addView(selectedCount, new LinearLayout.LayoutParams(0, -2, 1));
        batchActions = button(batchBar, "批量操作", this::showBatchActions);
        AudioUi.styleButton(batchActions, true);
        page.addView(batchBar, new LinearLayout.LayoutParams(-1, -2));
        refreshSelection();
    }

    private void addBookEmpty(LinearLayout body, Page page, String placeholder) {
        TextView empty = label(placeholder, 14, palette.muted());
        empty.setPadding(0, dp(6), 0, dp(8));
        empty.setVisibility(View.GONE);
        body.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        bookEmpty.put(page, empty);
    }

    private void buildAccountPage(LinearLayout body) {
        addSettingsHeading(body, "平台账号", 0);
        LinearLayout providers = panel();
        LinearLayout bili = settingsRow(providers, AudioUi.Icon.PLAY);
        accountStatus = settingText(bili, "B 站", "正在检查…");
        accountButton = button(bili, "登录", this::openLogin);
        AudioUi.styleButton(accountButton, false);
        LinearLayout ximalaya = settingsRow(providers, AudioUi.Icon.LIBRARY);
        ximalayaStatus = settingText(ximalaya, "喜马拉雅", "未登录");
        ximalayaAccount = button(ximalaya, "登录", () ->
                startActivity(CatalogLoginActivity.intent(this, CatalogLoginActivity.Provider.XIMALAYA)));
        AudioUi.styleButton(ximalayaAccount, false);
        LinearLayout lanren = settingsRow(providers, AudioUi.Icon.HEADPHONES);
        lanrenStatus = settingText(lanren, "懒人听书", "未登录");
        lanrenAccount = button(lanren, "登录", () ->
                startActivity(CatalogLoginActivity.intent(this, CatalogLoginActivity.Provider.LANREN)));
        AudioUi.styleButton(lanrenAccount, false);
        body.addView(providers, new LinearLayout.LayoutParams(-1, -2));

        addSettingsHeading(body, "应用设置", 21);
        LinearLayout preferences = panel();
        LinearLayout theme = settingsRow(preferences, AudioUi.Icon.SETTINGS);
        settingText(theme, "外观", appearance(this).label);
        AudioUi.styleButton(button(theme, "更改", this::chooseAppearance), false);
        LinearLayout model = settingsRow(preferences, AudioUi.Icon.DOWNLOAD);
        modelStatus = settingText(model, "离线字幕模型", "");
        refreshModelStatus();
        AudioUi.styleButton(button(model, "导入", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip"), MODEL_IMPORT_REQUEST)), false);
        body.addView(preferences, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addSettingsHeading(LinearLayout body, String title, int top) {
        TextView heading = leadingLabel(title, 15, palette.muted());
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(dp(3), dp(top), 0, dp(9));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        body.addView(heading, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout settingsRow(LinearLayout panel, AudioUi.Icon icon) {
        if (panel.getChildCount() > 0) {
            addDivider(panel);
        }
        LinearLayout row = row();
        ImageView image = iconTile(icon, 36);
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(dp(36), dp(36));
        place.setMarginEnd(dp(11));
        row.addView(image, place);
        row.setMinimumHeight(dp(56));
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void refreshModelStatus() {
        boolean available = LocalTranscription.hasModel(this);
        modelStatus.setText(available ? "已安装 · 可离线转写" : "未安装 · 支持导入 ZIP");
        modelStatus.setTextColor(available ? palette.accent() : palette.muted());
    }

    private TextView settingText(LinearLayout row, String title, String detail) {
        LinearLayout text = column();
        text.setPadding(0, dp(5), dp(8), dp(5));
        TextView name = leadingLabel(title, 16, palette.paper());
        name.setTypeface(null, Typeface.BOLD);
        text.addView(name);
        TextView description = leadingLabel(detail, 13, palette.muted());
        description.setPadding(0, dp(3), 0, 0);
        text.addView(description);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        return description;
    }

    private void chooseAppearance() {
        Appearance[] choices = Appearance.values();
        String[] labels = new String[choices.length];
        for (int index = 0; index < choices.length; index++) {
            labels[index] = choices[index].label;
        }
        new AlertDialog.Builder(this).setTitle("外观")
                .setSingleChoiceItems(labels, appearance(this).ordinal(), (dialog, index) -> {
                    dialog.dismiss();
                    changeAppearance(choices[index]);
                }).setNegativeButton("取消", null).show();
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
        main.post(poll);
    }

    @Override protected void onResume() {
        super.onResume();
        if (currentPage == Page.ACCOUNT) {
            refreshAccount();
        } else if (currentPage == Page.PLAYER) {
            refreshResume();
        }
    }

    @Override protected void onStop() {
        main.removeCallbacks(poll);
        if (bound) {
            unbindService(connection);
        }
        playing = null;
        bound = false;
        super.onStop();
    }

    @Override protected void onDestroy() {
        catalog.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBundle("export_parts", exportParts);
        state.putBoolean("captions", showCaptions);
        state.putString("episode_filter", episodeFilter.name());
        state.putString("screen", currentPage.name());
        state.putString("manage_filter", manageFilter.name());
        state.putString("link", link.getText().toString());
        for (Map.Entry<Page, BookSectionList> list : bookLists.entrySet()) {
            state.putStringArrayList("books_" + list.getKey(), list.getValue().expandedBooks());
        }
        super.onSaveInstanceState(state);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (currentPage == Page.DOWNLOAD) {
                refreshNameSyncStatus();
            }
            if (currentPage == Page.DOWNLOAD && part != null && (!Objects.equals(transfer, TransferService.status(part))
                    || audioReady != library.audio(part).isFile()
                    || analysisVersion != library.analysisVersion(part))) {
                TransferService.Status status = TransferService.status(part);
                boolean stageChanged = transfer == null || status == null
                        || transfer.running() != status.running()
                        || audioReady != library.audio(part).isFile()
                        || analysisVersion != library.analysisVersion(part);
                if (stageChanged) {
                    renderPart();
                } else {
                    transfer = status;
                    partStatus.setText(statusText());
                }
            }
            if (SystemClock.uptimeMillis() - lastBookRefresh >= 1500) {
                refreshBooks();
            }
            if (currentPage == Page.PLAYER) {
                updatePlayer();
            }
            main.postDelayed(this, 350);
        }
    };

    private void refreshNameSyncStatus() {
        if (nameSyncStatus != null && title != null) {
            String state = nameSyncDescription(title.bvid());
            if (!TextUtils.equals(nameSyncStatus.getText(), state)) {
                nameSyncStatus.setText(state);
                nameSyncStatus.setVisibility(state.isEmpty() ? View.GONE : View.VISIBLE);
            }
        }
    }

    private String nameSyncDescription(String bvid) {
        String state = EpisodeNameSync.status(this, bvid);
        if (state.isEmpty()) {
            return "";
        }
        Uri uri = Uri.parse(EpisodeNameSync.source(this, bvid));
        for (CatalogLoginActivity.Provider provider : CatalogLoginActivity.Provider.values()) {
            if (provider.allows(uri)) {
                return provider.label + " · " + state;
            }
        }
        return state;
    }

    private TextView partStatus;

    private void restoreSelection() {
        android.content.SharedPreferences prefs = getSharedPreferences("catalog", MODE_PRIVATE);
        String id = prefs.getString("bvid", null);
        if (id != null) {
            try {
                select(library.title(id), prefs.getInt("page", 1));
                return;
            } catch (IOException | JSONException | IllegalArgumentException ignored) {
                // Cached catalog may have been cleared.
            }
        }
        renderPart();
    }

    private void load(String input) {
        load(input, false);
    }

    private void load(String input, boolean openDownload) {
        if (input.trim().isEmpty()) {
            toast("请粘贴 B 站链接或输入 BV 号。");
            return;
        }
        loadLink.setEnabled(false);
        loadLink.setText("正在读取…");
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(link.getWindowToken(), 0);
        link.clearFocus();
        catalog.execute(() -> {
            try {
                BilibiliClient client = new BilibiliClient();
                BilibiliClient.Selection request = client.selection(input);
                AudioLibrary.Title loaded;
                try {
                    loaded = library.title(request.bvid());
                } catch (IOException | JSONException ignored) {
                    loaded = library.saveTitle(client.metadata(request.bvid()));
                }
                AudioLibrary.Title result = loaded;
                main.post(() -> {
                    if (!isDestroyed() && !isFinishing()) {
                        select(result, request.page());
                        finishLoad();
                        if (openDownload) {
                            batch();
                        }
                    }
                });
            } catch (IOException | JSONException | RuntimeException error) {
                main.post(() -> {
                    if (!isDestroyed() && !isFinishing()) {
                        toast(friendly(error));
                        finishLoad();
                    }
                });
            }
        });
    }

    private void finishLoad() {
        loadLink.setEnabled(true);
        loadLink.setText("读取分集");
    }

    private void select(AudioLibrary.Title selected, int page) {
        title = selected;
        part = selected.parts().get(0);
        for (AudioLibrary.Part candidate : selected.parts()) {
            if (candidate.page() == page) {
                part = candidate;
                break;
            }
        }
        getSharedPreferences("catalog", MODE_PRIVATE).edit().putString("bvid", title.bvid())
                .putInt("page", part.page()).putInt("page_" + title.bvid(), part.page()).apply();
        renderPart();
    }

    private void renderPart() {
        nameSyncStatus = null;
        downloadBookName.setText(title == null ? "尚未添加书籍" : library.displayName(title));
        showArtwork(downloadCover, title == null ? "" : title.cover());
        workspace.removeAllViews();
        if (part == null) {
            LinearLayout empty = panel();
            TextView name = label("《重回小渔村》", 18, palette.paper());
            name.setTypeface(null, Typeface.BOLD);
            empty.addView(name);
            TextView detail = label("从第 1–5 集体验分集播放", 14, palette.muted());
            detail.setPadding(0, dp(5), 0, 0);
            empty.addView(detail);
            Button sample = button(empty, "查看样例", () -> {
                link.setText(SAMPLE);
                load(SAMPLE);
            });
            AudioUi.styleButton(sample, false);
            sample.setLayoutParams(spaced());
            workspace.addView(empty);
            return;
        }
        AudioLibrary.Part active = part;
        transfer = TransferService.status(active);
        audioReady = library.audio(active).isFile();
        analysisVersion = library.analysisVersion(active);
        try {
            analysis = library.analysis(active);
        } catch (IOException | JSONException error) {
            analysis = AudioLibrary.Analysis.empty();
        }
        AudioLibrary.Analysis loaded = analysis;
        boolean working = transfer != null && transfer.running();

        LinearLayout card = panel();
        TextView partName = leadingLabel(active.label(), 21, palette.paper());
        partName.setTypeface(null, Typeface.BOLD);
        card.addView(partName, new LinearLayout.LayoutParams(-1, -2));
        TextView partLength = leadingLabel(AudioLibrary.time(active.duration()) + " · 全书 " + title.parts().size() + " 个分 P",
                13, palette.muted());
        partLength.setPadding(0, dp(4), 0, 0);
        card.addView(partLength, new LinearLayout.LayoutParams(-1, -2));
        partStatus = leadingLabel(statusText(), 14, palette.accent());
        partStatus.setPadding(dp(12), dp(9), dp(12), dp(9));
        partStatus.setBackground(AudioUi.card(this, palette.field(), 11));
        card.addView(partStatus, spaced());
        LinearLayout actions = row();
        weightedButton(actions, "选分 P", false, this::choosePart);
        weightedButton(actions, "找集数", false, this::findEpisode);
        weightedButton(actions, "批量下载", false, this::batch);
        card.addView(actions, spaced());
        Switch compact = new Switch(this);
        compact.setText("省空间下载");
        compact.setTextColor(palette.muted());
        compact.setMinHeight(dp(48));
        compact.setChecked(getSharedPreferences("download_options", MODE_PRIVATE).getBoolean("compact_audio", false));
        compact.setOnCheckedChangeListener((view, enabled) -> getSharedPreferences("download_options", MODE_PRIVATE)
                .edit().putBoolean("compact_audio", enabled).apply());
        if (!audioReady && !working) {
            LinearLayout download = row();
            weightedButton(download, "下载音频", false, () -> submit(active, false));
            weightedButton(download, "音频和 AI 字幕", true, () -> submit(active, true));
            card.addView(download, spaced());
        }
        if (working) {
            button(card, "暂停队列", () -> startService(new Intent(this, TransferService.class)
                    .setAction(TransferService.PAUSE)));
        }
        if (TransferService.compressionPending(this, active)) {
            LinearLayout compression = row();
            if (!working && audioReady) {
                button(compression, "继续压缩", () -> startTask(TransferService.COMPACT,
                        active.bvid(), new long[]{active.cid()}, false, null));
            }
            button(compression, "取消压缩", () -> cancelCompression(active));
            card.addView(compression);
        }
        if (audioReady && !working) {
            LinearLayout files = row();
            if (loaded.cues().isEmpty()) {
                weightedButton(files, "获取 AI 字幕", false, () -> {
                    Runnable start = () -> startTask(TransferService.SUBTITLE, active.bvid(),
                            new long[]{active.cid()}, true, null);
                    if (LocalTranscription.hasModel(this)) {
                        start.run();
                    } else {
                        modelNotice(start);
                    }
                });
            }
            weightedButton(files, "离线转写", false, () -> transcribeOffline(Collections.singletonList(active)));
            weightedButton(files, "管理文件", true, () -> showPage(Page.MANAGE));
            card.addView(files, spaced());
        }
        workspace.addView(card);
        LinearLayout options = panel();
        options.addView(compact, new LinearLayout.LayoutParams(-1, -2));
        addDivider(options);
        AudioLibrary.Title book = title;
        LinearLayout bookActions = row();
        weightedButton(bookActions, "重命名", false, () -> renameBook(book));
        weightedButton(bookActions, "关联集名", false, () -> linkEpisodeNames(book));
        options.addView(bookActions, new LinearLayout.LayoutParams(-1, -2));
        nameSyncStatus = leadingLabel(nameSyncDescription(book.bvid()), 13, palette.muted());
        nameSyncStatus.setVisibility(nameSyncStatus.length() == 0 ? View.GONE : View.VISIBLE);
        options.addView(nameSyncStatus, spaced());
        workspace.addView(options, spaced());
    }

    private String statusText() {
        String captionSource = analysis.source();
        if (transfer != null) {
            return !transfer.running() && !captionSource.isEmpty()
                    ? transfer.text() + " · " + captionSource : transfer.text();
        }
        if (!audioReady) {
            return "未下载";
        }
        return captionSource.isEmpty() ? "音频已下载" : "音频已下载 · " + captionSource;
    }

    private void refreshResume() {
        PlaybackService.LastPlayed last = PlaybackService.last(this);
        boolean idle = playing == null || playing.part() == null;
        boolean available = last != null && library.hasAudio(last.bvid(), last.cid());
        resumeButton.setVisibility(idle && available ? View.VISIBLE : View.GONE);
    }

    private void resumeLast() {
        PlaybackService.LastPlayed last = PlaybackService.last(this);
        if (last != null && library.hasAudio(last.bvid(), last.cid())) {
            launchPlayback(last.bvid(), last.cid(), -1, -1);
        } else {
            refreshResume();
        }
    }

    private void chooseDownloadBook() {
        catalog.execute(() -> {
            List<AudioLibrary.Title> choices = library.titles();
            main.post(() -> {
                if (isFinishing() || isDestroyed() || currentPage != Page.DOWNLOAD) {
                    return;
                }
                if (choices.isEmpty()) {
                    toast("还没有书籍，可以搜索、打开收藏夹或导入链接。");
                    return;
                }
                String[] labels = new String[choices.size()];
                int selected = -1;
                for (int index = 0; index < choices.size(); index++) {
                    AudioLibrary.Title book = choices.get(index);
                    labels[index] = library.displayName(book);
                    if (title != null && title.bvid().equals(book.bvid())) {
                        selected = index;
                    }
                }
                new AlertDialog.Builder(this).setTitle("切换下载书籍")
                        .setSingleChoiceItems(labels, selected, (dialog, index) -> {
                            dialog.dismiss();
                            AudioLibrary.Title book = choices.get(index);
                            int page = getSharedPreferences("catalog", MODE_PRIVATE).getInt("page_" + book.bvid(), 1);
                            select(book, page);
                            downloadScroll.scrollTo(0, 0);
                        }).setNegativeButton("取消", null).show();
            });
        });
    }

    private void openDiscovery(boolean favorites) {
        startActivityForResult(new Intent(this, DiscoveryActivity.class)
                .putExtra(DiscoveryActivity.EXTRA_FAVORITES, favorites), DISCOVERY_REQUEST);
    }

    private void chooseManageFilter() {
        ManageFilter[] filters = ManageFilter.values();
        CharSequence[] names = new CharSequence[filters.length];
        for (int index = 0; index < filters.length; index++) {
            names[index] = filters[index].label;
        }
        new AlertDialog.Builder(this).setTitle("筛选管理列表")
                .setSingleChoiceItems(names, manageFilter.ordinal(), (dialog, index) -> {
                    dialog.dismiss();
                    if (manageFilter != filters[index]) {
                        manageFilter = filters[index];
                        manageFilterButton.setText("筛选 · " + manageFilter.label);
                        managedBooks = Collections.emptyList();
                        selectedParts.clear();
                        bookLists.get(Page.MANAGE).submit(managedBooks);
                        bookEmpty.get(Page.MANAGE).setVisibility(View.GONE);
                        refreshSelection();
                        refreshBooks();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private List<AudioLibrary.Part> visibleManagedParts() {
        List<AudioLibrary.Part> parts = new ArrayList<>();
        for (BookSectionList.Section book : managedBooks) {
            for (BookSectionList.Entry entry : book.entries()) {
                parts.add(entry.part());
            }
        }
        return parts;
    }

    private void toggleAllFiles() {
        List<AudioLibrary.Part> visible = visibleManagedParts();
        if (selectedParts.containsAll(visible)) {
            selectedParts.clear();
        } else {
            selectedParts.addAll(visible);
        }
        refreshSelection();
    }

    private void refreshSelection() {
        List<AudioLibrary.Part> visible = visibleManagedParts();
        selectedParts.retainAll(new HashSet<>(visible));
        manageModeButton.setText(batchManage ? "退出批量" : "批量管理");
        batchBar.setVisibility(batchManage ? View.VISIBLE : View.GONE);
        selectedCount.setText("已选 " + selectedParts.size() + " 项");
        selectAllFiles.setEnabled(!visible.isEmpty());
        selectAllFiles.setText(!visible.isEmpty() && selectedParts.containsAll(visible) ? "取消全选" : "全选");
        batchActions.setEnabled(!selectedParts.isEmpty());
        bookLists.get(Page.MANAGE).showSelection(batchManage ? selectedParts : null);
    }

    private void showBatchActions() {
        List<AudioLibrary.Part> running = new ArrayList<>();
        List<AudioLibrary.Part> retryable = new ArrayList<>();
        List<AudioLibrary.Part> downloadable = new ArrayList<>();
        List<AudioLibrary.Part> deletable = new ArrayList<>();
        for (AudioLibrary.Part source : visibleManagedParts()) {
            if (!selectedParts.contains(source)) {
                continue;
            }
            TransferService.Status status = TransferService.status(source);
            boolean audio = library.audio(source).isFile();
            if (status != null && status.running()) {
                running.add(source);
            } else {
                deletable.add(source);
                if (audio) {
                    downloadable.add(source);
                }
                if (ManageFilter.UNFINISHED.includes(audio && !library.hasPendingTranscription(source), status)) {
                    retryable.add(source);
                }
            }
        }
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!running.isEmpty()) {
            labels.add("暂停选中任务 · " + running.size());
            actions.add(() -> submitGrouped(TransferService.PAUSE, taskParts(running), false, null));
        }
        if (!retryable.isEmpty()) {
            labels.add("重试未完成 · " + retryable.size());
            actions.add(() -> chooseRetry(retryable));
        }
        if (!downloadable.isEmpty()) {
            labels.add("导出分集 · " + downloadable.size());
            actions.add(() -> chooseBatchExport(downloadable));
            labels.add("手动离线转写 · " + downloadable.size());
            actions.add(() -> transcribeOffline(downloadable));
            labels.add("压缩音频 · " + downloadable.size());
            actions.add(() -> confirmCompact(downloadable));
        }
        if (!deletable.isEmpty()) {
            labels.add("删除本地文件或记录 · " + deletable.size());
            actions.add(() -> confirmDeletion(deletable));
        }
        if (!actions.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("批量操作")
                    .setItems(labels.toArray(new String[0]), (dialog, index) -> actions.get(index).run())
                    .setNegativeButton("取消", null).show();
        }
    }

    private void chooseRetry(List<AudioLibrary.Part> parts) {
        new AlertDialog.Builder(this).setTitle("重试 " + parts.size() + " 个分 P")
                .setItems(new String[]{"仅音频", "音频和 AI 字幕"}, (dialog, index) -> {
                    boolean captions = index == 1;
                    Runnable retry = () -> submitGrouped(TransferService.DOWNLOAD, taskParts(parts), captions, null);
                    if (captions && !LocalTranscription.hasModel(this)) {
                        modelNotice(retry);
                    } else {
                        retry.run();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private record EpisodeIndex(long version, EpisodeNames.Catalog names, List<BookSectionList.Entry> entries) {}
    private record BooksResult(List<BookSectionList.Section> sections, long bytes) {}

    private void refreshBooks() {
        if (loadingBooks || !bookLists.containsKey(currentPage)) {
            return;
        }
        Page page = currentPage;
        ManageFilter filter = manageFilter;
        EpisodeFilter episodeChoice = episodeFilter;
        loadingBooks = true;
        lastBookRefresh = SystemClock.uptimeMillis();
        catalog.execute(() -> {
            BooksResult result = collectBooks(page, filter, episodeChoice);
            main.post(() -> {
                loadingBooks = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if ((page == Page.MANAGE && filter != manageFilter)
                        || (page == Page.EPISODES && episodeChoice != episodeFilter)) {
                    refreshBooks();
                    return;
                }
                bookLists.get(page).submit(result.sections());
                bookEmpty.get(page).setVisibility(result.sections().isEmpty() ? View.VISIBLE : View.GONE);
                if (page == Page.MANAGE) {
                    managedBooks = result.sections();
                    bookHeadings.get(page).setText("本地文件 · "
                            + Formatter.formatShortFileSize(this, result.bytes()));
                    bookEmpty.get(page).setText(filter == ManageFilter.ALL
                            ? "还没有本地音频或下载任务。" : "没有符合筛选条件的条目。");
                    refreshSelection();
                } else if (page == Page.EPISODES) {
                    bookEmpty.get(page).setText(episodeChoice == EpisodeFilter.ALL
                            ? "还没有可播放的本地剧集。" : "没有符合这个筛选条件的分 P。");
                    if (page == currentPage && locateEpisodeGroup) {
                        locateEpisodeGroup = false;
                        PlaybackService.LastPlayed current = episodePosition();
                        if (current != null) {
                            bookLists.get(page).openEpisodeGroup(current.bvid(), current.cid(), current.position());
                        }
                    }
                }
                if (page != currentPage) {
                    refreshBooks();
                }
            });
        });
    }

    private BooksResult collectBooks(Page page, ManageFilter filter, EpisodeFilter episodeChoice) {
        List<BookSectionList.Section> sections = new ArrayList<>();
        Set<AudioLibrary.Part> indexed = new HashSet<>();
        long total = 0;
        for (AudioLibrary.Title book : library.titles()) {
            List<BookSectionList.Entry> entries = new ArrayList<>();
            EpisodeNames.Catalog names = page == Page.EPISODES ? library.episodeNames(book.bvid()) : EpisodeNames.Catalog.EMPTY;
            Map<Long, Long> sizes = library.mediaSizes(book);
            long bytes = 0;
            for (AudioLibrary.Part source : book.parts()) {
                Long size = sizes.get(source.cid());
                boolean stored = size != null;
                boolean audio = stored && library.audio(source).isFile();
                if (page == Page.EPISODES) {
                    if (audio) {
                        indexed.add(source);
                        entries.addAll(episodeIndex(source, names).entries());
                    }
                    continue;
                }
                TransferService.Status job = TransferService.status(source);
                boolean pendingCaptions = audio && library.hasPendingTranscription(source);
                if ((!stored && job == null) || !filter.includes(audio && !pendingCaptions, job)) {
                    continue;
                }
                if (filter == ManageFilter.CUTS || filter == ManageFilter.CAPTIONS) {
                    if (!audio) {
                        continue;
                    }
                    EpisodeFilter condition = filter == ManageFilter.CUTS ? EpisodeFilter.CUTS : EpisodeFilter.CAPTIONS;
                    if (!condition.includes(episodeIndex(source, names).entries().get(0))) {
                        continue;
                    }
                }
                long fileBytes = stored ? size : 0;
                bytes += fileBytes;
                String state = pendingCaptions && (job == null || !job.unfinished())
                        ? "字幕未完成" : statusLabel(job, audio, stored);
                if ((filter == ManageFilter.CUTS || filter == ManageFilter.CAPTIONS) && (job == null || !job.unfinished())) {
                    state = filter.label;
                }
                String detail = fileBytes > 0
                        ? state + " · " + Formatter.formatShortFileSize(this, fileBytes)
                        : state + " · " + AudioLibrary.time(source.duration());
                entries.add(new BookSectionList.Entry(source, -1, source.label(), detail, stored));
            }
            if (page == Page.EPISODES && episodeChoice != EpisodeFilter.ALL) {
                entries.removeIf(entry -> !episodeChoice.includes(entry));
            }
            if (!entries.isEmpty()) {
                boolean grouped = page == Page.EPISODES;
                String summary = grouped ? "每 " + EPISODES_PER_GROUP + " 集一组"
                        : entries.size() + " 个分 P · " + Formatter.formatShortFileSize(this, bytes);
                sections.add(new BookSectionList.Section(book, library.displayName(book), summary,
                        grouped ? Collections.emptyList() : entries,
                        grouped ? groupEpisodes(book, entries) : Collections.emptyList()));
                total += bytes;
            }
        }
        if (page == Page.EPISODES) {
            episodeIndexes.keySet().retainAll(indexed);
        }
        return new BooksResult(sections, total);
    }

    private static List<BookSectionList.EpisodeGroup> groupEpisodes(AudioLibrary.Title book,
                                                                    List<BookSectionList.Entry> entries) {
        Map<Integer, List<BookSectionList.Entry>> ranges = new TreeMap<>();
        int last = 0;
        for (AudioLibrary.Part source : book.parts()) {
            last = Math.max(last, source.last());
        }
        for (BookSectionList.Entry entry : entries) {
            int number = episodeNumber(entry);
            int first = (number - 1) / EPISODES_PER_GROUP * EPISODES_PER_GROUP + 1;
            ranges.computeIfAbsent(first, ignored -> new ArrayList<>()).add(entry);
            last = Math.max(last, number);
        }
        List<BookSectionList.EpisodeGroup> groups = new ArrayList<>(ranges.size());
        for (Map.Entry<Integer, List<BookSectionList.Entry>> range : ranges.entrySet()) {
            int first = range.getKey();
            int end = Math.min(last, first + EPISODES_PER_GROUP - 1);
            List<BookSectionList.Entry> items = range.getValue();
            items.sort(Comparator.comparingInt(MainActivity::episodeNumber));
            int pending = 0;
            for (BookSectionList.Entry item : items) {
                if (item.segment() < 0) {
                    pending++;
                }
            }
            groups.add(new BookSectionList.EpisodeGroup(first, end, pending, items));
        }
        return groups;
    }

    private static int episodeNumber(BookSectionList.Entry entry) {
        return entry.part().first() + Math.max(0, entry.segment());
    }

    private void renameBook(AudioLibrary.Title book) {
        LinearLayout form = panel();
        EditText name = input("书名");
        String display = library.displayName(book);
        name.setSingleLine(true);
        name.setText(display);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Math.max(100, display.length()))});
        name.selectAll();
        form.addView(name);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("重命名书籍")
                .setView(form).setPositiveButton("保存", null).setNegativeButton("取消", null);
        if (!display.equals(book.name())) {
            builder.setNeutralButton("恢复原名", null);
        }
        AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) {
                name.setError("请输入书名");
            } else {
                saveBookName(book, value, dialog);
            }
        });
        Button reset = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (reset != null) {
            reset.setOnClickListener(view -> saveBookName(book, book.name(), dialog));
        }
    }

    private void saveBookName(AudioLibrary.Title book, String value, AlertDialog dialog) {
        if (!library.rename(book, value)) {
            toast("无法保存书名，请重试。");
            return;
        }
        dialog.dismiss();
        if (title != null && title.bvid().equals(book.bvid())) {
            renderPart();
        }
        if (playing != null) {
            playing.bookRenamed(book.bvid());
        }
        refreshBooks();
    }

    private void linkEpisodeNames(AudioLibrary.Title book) {
        LinearLayout form = panel();
        form.addView(label("粘贴喜马拉雅或懒人听书专辑链接，也可以粘贴带集号的目录。",
                14, palette.muted()));
        EditText input = input("例如  第 1 集 出发");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(3);
        input.setMaxLines(7);
        EpisodeNames.Catalog saved = library.episodeNames(book.bvid());
        String linked = EpisodeNameSync.source(this, book.bvid());
        input.setText(linked.isEmpty() ? saved.source() : linked);
        form.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("关联集名").setView(form)
                .setPositiveButton("读取目录", null).setNegativeButton("取消", null).show();
        Button read = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        read.setOnClickListener(view -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) {
                input.setError("请输入专辑链接或目录");
                return;
            }
            if (value.startsWith("https://")) {
                try {
                    String source = EpisodeNames.source(value);
                    if (EpisodeNameSync.subscribe(this, book.bvid(), source)) {
                        dialog.dismiss();
                        refreshNameSyncStatus();
                        toast("已安排后台同步目录");
                    } else {
                        input.setError("系统暂未接受同步任务，请重试。");
                    }
                } catch (IOException | JSONException error) {
                    input.setError(friendly(error));
                }
                return;
            }
            read.setEnabled(false);
            read.setText("读取中…");
            int last = book.parts().stream().mapToInt(AudioLibrary.Part::last).max().orElse(1);
            catalog.execute(() -> {
                try {
                    EpisodeNames.Catalog loaded = EpisodeNames.fromText(value, last);
                    main.post(() -> {
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                            confirmEpisodeNames(book, loaded);
                        }
                    });
                } catch (IOException | IllegalArgumentException error) {
                    main.post(() -> {
                        read.setEnabled(true);
                        read.setText("读取目录");
                        if (dialog.isShowing()) {
                            input.setError(friendly(error));
                        }
                    });
                }
            });
        });
    }

    private void confirmEpisodeNames(AudioLibrary.Title book, EpisodeNames.Catalog loaded) {
        StringBuilder preview = new StringBuilder("找到 ").append(loaded.names().size())
                .append(" 个集名。请核对集号与演播版本。\n");
        loaded.names().keySet().stream().limit(4).forEach(episode -> preview.append('\n').append(loaded.label(episode)));
        new AlertDialog.Builder(this).setTitle(library.displayName(book)).setMessage(preview)
                .setNegativeButton("返回修改", (dialog, ignored) -> linkEpisodeNames(book))
                .setPositiveButton("保存集名", (dialog, ignored) -> {
                    try {
                        EpisodeNames.Catalog previous = library.episodeNames(book.bvid());
                        if (!loaded.source().equals(previous.source()) && !loaded.source().isEmpty()) {
                            library.saveEpisodeNames(book.bvid(), loaded);
                        } else {
                            Map<Integer, String> combined = new TreeMap<>(previous.names());
                            combined.putAll(loaded.names());
                            String source = loaded.source().isEmpty() ? previous.source() : loaded.source();
                            library.saveEpisodeNames(book.bvid(), new EpisodeNames.Catalog(source, combined));
                        }
                        refreshBooks();
                        toast("已保存集名");
                    } catch (IOException | JSONException error) {
                        toast("无法保存集名，请重试。");
                    }
                }).show();
    }

    private static String statusLabel(TransferService.Status job, boolean audio, boolean stored) {
        if (job != null) {
            return job.text();
        }
        if (audio) {
            return "已下载";
        }
        return stored ? "有未完成的文件" : "未下载";
    }

    private EpisodeIndex episodeIndex(AudioLibrary.Part source, EpisodeNames.Catalog names) {
        long version = library.analysisVersion(source);
        EpisodeIndex cached = episodeIndexes.get(source);
        if (cached == null || cached.version() != version || cached.names() != names) {
            AudioLibrary.Analysis data;
            try {
                data = library.resolveCuts(source);
            } catch (IOException | JSONException ignored) {
                data = AudioLibrary.Analysis.empty();
            }
            List<AudioLibrary.Segment> segments = AudioLibrary.segments(source, data);
            List<BookSectionList.Entry> entries = new ArrayList<>(segments.size());
            for (int index = 0; index < segments.size(); index++) {
                AudioLibrary.Segment episode = segments.get(index);
                String number = episode.number() == 0 ? source.label() : names.label(episode.number());
                boolean pending = episode.number() == 0;
                boolean hasSubtitles = !data.cues().isEmpty();
                String state = hasSubtitles ? "缺切点 · 已找到 " + data.starts().size() + "/" + (source.count() - 1)
                        + " 个切点" : "缺字幕";
                String detail = (pending ? state : "P" + source.page() + (hasSubtitles ? "" : " · 缺字幕"))
                        + " · " + AudioLibrary.time(episode.end() - episode.start());
                entries.add(new BookSectionList.Entry(source, pending ? -1 : index, number, detail, true,
                        episode.start(), episode.end(), hasSubtitles));
            }
            cached = new EpisodeIndex(version, names, entries);
            episodeIndexes.put(source, cached);
        }
        return cached;
    }

    private void openBookEntry(Page page, AudioLibrary.Title book, BookSectionList.Entry entry) {
        AudioLibrary.Part source = entry.part();
        switch (page) {
            case EPISODES -> {
                if (library.audio(source).isFile()) {
                    if (entry.segment() < 0) {
                        showPendingEpisode(source);
                    } else {
                        launchPlayback(source.bvid(), source.cid(), entry.segment(), -1);
                    }
                } else {
                    refreshBooks();
                }
            }
            case MANAGE -> {
                if (batchManage) {
                    if (!selectedParts.add(source)) {
                        selectedParts.remove(source);
                    }
                    refreshSelection();
                } else {
                    manageDownload(book, entry);
                }
            }
            default -> { }
        }
    }

    private void showPendingEpisode(AudioLibrary.Part source) {
        new AlertDialog.Builder(this).setTitle(source.label())
                .setItems(new String[]{"播放整段", "获取字幕或重新识别", "手动离线转写", "编辑切点"}, (ignored, index) -> {
                    switch (index) {
                        case 0 -> launchPlayback(source.bvid(), source.cid(), 0, -1);
                        case 1 -> identifyEpisodes(source);
                        case 2 -> transcribeOffline(Collections.singletonList(source));
                        case 3 -> editCuts(source);
                    }
                }).setNegativeButton("关闭", null).show();
    }

    private void identifyEpisodes(AudioLibrary.Part source) {
        catalog.execute(() -> {
            try {
                AudioLibrary.Analysis old = library.analysis(source);
                AudioLibrary.Analysis detected = EpisodeCuts.analyze(source, old.source(), old.cues(),
                        EpisodeCuts.verifiedChapters(source), AudioLibrary.Analysis.empty());
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (old.cues().isEmpty() && !detected.confirmed()) {
                        submit(source, true);
                    } else if (old.confirmed() && !detected.confirmed()) {
                        toast("字幕中的报幕不完整，可以试听并编辑已保存的切点。");
                        editCuts(source, old);
                    } else {
                        editCuts(source, detected);
                    }
                });
            } catch (IOException | JSONException error) {
                main.post(() -> toast("无法识别这个分 P 的切点。"));
            }
        });
    }

    private void manageDownload(AudioLibrary.Title book, BookSectionList.Entry item) {
        AudioLibrary.Part source = item.part();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("打开作品");
        actions.add(() -> {
            select(book, source.page());
            showPage(Page.DOWNLOAD);
        });
        if (library.audio(source).isFile()) {
            labels.add("继续播放");
            actions.add(() -> launchPlayback(source.bvid(), source.cid(), -1, -1));
            labels.add("收听进度与清理");
            actions.add(() -> showListeningProgress(source));
            labels.add("编辑切点");
            actions.add(() -> editCuts(source));
            if (source.count() > 1) {
                labels.add("获取字幕或重新识别分集");
                actions.add(() -> identifyEpisodes(source));
            }
        }
        TransferService.Status status = TransferService.status(source);
        boolean compressionPending = TransferService.compressionPending(this, source);
        if (compressionPending) {
            labels.add("取消压缩");
            actions.add(() -> cancelCompression(source));
        }
        if (status != null && status.running()) {
            labels.add("暂停队列");
            actions.add(() -> startService(new Intent(this, TransferService.class).setAction(TransferService.PAUSE)));
        } else {
            if (library.audio(source).isFile()) {
                labels.add("导出分集");
                actions.add(() -> chooseExport(source));
                labels.add("手动离线转写");
                actions.add(() -> transcribeOffline(Collections.singletonList(source)));
                labels.add(compressionPending ? "继续压缩" : "压缩音频");
                actions.add(() -> {
                    if (compressionPending) {
                        startTask(TransferService.COMPACT, source.bvid(), new long[]{source.cid()}, false, null);
                    } else {
                        confirmCompact(Collections.singletonList(source));
                    }
                });
            } else {
                labels.add("继续下载音频");
                actions.add(() -> submit(source, false));
            }
            labels.add(library.audio(source).isFile() ? "获取或重试 AI 字幕" : "继续下载音频和 AI 字幕");
            actions.add(() -> submit(source, true));
            labels.add(item.stored() ? "删除本地文件" : "移除任务记录");
            actions.add(() -> confirmDelete(source, item.stored()));
        }
        new AlertDialog.Builder(this).setTitle(source.label())
                .setItems(labels.toArray(new String[0]), (ignored, index) -> actions.get(index).run())
                .setNegativeButton("关闭", null).show();
    }

    private void showListeningProgress(AudioLibrary.Part source) {
        try {
            List<AudioLibrary.Segment> episodes = AudioLibrary.segments(source, library.analysis(source));
            List<AudioLibrary.Segment> remaining = history.unfinished(source, episodes);
            String message = "本机已记录 " + (episodes.size() - remaining.size()) + " / " + episodes.size() + " 集听完。";
            if (!remaining.isEmpty() && remaining.get(0).number() > 0) {
                String list = remaining.stream().limit(8).map(ep -> Integer.toString(ep.number()))
                        .collect(java.util.stream.Collectors.joining("、"));
                message += "\n待确认的集数 " + list + (remaining.size() > 8 ? " 等" : "");
            }
            new AlertDialog.Builder(this).setTitle(source.label()).setMessage(message)
                    .setPositiveButton("标记整段听完", (dialog, index) -> new AlertDialog.Builder(this)
                            .setTitle("标记整段听完")
                            .setMessage(history.autoCleanup()
                                    ? "确认后，这个分 P 会按听完自动清理设置删除本地音频和字幕。"
                                    : "确认后，本机会将这个分 P 的全部剧集记为已听完。")
                            .setPositiveButton("确认标记", (confirmed, button) -> {
                                history.finishPart(source, episodes);
                                if (history.autoCleanup()) {
                                    history.queueCleanup(source);
                                    PlaybackService.cleanFinishedMedia(this, source);
                                }
                                refreshBooks();
                            }).setNegativeButton("取消", null).show())
                    .setNegativeButton("关闭", null).show();
        } catch (IOException | JSONException error) {
            toast("无法读取这个分 P 的收听进度。");
        }
    }

    private void scheduleFinishedCleanup() {
        if (!history.autoCleanup()) {
            return;
        }
        catalog.execute(() -> {
            Set<String> finished = history.partsWithFinishedSegments();
            List<AudioLibrary.Part> eligible = new ArrayList<>();
            for (AudioLibrary.Title book : library.titles()) {
                for (AudioLibrary.Part part : book.parts()) {
                    if (finished.contains(part.bvid() + "/" + part.cid()) && library.audio(part).isFile()) {
                        try {
                            if (history.allFinished(part, AudioLibrary.segments(part, library.analysis(part)))) {
                                eligible.add(part);
                            }
                        } catch (IOException | JSONException ignored) { }
                    }
                }
            }
            main.post(() -> {
                if (history.autoCleanup()) {
                    for (AudioLibrary.Part part : eligible) {
                        history.queueCleanup(part);
                        PlaybackService.cleanFinishedMedia(this, part);
                    }
                    if (!eligible.isEmpty()) {
                        refreshBooks();
                    }
                }
            });
        });
    }

    private void confirmDelete(AudioLibrary.Part source, boolean hasFiles) {
        if (!hasFiles) {
            TransferService.clearInactiveStatus(source);
            refreshBooks();
            return;
        }
        confirmDeletion(Collections.singletonList(source));
    }

    private void confirmDeletion(List<AudioLibrary.Part> parts) {
        new AlertDialog.Builder(this).setTitle("删除 " + parts.size() + " 个分 P")
                .setMessage("删除所选分 P 的本地音频、字幕和下载进度。已确认的切点和播放进度会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, index) -> {
                    List<AudioLibrary.Part> eligible = new ArrayList<>();
                    for (AudioLibrary.Part source : parts) {
                        TransferService.Status status = TransferService.status(source);
                        if (status == null || !status.running()) {
                            eligible.add(source);
                        }
                    }
                    if (eligible.isEmpty()) {
                        toast("所选任务正在运行，请先暂停。");
                        return;
                    }
                    selectedParts.removeAll(eligible);
                    refreshSelection();
                    submitGrouped(TransferService.DELETE, taskParts(eligible), false, null);
                }).show();
    }

    private void choosePart() {
        AudioLibrary.Title selected = title;
        String[] names = partNames(selected);
        int selectedIndex = selected.parts().indexOf(part);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("选择 B 站分 P")
                .setSingleChoiceItems(names, selectedIndex, (box, index) -> {
                    select(selected, selected.parts().get(index).page());
                    box.dismiss();
                }).setNegativeButton("取消", null).show();
        dialog.getListView().setSelection(Math.max(0, selectedIndex));
    }

    private String[] partNames(AudioLibrary.Title selected) {
        String[] names = new String[selected.parts().size()];
        for (int index = 0; index < names.length; index++) {
            AudioLibrary.Part item = selected.parts().get(index);
            names[index] = item.label() + (library.audio(item).isFile() ? "  ·  已下载" : "");
        }
        return names;
    }

    private void findEpisode() {
        EditText number = input("输入剧集编号");
        number.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this).setTitle("找集数").setView(number)
                .setPositiveButton("查找", (dialog, value) -> {
                    try {
                        int episode = Integer.parseInt(number.getText().toString());
                        for (AudioLibrary.Part candidate : title.parts()) {
                            if (episode >= candidate.first() && episode <= candidate.last()) {
                                select(title, candidate.page());
                                return;
                            }
                        }
                    } catch (NumberFormatException ignored) {
                        // Empty or invalid episode number.
                    }
                    toast("作品目录里没有这个集号。");
                }).setNegativeButton("取消", null).show();
    }

    private void batch() {
        AudioLibrary.Title selected = title;
        int currentIndex = Math.max(0, selected.parts().indexOf(part));
        boolean[] checked = new boolean[selected.parts().size()];
        checked[currentIndex] = true;

        LinearLayout header = column();
        header.setPadding(dp(8), dp(11), dp(8), dp(3));
        header.addView(label("选择要下载的分 P", 19, palette.paper()));
        TextView count = label("", 14, palette.muted());
        header.addView(count);
        LinearLayout shortcuts = row();
        header.addView(shortcuts);
        Button selectAll = new Button(this, null, android.R.attr.borderlessButtonStyle);
        selectAll.setTextColor(palette.accent());
        shortcuts.addView(selectAll);

        Runnable updateSelection = () -> {
            int selectedCount = 0;
            for (boolean value : checked) {
                if (value) {
                    selectedCount++;
                }
            }
            count.setText("当前 P" + selected.parts().get(currentIndex).page()
                    + " · 已选 " + selectedCount + " / " + checked.length);
            selectAll.setText(selectedCount == checked.length ? "清空" : "全选");
        };

        AlertDialog dialog = new AlertDialog.Builder(this).setCustomTitle(header)
                .setMultiChoiceItems(partNames(selected), checked, (box, index, value) -> {
                    checked[index] = value;
                    updateSelection.run();
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("下载音频", (box, value) -> submitBatch(selected, checked, false))
                .setPositiveButton("音频和 AI 字幕", (box, value) -> submitBatch(selected, checked, true))
                .create();
        selectAll.setOnClickListener(view -> {
            boolean value = dialog.getListView().getCheckedItemCount() < checked.length;
            applyPartSelection(dialog, checked, index -> value, updateSelection);
        });
        button(shortcuts, "当前起", () ->
                applyPartSelection(dialog, checked, index -> index >= currentIndex, updateSelection));
        button(shortcuts, "当前之后", () ->
                applyPartSelection(dialog, checked, index -> index > currentIndex, updateSelection));
        button(shortcuts, "仅未下载", () -> applyPartSelection(dialog, checked,
                index -> !library.audio(selected.parts().get(index)).isFile(), updateSelection));
        updateSelection.run();
        dialog.show();
        dialog.getListView().setSelection(currentIndex);
    }

    private static void applyPartSelection(AlertDialog dialog, boolean[] checked,
                                           IntPredicate include, Runnable changed) {
        for (int index = 0; index < checked.length; index++) {
            checked[index] = include.test(index);
            dialog.getListView().setItemChecked(index, checked[index]);
        }
        changed.run();
    }

    private void submitBatch(AudioLibrary.Title selected, boolean[] checked, boolean subtitle) {
        List<Long> cids = new ArrayList<>();
        for (int index = 0; index < checked.length; index++) {
            if (checked[index]) {
                cids.add(selected.parts().get(index).cid());
            }
        }
        if (cids.isEmpty()) {
            toast("请选择分 P。");
            return;
        }
        long[] values = new long[cids.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = cids.get(index);
        }
        Runnable action = () -> startTask(TransferService.DOWNLOAD, selected.bvid(), values, subtitle, null);
        if (subtitle && !LocalTranscription.hasModel(this)) {
            modelNotice(action);
        } else {
            action.run();
        }
    }

    private void submit(AudioLibrary.Part selected, boolean subtitle) {
        Runnable action = () -> startTask(TransferService.DOWNLOAD, selected.bvid(),
                new long[]{selected.cid()}, subtitle, null);
        if (subtitle && !LocalTranscription.hasModel(this)) {
            modelNotice(action);
        } else {
            action.run();
        }
    }

    private void transcribeOffline(List<AudioLibrary.Part> selection) {
        List<AudioLibrary.Part> sources = new ArrayList<>();
        for (AudioLibrary.Part source : selection) {
            if (library.audio(source).isFile()) {
                sources.add(source);
            }
        }
        if (sources.isEmpty()) {
            toast("先下载音频，再离线转写。");
            return;
        }
        Runnable start = () -> submitGrouped(TransferService.TRANSCRIBE, taskParts(sources), false, null);
        if (LocalTranscription.hasModel(this)) {
            start.run();
        } else {
            new AlertDialog.Builder(this).setTitle("手动离线转写")
                    .setMessage("需要约 240 MB 的离线模型。可以立即下载，或在账号页导入模型 ZIP。")
                    .setPositiveButton("下载模型并开始", (dialog, ignored) -> start.run())
                    .setNegativeButton("取消", null).show();
        }
    }

    private void confirmCompact(List<AudioLibrary.Part> selection) {
        new AlertDialog.Builder(this).setTitle("压缩 " + selection.size() + " 个分 P")
                .setMessage("转成更小的单声道音频，音质会降低。字幕、分集和播放进度会保留。")
                .setPositiveButton("开始压缩", (dialog, ignored) ->
                        submitGrouped(TransferService.COMPACT, taskParts(selection), false, null))
                .setNegativeButton("取消", null).show();
    }

    private void cancelCompression(AudioLibrary.Part source) {
        if (requestTask(TransferService.CANCEL_COMPACT, source.bvid(), new long[]{source.cid()}, false, null)) {
            toast("已提交取消压缩");
        }
    }

    private void modelNotice(Runnable action) {
        new AlertDialog.Builder(this).setTitle("生成 AI 字幕")
                .setMessage("优先保存可用的 B 站字幕。缺失时会下载约 240 MB 的模型，并在手机上转写。")
                .setPositiveButton("开始", (dialog, index) -> action.run())
                .setNegativeButton("取消", null).show();
    }

    private void startTask(String action, String bvid, long[] parts, boolean subtitle, Uri folder) {
        if (requestTask(action, bvid, parts, subtitle, folder)) {
            toast("任务已提交");
            offerNotifications();
        } else {
            toast("无法启动后台任务，请重试。");
        }
    }

    private boolean requestTask(String action, String bvid, long[] parts, boolean subtitle, Uri folder) {
        try {
            Intent request = new Intent(this, TransferService.class).setAction(action).setData(folder)
                    .putExtra(TransferService.EXTRA_BVID, bvid)
                    .putExtra(TransferService.EXTRA_PARTS, parts)
                    .putExtra(TransferService.EXTRA_SUBTITLE, subtitle)
                    .putExtra(TransferService.EXTRA_COMPACT, TransferService.DOWNLOAD.equals(action)
                            && getSharedPreferences("download_options", MODE_PRIVATE).getBoolean("compact_audio", false));
            if (TransferService.PAUSE.equals(action) || TransferService.CANCEL_COMPACT.equals(action)) {
                startService(request);
            } else {
                startForegroundService(request);
            }
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static Bundle taskParts(List<AudioLibrary.Part> parts) {
        Map<String, List<Long>> works = new LinkedHashMap<>();
        for (AudioLibrary.Part source : parts) {
            works.computeIfAbsent(source.bvid(), key -> new ArrayList<>()).add(source.cid());
        }
        Bundle result = new Bundle();
        works.forEach((bvid, ids) -> result.putLongArray(bvid, ids.stream().mapToLong(Long::longValue).toArray()));
        return result;
    }

    private void submitGrouped(String action, Bundle tasks, boolean subtitle, Uri folder) {
        int submitted = 0;
        for (String bvid : tasks.keySet()) {
            long[] parts = tasks.getLongArray(bvid);
            if (parts != null && parts.length > 0 && requestTask(action, bvid, parts, subtitle, folder)) {
                submitted += parts.length;
            }
        }
        if (submitted > 0) {
            if (TransferService.PAUSE.equals(action)) {
                toast("已请求暂停 " + submitted + " 个分 P");
            } else {
                toast("已提交 " + submitted + " 个分 P");
                offerNotifications();
            }
        } else {
            toast("无法提交这批任务，请重试。");
        }
    }

    private void offerNotifications() {
        android.content.SharedPreferences settings = getSharedPreferences("catalog", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && !settings.getBoolean("notification_prompt", false)) {
            settings.edit().putBoolean("notification_prompt", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 22);
        }
    }

    private void editCuts(AudioLibrary.Part active) {
        try {
            editCuts(active, library.analysis(active));
        } catch (IOException | JSONException error) {
            toast("无法读取这个分 P 的切点。");
        }
    }

    private void editCuts(AudioLibrary.Part active, AudioLibrary.Analysis current) {
        LinearLayout controls = panel();
        String rule = active.count() > 1 ? "每行一个起点，从第 " + (active.first() + 1)
                + " 集开始。需要 " + (active.count() - 1) + " 个切点。"
                : "每行一个新集的起点。时间使用 mm:ss 或 hh:mm:ss.SSS。";
        controls.addView(label(rule, 15, palette.paper()));
        EditText times = input("例如 08:12.300");
        times.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        times.setMinLines(3);
        times.setMaxLines(8);
        StringBuilder preferred = new StringBuilder();
        for (int cut : current.starts()) {
            preferred.append(AudioLibrary.time(cut)).append('.')
                    .append(String.format(Locale.US, "%03d", cut % 1000)).append('\n');
        }
        times.setText(preferred.toString());
        controls.addView(times);
        button(controls, "试听所选行", () -> {
            String value = times.getText().toString();
            int cursor = Math.max(0, times.getSelectionStart());
            int begin = cursor == 0 ? 0 : value.lastIndexOf('\n', cursor - 1) + 1;
            int end = value.indexOf('\n', cursor);
            try {
                int position = parseTime(value.substring(begin, end < 0 ? value.length() : end));
                if (position >= active.duration()) {
                    throw new IllegalArgumentException();
                }
                launchPlayback(active.bvid(), active.cid(), -1, Math.max(0, position - 4000));
            } catch (IllegalArgumentException error) {
                toast("将光标放在一个有效的切点上。");
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("编辑分集切点")
                .setView(controls).setNeutralButton("添加当前播放位置", null)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            if (playing == null || playing.part() == null || playing.part().cid() != active.cid()) {
                toast("请先播放当前分 P。");
                return;
            }
            int position = playing.sourcePosition();
            String stamp = AudioLibrary.time(position) + "." + String.format(Locale.US, "%03d", position % 1000);
            String existing = times.getText().toString();
            if (!existing.isEmpty() && !existing.endsWith("\n")) {
                times.append("\n");
            }
            times.append(stamp + "\n");
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                List<Integer> starts = new ArrayList<>();
                for (String line : times.getText().toString().split("\\R")) {
                    if (!line.trim().isEmpty()) {
                        starts.add(parseTime(line));
                    }
                }
                if (!EpisodeCuts.validManual(active, starts)) {
                    toast("请按顺序输入切点，间隔至少 1 秒，并核对集数。");
                    return;
                }
                library.updateAnalysis(active, latest -> new AudioLibrary.Analysis(latest.source(),
                        latest.cues(), starts, true));
                dialog.dismiss();
                renderPart();
                refreshBooks();
            } catch (IllegalArgumentException error) {
                toast("无法识别时间，请使用 mm:ss 或 hh:mm:ss.SSS。");
            } catch (IOException | JSONException error) {
                toast("无法保存切点。");
            }
        });
    }

    private static int parseTime(String input) {
        String[] chunks = input.trim().split(":", -1);
        if (chunks.length < 2 || chunks.length > 3) {
            throw new IllegalArgumentException();
        }
        int hours = chunks.length == 3 ? Integer.parseInt(chunks[0]) : 0;
        int minutes = Integer.parseInt(chunks[chunks.length - 2]);
        double seconds = Double.parseDouble(chunks[chunks.length - 1]);
        if (hours < 0 || hours > 100 || minutes < 0 || minutes > 6000
                || !Double.isFinite(seconds) || seconds < 0 || seconds >= 60) {
            throw new IllegalArgumentException();
        }
        return Math.toIntExact(Math.round((hours * 3600L + minutes * 60L + seconds) * 1000));
    }

    private void chooseExport(AudioLibrary.Part active) {
        try {
            if (active.count() > 1 && !library.analysis(active).confirmed()) {
                toast("请先在文件操作里确认分集切点。");
                return;
            }
        } catch (IOException | JSONException error) {
            toast("无法读取这个分 P 的切点。");
            return;
        }
        openExportFolder(taskParts(Collections.singletonList(active)));
    }

    private void chooseBatchExport(List<AudioLibrary.Part> parts) {
        catalog.execute(() -> {
            List<AudioLibrary.Part> ready = new ArrayList<>();
            for (AudioLibrary.Part source : parts) {
                try {
                    if (library.audio(source).isFile()
                            && (source.count() == 1 || library.analysis(source).confirmed())) {
                        ready.add(source);
                    }
                } catch (IOException | JSONException ignored) {
                    // Other confirmed files can still be exported.
                }
            }
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (ready.isEmpty()) {
                    toast("没有已确认切点的本地音频可导出。");
                    return;
                }
                if (ready.size() < parts.size()) {
                    toast("将导出 " + ready.size() + " 个已确认分集，其余待确认切点。");
                }
                openExportFolder(taskParts(ready));
            });
        });
    }

    private void openExportFolder(Bundle tasks) {
        exportParts = tasks;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
                EXPORT_REQUEST);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == MODEL_IMPORT_REQUEST) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                Uri file = data.getData();
                catalog.execute(() -> {
                    try (java.io.InputStream input = getContentResolver().openInputStream(file)) {
                        if (input == null) {
                            throw new IOException("无法读取模型包。");
                        }
                        LocalTranscription.importModel(this, input);
                        main.post(() -> {
                            refreshModelStatus();
                            toast("离线模型已安装，可重试字幕任务。");
                        });
                    } catch (IOException | SecurityException error) {
                        main.post(() -> toast(friendly(error)));
                    }
                });
            }
            return;
        }
        if (request == DISCOVERY_REQUEST) {
            if (result == RESULT_OK && data != null) {
                String bvid = data.getStringExtra(DiscoveryActivity.EXTRA_BVID);
                if (bvid != null) {
                    showPage(Page.DOWNLOAD);
                    link.setText(bvid);
                    load(bvid, data.getBooleanExtra(DiscoveryActivity.EXTRA_DOWNLOAD, false));
                }
            }
            return;
        }
        if (request != EXPORT_REQUEST) {
            return;
        }
        Bundle tasks = exportParts;
        exportParts = null;
        if (result != RESULT_OK || data == null || data.getData() == null || tasks == null) {
            return;
        }
        Uri directory = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(directory,
                    data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        } catch (SecurityException ignored) {
            // Temporary access suffices if the provider does not offer persistent grants.
        }
        submitGrouped(TransferService.EXPORT, tasks, false, directory);
    }

    private void openLogin() {
        startActivity(new Intent(this, BilibiliLoginActivity.class));
    }

    private void refreshAccount() {
        refreshModelStatus();
        boolean ximalayaSignedIn = CatalogLoginActivity.Provider.XIMALAYA.hasSession(this);
        ximalayaStatus.setText(ximalayaSignedIn ? "会话已保存" : "未登录");
        ximalayaStatus.setTextColor(ximalayaSignedIn ? palette.accent() : palette.muted());
        ximalayaAccount.setText(ximalayaSignedIn ? "管理" : "登录");
        boolean lanrenSignedIn = CatalogLoginActivity.Provider.LANREN.hasSession(this);
        lanrenStatus.setText(lanrenSignedIn ? "会话已保存" : "未登录");
        lanrenStatus.setTextColor(lanrenSignedIn ? palette.accent() : palette.muted());
        lanrenAccount.setText(lanrenSignedIn ? "管理" : "登录");
        if (accountChecking) {
            return;
        }
        accountChecking = true;
        accountStatus.setText("正在检查…");
        accountStatus.setTextColor(palette.muted());
        accountButton.setEnabled(false);
        BilibiliSession.check((name, failed) -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            accountChecking = false;
            accountButton.setEnabled(true);
            if (name == null && !failed) {
                accountStatus.setText("未登录");
                accountButton.setText(R.string.bilibili_login);
                accountButton.setOnClickListener(view -> openLogin());
            } else {
                accountStatus.setText(failed ? "暂时无法验证" : name);
                accountStatus.setTextColor(failed ? palette.muted() : palette.accent());
                accountButton.setText("管理");
                accountButton.setOnClickListener(view -> manageBilibiliAccount());
            }
        });
    }

    private void manageBilibiliAccount() {
        new AlertDialog.Builder(this).setTitle("B 站账号")
                .setItems(new String[]{"重新检查", "退出当前账号"}, (dialog, index) -> {
                    if (index == 0) {
                        refreshAccount();
                        return;
                    }
                    accountButton.setEnabled(false);
                    BilibiliSession.signOut(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            toast("已退出本应用的 B 站账号。");
                            refreshAccount();
                        }
                    });
                }).setNegativeButton("取消", null).show();
    }

    private void launchPlayback(String bvid, long cid, int segment, int preview) {
        try {
            if (preview < 0) {
                showPage(Page.PLAYER);
            }
            startForegroundService(new Intent(this, PlaybackService.class).setAction(PlaybackService.PLAY)
                    .putExtra(TransferService.EXTRA_BVID, bvid).putExtra(PlaybackService.EXTRA_PAGE, cid)
                    .putExtra(PlaybackService.EXTRA_INDEX, segment)
                    .putExtra(PlaybackService.EXTRA_PREVIEW, preview));
        } catch (RuntimeException error) {
            toast("无法启动播放器，请重试。");
        }
    }

    private void buildPlayer(FrameLayout base) {
        playerPanel = column();
        playerPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        playerPanel.setPadding(dp(16), 0, dp(16), dp(6));
        playerPanel.setVisibility(View.GONE);
        LinearLayout nowPlaying = row();
        nowPlaying.setPadding(0, dp(1), 0, dp(9));
        playerCover = iconTile(AudioUi.Icon.HEADPHONES, 48);
        nowPlaying.addView(playerCover, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = column();
        LinearLayout.LayoutParams textArea = new LinearLayout.LayoutParams(0, -2, 1);
        textArea.setMargins(dp(10), 0, dp(3), 0);
        nowPlaying.addView(titles, textArea);
        playerTitle = leadingLabel("", 18, palette.paper());
        playerTitle.setTypeface(null, Typeface.BOLD);
        playerTitle.setMaxLines(2);
        playerTitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(playerTitle, new LinearLayout.LayoutParams(-1, -2));
        playerEpisode = leadingLabel("", 13, palette.accent());
        playerEpisode.setMaxLines(2);
        playerEpisode.setEllipsize(TextUtils.TruncateAt.END);
        playerEpisode.setPadding(0, dp(3), 0, 0);
        titles.addView(playerEpisode, new LinearLayout.LayoutParams(-1, -2));
        iconAction(nowPlaying, AudioUi.Icon.SETTINGS, "播放设置", this::showPlaybackSettings);
        playerPanel.addView(nowPlaying, new LinearLayout.LayoutParams(-1, -2));
        transcript = new CaptionScrubber(this, palette, position -> {
            if (playing != null) {
                playing.seek(position);
                updatePlayer();
            }
        }, this::updateTimeline);
        playerPanel.addView(transcript, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout controls = panel();
        controls.setPadding(dp(10), dp(4), dp(10), dp(7));
        progress = new SeekBar(this);
        progress.setProgressTintList(ColorStateList.valueOf(palette.accent()));
        progress.setContentDescription("播放进度");
        controls.addView(progress, new LinearLayout.LayoutParams(-1, -2));
        progress.setOnSeekBarChangeListener(new SeekListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (playing != null) {
                    playing.seek(bar.getProgress());
                }
            }
        });
        LinearLayout timeline = row();
        timeline.setPadding(dp(9), 0, dp(9), 0);
        playerTime = leadingLabel("", 12, palette.muted());
        playerDuration = label("", 12, palette.muted());
        playerTime.setTypeface(Typeface.MONOSPACE);
        playerDuration.setTypeface(Typeface.MONOSPACE);
        timeline.addView(playerTime, new LinearLayout.LayoutParams(0, -2, 1));
        timeline.addView(playerDuration, new LinearLayout.LayoutParams(-2, -2));
        controls.addView(timeline, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout play = row();
        iconAction(play, AudioUi.Icon.PREVIOUS, "上一集", () -> { if (playing != null) playing.skip(-1); });
        toggle = new ImageButton(this);
        toggle.setBackground(AudioUi.clickable(this, palette.accent(), 32));
        toggle.setPadding(dp(19), dp(19), dp(19), dp(19));
        toggle.setOnClickListener(view -> { if (playing != null) playing.toggle(); });
        LinearLayout.LayoutParams transport = new LinearLayout.LayoutParams(dp(60), dp(60));
        transport.setMargins(dp(28), dp(2), dp(28), dp(3));
        play.addView(toggle, transport);
        iconAction(play, AudioUi.Icon.NEXT, "下一集", () -> { if (playing != null) playing.skip(1); });
        controls.addView(play, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout tools = row();
        weightedButton(tools, "选集", false, this::showEpisodes);
        speed = weightedButton(tools, "1.0×", false, this::chooseSpeed);
        captions = weightedButton(tools, "字幕", false, () -> {
            showCaptions = !showCaptions;
            updatePlayer();
        });
        Button forward = weightedButton(tools, "+30 秒", false, this::skipAhead);
        forward.setContentDescription("快进 30 秒");
        controls.addView(tools, new LinearLayout.LayoutParams(-1, -2));
        playerError = label("", 13, palette.accent());
        playerError.setVisibility(View.GONE);
        controls.addView(playerError, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams dock = spaced();
        dock.topMargin = dp(8);
        playerPanel.addView(controls, dock);
        base.addView(playerPanel, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showPlaybackSettings() {
        PlaybackService service = playing;
        if (service == null) {
            return;
        }
        LinearLayout settings = column();
        settings.setPadding(dp(22), dp(10), dp(22), dp(4));
        Switch simultaneous = new Switch(this);
        simultaneous.setText("同时播放");
        simultaneous.setChecked(service.mix());
        simultaneous.setOnCheckedChangeListener((view, value) -> {
            if (playing == service) {
                service.setMix(value);
            }
        });
        settings.addView(simultaneous, new LinearLayout.LayoutParams(-1, dp(44)));
        settings.addView(label("本应用音量", 14, palette.muted()), spaced());
        SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress(Math.round(service.volume() * 100));
        volume.setContentDescription("本应用音量");
        volume.setOnSeekBarChangeListener(new SeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && playing == service) {
                    service.setVolume(value / 100f);
                }
            }
        });
        settings.addView(volume, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("播放设置").setView(settings)
                .setPositiveButton("完成", null).show();
    }

    private AudioLibrary.Part currentPart() {
        return playing == null ? null : playing.part();
    }

    private void skipAhead() {
        if (currentPart() != null) {
            playing.seek(Math.min(playing.duration() - 1, playing.position() + 30_000));
            updatePlayer();
        }
    }

    private void updateTimeline() {
        if (currentPart() == null) {
            return;
        }
        int duration = playing.duration();
        if (!dragging) {
            progress.setMax(duration);
            progress.setProgress(transcript.displayedPosition());
        }
        setTextIfChanged(playerTime, AudioLibrary.time(progress.getProgress()));
        setTextIfChanged(playerDuration, AudioLibrary.time(duration));
    }

    private void updatePlayer() {
        AudioLibrary.Part source = currentPart();
        boolean active = source != null;
        playerPanel.setVisibility(active ? View.VISIBLE : View.GONE);
        playerLanding.setVisibility(active ? View.GONE : View.VISIBLE);
        if (!active) {
            refreshResume();
            return;
        }
        resumeButton.setVisibility(View.GONE);
        int episode = playing.episodeNumber();
        showArtwork(playerCover, playing.artwork());
        setTextIfChanged(playerTitle, playing.title());
        setTextIfChanged(playerEpisode, episode > 0 ? library.episodeNames(source.bvid()).label(episode) : source.label());
        PlaybackService.CaptionTrack track = playing.captionTrack();
        transcript.update(track, playing.position(), playing.duration(), showCaptions);
        if (captions.isSelected() != showCaptions) {
            captions.setSelected(showCaptions);
            captions.setBackground(AudioUi.clickable(this, showCaptions ? palette.selected() : palette.subtle(), 12));
            captions.setContentDescription(showCaptions ? "隐藏字幕" : "显示字幕");
        }
        captions.setEnabled(!track.cues().isEmpty());
        captions.setAlpha(track.cues().isEmpty() ? .58f : 1f);
        setTextIfChanged(playerError, playing.error());
        playerError.setVisibility(playing.error().isEmpty() ? View.GONE : View.VISIBLE);
        updateTimeline();
        boolean isPlaying = playing.playing();
        if (!Objects.equals(toggle.getTag(), isPlaying)) {
            toggle.setTag(isPlaying);
            toggle.setImageDrawable(AudioUi.icon(isPlaying ? AudioUi.Icon.PAUSE : AudioUi.Icon.PLAY,
                    getColor(R.color.action_foreground)));
        }
        toggle.setEnabled(!playing.loading());
        toggle.setContentDescription(playing.loading() ? "加载中" : isPlaying ? "暂停" : "播放");
        setTextIfChanged(speed, playing.speed() + "×");
    }

    private static void setTextIfChanged(TextView target, String text) {
        if (!TextUtils.equals(target.getText(), text)) {
            target.setText(text);
        }
    }

    private void chooseSpeed() {
        if (playing == null) {
            return;
        }
        float[] options = {0.8f, 1f, 1.15f, 1.25f, 1.5f, 1.75f, 2f};
        String[] names = new String[options.length];
        for (int index = 0; index < options.length; index++) {
            names[index] = options[index] + "×";
        }
        new AlertDialog.Builder(this).setTitle("播放倍速").setItems(names, (dialog, index) -> {
            if (playing != null) {
                playing.setSpeed(options[index]);
            }
        }).show();
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private LinearLayout panel() {
        LinearLayout view = column();
        GradientDrawable surface = AudioUi.card(this, palette.surface(), 20);
        surface.setStroke(dp(1), palette.outline());
        view.setBackground(surface);
        view.setPadding(dp(15), dp(14), dp(15), dp(14));
        return view;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(11);
        return params;
    }

    private TextView label(String line, int size, int color) {
        TextView view = new TextView(this);
        view.setText(line);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private TextView leadingLabel(String line, int size, int color) {
        TextView view = label(line, size, color);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button button(LinearLayout parent, String title, Runnable action) {
        Button view = new Button(this, null, android.R.attr.borderlessButtonStyle);
        view.setText(title);
        view.setTextColor(palette.paper());
        view.setAllCaps(false);
        view.setMinWidth(dp(48));
        view.setMinimumHeight(dp(48));
        view.setTextSize(14);
        view.setPadding(dp(8), 0, dp(8), 0);
        view.setOnClickListener(ignored -> action.run());
        parent.addView(view);
        return view;
    }

    private Button weightedButton(LinearLayout row, String title, boolean primary, Runnable action) {
        int count = row.getChildCount();
        Button view = button(row, title, action);
        AudioUi.styleButton(view, primary);
        view.setPadding(dp(4), dp(3), dp(4), dp(3));
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -1, 1);
        if (count > 0) {
            space.setMarginStart(dp(6));
        }
        view.setLayoutParams(space);
        return view;
    }

    private void textIcon(Button button, AudioUi.Icon icon) {
        android.graphics.drawable.Drawable drawable = AudioUi.icon(icon, palette.accent());
        drawable.setBounds(0, 0, dp(18), dp(18));
        button.setCompoundDrawablesRelative(drawable, null, null, null);
        button.setCompoundDrawablePadding(dp(4));
        button.setPadding(dp(10), dp(3), dp(10), dp(3));
    }

    private ImageButton iconAction(LinearLayout row, AudioUi.Icon icon, String description, Runnable action) {
        ImageButton view = new ImageButton(this);
        view.setBackground(AudioUi.clickable(this, palette.subtle(), 14));
        view.setImageDrawable(AudioUi.icon(icon, palette.paper()));
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setContentDescription(description);
        view.setOnClickListener(ignored -> action.run());
        row.addView(view, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return view;
    }

    private void addDivider(LinearLayout column) {
        View divider = new View(this);
        divider.setBackgroundColor(palette.outline());
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, dp(1));
        space.topMargin = dp(8);
        space.bottomMargin = dp(8);
        column.addView(divider, space);
    }

    private LinearLayout navTile(LinearLayout row, AudioUi.Icon icon, String name, Runnable action) {
        LinearLayout tile = column();
        tile.setBackground(AudioUi.clickable(this, palette.surface(), 15));
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(0, dp(2), 0, dp(3));
        tile.setFocusable(true);
        tile.setContentDescription(name);
        tile.setOnClickListener(view -> action.run());
        ImageView image = new ImageView(this);
        image.setImageDrawable(AudioUi.icon(icon, palette.accent()));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(16), dp(5), dp(16), dp(5));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(image, new LinearLayout.LayoutParams(dp(52), dp(30)));
        TextView text = label(name, 12, palette.paper());
        text.setPadding(0, dp(2), 0, 0);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(text);
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) {
            tileParams.leftMargin = dp(8);
        }
        row.addView(tile, tileParams);
        return tile;
    }

    private ImageView iconTile(AudioUi.Icon icon, int size) {
        ImageView view = new ImageView(this);
        view.setBackground(AudioUi.card(this, palette.subtle(), 13));
        view.setImageDrawable(AudioUi.icon(icon, palette.accent()));
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int padding = dp(size / 5);
        view.setPadding(padding, padding, padding, padding);
        view.setClipToOutline(true);
        return view;
    }

    private void showArtwork(ImageView view, String address) {
        if (Objects.equals(view.getTag(), address)) {
            return;
        }
        view.setTag(address);
        view.setPadding(dp(8), dp(8), dp(8), dp(8));
        view.setImageDrawable(AudioUi.icon(AudioUi.Icon.HEADPHONES, palette.accent()));
        CoverImages.load(view, address);
    }

    private EditText input(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        return field;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String friendly(Throwable error) {
        String text = error.getMessage();
        return text == null || text.length() > 130 || text.contains("://") ? "读取失败，请重试。" : text;
    }

    private abstract static class SeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar bar) {}
        @Override public void onStopTrackingTouch(SeekBar bar) {}
        @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {}
    }
}
