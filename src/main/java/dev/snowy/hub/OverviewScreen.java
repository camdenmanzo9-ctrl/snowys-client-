package dev.snowy.hub;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Snowy's Dashboard: Overview, Win / Loss, Rakeback and Sessions tabs in the purple style. */
public class OverviewScreen extends Screen {
    // ---------------- theme ----------------
    private static int BG = 0xFF060509, FRAME = 0xFF0D0B15, PANEL = 0xFF12101C, BORDER = 0xFF221E33,
            PURPLE = 0xFF8B5CF6, PURPLE_LIGHT = 0xFFB794F6;   // set from the mod theme
    private static final int TEXT = 0xFFF2F0FA, MUTED = 0xFFADA9C4, DIM = 0xFF8A86A3,
            GOLD = 0xFFE0B24A, BLUE = 0xFF7B8CFF, RED = 0xFFFF5A6E, GREEN = 0xFF4ADE80, AMBER = 0xFFE0A030;

    /** Load the mod theme's colors (Settings > General > Theme). */
    private static void applyPalette() {
        int[] t = Settings.themeColors(Settings.S.modTheme);
        BG = 0xFF000000 | t[0]; FRAME = 0xFF000000 | t[1]; PANEL = 0xFF000000 | t[2]; BORDER = 0xFF000000 | t[3];
        PURPLE = 0xFF000000 | t[4]; PURPLE_LIGHT = 0xFF000000 | t[5];
    }

    /** Text color that reads on top of the accent (dark text on light accents like Mono). */
    private static int onAccent() {
        int r = (PURPLE >> 16) & 0xFF, g = (PURPLE >> 8) & 0xFF, b = PURPLE & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 > 170 ? 0xFF111111 : TEXT;
    }

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final String[] RANGE_LABELS = {"SESSION", "24H", "7D", "30D", "ALL"};
    private static final long[] RANGES = {-1, DAY, 7 * DAY, 30 * DAY, 0};   // -1 = this session
    private static final String[] TAB_LABELS = {"OVERVIEW", "WIN / LOSS", "RAKEBACK", "SESSIONS", "AUCTIONS", "PROFIT PANEL"};
    // left sidebar: label -> page
    private static final String[] SIDE_LABELS = {"Dashboard", "Players", "Payments", "Auctions/1 Flick", "Gambling", "Overlay"};
    private static final String[] PAGE_TITLES = {"DASHBOARD", "PLAYERS", "PAYMENTS", "AUCTIONS/1 FLICK", "GAMBLING", "OVERLAY"};
    private static final int[] SIDE_TABS = {0, 1, 6, 4, 7, 5};
    private static final int SB = 118;   // sidebar width
    private int ox, oy, ow, oh;          // the whole window (sidebar + content)
    private Button sideSettings, resetBtn;
    private long resetArmedUntil = 0;

    private static int tab = 0;
    private static int range = 2;           // 7D like the picture
    private static int filter = 0;          // 0 all, 1 in, 2 out
    private static boolean paused = false;
    private static long frozenAt = 0;

    private final List<Button> rangeButtons = new ArrayList<>();
    private final List<Button> tabButtons = new ArrayList<>();
    private final List<Button> filterButtons = new ArrayList<>();
    private Button settingsButton, sessionButton, headerSettings;
    private EditBox searchBox;
    private Object pendingFocus;   // the search box to focus on the next frame
    private static String search = "";
    private final List<Button> panelButtons = new ArrayList<>();
    private long confirmUntil = 0;
    private static int scroll = 0;

    private int fx, fy, fw, fh, escX;
    private boolean showEsc = true;
    private float tabScale = 0.76f;
    private int rangeGroupX, tabGroupX, tabGroupEnd;

    public OverviewScreen() {
        super(Component.literal("Snowy's Dashboard"));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private long now() { return paused ? frozenAt : System.currentTimeMillis(); }
    private long since() {
        if (RANGES[range] < 0) return PaymentLedger.sessionStartTime();   // SESSION
        return RANGES[range] == 0 ? 0 : now() - RANGES[range];
    }

    // ---------------- font ----------------
    private static final Style INTER = Style.EMPTY.withFont(
            new FontDescription.Resource(Identifier.fromNamespaceAndPath("snowyshub", "inter")));
    private static final Style INTER_BOLD = Style.EMPTY.withFont(
            new FontDescription.Resource(Identifier.fromNamespaceAndPath("snowyshub", "inter_bold")));

    private static Component c(String s, boolean bold) {
        return Component.literal(s).withStyle(bold ? INTER_BOLD : INTER);
    }

    private static final float TEXT_SIZE = 1.25f;   // overall text size

    private void text(GuiGraphicsExtractor g, String s, int x, int y, int color, float scale, boolean bold) {
        Fonts.draw(g, font, s, x, y, color, scale * TEXT_SIZE, bold);
    }

    private int w(String s, float scale) { return Fonts.width(font, s, scale * TEXT_SIZE, false); }
    private int wb(String s, float scale) { return Fonts.width(font, s, scale * TEXT_SIZE, true); }

    private int caps(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        int cx = x;
        for (char ch : s.toCharArray()) {
            String t = String.valueOf(ch);
            text(g, t, cx, y, color, 0.76f, true);
            cx += wb(t, 0.76f);
        }
        return cx - x;
    }

    private int capsW(String s) {
        int t = 0;
        for (char ch : s.toCharArray()) t += wb(String.valueOf(ch), 0.76f);
        return t;
    }

    // ---------------- keys ----------------
    private void togglePause() {
        paused = !paused;
        if (paused) frozenAt = System.currentTimeMillis();
    }

    /** 1-4 change the range, Space pauses, Tab switches pages, Esc closes. */
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int k = event.key();
        if (searchBox != null && searchBox.isFocused() && k != GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(event);
        if (itemSearch != null && itemSearch.isFocused() && k != GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(event);
        for (EditBox eb : new EditBox[]{nameBox, qtyBox, secBox})
            if (eb != null && eb.isFocused() && k != GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(event);
        if (k >= GLFW.GLFW_KEY_1 && k <= GLFW.GLFW_KEY_5) { range = k - GLFW.GLFW_KEY_1; return true; }
        if (k == GLFW.GLFW_KEY_SPACE) { togglePause(); return true; }
        if (k == GLFW.GLFW_KEY_TAB) {
            int at = 0;
            for (int i = 0; i < SIDE_TABS.length; i++) if (SIDE_TABS[i] == tab) at = i;
            tab = SIDE_TABS[(at + 1) % SIDE_TABS.length]; scroll = 0; wlPlayer = null; rebuildWidgets(); return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * 3);
        if (tab == 3 || (tab == 1 && wlPlayer == null)) rebuildWidgets();
        return true;
    }

    // ---------------- layout ----------------
    private int small(String s) { return w(s, 0.75f) + 12; }

    private Button btn(String label, int x, int y, int bw, Runnable r) {
        return addWidget(Button.builder(Component.literal(label), b -> r.run()).bounds(x, y, bw, 16).build());
    }

    private int contentY() { return fy + 30; }
    private int contentBottom() { return fy + fh - 22; }

    @Override
    protected void init() {
        applyPalette();
        // whole window: thin border all round; sidebar on the left, pages on the right
        int mx = Math.max(6, width / 70), my = Math.max(5, height / 60);
        ow = width - mx * 2; oh = height - my * 2; ox = mx; oy = my;
        fx = ox + SB; fw = ow - SB; fy = oy; fh = oh;   // every page lays itself out inside fx..fx+fw
        rangeButtons.clear();
        tabButtons.clear();
        filterButtons.clear();
        panelButtons.clear();
        sessionButton = null;

        // top bar, right side: [SESSION 24H 7D 30D ALL]  [RESET SESSION]
        int resetW = wb("RESET SESSION", 0.7f) + 24;
        resetBtn = btn("", fx + fw - 10 - resetW, fy + 8, resetW, () -> {
            long t = System.currentTimeMillis();
            if (t > resetArmedUntil) { resetArmedUntil = t + 3000; return; }   // first click asks to confirm
            resetArmedUntil = 0;
            if (PaymentLedger.isSessionActive()) PaymentLedger.endSession();
            PaymentLedger.startSession();
            rebuildWidgets();
        });
        int bx = resetBtn.getX() - 8 - 2;
        for (int i = RANGE_LABELS.length - 1; i >= 0; i--) {
            final int r = i;
            int bw = wb(RANGE_LABELS[i], 0.68f) + 12;
            rangeButtons.add(0, btn(RANGE_LABELS[i], bx - bw, fy + 8, bw, () -> range = r));
            bx -= bw + 1;
        }
        rangeGroupX = bx - 1;
        escX = rangeGroupX - 10;
        headerSettings = null;

        // sidebar
        for (int i = 0; i < SIDE_LABELS.length; i++) {
            final int t = SIDE_TABS[i];
            Button b = btn(SIDE_LABELS[i], ox + 8, oy + 36 + i * 20, SB - 16, () -> { tab = t; scroll = 0; wlPlayer = null; rebuildWidgets(); });
            b.setHeight(18);
            tabButtons.add(b);
        }
        sideSettings = btn("Settings", ox + 8, oy + oh - 28, SB - 16, () -> {
            if (minecraft != null) minecraft.setScreen(new SettingsScreen(this));
        });
        sideSettings.setHeight(18);
        tabScale = 0.76f;
        tabGroupX = 0; tabGroupEnd = 0;
        settingsButton = null;
        searchBox = null;
        sessionDelButtons.clear();
        sessionPickButtons.clear();
        clearSessionsBtn = null;
        wlRows.clear();
        wlBack = null;
        itemSearch = null;
        nameBox = null; qtyBox = null; secBox = null;
        auctionButtons.clear();

        if (tab == 0) {
            // feed filter pills
            int[] feed = feedBox();
            int px = feed[0] + feed[2] - 8;
            String[] f = {"OUT", "IN", "ALL"};
            for (int i = 0; i < 3; i++) {
                final int idx = 2 - i;
                int pw = small(f[i]);
                filterButtons.add(0, btn(f[i], px - pw, feed[1] + 6, pw, () -> filter = idx));
                px -= pw + 3;
            }
        } else if (tab == 6) {
            int py = contentY() + 30 + 58 + 10;
            int px = fx + 10 + fw - 20 - 10;
            String[] f = {"OUT", "IN", "ALL"};
            for (int i = 0; i < 3; i++) {
                final int idx = 2 - i;
                int pw = small(f[i]);
                filterButtons.add(0, btn(f[i], px - pw, py + 8, pw, () -> filter = idx));
                px -= pw + 3;
            }
            searchBox = new EditBox(font, -4000, -4000, 200, 12, Component.literal("Search"));
            searchBox.setMaxLength(32);
            searchBox.setValue(search);
            searchBox.setResponder(v -> { search = v; scroll = 0; });
            addWidget(searchBox);
            Button click = btn("", filterButtons.get(0).getX() - 8 - 150, py + 7, 150, () -> pendingFocus = searchBox);
            click.setHeight(20);
        } else if (tab == 1 && wlPlayer != null) {
            int bw = w("All players", 0.8f) + 26;
            wlBack = btn("All players", fx + 10, contentY() + 4, bw, () -> { wlPlayer = null; scroll = 0; rebuildWidgets(); });
            wlBack.setHeight(18);
        } else if (tab == 1 || tab == 2) {
            int sbw = Math.min(170, fw / 4), sbx = fx + fw - 10 - sbw, sby = contentY() + 30 + 8;
            searchBox = new EditBox(font, -4000, -4000, 200, 12, Component.literal("Search"));   // hidden: we draw it ourselves
            searchBox.setMaxLength(32);
            searchBox.setValue(search);
            searchBox.setResponder(v -> { search = v; scroll = 0; });
            addWidget(searchBox);
            Button click = btn("", sbx, sby, sbw, () -> pendingFocus = searchBox);
            click.setHeight(20);
            if (tab == 1) addWinLossRowButtons();
        } else if (tab == 4) {
            initAuctions();
        } else if (tab == 5) {
            Panels.load();
            int y = contentY() + 30, rowH = 44, x = fx + 10, rw = fw - 20;
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                panelButtons.add(addWidget(Button.builder(Component.literal(""), b -> {
                    if (idx == 0) Panels.profit = !Panels.profit;
                    else if (idx == 1) Panels.rakeback = !Panels.rakeback;
                    else Panels.balance = !Panels.balance;
                    Panels.save();
                }).bounds(x, y + i * (rowH + 8), rw, rowH).build()));
            }
        } else if (tab == 3) {
            int bw = wb("START SESSION", 0.8f) + 34;
            sessionButton = addWidget(Button.builder(Component.literal(""), b -> onSession())
                    .bounds(fx + 22, sTop() + sCardH() - 30, bw, 20).build());
            addSessionRowButtons();
        }
    }

    // Display copy of you (empty hands, no cape), made once per world
    private static PreviewPlayer previewPlayer;
    private static Object previewLevel;

    private PreviewPlayer preview() {
        if (previewPlayer == null || previewLevel != minecraft.level) {
            previewPlayer = new PreviewPlayer(minecraft.level, minecraft.player.getGameProfile());
            previewLevel = minecraft.level;
        }
        previewPlayer.refreshParts();
        return previewPlayer;
    }

    // ---- sessions page: history clicks, delete buttons ----
    private final java.util.Map<Button, Integer> sessionDelButtons = new java.util.LinkedHashMap<>();
    private final java.util.Map<Button, Integer> sessionPickButtons = new java.util.LinkedHashMap<>();
    private Button clearSessionsBtn;
    private int pendingDel = -2;          // which row is waiting for the 2nd click
    private long pendingUntil = 0;
    private static int pickedSession = -1;   // -1 = the current session
    private static final int S_HIST_ROW = 30;

    private int sTop() { return contentY() + 30; }
    private int sLeftW() { return (fw - 28) / 2; }
    private int sCardH() { return 104; }
    private int[] sHist() {   // x, y, w, h of the history panel
        int rx = fx + 10 + sLeftW() + 8;
        return new int[]{rx, sTop(), fx + fw - 10 - rx, contentBottom() - sTop()};
    }
    private int sHistRowsY() { return sHist()[1] + 30; }
    private int sVisible() { return Math.max(1, (sHist()[1] + sHist()[3] - 6 - sHistRowsY()) / S_HIST_ROW); }

    private void addSessionRowButtons() {
        sessionDelButtons.clear();
        sessionPickButtons.clear();
        clearSessionsBtn = null;
        int[] h = sHist();
        int size = PaymentLedger.history().size();
        if (pickedSession >= size) pickedSession = -1;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, size - sVisible())));
        for (int i = 0; i < sVisible() && scroll + i < size; i++) {
            final int idx = scroll + i;
            int ry = sHistRowsY() + i * S_HIST_ROW;
            Button pick = btn("", h[0] + 8, ry, h[2] - 40, () -> { pickedSession = pickedSession == idx ? -1 : idx; rebuildWidgets(); });
            pick.setHeight(S_HIST_ROW - 4);
            sessionPickButtons.put(pick, idx);
            Button del = btn("", h[0] + h[2] - 28, ry + 5, 16,
                    () -> armOrRun(idx, () -> { PaymentLedger.deleteSession(idx); pickedSession = -1; rebuildWidgets(); }));
            sessionDelButtons.put(del, idx);
        }
    }

    /** First click arms it (turns red, "Sure?"), a second click within 3 seconds does it. */
    private void armOrRun(int key, Runnable action) {
        long now = System.currentTimeMillis();
        if (pendingDel == key && now < pendingUntil) { pendingDel = -2; action.run(); }
        else { pendingDel = key; pendingUntil = now + 3000; }
    }

    // ---- Win / Loss: click a player to open their page ----
    private static String wlPlayer = null;
    private Button wlBack;
    private final java.util.Map<Button, String> wlRows = new java.util.LinkedHashMap<>();

    /** The Win / Loss list in display order (search applied), shared by drawing and click areas. */
    private List<PaymentLedger.Entry> winLossList() {
        List<PaymentLedger.Entry> all = new ArrayList<>();
        String q = search.trim().toLowerCase(Locale.US);
        for (PaymentLedger.Entry e : PaymentLedger.entries(false))
            if (q.isEmpty() || e.name.toLowerCase(Locale.US).contains(q)) all.add(e);
        all.sort(Comparator.comparingLong(PaymentLedger.Entry::net).reversed());
        return all;
    }

    private int wlTableY() { return contentY() + 30 + 44; }
    private int wlRowsY() { return wlTableY() + 8 + 19; }
    private static final int WL_ROW_H = 21;
    private int wlVisible() { return Math.max(1, (contentBottom() - 4 - wlRowsY()) / WL_ROW_H); }

    private void addWinLossRowButtons() {
        wlRows.clear();
        List<PaymentLedger.Entry> all = winLossList();
        int px = fx + 10, pw = fw - 20;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, all.size() - wlVisible())));
        for (int i = 0; i < wlVisible() && scroll + i < all.size(); i++) {
            String name = all.get(scroll + i).name;
            Button b = btn("", px + 4, wlRowsY() + i * WL_ROW_H - 3, pw - 12, () -> { wlPlayer = name; scroll = 0; rebuildWidgets(); });
            b.setHeight(WL_ROW_H);
            wlRows.put(b, name);
        }
    }

    /** Esc on a player page goes back to the list instead of closing. */
    @Override
    public void onClose() {
        if (tab == 1 && wlPlayer != null) { wlPlayer = null; scroll = 0; rebuildWidgets(); return; }
        super.onClose();
    }

    private static String ago(long ts) {
        long m = Math.max(0, (System.currentTimeMillis() - ts) / 60000);
        if (m < 1) return "just now";
        if (m < 60) return m + "m ago";
        long h = m / 60;
        if (h < 24) return h + "h ago";
        return (h / 24) + "d ago";
    }

    private void drawPlayerPage(GuiGraphicsExtractor g, int mx, int my) {
        String name = wlPlayer;
        PaymentLedger.Entry all = PaymentLedger.allTimeFor(name);
        List<PaymentLedger.Payment> pays = PaymentLedger.paymentsFor(name, 0);   // newest first
        List<PaymentLedger.Payment> oldest = new ArrayList<>(pays);
        java.util.Collections.reverse(oldest);

        // back button
        {
            Button b = wlBack;
            boolean hot = over(b, mx, my);
            box(g, b.getX(), b.getY(), b.getWidth(), b.getHeight(), hot ? 0xFF1E1A2E : PANEL, hot ? PURPLE : BORDER);
            int ax = b.getX() + 8, ay = b.getY() + b.getHeight() / 2;
            for (int i = 0; i < 4; i++) {   // little left arrow
                g.fill(ax + i, ay - i, ax + i + 1, ay - i + 1, hot ? TEXT : MUTED);
                g.fill(ax + i, ay + i, ax + i + 1, ay + i + 1, hot ? TEXT : MUTED);
            }
            text(g, "All players", ax + 9, b.getY() + (b.getHeight() - 7) / 2, hot ? TEXT : MUTED, 0.8f, false);
        }

        // header: face, name, first / last bet
        int hx = fx + 10, hy = contentY() + 30;
        face(g, name, hx, hy, 26);
        text(g, name, hx + 34, hy + 2, TEXT, 1.4f, true);
        String sub = pays.isEmpty() ? "No dated payments yet"
                : "first bet " + day(oldest.get(0).ts) + "   -   last bet " + ago(pays.get(0).ts);
        text(g, sub, hx + 34, hy + 18, DIM, 0.72f, false);

        // stat boxes
        long theirNet = all == null ? 0 : all.net();
        long paidYou = all == null ? 0 : all.paidYou, youPaid = all == null ? 0 : all.youPaid;
        int bets = all == null ? pays.size() : all.payments;
        long biggest = 0;
        for (PaymentLedger.Payment p : pays) biggest = Math.max(biggest, p.youPaid);
        int cy = hy + 34, gap = 8, cw = (fw - 20 - gap * 4) / 5;
        int[] cols = {fx + 10, 0, 0, 0, 0};
        for (int i = 1; i < 5; i++) cols[i] = cols[i - 1] + cw + gap;
        statBox(g, cols[0], cy, cw, "THEIR NET", signedMoney(theirNet), theirNet > 0 ? GREEN : theirNet < 0 ? RED : TEXT,
                theirNet > 0 ? "up on you" : theirNet < 0 ? "down to you" : "even");
        statBox(g, cols[1], cy, cw, "PAID YOU", Money.format(paidYou), TEXT, "their bets");
        statBox(g, cols[2], cy, cw, "YOU PAID", Money.format(youPaid), TEXT, "their wins");
        statBox(g, cols[3], cy, cw, "BETS", String.valueOf(bets), TEXT, "payments");
        statBox(g, cols[4], cy, cw, "BIGGEST WIN", Money.format(biggest), biggest > 0 ? GREEN : TEXT, "one payout");

        // chart (left) + history (right)
        int py = cy + 58, ph = contentBottom() - py;
        int lw = (fw - 28) * 58 / 100, rx = fx + 10 + lw + 8, rw = fx + fw - 10 - rx;
        box(g, fx + 10, py, lw, ph, PANEL, BORDER);
        caps(g, "THEIR NET OVER TIME", fx + 20, py + 10, TEXT);
        long[] series = new long[oldest.size() + 1];
        long run = 0;
        for (int i = 0; i < oldest.size(); i++) {
            run += oldest.get(i).youPaid - oldest.get(i).paidYou;   // their side
            series[i + 1] = run;
        }
        String now = signedMoney(run);
        text(g, now, fx + 10 + lw - 12 - wb(now, 1.1f), py + 8, run > 0 ? GREEN : run < 0 ? RED : MUTED, 1.1f, true);
        chart(g, series, fx + 20, py + 28, lw - 30, ph - 38);

        box(g, rx, py, rw, ph, PANEL, BORDER);
        caps(g, "PAYMENTS", rx + 10, py + 10, TEXT);
        if (!pays.isEmpty()) text(g, String.valueOf(pays.size()), rx + 16 + capsW("PAYMENTS"), py + 10, DIM, 0.72f, false);
        int rowH = 18, ry = py + 28, rows = Math.max(0, (py + ph - 6 - ry) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, pays.size() - rows)));
        for (int i = 0; i < rows && scroll + i < pays.size(); i++) {
            PaymentLedger.Payment p = pays.get(scroll + i);
            boolean in = p.paidYou > 0;
            int ty = ry + i * rowH;
            if (i % 2 == 0) g.fill(rx + 6, ty - 3, rx + rw - 6, ty + rowH - 3, 0xFF15121F);
            text(g, day(p.ts) + "  " + clock(p.ts), rx + 12, ty + 2, DIM, 0.74f, false);
            text(g, in ? "bet" : "won", rx + rw * 52 / 100, ty + 2, MUTED, 0.74f, false);
            String amt = in ? "+" + Money.format(p.paidYou) : "-" + Money.format(p.youPaid);
            text(g, amt, rx + rw - 12 - wb(amt, 0.8f), ty + 1, in ? GREEN : RED, 0.8f, true);
        }
        if (pays.isEmpty()) text(g, "No dated payments with " + name + " yet", rx + 12, ry + 2, DIM, 0.8f, false);
        scrollbar(g, rx + rw - 4, ry - 3, py + ph - 6 - ry, rows, pays.size());
    }

    private void statBox(GuiGraphicsExtractor g, int x, int y, int bw, String label, String value, int valueColor, String sub) {
        box(g, x, y, bw, 50, PANEL, BORDER);
        caps(g, label, x + 10, y + 8, MUTED);
        text(g, value, x + 10, y + 21, valueColor, 1.15f, true);
        text(g, sub, x + 10, y + 38, DIM, 0.66f, false);
    }

    private void onSession() {
        if (!PaymentLedger.isSessionActive()) {
            PaymentLedger.startSession();
            confirmUntil = 0;
            pickedSession = -1;
            rebuildWidgets();
            return;
        }
        long t = System.currentTimeMillis();
        if (t > confirmUntil) { confirmUntil = t + 3000; return; }   // first click asks to confirm
        confirmUntil = 0;
        PaymentLedger.endSession();
        pickedSession = -1;
        rebuildWidgets();   // the ended session shows at the top of the history right away
    }

    private int welcomeY() { return contentY() + 2; }
    private int cardsY() { return welcomeY() + 56; }
    private int cardsH() { return 66; }
    private static final int TOP_WL_H = 100;   // Top winners / losers panel
    private int[] feedBox() {
        int y = cardsY() + cardsH() + 8, h = contentBottom() - TOP_WL_H - 8 - y;
        int bw = (fw - 28) * 40 / 100;
        return new int[]{fx + 10, y, bw, h};
    }
    private int[] chartBox() {
        int[] f = feedBox();
        int x = f[0] + f[2] + 8;
        return new int[]{x, f[1], fx + fw - 10 - x, f[3]};
    }

    // ---------------- drawing helpers ----------------
    /** Rounded box: border color first, then the fill inset by 1px. */
    private void box(GuiGraphicsExtractor g, int x, int y, int bw, int bh, int fill, int border) {
        if (fill == PANEL) fill = (0xE6 << 24) | (PANEL & 0xFFFFFF);
        int r = Math.max(1, Math.min(4, Math.min(bw, bh) / 5));
        round(g, x, y, bw, bh, r, border);
        round(g, x + 1, y + 1, bw - 2, bh - 2, Math.max(0, r - 1), fill);
    }

    /** Filled rectangle with rounded corners of radius r. */
    static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) { g.fill(x, y, x + w, y + h, color); return; }
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    private static int alpha(int col, int a) { return (a << 24) | (col & 0xFFFFFF); }

    private static final boolean GLOWS = false;   // flat look: cleaner and lighter to draw

    private void glow(GuiGraphicsExtractor g, int cx, int cy, int rx, int ry, int color, int strength) {
        if (!GLOWS) return;
        int steps = 10;   // light: 10 soft layers (looks the same, far less to draw)
        int per = Math.max(1, Math.round(strength / (float) steps));
        for (int i = steps; i >= 1; i--) {
            float t = i / (float) steps;
            int gw = Math.round(rx * t), gh = Math.round(ry * t);
            int cw = Math.round(gw * 0.71f);
            g.fill(cx - cw, cy - gh, cx + cw, cy + gh, alpha(color, per));
            g.fill(cx - gw, cy - Math.round(gh * 0.71f), cx - cw, cy + Math.round(gh * 0.71f), alpha(color, per));
            g.fill(cx + cw, cy - Math.round(gh * 0.71f), cx + gw, cy + Math.round(gh * 0.71f), alpha(color, per));
        }
    }

    private static boolean over(Button b, int mx, int my) {
        return b != null && mx >= b.getX() && mx < b.getX() + b.getWidth() && my >= b.getY() && my < b.getY() + b.getHeight();
    }

    private void pill(GuiGraphicsExtractor g, Button b, boolean on, int mx, int my) {
        pill(g, b, on, mx, my, 0.76f);
    }

    private void pill(GuiGraphicsExtractor g, Button b, boolean on, int mx, int my, float sc) {
        int x = b.getX(), y = b.getY(), bw = b.getWidth(), bh = b.getHeight();
        boolean hover = over(b, mx, my);
        cover(g, b, PANEL);
        round(g, x, y, bw, bh, 3, on ? PURPLE : hover ? mixColor(PANEL, PURPLE, 0.45f) : mixColor(PANEL, PURPLE, 0.25f));
        String s = b.getMessage().getString();
        text(g, s, x + (bw - wb(s, sc)) / 2, y + (bh - Math.round(9 * sc)) / 2, on ? onAccent() : hover ? TEXT : MUTED, sc, true);
    }

    /** Thin, smooth, wavy line (no fill), soft edges. */
    private void smoothLine(GuiGraphicsExtractor g, long[] vals, int x, int y, int w, int h, int color) {
        if (vals.length < 2) { g.fill(x, y + h - 2, x + w, y + h, alpha(color, 90)); return; }
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (long v : vals) { min = Math.min(min, v); max = Math.max(max, v); }
        if (max == min) { max++; min--; }
        int prev = Integer.MIN_VALUE;
        for (int px = 0; px < w; px++) {
            double t = (double) px * (vals.length - 1) / Math.max(1, w - 1);
            int i0 = (int) t, i1 = Math.min(vals.length - 1, i0 + 1);
            double f = t - i0, e = (1 - Math.cos(Math.PI * f)) / 2;   // rounded waves between points
            double v = vals[i0] + (vals[i1] - vals[i0]) * e;
            int yy = y + (int) Math.round((max - v) / (double) (max - min) * (h - 2));
            int a = prev == Integer.MIN_VALUE ? yy : prev;
            g.fill(x + px, yy + 2, x + px + 1, y + h + 2, alpha(color, 30));             // filled area under the line
            g.fill(x + px, Math.min(a, yy), x + px + 1, Math.max(a, yy) + 2, color);   // solid, 2 px
            prev = yy;
        }
    }

    /** One bar per day, today on the right and brightest. */
    private void bars(GuiGraphicsExtractor g, long[] vals, int x, int y, int bw, int bh, int color) {
        int n = vals.length;
        long max = 1;
        for (long v : vals) max = Math.max(max, v);
        int gap = n > 14 ? 1 : 3;
        float each = (bw - gap * (n - 1)) / (float) n;
        for (int i = 0; i < n; i++) {
            int x0 = x + Math.round(i * (each + gap)), x1 = x + Math.round(i * (each + gap) + each);
            int h = vals[i] <= 0 ? 1 : Math.max(2, Math.round(bh * vals[i] / (float) max));
            boolean today = i == n - 1;
            g.fill(x0, y + bh - h, Math.max(x0 + 1, x1), y + bh, today ? color : alpha(color, vals[i] <= 0 ? 50 : 130));
        }
    }

    private static int mixColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gg = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (gg << 8) | bl;
    }

    /** Letter-spaced bold text (tabs). */
    private int spacedW(String s, float sc) {
        int t = 0;
        for (char ch : s.toCharArray()) t += wb(String.valueOf(ch), sc) + 1;
        return Math.max(0, t - 1);
    }

    private void spacedText(GuiGraphicsExtractor g, String s, int x, int y, int color, float sc) {
        for (char ch : s.toCharArray()) {
            String c = String.valueOf(ch);
            text(g, c, x, y, color, sc, true);
            x += wb(c, sc) + 1;
        }
    }

    /** Paint over Minecraft's own button underneath, so its white edge never peeks out at rounded corners. */
    private void cover(GuiGraphicsExtractor g, Button b) { cover(g, b, FRAME); }

    private void cover(GuiGraphicsExtractor g, Button b, int color) {
        g.fill(b.getX(), b.getY(), b.getX() + b.getWidth(), b.getY() + b.getHeight(), color);
    }

    /** Rounded bar drawn behind a row of pills. */
    private void groupBar(GuiGraphicsExtractor g, List<Button> btns) {
        if (btns.isEmpty()) return;
        Button a = btns.get(0), z = btns.get(btns.size() - 1);
        box(g, a.getX() - 2, a.getY() - 2, z.getX() + z.getWidth() - a.getX() + 4, a.getHeight() + 4, PANEL, BORDER);
    }

    private void spark(GuiGraphicsExtractor g, long[] vals, int x, int y, int sw, int sh, int color) {
        if (vals.length < 2) {
            g.fill(x, y + sh - 1, x + sw, y + sh, alpha(color, 60));
            return;
        }
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (long v : vals) { min = Math.min(min, v); max = Math.max(max, v); }
        if (max == min) { max++; min--; }
        int last = -1;
        for (int px = 0; px < sw; px += 2) {
            double t = (double) px * (vals.length - 1) / Math.max(1, sw - 1);
            int i0 = (int) t, i1 = Math.min(vals.length - 1, i0 + 1);
            double v = vals[i0] + (vals[i1] - vals[i0]) * (t - i0);
            int yy = y + (int) Math.round((max - v) / (double) (max - min) * (sh - 2)) + 1;
            g.fill(x + px, yy, x + px + 2, y + sh, alpha(color, 34));
            int from = last < 0 ? yy : last;
            g.fill(x + px, Math.min(yy, from), x + px + 2, Math.max(yy, from) + 1, color);
            last = yy;
        }
    }

    private static String clock(long ts) {
        return java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US)).toLowerCase(Locale.US);
    }

    private static String day(long ts) {
        return java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.US));
    }

    private static String dayTime(long ts) {
        return java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US));
    }

    private static String signedMoney(long v) {
        return (v > 0 ? "+" : v < 0 ? "-" : "") + Money.format(Math.abs(v));
    }

    private String rangeWord() {
        return switch (range) { case 0 -> "this session"; case 1 -> "today"; case 2 -> "this week"; case 3 -> "this month"; default -> "all time"; };
    }

    private void face(GuiGraphicsExtractor g, String name, int x, int y, int size) {
        if (Settings.S.skinFaces) try {
            var conn = minecraft == null ? null : minecraft.getConnection();
            var info = conn == null ? null : conn.getPlayerInfo(name);
            if (info != null) {
                PlayerFaceExtractor.extractRenderState(g, info.getSkin(), x, y, size);
                return;
            }
        } catch (Exception ignored) { }
        // not online: purple circle-ish badge with the first letter, like the picture
        g.fill(x + 1, y, x + size - 1, y + size, PURPLE);
        g.fill(x, y + 1, x + size, y + size - 1, PURPLE);
        String l = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.US);
        float sc = size / 12f;
        int ty = y + Math.round((size - Fonts.capHeight(sc)) / 2f - Fonts.capTop(sc));   // letter centered top-to-bottom
        text(g, l, x + (size - wb(l, sc)) / 2, ty, TEXT, sc, true);
    }

    /** Payments in the current range, oldest first. */
    private List<PaymentLedger.Payment> rangePayments() {
        long since = since(), now = now();
        List<PaymentLedger.Payment> out = new ArrayList<>();
        for (PaymentLedger.Payment p : PaymentLedger.paymentsSince(since)) if (p.ts <= now) out.add(p);
        return out;
    }

    /** Your net per player in the current range (+ = you won from them). */
    private Map<String, long[]> perPlayer(List<PaymentLedger.Payment> pays) {
        Map<String, long[]> m = new LinkedHashMap<>();   // name -> {net, paidYou, youPaid, count}
        for (PaymentLedger.Payment p : pays) {
            long[] a = m.computeIfAbsent(p.name, k -> new long[4]);
            a[0] += p.paidYou - p.youPaid;
            a[1] += p.paidYou;
            a[2] += p.youPaid;
            a[3]++;
        }
        return m;
    }

    // ---------------- render ----------------
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // nothing drawn outside the window: your game shows normally around it
        round(g, ox, oy, ow, oh, 6, (0xC0 << 24) | (BORDER & 0xFFFFFF));
        round(g, ox + 1, oy + 1, ow - 2, oh - 2, 5, (0xE8 << 24) | (FRAME & 0xFFFFFF));
        forest(g, fx, fy + 27, fw - 1, fh - 28);
        g.fill(ox + 1, oy + 1, ox + SB, oy + oh - 1, (0xF0 << 24) | (mixColor(FRAME, 0xFF000000, 0.35f) & 0xFFFFFF));   // sidebar
        g.fill(ox + SB, oy + 1, ox + SB + 1, oy + oh - 1, BORDER);
    }

    private int lastMouseX, lastMouseY;

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        applyPalette();
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (pendingFocus instanceof EditBox eb) { setFocused(eb); pendingFocus = null; }
        super.extractRenderState(g, mouseX, mouseY, delta);
        drawSidebar(g, mouseX, mouseY);
        drawHeader(g, mouseX, mouseY);
        switch (tab) {
            case 6 -> drawPayments(g, mouseX, mouseY);
            case 7 -> drawGambling(g);
            case 0 -> drawOverview(g, mouseX, mouseY);
            case 1 -> { if (wlPlayer != null) drawPlayerPage(g, mouseX, mouseY); else drawWinLoss(g, mouseX, mouseY); }
            case 2 -> drawRakeback(g);
            case 3 -> drawSessions(g, mouseX, mouseY);
            case 4 -> drawAuctions(g, mouseX, mouseY);
            default -> drawPanels(g, mouseX, mouseY);
        }
        text(g, "Display only. Data is read from your chat, nothing is sent to the server.", fx + 10, fy + fh - 11, DIM, 0.62f, false);
    }

    private void drawHeader(GuiGraphicsExtractor g, int mx, int my) {
        int hy = fy + 10;
        g.fill(fx, fy + 1, fx + fw - 1, fy + 27, (0xF0 << 24) | (mixColor(FRAME, 0xFF000000, 0.2f) & 0xFFFFFF));
        // page title with the ring logo, then PROFILE 1
        String title = "DASHBOARD";
        for (int i = 0; i < SIDE_TABS.length; i++) if (SIDE_TABS[i] == tab) title = PAGE_TITLES[i];
        if (tab == 2) title = "RAKEBACK";
        if (tab == 3) title = "SESSIONS";
        ring(g, fx + 14, hy + 4, 5, 2, 1f, PURPLE, PURPLE);
        int x = fx + 24 + caps(g, title, fx + 24, hy + 1, TEXT) + 10;
        String prof = "PROFILE 1";
        int pw = capsW(prof) + 22;
        box(g, x, fy + 7, pw, 16, mixColor(PANEL, PURPLE, 0.25f), PURPLE);
        caps(g, prof, x + 8, fy + 11, PURPLE_LIGHT);
        int ax = x + pw - 10, ay = fy + 14;   // little down arrow
        g.fill(ax, ay, ax + 5, ay + 1, PURPLE_LIGHT); g.fill(ax + 1, ay + 1, ax + 4, ay + 2, PURPLE_LIGHT); g.fill(ax + 2, ay + 2, ax + 3, ay + 3, PURPLE_LIGHT);
        groupBar(g, rangeButtons);
        for (int i = 0; i < rangeButtons.size(); i++) {
            Button b = rangeButtons.get(i);
            boolean on = i == range;
            cover(g, b, PANEL);
            round(g, b.getX(), b.getY(), b.getWidth(), b.getHeight(), 3,
                    on ? PURPLE : over(b, mx, my) ? mixColor(PANEL, PURPLE, 0.45f) : mixColor(PANEL, PURPLE, 0.25f));
            String s = b.getMessage().getString();
            text(g, s, b.getX() + (b.getWidth() - wb(s, 0.68f)) / 2, b.getY() + (b.getHeight() - 7) / 2, on ? onAccent() : MUTED, 0.68f, true);
        }
        if (resetBtn != null) {   // RESET SESSION
            boolean armed = resetArmedUntil > System.currentTimeMillis(), hot = over(resetBtn, mx, my);
            cover(g, resetBtn);
            box(g, resetBtn.getX(), resetBtn.getY(), resetBtn.getWidth(), resetBtn.getHeight(), armed ? alpha(RED, 70) : hot ? alpha(RED, 30) : PANEL, RED);
            String s = armed ? "CLICK AGAIN" : "RESET SESSION";
            int tx0 = resetBtn.getX() + (resetBtn.getWidth() - wb(s, 0.7f)) / 2 + 4;
            text(g, "\u21BB", tx0 - 10, resetBtn.getY() + 4, RED, 0.7f, true);
            text(g, s, tx0, resetBtn.getY() + (resetBtn.getHeight() - 7) / 2, RED, 0.7f, true);
        }
        g.fill(fx, fy + 26, fx + fw - 1, fy + 27, BORDER);
    }

    /** Little pixel donut logo: golden dough, pink frosting, sprinkles, a hole in the middle. */
    private static void donut(GuiGraphicsExtractor g, int cx, int cy) {
        final int DOUGH = 0xFFD99A4E, DOUGH_DARK = 0xFFB0763A, FROST = 0xFFF472B6, FROST_LIGHT = 0xFFF9A8D4;
        double R = 5.6, HOLE = 1.9;
        for (int dy = -6; dy <= 6; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                double d = Math.sqrt((dx + 0.5) * (dx + 0.5) + (dy + 0.5) * (dy + 0.5));
                if (d > R || d < HOLE) continue;
                int col;
                boolean frosting = d < R - 1.2 && (dy < 1 || (dy == 1 && (dx + 7) % 3 != 0));   // frosting with little drips
                if (frosting) col = (dx < -1 && dy < -1) ? FROST_LIGHT : FROST;
                else col = dy > 2 ? DOUGH_DARK : DOUGH;
                g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, col);
            }
        }
        // sprinkles on the frosting
        g.fill(cx - 3, cy - 4, cx - 2, cy - 3, 0xFFFFFFFF);
        g.fill(cx + 2, cy - 4, cx + 3, cy - 3, 0xFFFDE047);
        g.fill(cx + 3, cy - 1, cx + 4, cy, 0xFF60A5FA);
        g.fill(cx - 4, cy - 1, cx - 3, cy, 0xFF4ADE80);
        g.fill(cx, cy - 5, cx + 1, cy - 4, 0xFF60A5FA);
    }

    /** Sharp purple donut: thick ring, clear hole, light top-left, shaded bottom-right. No soft pixels. */
    private static void donutRing(GuiGraphicsExtractor g, int cx, int cy) {
        final int MID = PURPLE, LIGHT = PURPLE_LIGHT;
        final int DARK = 0xFF000000 | ((((MID >> 16) & 0xFF) * 6 / 10) << 16) | ((((MID >> 8) & 0xFF) * 6 / 10) << 8) | ((MID & 0xFF) * 6 / 10);
        double R = 8.0, HOLE = 3.2;
        for (int dy = -9; dy <= 9; dy++) {
            for (int dx = -9; dx <= 9; dx++) {
                double px = dx + 0.5, py = dy + 0.5;
                double d = Math.sqrt(px * px + py * py);
                if (d > R || d < HOLE) continue;
                double light = -(px + py) / Math.max(0.001, d);           // + toward the top-left
                double inner = (d - HOLE) / (R - HOLE);                   // 0 at the hole, 1 at the edge
                int col = light > 0.55 && inner > 0.25 ? LIGHT : light < -0.45 ? DARK : MID;
                g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, col);
            }
        }
    }

    /** Text with a soft glow around the letters. */
    private void glowText(GuiGraphicsExtractor g, String s, int x, int y, int color, float scale) {
        // soft light BEHIND the name; the letters themselves stay sharp
        int tw = wb(s, scale), th = Math.round(9 * scale);
        softGlow(g, x + tw / 2, y + th / 2, tw / 2 + 10, th / 2 + 6, PURPLE, 40);
        text(g, s, x, y, color, scale, true);
    }

    /** Soft round glow: stacked rounded shapes, very faint each. */
    private static void softGlow(GuiGraphicsExtractor g, int cx, int cy, int rw, int rh, int color, int strength) {
        int layers = 8, per = Math.max(1, strength / layers);
        for (int i = layers; i >= 1; i--) {
            int w = rw * i / layers, h = rh * i / layers;
            round(g, cx - w, cy - h, w * 2, h * 2, Math.min(Math.min(w, h), 12), alpha(color, per));
        }
    }

    /** Left sidebar: logo on top, pages, Settings at the bottom. */
    private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
        donutRing(g, ox + 16, oy + 15);
        caps(g, "SNOWY'S HUB", ox + 28, oy + 12, TEXT);
        for (int i = 0; i < tabButtons.size(); i++) sideItem(g, tabButtons.get(i), SIDE_TABS[i] == tab, i, mx, my);
        if (sideSettings != null) sideItem(g, sideSettings, false, 6, mx, my);
    }

    private void sideItem(GuiGraphicsExtractor g, Button b, boolean on, int icon, int mx, int my) {
        int x = b.getX(), y = b.getY(), w0 = b.getWidth(), h = b.getHeight();
        boolean hot = over(b, mx, my);
        if (on) {
            round(g, x, y, w0, h, 3, alpha(PURPLE, 60));
            g.fill(x - 6, y + 3, x - 4, y + h - 3, PURPLE);
        } else if (hot) round(g, x, y, w0, h, 3, alpha(PURPLE, 26));
        int c = on ? PURPLE_LIGHT : hot ? TEXT : MUTED;
        sideIcon(g, icon, x + 6, y + (h - 7) / 2, c);
        text(g, b.getMessage().getString(), x + 19, y + (h - 7) / 2, on ? TEXT : c, 0.74f, on);
    }

    /** Little 7x7 icons for the sidebar. */
    private static void sideIcon(GuiGraphicsExtractor g, int i, int x, int y, int c) {
        switch (i) {
            case 0 -> { g.fill(x, y, x + 3, y + 3, c); g.fill(x + 4, y, x + 7, y + 3, c); g.fill(x, y + 4, x + 3, y + 7, c); g.fill(x + 4, y + 4, x + 7, y + 7, c); }   // grid
            case 1 -> { g.fill(x + 2, y, x + 5, y + 3, c); g.fill(x, y + 4, x + 7, y + 7, c); }                                                                // person
            case 2 -> { g.fill(x, y + 1, x + 7, y + 6, c); g.fill(x + 1, y + 2, x + 6, y + 3, 0xFF000000 | 0x221E33); }                                       // card
            case 3 -> { g.fill(x + 1, y, x + 6, y + 2, c); g.fill(x + 3, y + 2, x + 4, y + 6, c); g.fill(x, y + 6, x + 7, y + 7, c); }                        // gavel
            case 4 -> { g.fill(x, y, x + 7, y + 7, c); g.fill(x + 1, y + 1, x + 2, y + 2, 0xFF0D0B15); g.fill(x + 5, y + 5, x + 6, y + 6, 0xFF0D0B15); g.fill(x + 3, y + 3, x + 4, y + 4, 0xFF0D0B15); }   // dice
            case 5 -> { g.fill(x, y + 1, x + 7, y + 2, c); g.fill(x + 1, y + 3, x + 6, y + 4, c); g.fill(x + 2, y + 5, x + 5, y + 6, c); }                   // layers
            default -> { g.fill(x + 2, y, x + 5, y + 7, c); g.fill(x, y + 2, x + 7, y + 5, c); g.fill(x + 3, y + 3, x + 4, y + 4, 0xFF0D0B15); }             // gear
        }
    }

    /** Dark purple sky, a few stars, and two rows of pine trees along the bottom. */
    private void forest(GuiGraphicsExtractor g, int x, int y, int w0, int h) {
        for (int yy = 0; yy < h; yy += 6) {
            float t = yy / (float) Math.max(1, h);
            g.fill(x, y + yy, x + w0, y + Math.min(h, yy + 6), alpha(mixColor(mixColor(FRAME, PURPLE, 0.10f), FRAME, t), 0xFF));
        }
        for (int i = 0; i < 40; i++) {   // stars
            int sx = x + Math.abs((i * 73856093) % Math.max(1, w0)), sy = y + Math.abs((i * 19349663) % Math.max(1, h * 2 / 3));
            g.fill(sx, sy, sx + 1, sy + 1, alpha(0xFFFFFFFF, 40 + (i % 3) * 25));
        }
        pines(g, x, y + h, w0, h * 55 / 100, mixColor(FRAME, PURPLE, 0.16f), 23, 0);
        pines(g, x, y + h, w0, h * 40 / 100, mixColor(FRAME, 0xFF000000, 0.35f), 31, 17);
    }

    private static void pines(GuiGraphicsExtractor g, int x, int base, int w0, int maxH, int color, int seed, int shift) {
        int n = Math.max(6, w0 / 40);
        for (int i = 0; i < n; i++) {
            int r = Math.abs((i + seed) * 2654435 % 1000);
            int cx = x + shift + i * w0 / n + (r % 20) - 10;
            int th = maxH * (55 + r % 45) / 100, half = th / 4 + 4;
            for (int k = 0; k < th; k += 2) {   // triangle, row by row
                int hw = half * k / Math.max(1, th);
                g.fill(Math.max(x, cx - hw), base - th + k, Math.min(x + w0, cx + hw + 1), base - th + k + 2, color);
            }
        }
    }

    /** Area chart for things that aren't around zero (like your balance). */
    private void areaChart(GuiGraphicsExtractor g, long[] vals, int x, int y, int w0, int h, int color) {
        if (vals.length < 2) { text(g, "Type /bal to start your balance chart", x + 4, y + h / 2 - 4, DIM, 0.74f, false); return; }
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (long v : vals) { min = Math.min(min, v); max = Math.max(max, v); }
        if (max == min) { max++; min--; }
        int prev = Integer.MIN_VALUE;
        for (int px = 0; px < w0; px++) {
            double t = (double) px * (vals.length - 1) / Math.max(1, w0 - 1);
            int i0 = (int) t, i1 = Math.min(vals.length - 1, i0 + 1);
            double v = vals[i0] + (vals[i1] - vals[i0]) * (t - i0);
            int yy = y + (int) Math.round((max - v) / (double) (max - min) * (h - 2));
            g.fill(x + px, yy + 2, x + px + 1, y + h, alpha(color, 44));
            int a = prev == Integer.MIN_VALUE ? yy : prev;
            g.fill(x + px, Math.min(a, yy), x + px + 1, Math.max(a, yy) + 2, color);
            prev = yy;
        }
    }

    // ================= PAYMENTS =================
    private void drawPayments(GuiGraphicsExtractor g, int mx, int my) {
        int y = pageTitle(g, "Payments", "Every payment you sent or received.");
        List<PaymentLedger.Payment> pays = rangePayments();
        long recv = 0, sent = 0; int nIn = 0;
        for (PaymentLedger.Payment p : pays) { recv += p.paidYou; sent += p.youPaid; if (p.paidYou > 0) nIn++; }
        long net = recv - sent;
        int gap = 8, cw = (fw - 20 - gap * 3) / 4, ch = 58, x0 = fx + 10;
        statCard(g, x0, y, cw, ch, "RECEIVED", Money.format(recv), nIn + (nIn == 1 ? " payment" : " payments"), GREEN, GREEN);
        statCard(g, x0 + (cw + gap), y, cw, ch, "SENT", "-" + Money.format(sent), (pays.size() - nIn) + " payments", RED, RED);
        statCard(g, x0 + (cw + gap) * 2, y, cw, ch, "NET", signedMoney(net), "received minus sent", net >= 0 ? GREEN : RED, net >= 0 ? GREEN : RED);
        statCard(g, x0 + (cw + gap) * 3, y, cw, ch, "TRANSACTIONS", String.valueOf(pays.size()),
                pays.isEmpty() ? "none yet" : "avg " + Money.format((recv + sent) / pays.size()) + " each", PURPLE, TEXT);

        int py = y + ch + 10, ph = contentBottom() - py, pw = fw - 20, px = x0;
        box(g, px, py, pw, ph, PANEL, BORDER);
        int capW = caps(g, "HISTORY", px + 12, py + 10, TEXT);
        text(g, rangeWord(), px + 20 + capW, py + 10, DIM, 0.62f, false);
        groupBar(g, filterButtons);
        for (int i = 0; i < filterButtons.size(); i++) pill(g, filterButtons.get(i), i == filter, mx, my);
        if (searchBox != null) searchField(g, filterButtons.get(0).getX() - 8 - 150, py + 7, 150, 20, searchBox, "Search player");

        int c1 = px + 12, cType = px + pw * 55 / 100, cAmt = px + pw * 76 / 100, cWhen = px + pw - 14;
        int hy = py + 34;
        caps(g, "PLAYER", c1, hy, DIM);
        caps(g, "TYPE", cType, hy, DIM);
        caps(g, "AMOUNT", cAmt, hy, DIM);
        caps(g, "WHEN", cWhen - capsW("WHEN"), hy, DIM);
        g.fill(px + 1, hy + 13, px + pw - 1, hy + 14, BORDER);

        String q = search.trim().toLowerCase(Locale.US);
        List<PaymentLedger.Payment> rows = new ArrayList<>();
        for (int i = pays.size() - 1; i >= 0; i--) {
            PaymentLedger.Payment p = pays.get(i);
            boolean in = p.paidYou > 0;
            if (filter == 1 && !in || filter == 2 && in) continue;
            if (!q.isEmpty() && !p.name.toLowerCase(Locale.US).contains(q)) continue;
            rows.add(p);
        }
        int rowH = 21, ry = hy + 19, visible = Math.max(1, (py + ph - 22 - ry) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        for (int i = 0; i < visible && scroll + i < rows.size(); i++) {
            PaymentLedger.Payment p = rows.get(scroll + i);
            boolean in = p.paidYou > 0;
            int ty = ry + i * rowH;
            if (i > 0) g.fill(px + 8, ty - 4, px + pw - 10, ty - 3, alpha(BORDER, 200));
            face(g, p.name, c1, ty - 1, 11);
            text(g, p.name, c1 + 16, ty, TEXT, 0.76f, true);
            String badge = in ? "RECEIVED" : "SENT";
            int bwid = capsW(badge) + 10, bc = in ? GREEN : RED;
            box(g, cType, ty - 2, bwid, 12, alpha(bc, 40), bc);
            caps(g, badge, cType + 5, ty, bc);
            String amt = in ? "+" + Money.format(p.paidYou) : "-" + Money.format(p.youPaid);
            text(g, amt, cAmt, ty, bc, 0.76f, true);
            String when = ago(p.ts);
            text(g, when, cWhen - w(when, 0.7f), ty, DIM, 0.7f, false);
        }
        if (rows.isEmpty()) text(g, "No payments in this range", c1, ry, DIM, 0.76f, false);
        scrollbar(g, px + pw - 5, ry - 3, py + ph - 22 - ry, visible, rows.size());
        text(g, "Showing " + Math.min(visible, rows.size()) + " of " + rows.size(), px + 12, py + ph - 14, DIM, 0.62f, false);
    }

    /** Card with a colored top line, label, value and a small line under it. */
    private void statCard(GuiGraphicsExtractor g, int x, int y, int cw, int ch, String label, String value, String sub, int line, int valueColor) {
        box(g, x, y, cw, ch, PANEL, BORDER);
        g.fill(x + 2, y, x + cw - 2, y + 2, line);
        caps(g, label, x + 10, y + 9, MUTED);
        text(g, value, x + 10, y + 21, valueColor, 1.25f, true);
        text(g, sub, x + 10, y + 41, DIM, 0.62f, false);
    }

    // ================= GAMBLING (next stage) =================
    private void drawGambling(GuiGraphicsExtractor g) {
        int y = pageTitle(g, "Gambling", "Set up the games your overlay shows.");
        box(g, fx + 10, y, fw - 20, 60, PANEL, BORDER);
        caps(g, "GAMES SHEET", fx + 22, y + 12, TEXT);
        text(g, "The games sheet and My Games library come in the next update.", fx + 22, y + 30, DIM, 0.74f, false);
    }

    /** Settings gear button. */
    private void gear(GuiGraphicsExtractor g, Button b, int mx, int my) {
        boolean hot = over(b, mx, my);
        cover(g, b);
        box(g, b.getX() - 2, b.getY() - 2, b.getWidth() + 4, b.getHeight() + 4, hot ? 0xFF1E1A2E : PANEL, hot ? PURPLE : BORDER);
        int cx = b.getX() + b.getWidth() / 2, cy = b.getY() + b.getHeight() / 2, c = hot ? TEXT : MUTED;
        for (int k = 0; k < 8; k++) {   // teeth
            double a = k * Math.PI / 4;
            int tx = cx + (int) Math.round(Math.cos(a) * 5), ty = cy + (int) Math.round(Math.sin(a) * 5);
            g.fill(tx - 1, ty - 1, tx + 1, ty + 1, c);
        }
        for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {   // ring
            int d2 = dx * dx + dy * dy;
            if (d2 <= 16 && d2 >= 5) g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, c);
        }
    }

    // ================= OVERVIEW =================
    private void drawOverview(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String me = minecraft != null ? minecraft.getUser().getName() : "you";
        int wy = welcomeY();

        // welcome: your full skin, standing still and facing straight ahead (no item, no cape)
        if (minecraft != null && minecraft.player != null && Settings.S.skinFaces) {
            int bx1 = fx + 8, by1 = wy - 2, bx2 = fx + 52, by2 = wy + 52;
            InventoryScreen.extractEntityInInventoryFollowsMouse(g, bx1, by1, bx2, by2, 24, 0.0625f,
                    (bx1 + bx2) / 2f, (by1 + by2) / 2f, preview());
        } else {
            face(g, me, fx + 12, wy + 2, 30);
        }
        int nx = fx + 60;
        text(g, "Welcome, ", nx, wy + 4, TEXT, 1.35f, true);
        glowText(g, me, nx + wb("Welcome, ", 1.35f), wy + 4, PURPLE_LIGHT, 1.35f);
        text(g, "Have a great time on DonutSMP!", nx, wy + 22, MUTED, 0.66f, false);

        List<PaymentLedger.Payment> pays = rangePayments();
        long net = 0, volume = 0;
        long[] netSeries = new long[pays.size() + 1];
        for (int i = 0; i < pays.size(); i++) {
            net += pays.get(i).paidYou - pays.get(i).youPaid;
            volume += pays.get(i).paidYou;
            netSeries[i + 1] = net;
        }

        // ---- five cards ----
        int cy = cardsY(), ch = cardsH(), gap = 6, cw = (fw - 20 - gap * 4) / 5;
        int[] cx = new int[5];
        for (int i = 0; i < 5; i++) cx[i] = fx + 10 + i * (cw + gap);
        List<long[]> bal = PaymentLedger.balanceSince(since());
        long[] balVals = new long[bal.size()];
        for (int i = 0; i < bal.size(); i++) balVals[i] = bal.get(i)[1];
        long bv = PaymentLedger.balance();
        long balChange = balVals.length > 1 ? balVals[balVals.length - 1] - balVals[0] : 0;
        String balSub = bv < 0 ? "type /bal to load" : (balChange < 0 ? "\u25BC " : "\u25B2 ") + Money.format(Math.abs(balChange)) + " " + rangeWord();
        int balSubCol = balChange < 0 ? RED : GREEN;
        miniCard(g, cx[0], cy, cw, ch, "NET WORTH", bv < 0 ? "$---" : Money.format(bv), balSub, balSubCol, TEXT, PURPLE, balVals);
        miniCard(g, cx[1], cy, cw, ch, "BALANCE", bv < 0 ? "$---" : Money.format(bv), balSub, balSubCol, TEXT, GOLD, balVals);
        int days = switch (range) { case 0, 1 -> 1; case 2 -> 7; default -> 30; };
        long[] play = PaymentLedger.playLastDays(Math.max(7, days));
        long inRange = 0;
        for (int i = Math.max(0, play.length - days); i < play.length; i++) inRange += play[i];
        String playVal = (PaymentLedger.playTotal() / 3_600_000L) + "h";
        miniCard(g, cx[2], cy, cw, ch, "PLAYTIME", playVal, "+" + (inRange / 3_600_000L) + "h " + (inRange % 3_600_000L / 60_000L) + "m " + rangeWord(),
                GREEN, TEXT, BLUE, play);
        int pc = net >= 0 ? GREEN : RED;
        miniCard(g, cx[3], cy, cw, ch, RANGE_LABELS[range] + " PROFIT", signedMoney(net),
                pays.isEmpty() ? "no payments yet" : "since " + day(pays.get(0).ts), DIM, pc, pc, netSeries);
        miniCard(g, cx[4], cy, cw, ch, "VOLUME", Money.format(volume), "paid to you " + rangeWord(), DIM, TEXT, 0xFF3ADFDF, null);

        // ---- net worth breakdown + feed (left) ----
        int[] fb = feedBox();
        box(g, fx + 10, fb[1], fb[2], fb[3], PANEL, BORDER);
        caps(g, "NET WORTH BREAKDOWN", fb[0] + 10, fb[1] + 10, TEXT);
        groupBar(g, filterButtons);
        for (int i = 0; i < filterButtons.size(); i++) pill(g, filterButtons.get(i), i == filter, mouseX, mouseY);
        int by = fb[1] + 30;
        round(g, fb[0] + 10, by, 4, 6, 1, GOLD);
        text(g, "Balance", fb[0] + 18, by - 1, MUTED, 0.66f, false);
        text(g, "100%", fb[0] + fb[2] * 55 / 100, by - 1, DIM, 0.66f, false);
        String bs = bv < 0 ? "$---" : Money.format(bv);
        text(g, bs, fb[0] + fb[2] - 10 - wb(bs, 0.66f), by - 1, TEXT, 0.66f, true);
        g.fill(fb[0] + 10, by + 9, fb[0] + fb[2] - 10, by + 11, GOLD);
        text(g, "Total", fb[0] + 10, by + 15, MUTED, 0.66f, false);
        text(g, bs, fb[0] + fb[2] - 10 - wb(bs, 0.66f), by + 15, TEXT, 0.66f, true);
        g.fill(fb[0] + 8, by + 25, fb[0] + fb[2] - 8, by + 26, BORDER);

        List<PaymentLedger.Payment> feed = new ArrayList<>();
        for (int i = pays.size() - 1; i >= 0; i--) {
            PaymentLedger.Payment p = pays.get(i);
            boolean in = p.paidYou > 0;
            if (filter == 0 || (filter == 1 && in) || (filter == 2 && !in)) feed.add(p);
        }
        int rowH = 20, ry = by + 31, rows = Math.max(0, (fb[1] + fb[3] - 4 - ry) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, feed.size() - rows)));
        for (int i = 0; i < Math.min(rows, feed.size() - scroll); i++) {
            PaymentLedger.Payment p = feed.get(scroll + i);
            boolean in = p.paidYou > 0;
            int y = ry + i * rowH;
            if (i > 0) g.fill(fb[0] + 8, y - 3, fb[0] + fb[2] - 8, y - 2, alpha(BORDER, 200));
            text(g, clock(p.ts), fb[0] + 10, y + 3, DIM, 0.62f, false);
            int ix = fb[0] + 10 + w("00:00 pm", 0.62f) + 6;
            face(g, p.name, ix, y + 1, 11);
            text(g, p.name, ix + 15, y, TEXT, 0.7f, true);
            text(g, in ? "paid you" : "you paid", ix + 15, y + 9, DIM, 0.56f, false);
            String amt = signedMoney(in ? p.paidYou : -p.youPaid);
            text(g, amt, fb[0] + fb[2] - 10 - wb(amt, 0.72f), y + 3, in ? GREEN : RED, 0.72f, true);
        }
        if (feed.isEmpty()) text(g, "No payments in this range", fb[0] + 10, ry + 2, DIM, 0.7f, false);

        // ---- balance chart (right) ----
        int[] cb = chartBox();
        box(g, cb[0], cb[1], cb[2], cb[3], PANEL, BORDER);
        int capW = caps(g, "BALANCE", cb[0] + 10, cb[1] + 10, TEXT);
        text(g, rangeWord(), cb[0] + 18 + capW, cb[1] + 10, DIM, 0.62f, false);
        text(g, bs, cb[0] + cb[2] - 10 - wb(bs, 1.2f), cb[1] + 7, TEXT, 1.2f, true);
        areaChart(g, balVals, cb[0] + 10, cb[1] + 30, cb[2] - 20, cb[3] - 40, balChange < 0 ? RED : GREEN);

        // ---- top winners / losers ----
        int ty = contentBottom() - TOP_WL_H, tw = fw - 20, tx = fx + 10;
        box(g, tx, ty, tw, TOP_WL_H, PANEL, BORDER);
        int tcap = caps(g, "TOP WINNERS / LOSERS", tx + 10, ty + 9, TEXT);
        text(g, rangeWord(), tx + 18 + tcap, ty + 9, DIM, 0.62f, false);
        Map<String, long[]> per = perPlayer(pays);
        List<Map.Entry<String, long[]>> winners = new ArrayList<>(), losers = new ArrayList<>();
        for (var e : per.entrySet()) {
            long theirs = -e.getValue()[0];   // their side: + = they won from you
            if (theirs > 0) winners.add(e); else if (theirs < 0) losers.add(e);
        }
        winners.sort(Comparator.comparingLong(e -> e.getValue()[0]));             // most won from you first
        losers.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed());
        int half = (tw - 30) / 2;
        topList(g, "TOP WINNERS", winners, tx + 10, ty + 24, half, TOP_WL_H - 30, GREEN, "No winners in this time frame yet");
        topList(g, "TOP LOSERS", losers, tx + 20 + half, ty + 24, half, TOP_WL_H - 30, RED, "No losers in this time frame yet");
    }

    /** Compact card like the reference: label, value, small line, thin chart at the bottom. */
    private void miniCard(GuiGraphicsExtractor g, int x, int y, int cw, int ch, String label, String value, String sub,
                          int subColor, int valueColor, int lineColor, long[] series) {
        box(g, x, y, cw, ch, PANEL, BORDER);
        caps(g, label, x + 8, y + 7, MUTED);
        float vs = 1.2f;
        while (vs > 0.7f && wb(value, vs) > cw - 16) vs -= 0.05f;
        text(g, value, x + 8, y + 18, valueColor, vs, true);
        String sb = sub;
        while (sb.length() > 4 && w(sb, 0.56f) > cw - 14) sb = sb.substring(0, sb.length() - 2) + ".";
        text(g, sb, x + 8, y + 34, subColor, 0.56f, false);
        if (series != null) smoothLine(g, series, x + 6, y + ch - 16, cw - 12, 12, lineColor);
        else g.fill(x + 6, y + ch - 6, x + cw - 6, y + ch - 4, lineColor);
    }

    private void topList(GuiGraphicsExtractor g, String title, List<Map.Entry<String, long[]>> list, int x, int y, int w0, int h, int color, String empty) {
        caps(g, title, x, y, color);
        int rowH = 14, ry = y + 13, rows = Math.max(0, (y + h - ry) / rowH);
        long max = 1;
        for (var e : list) max = Math.max(max, Math.abs(e.getValue()[0]));
        for (int i = 0; i < Math.min(rows, list.size()); i++) {
            var e = list.get(i);
            long theirs = -e.getValue()[0];
            int ty = ry + i * rowH;
            text(g, String.valueOf(i + 1), x, ty + 1, DIM, 0.6f, false);
            face(g, e.getKey(), x + 10, ty, 9);
            text(g, e.getKey(), x + 23, ty + 1, TEXT, 0.64f, true);
            String amt = signedMoney(theirs);
            text(g, amt, x + w0 - wb(amt, 0.64f), ty + 1, color, 0.64f, true);
            int barX = x + 23, barW = (int) ((w0 * 45 / 100) * (Math.abs(theirs) / (double) max));
            g.fill(barX, ty + 10, barX + Math.max(1, barW), ty + 11, alpha(color, 160));
        }
        if (list.isEmpty()) text(g, empty, x, ry + 1, DIM, 0.62f, false);
    }

    private static int pinnedAt = -1;   // screen x of a pinned chart point (-1 = none)

    /** Hover (or click to pin) the chart: a line, a dot on the graph, and a box with the total, time and payment. */
    private void chartTooltip(GuiGraphicsExtractor g, long[] vals, List<PaymentLedger.Payment> pays,
                              int x, int y, int cw, int ch, int mx, int my, int[] panel) {
        if (vals.length < 2) return;
        boolean hovering = mx >= x && mx < x + cw && my >= y - 4 && my < y + ch + 4;
        int px;
        if (hovering) px = mx;
        else if (pinnedAt >= x && pinnedAt < x + cw) px = pinnedAt;
        else return;

        long min = 0, max = 0;
        for (long v : vals) { min = Math.min(min, v); max = Math.max(max, v); }
        long pad = Math.max(1, (max - min) / 12);
        long hi = max + pad, lo = min - pad;
        double t = (double) (px - x) * (vals.length - 1) / Math.max(1, cw - 1);
        int k = (int) Math.round(t);
        k = Math.max(0, Math.min(vals.length - 1, k));
        int dotX = x + (int) Math.round(k * (cw - 1) / (double) Math.max(1, vals.length - 1));
        int dotY = y + (int) Math.round((hi - vals[k]) / (double) (hi - lo) * (ch - 1));
        int col = vals[k] >= 0 ? GREEN : RED;

        for (int yy = y; yy < y + ch; yy += 4) g.fill(dotX, yy, dotX + 1, Math.min(y + ch, yy + 2), alpha(MUTED, 120));   // guide line
        round(g, dotX - 3, dotY - 3, 7, 7, 3, col);
        round(g, dotX - 1, dotY - 1, 3, 3, 1, FRAME);

        // info box
        String total = signedMoney(vals[k]);
        String when, what;
        if (k == 0) { when = "start of range"; what = "before the first payment"; }
        else {
            PaymentLedger.Payment p = pays.get(k - 1);
            when = day(p.ts) + ", " + clock(p.ts);
            what = p.paidYou > 0 ? p.name + " paid you " + Money.format(p.paidYou) : "you paid " + p.name + " " + Money.format(p.youPaid);
        }
        int bw = Math.max(wb(total, 0.9f), Math.max(w(when, 0.62f), w(what, 0.62f))) + 16, bh = 40;
        int bx = dotX + 10, by = dotY - bh - 8;
        if (bx + bw > panel[0] + panel[2] - 6) bx = dotX - 10 - bw;   // flip left near the right edge
        if (by < panel[1] + 4) by = dotY + 10;                          // flip below near the top
        box(g, bx, by, bw, bh, PANEL, col);
        text(g, total, bx + 8, by + 5, col, 0.9f, true);
        text(g, when, bx + 8, by + 18, DIM, 0.62f, false);
        text(g, what, bx + 8, by + 28, MUTED, 0.62f, false);
        if (!hovering) text(g, "pinned  -  click to unpin", bx + bw - 6 - w("pinned  -  click to unpin", 0.5f), by - 8, DIM, 0.5f, false);
    }

    private static String hms(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private void card(GuiGraphicsExtractor g, int x, int y, int cw, int ch, String label, String value, String sub,
                      int lineColor, int valueColor, int subColor, long[] series) {
        box(g, x, y, cw, ch, PANEL, BORDER);
        softGlow(g, x + cw * 60 / 100, y + ch / 2, cw / 3, ch / 2 - 4, lineColor, 14);
        g.fill(x + 2, y, x + cw - 2, y + 2, lineColor);                       // colored line on top
        caps(g, label, x + 10, y + 9, MUTED);
        text(g, value, x + 10, y + 28, valueColor, 1.5f, true);
        text(g, sub, x + 10, y + 54, subColor, 0.72f, false);
        if (series != null) smoothLine(g, series, x + 10, y + ch - 22, cw - 20, 16, lineColor);
    }

    private void chart(GuiGraphicsExtractor g, long[] vals, int x, int y, int cw, int ch) {
        if (vals.length < 2) {
            text(g, "No payments in this range yet", x + 4, y + ch / 2 - 4, DIM, 0.85f, false);
            return;
        }
        long min = 0, max = 0;
        for (long v : vals) { min = Math.min(min, v); max = Math.max(max, v); }
        long pad = Math.max(1, (max - min) / 12);
        final long hi = max + pad, lo = min - pad;
        final double span = (double) (hi - lo);
        double zyF = y + (double) hi / span * (ch - 1);
        int zy = (int) Math.round(zyF);
        for (int dx = 0; dx < cw; dx += 8) g.fill(x + dx, zy, x + Math.min(cw, dx + 4), zy + 1, 0xFF3A3358);

        // sharp line: one column at a time, solid 2 px, connected through drops
        int prevI = Integer.MIN_VALUE;
        double lastY = zyF;
        for (int px = 0; px < cw; px++) {
            double t = (double) px * (vals.length - 1) / Math.max(1, cw - 1);
            int i0 = (int) t, i1 = Math.min(vals.length - 1, i0 + 1);
            double v = vals[i0] + (vals[i1] - vals[i0]) * (t - i0);
            double fy = y + (hi - v) / span * (ch - 1);
            int yy = (int) Math.round(fy);
            int col = v >= 0 ? GREEN : RED;
            int cx0 = x + px;
            if (yy < zy) g.fill(cx0, yy + 1, cx0 + 1, zy, alpha(col, 38));        // even fill above zero
            else if (yy > zy) g.fill(cx0, zy + 1, cx0 + 1, yy, alpha(col, 38));   // even fill below zero
            int a = prevI == Integer.MIN_VALUE ? yy : prevI;
            g.fill(cx0, Math.min(a, yy), cx0 + 1, Math.max(a, yy) + 2, col);
            prevI = yy;
            lastY = fy;
        }
        int ex = x + cw - 1, ey = (int) Math.round(lastY);
        int ec = vals[vals.length - 1] >= 0 ? GREEN : RED;
        round(g, ex - 2, ey - 2, 5, 5, 2, ec);   // end dot in the line's own color
    }

    // ================= shared bits for the list pages =================
    private static final int PINK = 0xFFF472B6;

    private int pageTitle(GuiGraphicsExtractor g, String title, String sub) {
        int x = fx + 10, y = contentY() + 4;
        text(g, title, x, y, TEXT, 1.4f, true);
        text(g, sub, x, y + 14, MUTED, 0.72f, false);
        return y + 26;
    }

    /** Little stat box like "PLAYERS 175". Returns its width. */
    private int chip(GuiGraphicsExtractor g, int x, int y, String label, String value, int valueColor) {
        int cw = Math.max(capsW(label), wb(value, 0.95f)) + 22;
        box(g, x, y, cw, 36, PANEL, BORDER);
        caps(g, label, x + 11, y + 7, MUTED);
        text(g, value, x + 11, y + 20, valueColor, 0.95f, true);
        return cw;
    }

    private void scrollbar(GuiGraphicsExtractor g, int x, int y, int h, int visible, int total) {
        if (total <= visible) return;
        g.fill(x, y, x + 2, y + h, 0xFF1C1830);
        int bh = Math.max(12, h * visible / total);
        int by = y + (h - bh) * scroll / Math.max(1, total - visible);
        g.fill(x, by, x + 2, by + bh, 0xFF5F5B75);
    }

    private void drawSearchFrame(GuiGraphicsExtractor g, int y) {
        if (searchBox == null) return;
        int sbw = Math.min(170, fw / 4), sbx = fx + fw - 10 - sbw;
        searchField(g, sbx, y + 8, sbw, 20, searchBox, "Search players");
    }

    /** Clean search field: dark fill, purple outline when typing, magnifier, your text and a blinking cursor. */
    private void searchField(GuiGraphicsExtractor g, int x, int y, int bw, int bh, EditBox box, String hint) {
        boolean focused = box != null && box.isFocused();
        String value = box == null ? "" : box.getValue();
        int r = Math.min(6, bh / 2);
        round(g, x, y, bw, bh, r, focused ? PURPLE : BORDER);                 // thin border
        round(g, x + 1, y + 1, bw - 2, bh - 2, r - 1, mixColor(FRAME, PANEL, 0.6f));   // soft dark fill
        int lc = focused ? PURPLE_LIGHT : DIM, lx0 = x + 10, ly0 = y + bh / 2 - 1;
        ring(g, lx0, ly0, 3, 1, 1f, lc, lc);                                  // round lens
        g.fill(lx0 + 2, ly0 + 2, lx0 + 3, ly0 + 3, lc);                       // short handle
        g.fill(lx0 + 3, ly0 + 3, lx0 + 4, ly0 + 4, lc);
        int tx = x + 19, ty = y + (bh - 7) / 2, maxW = bw - 26;
        if (value.isEmpty()) {
            text(g, hint, tx, ty, DIM, 0.66f, false);
        } else {
            String shown = value;
            while (shown.length() > 1 && w(shown, 0.66f) > maxW) shown = shown.substring(1);   // keep the end visible
            text(g, shown, tx, ty, TEXT, 0.66f, false);
            tx += w(shown, 0.66f) + 1;
        }
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) g.fill(tx, ty - 1, tx + 1, ty + 8, TEXT);
    }

    // ================= WIN / LOSS =================
    private void drawWinLoss(GuiGraphicsExtractor g, int mx, int my) {
        List<PaymentLedger.Entry> all = winLossList();   // everyone, all time, won most off you first
        int up = 0, down = 0;
        long upSum = 0, downSum = 0;
        for (PaymentLedger.Entry e : all) {
            long n = e.net();
            if (n > 0) { up++; upSum += n; } else if (n < 0) { down++; downSum -= n; }
        }

        int y = pageTitle(g, "Win / Loss",
                "Everyone who has gambled with you. Top = won the most off you, bottom = lost the most.");
        int x = fx + 10;
        x += chip(g, x, y, "PLAYERS", String.valueOf(all.size()), TEXT) + 6;
        x += chip(g, x, y, "UP ON YOU", up + " \u00b7 +" + Money.format(upSum), GREEN) + 6;
        chip(g, x, y, "DOWN ON YOU", down + " \u00b7 -" + Money.format(downSum), RED);
        drawSearchFrame(g, y);

        int py = y + 44, ph = contentBottom() - py, pw = fw - 20, px = fx + 10;
        box(g, px, py, pw, ph, PANEL, BORDER);
        int cNum = px + 10, cName = px + 28, cBets = px + pw * 48 / 100, cWag = px + pw * 62 / 100, cNet = px + pw - 14;
        int hy = py + 8;
        caps(g, "#", cNum, hy, DIM);
        caps(g, "PLAYER", cName + 14, hy, DIM);
        caps(g, "BETS", cBets, hy, DIM);
        caps(g, "WAGERED", cWag, hy, DIM);
        caps(g, "NET (THEIR SIDE)", cNet - capsW("NET (THEIR SIDE)"), hy, DIM);
        g.fill(px + 1, hy + 13, px + pw - 1, hy + 14, BORDER);

        int rowH = WL_ROW_H, ry = hy + 19, visible = Math.max(1, (py + ph - 4 - ry) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, all.size() - visible)));
        for (int i = 0; i < visible && scroll + i < all.size(); i++) {
            int idx = scroll + i;
            PaymentLedger.Entry e = all.get(idx);
            int ty = ry + i * rowH;
            boolean hot = mx >= px + 4 && mx < px + pw - 8 && my >= ty - 3 && my < ty + rowH - 3;
            if (hot) g.fill(px + 4, ty - 3, px + pw - 8, ty + rowH - 3, 0xFF221D36);
            else if (i > 0) g.fill(px + 8, ty - 3, px + pw - 10, ty - 2, alpha(BORDER, 200));
            if (hot) g.fill(px + 4, ty - 3, px + 6, ty + rowH - 3, PURPLE);
            text(g, String.valueOf(idx + 1), cNum, ty + 1, DIM, 0.76f, false);
            face(g, e.name, cName, ty - 1, 10);
            text(g, e.name, cName + 14, ty, hot ? PURPLE_LIGHT : TEXT, 0.8f, true);
            text(g, String.valueOf(e.payments), cBets, ty + 1, MUTED, 0.75f, false);
            text(g, Money.format(e.paidYou), cWag, ty + 1, MUTED, 0.75f, false);
            long n = e.net();
            String v = signedMoney(n);
            text(g, v, cNet - wb(v, 0.8f), ty, n > 0 ? GREEN : n < 0 ? RED : MUTED, 0.8f, true);
        }
        if (all.isEmpty()) text(g, "No players yet", cName, ry, DIM, 0.85f, false);
        else text(g, "click a player to see their history", cName + 14 + capsW("PLAYER") + 10, hy, DIM, 0.66f, false);
        scrollbar(g, px + pw - 5, ry - 3, py + ph - ry, visible, all.size());
    }

    // ================= RAKEBACK =================
    private void drawRakeback(GuiGraphicsExtractor g) {
        Settings st = Settings.S;
        List<PaymentLedger.Entry> losers = new ArrayList<>();
        int ready = 0;
        long owe = 0;
        String q = search.trim().toLowerCase(Locale.US);
        for (PaymentLedger.Entry e : PaymentLedger.entries(false)) {   // everyone, all time
            long lost = -e.net();   // what they lost to you
            if (lost > 0 && (q.isEmpty() || e.name.toLowerCase(Locale.US).contains(q))) {
                losers.add(e);
                if (lost >= st.rakeMin) { ready++; owe += lost * st.rakePct / 100; }
            }
        }
        losers.sort(Comparator.comparingLong(PaymentLedger.Entry::net));   // closest to (or over) the target first

        int y = pageTitle(g, "Rakeback", st.rakePct + "% back once a player has lost " + Money.format(st.rakeMin)
                + ". Everyone, closest to " + Money.format(st.rakeMin) + " first.");
        int x = fx + 10;
        x += chip(g, x, y, "PLAYERS", String.valueOf(losers.size()), TEXT) + 6;
        x += chip(g, x, y, "READY", String.valueOf(ready), GREEN) + 6;
        chip(g, x, y, "YOU OWE", Money.format(owe), TEXT);
        drawSearchFrame(g, y);

        int py = y + 46, ph = contentBottom() - py, pw = fw - 20, px = fx + 10;
        int rowH = 24, ry = py, visible = Math.max(1, (py + ph - 4 - ry) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, losers.size() - visible)));
        for (int i = 0; i < visible && scroll + i < losers.size(); i++) {
            int idx = scroll + i;
            PaymentLedger.Entry e = losers.get(idx);
            long lost = -e.net();
            boolean isReady = lost >= st.rakeMin;
            int ty = ry + i * rowH, rx = px, rw = pw - 8;
            box(g, rx, ty, rw, rowH - 4, PANEL, BORDER);
            text(g, String.valueOf(idx + 1), rx + 10, ty + 5, DIM, 0.7f, false);
            text(g, e.name, rx + 28, ty + 4, TEXT, 0.74f, true);
            String right = isReady ? "READY  owe " + Money.format(lost * st.rakePct / 100)
                    : Money.format(lost) + " / " + Money.format(st.rakeMin);
            text(g, right, rx + rw - 10 - wb(right, 0.74f), ty + 4, isReady ? GREEN : TEXT, 0.74f, true);
            int bx = rx + 28, be = rx + rw - 10, by = ty + rowH - 9;
            g.fill(bx, by, be, by + 2, alpha(BORDER, 255));
            int fill = (int) ((be - bx) * Math.min(1.0, lost / (double) st.rakeMin));
            for (int k = 0; k < fill; k += 2) {   // rainbow: pink -> white -> green along the whole bar
                float t = k / (float) Math.max(1, be - bx);
                int c = t < 0.5f ? mixColor(0xFFF472B6, 0xFFEDE9FE, t * 2f) : mixColor(0xFFEDE9FE, 0xFF4ADE80, (t - 0.5f) * 2f);
                g.fill(bx + k, by, bx + Math.min(fill, k + 2), by + 2, c);
            }
        }
        if (losers.isEmpty()) text(g, q.isEmpty() ? "Nobody has lost to you yet" : "No players match \"" + search.trim() + "\"",
                px + 10, ry + 4, DIM, 0.85f, false);
        scrollbar(g, px + pw - 3, ry, Math.min(ph - 8, visible * rowH), visible, losers.size());
    }

    // ================= SESSIONS =================
    private static String weekday(long ts) {
        return java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US));
    }

    private void drawSessions(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean active = PaymentLedger.isSessionActive();
        if (pendingDel != -2 && System.currentTimeMillis() > pendingUntil) pendingDel = -2;
        List<PaymentLedger.SessionRecord> hist = PaymentLedger.history();
        PaymentLedger.SessionRecord picked = pickedSession >= 0 && pickedSession < hist.size() ? hist.get(pickedSession) : null;

        pageTitle(g, "Sessions", "Start a session when you host. It tracks your profit and every bet until you stop it.");
        int lx = fx + 10, lw = sLeftW(), top = sTop(), ch = sCardH();

        // which session the left side shows
        long from, to, length;
        if (picked != null) { from = picked.start; to = picked.end; length = picked.length(); }
        else { from = PaymentLedger.sessionStartTime(); to = active ? now() : from + PaymentLedger.sessionMillis(); length = PaymentLedger.sessionMillis(); }
        java.util.Map<String, long[]> per = new java.util.LinkedHashMap<>();
        long profit = 0;
        for (PaymentLedger.Payment p : PaymentLedger.paymentsSince(from)) {
            if (p.ts > to) continue;
            long[] a = per.computeIfAbsent(p.name, k -> new long[1]);
            a[0] += p.youPaid - p.paidYou;          // their side
            profit += p.paidYou - p.youPaid;        // your side
        }
        if (picked != null) profit = picked.profit();

        // ---- session card ----
        box(g, lx, top, lw, ch, PANEL, BORDER);
        caps(g, picked != null ? "PAST SESSION" : active ? "SESSION LIVE" : "SESSION", lx + 12, top + 10, picked == null && active ? GREEN : MUTED);
        String when = weekday(from) + "  -  " + clock(from) + " - " + (picked == null && active ? "now" : clock(to));
        text(g, when, lx + 12, top + 25, DIM, 0.66f, false);
        text(g, hms(length), lx + 12, top + 40, TEXT, 1.4f, true);
        caps(g, "PROFIT", lx + lw - 12 - capsW("PROFIT"), top + 10, MUTED);
        String pr = signedMoney(profit);
        text(g, pr, lx + lw - 12 - wb(pr, 1.5f), top + 27, profit > 0 ? GREEN : profit < 0 ? RED : GREEN, 1.5f, true);

        if (sessionButton != null) {
            boolean confirming = confirmUntil > System.currentTimeMillis();
            Button b = sessionButton;
            int bx = b.getX(), by = b.getY(), bw = b.getWidth(), bh = b.getHeight();
            int col = confirming || active ? RED : PURPLE;
            round(g, bx, by, bw, bh, 3, over(b, mouseX, mouseY) ? mixColor(col, 0xFFFFFFFF, 0.12f) : col);
            int ix = bx + 10, iy = by + bh / 2;
            if (confirming || active) g.fill(ix, iy - 3, ix + 6, iy + 3, TEXT);                        // stop square
            else for (int k = 0; k < 4; k++) g.fill(ix + k, iy - 4 + k, ix + k + 1, iy + 4 - k, TEXT);    // play triangle
            String s0 = confirming ? "CONFIRM?" : active ? "END SESSION" : "START SESSION";
            text(g, s0, bx + 22, by + (bh - 7) / 2, TEXT, 0.76f, true);
        }

        // ---- players this session ----
        int py = top + ch + 8, ph = contentBottom() - py;
        box(g, lx, py, lw, ph, PANEL, BORDER);
        caps(g, "PLAYERS THIS SESSION", lx + 12, py + 10, TEXT);
        caps(g, "THEIR SIDE", lx + lw - 12 - capsW("THEIR SIDE"), py + 10, DIM);
        List<java.util.Map.Entry<String, long[]>> players = new ArrayList<>(per.entrySet());
        players.sort((a2, b2) -> Long.compare(b2.getValue()[0], a2.getValue()[0]));
        int prow = 22, pry = py + 30, prows = Math.max(0, (py + ph - 6 - pry) / prow);
        for (int i = 0; i < Math.min(prows, players.size()); i++) {
            var e = players.get(i);
            int ty = pry + i * prow;
            if (i > 0) g.fill(lx + 10, ty - 3, lx + lw - 10, ty - 2, alpha(BORDER, 200));
            face(g, e.getKey(), lx + 12, ty, 10);
            text(g, e.getKey(), lx + 26, ty + 1, TEXT, 0.74f, true);
            long n = e.getValue()[0];
            String v = signedMoney(n);
            text(g, v, lx + lw - 12 - wb(v, 0.74f), ty + 1, n > 0 ? GREEN : n < 0 ? RED : MUTED, 0.74f, true);
        }
        if (players.isEmpty()) text(g, "No bets in this session yet", lx + 12, pry, DIM, 0.74f, false);

        // ---- session history ----
        int[] h = sHist();
        box(g, h[0], h[1], h[2], h[3], PANEL, BORDER);
        int capW = caps(g, "SESSION HISTORY", h[0] + 12, h[1] + 10, TEXT);
        text(g, "click one to see its players", h[0] + 20 + capW, h[1] + 10, DIM, 0.64f, false);
        for (var e : sessionPickButtons.entrySet()) {
            Button b = e.getKey();
            int idx = e.getValue();
            PaymentLedger.SessionRecord r = hist.get(idx);
            boolean sel = idx == pickedSession, hot = over(b, mouseX, mouseY);
            int bx = b.getX(), by = b.getY(), bw = h[2] - 16, bh = b.getHeight();
            round(g, bx, by, bw, bh, 3, sel ? alpha(PURPLE, 40) : hot ? alpha(BORDER, 200) : alpha(BORDER, 110));
            if (sel) g.fill(bx, by + 2, bx + 2, by + bh - 2, PURPLE);
            text(g, weekday(r.start), bx + 10, by + 4, TEXT, 0.74f, true);
            text(g, clock(r.start) + " - " + clock(r.end) + "   " + hms(r.length()), bx + 10, by + 15, DIM, 0.62f, false);
            String p = signedMoney(r.profit());
            text(g, p, bx + bw - 30 - wb(p, 0.8f), by + 8, r.profit() > 0 ? GREEN : r.profit() < 0 ? RED : GREEN, 0.8f, true);
        }
        for (var e : sessionDelButtons.entrySet()) {   // the x / Sure? on each row
            Button b = e.getKey();
            boolean armed = pendingDel == e.getValue(), hot = over(b, mouseX, mouseY);
            int bx = b.getX(), by = b.getY();
            if (armed) {
                String s1 = "Sure?";
                int bw = wb(s1, 0.66f) + 8;
                box(g, bx + 16 - bw, by, bw, b.getHeight(), alpha(RED, 80), RED);
                text(g, s1, bx + 16 - bw + 4, by + (b.getHeight() - 7) / 2, TEXT, 0.66f, true);
            } else {
                int c = hot ? RED : DIM, mx0 = bx + 5, my0 = by + 5;
                for (int k = 0; k < 6; k++) {
                    g.fill(mx0 + k, my0 + k, mx0 + k + 1, my0 + k + 1, c);
                    g.fill(mx0 + 5 - k, my0 + k, mx0 + 6 - k, my0 + k + 1, c);
                }
            }
        }
        scrollbar(g, h[0] + h[2] - 4, sHistRowsY(), Math.min(h[3] - 36, sVisible() * S_HIST_ROW), sVisible(), hist.size());
        if (hist.isEmpty()) text(g, "Ended sessions show up here", h[0] + 12, sHistRowsY() + 2, DIM, 0.74f, false);
    }

    // ================= AUCTIONS =================
    private EditBox itemSearch;
    private static String itemQuery = "";
    private final java.util.Map<Button, Runnable> auctionButtons = new java.util.LinkedHashMap<>();
    private Button startBtn, clearBtn, switchBtn, gridBtn;
    private final List<Button> durationBtns = new ArrayList<>();
    private final List<Button> colorBtns = new ArrayList<>();
    private static final long[] DURATIONS = {15_000, 30_000, 45_000, 60_000, 120_000, 300_000};
    private static final String[] DURATION_LABELS = {"15s", "30s", "45s", "1m", "2m", "5m"};
    private static final int[] TIMER_COLORS = {GOLD, PURPLE, GREEN, RED, 0xFF5B8CFF, 0xFFF472B6, 0xFF3ADFDF, 0xFFF2F0FA};

    /** Every item in the game with its name, built once. */
    private static List<net.minecraft.world.item.ItemStack> allItems;
    private static List<String> allNames;

    private static void loadItems() {
        if (allItems != null) return;
        allItems = new ArrayList<>();
        allNames = new ArrayList<>();
        for (net.minecraft.world.item.Item it : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            net.minecraft.world.item.ItemStack st = new net.minecraft.world.item.ItemStack(it);
            if (st.isEmpty()) continue;
            allItems.add(st);
            allNames.add(st.getHoverName().getString());
        }
    }

    private static String cachedQuery = null;
    private static List<Integer> cachedItems = null;

    private List<Integer> filteredItems() {
        loadItems();
        if (itemQuery.equals(cachedQuery) && cachedItems != null) return cachedItems;
        String q = itemQuery.trim().toLowerCase(Locale.US);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < allItems.size(); i++)
            if (q.isEmpty() || allNames.get(i).toLowerCase(Locale.US).contains(q)) out.add(i);
        cachedQuery = itemQuery;
        cachedItems = out;
        return out;
    }

    // ---- layout shared by click areas and drawing ----
    private EditBox nameBox, qtyBox, secBox;
    private Button nameClick, qtyClick, secClick, svBtn, hueBtn;
    private static float hue = 300f, sat = 0.55f, val = 0.72f;   // color picker
    private static final int CELL = 26;
    private static final int BID_H = 92;   // TOP BIDDERS panel under the item picker

    private int aTop() { return contentY() + 30; }
    private int aLeftW() { return (fw - 28) * 49 / 100; }
    private int aRingH() {   // whatever height is left after the controls, so the switch never runs past the footer
        int controls = 8 + 28 + 36 + 50 + 28 + 4;
        return Math.max(72, Math.min(170, contentBottom() - aTop() - controls));
    }
    private int aCtrlY() { return aTop() + aRingH() + 8; }
    private int aFieldsY() { return aCtrlY() + 28; }
    private int aColorY() { return aFieldsY() + 36; }
    private int aSwitchY() { return aColorY() + 50; }
    private int aLastY() { return aSwitchY() + 34; }
    private int[] aGrid() {   // x, y, w, h of the item grid
        int rx = fx + 10 + aLeftW() + 8, rw = fx + fw - 10 - rx;
        return new int[]{rx + 10, aTop() + 48, rw - 24, contentBottom() - BID_H - 8 - 18 - (aTop() + 48)};
    }
    private int[] aFields() {   // name x/w, qty x/w, sec x/w
        int lx = fx + 10, lw = aLeftW();
        int nw = lw * 50 / 100, qw = (lw - nw - 12) / 2;
        return new int[]{lx, nw, lx + nw + 6, qw, lx + nw + 12 + qw, lw - nw - 12 - qw};
    }
    private int[] aPicker() {   // square x/y/w/h, hue bar x, hex box x/w
        int lx = fx + 10, lw = aLeftW();
        int sx = lx + 76, hexW = 64, sw = lw - 76 - 10 - 8 - hexW - 6;
        return new int[]{sx, aColorY() + 13, sw, 30, sx + sw + 4, sx + sw + 18, hexW};
    }

    private static int hsv(float h, float s, float v) {
        float c = v * s, hp = (h % 360f) / 60f, x = c * (1 - Math.abs(hp % 2 - 1)), m = v - c;
        float r, g, b;
        if (hp < 1) { r = c; g = x; b = 0; }
        else if (hp < 2) { r = x; g = c; b = 0; }
        else if (hp < 3) { r = 0; g = c; b = x; }
        else if (hp < 4) { r = 0; g = x; b = c; }
        else if (hp < 5) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return 0xFF000000 | (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    private EditBox hiddenBox(String value, int max, java.util.function.Predicate<String> filter, java.util.function.Consumer<String> onChange) {
        final EditBox b = new EditBox(font, -4000, -4000, 200, 12, Component.literal(""));   // hidden: we draw it ourselves
        b.setMaxLength(max);
        b.setValue(value);
        b.setResponder(v -> {
            if (filter != null && !filter.test(v)) {   // numbers only: strip anything else
                b.setValue(v.replaceAll("[^0-9]", ""));
                return;
            }
            onChange.accept(v);
        });
        addWidget(b);
        return b;
    }

    private void initAuctions() {
        colorBtns.clear();
        int lx = fx + 10, lw = aLeftW();
        startBtn = btn("START AUCTION", lx, aCtrlY(), wb("START AUCTION", 0.76f) + 34, () -> {
            if (Auction.running) Auction.endAt = System.currentTimeMillis();   // end now
            else Auction.start();
        });
        startBtn.setHeight(20);
        clearBtn = btn("Clear", startBtn.getX() + startBtn.getWidth() + 8, aCtrlY(), w("Clear", 0.8f) + 22, () -> {
            Auction.clearItem();
            rebuildWidgets();   // empties the NAME / QUANTITY boxes too
        });
        clearBtn.setHeight(20);

        int[] fl = aFields();
        nameBox = hiddenBox(Auction.shortName() == null ? "" : Auction.shortName(), 40, null, v -> Auction.customName = v);
        qtyBox = hiddenBox(String.valueOf(Auction.quantity), 7, v -> v.matches("\\d*"), v -> {
            try { Auction.quantity = Math.max(1, Integer.parseInt(v)); } catch (Exception e) { Auction.quantity = 1; }
        });
        secBox = hiddenBox(String.valueOf(Auction.durationMs / 1000), 4, v -> v.matches("\\d*"), v -> {
            try { if (!Auction.running) Auction.durationMs = Math.max(5, Math.min(3600, Integer.parseInt(v))) * 1000L; } catch (Exception ignored) { }
        });
        int fy0 = aFieldsY() + 12;
        nameClick = btn("", fl[0], fy0, fl[1], () -> pendingFocus = nameBox); nameClick.setHeight(16);
        qtyClick = btn("", fl[2], fy0, fl[3], () -> pendingFocus = qtyBox); qtyClick.setHeight(16);
        secClick = btn("", fl[4], fy0, fl[5], () -> pendingFocus = secBox); secClick.setHeight(16);

        for (int i = 0; i < TIMER_COLORS.length; i++) {   // 2 rows of 4 swatches
            final int col = TIMER_COLORS[i];
            Button cb = btn("", lx + (i % 4) * 16, aColorY() + 13 + (i / 4) * 16, 12, () -> Auction.color = col);
            cb.setHeight(12);
            colorBtns.add(cb);
        }
        int[] pk = aPicker();
        svBtn = btn("", pk[0], pk[1], pk[2], this::pickSV); svBtn.setHeight(pk[3]);
        hueBtn = btn("", pk[4], pk[1], 8, this::pickHue); hueBtn.setHeight(pk[3]);

        switchBtn = btn("", lx, aSwitchY(), lw, () -> Auction.switchOn = !Auction.switchOn);
        switchBtn.setHeight(28);

        int[] gr = aGrid();
        int rx = fx + 10 + aLeftW() + 8, rw = fx + fw - 10 - rx;
        itemSearch = hiddenBox(itemQuery, 32, null, v -> { itemQuery = v; scroll = 0; });
        Button click = btn("", rx + 10, aTop() + 24, rw - 20, () -> pendingFocus = itemSearch);
        click.setHeight(18);
        gridBtn = addWidget(Button.builder(Component.literal(""), b -> pickItemAtMouse())
                .bounds(gr[0], gr[1], gr[2], gr[3]).build());
    }

    private void pickSV() {
        int[] pk = aPicker();
        sat = Math.max(0f, Math.min(1f, (lastMouseX - pk[0]) / (float) Math.max(1, pk[2] - 1)));
        val = Math.max(0f, Math.min(1f, 1f - (lastMouseY - pk[1]) / (float) Math.max(1, pk[3] - 1)));
        Auction.color = hsv(hue, sat, val);
    }

    private void pickHue() {
        int[] pk = aPicker();
        hue = Math.max(0f, Math.min(359f, (lastMouseY - pk[1]) / (float) Math.max(1, pk[3] - 1) * 360f));
        Auction.color = hsv(hue, sat, val);
    }

    private void pickItemAtMouse() {
        int[] gr = aGrid();
        int cols = Math.max(1, gr[2] / CELL);
        int col = (lastMouseX - gr[0]) / CELL, row = (lastMouseY - gr[1]) / CELL;
        if (col < 0 || col >= cols || row < 0) return;
        List<Integer> items = filteredItems();
        int idx = (scroll + row) * cols + col;
        if (idx >= 0 && idx < items.size() && !Auction.running) {
            Auction.item = allItems.get(items.get(idx)).copy();
            Auction.itemName = allNames.get(items.get(idx));
            Auction.customName = Auction.itemName;   // NAME box fills in; you can retype it
            Auction.ended = false;
            rebuildWidgets();
        }
    }

    /** Plain input box drawn like the reference: label above, dark field, your text and a cursor. */
    private void inputField(GuiGraphicsExtractor g, String label, int x, int y, int w, EditBox box, String hint) {
        caps(g, label, x, y, DIM);
        int by = y + 12, bh = 16;
        boolean focused = box != null && box.isFocused();
        box(g, x, by, w, bh, 0xFF0B0A12, focused ? PURPLE : BORDER);
        String v = box == null ? "" : box.getValue();
        int tx = x + 7, ty = by + (bh - 7) / 2;
        if (v.isEmpty()) text(g, hint, tx, ty, DIM, 0.74f, false);
        else {
            String shown = v;
            while (shown.length() > 1 && w(shown, 0.74f) > w - 14) shown = shown.substring(1);
            text(g, shown, tx, ty, TEXT, 0.74f, false);
            tx += w(shown, 0.74f) + 1;
        }
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) g.fill(tx, ty - 1, tx + 1, ty + 8, TEXT);
    }

    /** Smooth ring: lit part (from the top, clockwise) shows the time left. */
    static void ring(GuiGraphicsExtractor g, int cx, int cy, int r, int thick, float frac, int litColor, int restColor) {
        float ro = r, ri = r - thick;
        int lim = r + 1;
        for (int dy = -lim; dy <= lim; dy++) {
            int runStart = 0, runColor = 0;
            for (int dx = -lim; dx <= lim + 1; dx++) {
                int col = 0;
                float cover = 0f;
                if (dx <= lim) {
                    float d = (float) Math.sqrt((dx + 0.5f) * (dx + 0.5f) + (dy + 0.5f) * (dy + 0.5f));
                    cover = Math.max(0f, Math.min(1f, ro - d + 0.5f)) * Math.max(0f, Math.min(1f, d - ri + 0.5f));
                    if (cover > 0f) {
                        double ang = Math.toDegrees(Math.atan2(dx + 0.5, -(dy + 0.5)));   // 0 at the top, clockwise
                        if (ang < 0) ang += 360;
                        col = (litColor != 0 && ang < frac * 360) ? litColor : restColor;
                    }
                }
                boolean solid = cover >= 0.99f;
                int runKey = solid ? col : 0;
                if (runKey != runColor) {   // close the previous solid run
                    if (runColor != 0) g.fill(cx + runStart, cy + dy, cx + dx, cy + dy + 1, runColor);
                    runStart = dx;
                    runColor = runKey;
                }
                if (!solid && cover > 0f) {   // soft edge pixel
                    int a = (int) (((col >>> 24) & 0xFF) * cover);
                    g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, (a << 24) | (col & 0xFFFFFF));
                }
            }
        }
    }

    private void itemIcon(GuiGraphicsExtractor g, net.minecraft.world.item.ItemStack st, int x, int y, float scale) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.item(st, 0, 0);
        g.pose().popMatrix();
    }

    private void drawAuctions(GuiGraphicsExtractor g, int mx, int my) {
        pageTitle(g, "Auctions", "Pick an item, set the timer and start. Whoever pays you the most before it hits 0 wins.");
        int lx = fx + 10, lw = aLeftW(), top = aTop();
        int acc = Auction.color;

        // ---- ring panel ----
        int rh = aRingH();
        box(g, lx, top, lw, rh, PANEL, BORDER);
        String t = "AUCTIONS";
        caps(g, t, lx + (lw - capsW(t)) / 2, top + 9, acc);
        int ccx = lx + lw / 2, rad = Math.max(12, Math.min(64, (rh - 48) / 2)), ccy = top + 25 + rad;   // title above, status below
        float frac = Auction.running ? Auction.left() / (float) Auction.durationMs : 1f;
        ring(g, ccx, ccy, rad, 6, frac, acc, Auction.running ? alpha(acc, 60) : acc);
        int inner = (rad - 6) * 2 - 10;
        float iconScale = Math.max(1.2f, Math.min(2f, rad / 30f));
        int iconSize = (int) (16 * iconScale);
        int iconY = ccy - rad + 10;
        if (!Auction.item.isEmpty()) {
            itemIcon(g, Auction.item, ccx - iconSize / 2, iconY, iconScale);
            if (Auction.quantity > 1) {   // quantity badge, beside the item
                String q = "x" + Auction.quantity;
                int qw = wb(q, 0.55f) + 8, qx = ccx + iconSize / 2 - 2, qy = iconY + iconSize - 10;
                round(g, qx, qy, qw, 11, 3, acc);
                text(g, q, qx + 4, qy + 2, onAccentFor(acc), 0.55f, true);
            }
        }
        String big = Auction.running ? String.format("%d:%02d", Auction.left() / 60000, (Auction.left() / 1000) % 60)
                : Auction.ended ? (Auction.winner != null ? "SOLD" : "NO BIDS")
                : Auction.item.isEmpty() ? "PICK" : String.format("%d:%02d", Auction.durationMs / 60000, (Auction.durationMs / 1000) % 60);
        float bs = 1.05f;
        while (bs > 0.6f && wb(big, bs) > inner) bs -= 0.05f;
        int bigY = Auction.item.isEmpty() ? ccy - 8 : iconY + iconSize + 4;
        text(g, big, ccx - wb(big, bs) / 2, bigY, Auction.running && Auction.left() <= 10_000 ? RED : TEXT, bs, true);
        String nm = Auction.item.isEmpty() ? "an item" : Auction.displayName();
        while (nm.length() > 3 && w(nm, 0.52f) > inner - 8) nm = nm.substring(0, nm.length() - 2) + ".";
        text(g, nm, ccx - w(nm, 0.52f) / 2, bigY + 15, MUTED, 0.52f, false);
        String status = Auction.running ? "Bidding is open" : Auction.ended && Auction.winner != null
                ? Auction.winner + " won it  -  " + Money.format(Auction.winAmount)
                : Auction.item.isEmpty() ? "Pick an item on the right" : "Ready when you are";
        text(g, status, ccx - wb(status, 0.7f) / 2, top + rh - 13, Auction.ended && Auction.winner != null ? GREEN : MUTED, 0.7f, true);

        // ---- start + note ----
        {
            Button b = startBtn;
            int x = b.getX(), y = b.getY(), bw = b.getWidth(), bh = b.getHeight();
            boolean disabled = !Auction.running && Auction.item.isEmpty();
            int col = Auction.running ? RED : disabled ? 0xFF3A3550 : PURPLE;
            round(g, x, y, bw, bh, 3, over(b, mx, my) && !disabled ? mixColor(col, 0xFFFFFFFF, 0.12f) : col);
            int ix = x + 10, iy = y + bh / 2;
            if (Auction.running) g.fill(ix, iy - 3, ix + 6, iy + 3, TEXT);
            else for (int i = 0; i < 4; i++) g.fill(ix + i, iy - 4 + i, ix + i + 1, iy + 4 - i, TEXT);
            String s0 = Auction.running ? "END NOW" : "START AUCTION";
            text(g, s0, x + 22, y + (bh - 7) / 2, TEXT, 0.76f, true);
        }
        {   // Clear
            Button b = clearBtn;
            boolean hot = over(b, mx, my);
            box(g, b.getX(), b.getY(), b.getWidth(), b.getHeight(), hot ? mixColor(PANEL, PURPLE, 0.3f) : PANEL, hot ? PURPLE : BORDER);
            text(g, "Clear", b.getX() + (b.getWidth() - w("Clear", 0.8f)) / 2, b.getY() + (b.getHeight() - 7) / 2, hot ? TEXT : MUTED, 0.8f, false);
        }
        String note = "Biggest single payment wins (bids don't stack)";
        if (lx + lw - w(note, 0.62f) > clearBtn.getX() + clearBtn.getWidth() + 8)   // only when it doesn't touch Clear
            text(g, note, lx + lw - w(note, 0.62f), aCtrlY() + 7, DIM, 0.62f, false);

        // ---- name / quantity / seconds ----
        int[] fl = aFields();
        inputField(g, "NAME", fl[0], aFieldsY(), fl[1], nameBox, "Item name");
        inputField(g, "QUANTITY", fl[2], aFieldsY(), fl[3], qtyBox, "1");
        inputField(g, "SECONDS", fl[4], aFieldsY(), fl[5], secBox, "30");

        // ---- timer color: swatches, picker, hex ----
        caps(g, "TIMER COLOR", lx, aColorY(), DIM);
        for (int i = 0; i < colorBtns.size(); i++) {
            Button b = colorBtns.get(i);
            int x = b.getX(), y = b.getY();
            if (TIMER_COLORS[i] == acc) round(g, x - 1, y - 1, 14, 14, 7, TEXT);
            round(g, x, y, 12, 12, 6, TIMER_COLORS[i]);
        }
        int[] pk = aPicker();
        int step = 4;
        for (int px = 0; px < pk[2]; px += step) {
            for (int py = 0; py < pk[3]; py += 3) {
                float s1 = px / (float) Math.max(1, pk[2] - 1), v1 = 1f - py / (float) Math.max(1, pk[3] - 1);
                g.fill(pk[0] + px, pk[1] + py, pk[0] + Math.min(pk[2], px + step), pk[1] + Math.min(pk[3], py + 3), hsv(hue, s1, v1));
            }
        }
        int kx = pk[0] + Math.round(sat * (pk[2] - 1)), ky = pk[1] + Math.round((1f - val) * (pk[3] - 1));
        ring(g, kx, ky, 3, 1, 1f, 0xFFFFFFFF, 0xFFFFFFFF);
        for (int py = 0; py < pk[3]; py += 2) g.fill(pk[4], pk[1] + py, pk[4] + 8, pk[1] + Math.min(pk[3], py + 2), hsv(py / (float) pk[3] * 360f, 1f, 1f));
        int hy = pk[1] + Math.round(hue / 360f * (pk[3] - 1));
        g.fill(pk[4] - 1, hy - 1, pk[4] + 9, hy + 1, 0xFFFFFFFF);
        round(g, pk[5], pk[1], pk[6], pk[3], 4, acc);
        String hex = Settings.hex(acc & 0xFFFFFF);
        text(g, hex, pk[5] + (pk[6] - w(hex, 0.62f)) / 2, pk[1] + (pk[3] - 7) / 2, onAccentFor(acc), 0.62f, true);

        // ---- auction switch ----
        {
            Button b = switchBtn;
            int x = b.getX(), y = b.getY(), bw = b.getWidth(), bh = b.getHeight();
            box(g, x, y, bw, bh, over(b, mx, my) ? 0xFF181526 : PANEL, BORDER);
            text(g, "Auction switch", x + 10, y + 5, TEXT, 0.76f, true);
            text(g, "On: while an auction runs, money paid to you counts as bids, not bets.", x + 10, y + 16, DIM, 0.6f, false);
            int sw = 24, sh = 12, sx = x + bw - sw - 10, sy = y + (bh - sh) / 2;
            round(g, sx, sy, sw, sh, 6, Auction.switchOn ? PURPLE : 0xFF2A2640);
            round(g, Auction.switchOn ? sx + sw - 10 : sx + 2, sy + 2, 8, 8, 4, Auction.switchOn ? FRAME : 0xFF6B6684);
        }

        // ---- top bidders (left) + last auctions (right) ----
        int ly = aLastY(), lh = contentBottom() - ly;
        if (lh > 24) {
            int half = (lw - 6) / 2, bx2 = lx + half + 6;
            box(g, lx, ly, half, lh, PANEL, BORDER);
            caps(g, "TOP BIDDERS", lx + 10, ly + 8, TEXT);
            List<java.util.Map.Entry<String, Long>> bids = Auction.sortedBids();
            int rowH = 15, ry = ly + 22, rows = Math.max(0, (ly + lh - 4 - ry) / rowH);
            for (int i = 0; i < Math.min(rows, bids.size()); i++) {
                var e = bids.get(i);
                int ty = ry + i * rowH;
                if (i == 0) round(g, lx + 4, ty - 2, half - 8, rowH - 1, 3, alpha(acc, 40));   // the leader
                face(g, e.getKey(), lx + 8, ty, 10);
                String n = e.getKey();
                while (n.length() > 3 && wb(n, 0.62f) > half - 80) n = n.substring(0, n.length() - 2) + ".";
                text(g, n, lx + 22, ty + 1, TEXT, 0.62f, true);
                String amt = Money.format(e.getValue());
                text(g, amt, lx + half - 8 - wb(amt, 0.62f), ty + 1, GREEN, 0.62f, true);
            }
            if (bids.isEmpty()) text(g, Auction.running ? "Waiting for bids..." : "No bids yet", lx + 10, ry, DIM, 0.62f, false);

            box(g, bx2, ly, lw - half - 6, lh, PANEL, BORDER);
            caps(g, "LAST AUCTIONS", bx2 + 10, ly + 8, TEXT);
            int lw2 = lw - half - 6;
            for (int i = 0; i < Math.min(rows, Auction.history.size()); i++) {
                Auction.Past a = Auction.history.get(i);
                int ty = ry + i * rowH;
                String item = (a.qty() > 1 ? a.qty() + "x " : "") + a.name();
                String who = a.winner() == null ? "no bids" : a.winner();
                String line = item + " > " + who;
                while (line.length() > 4 && w(line, 0.58f) > lw2 - 70) line = line.substring(0, line.length() - 2) + ".";
                text(g, line, bx2 + 10, ty + 1, MUTED, 0.58f, false);
                String amt = a.winner() == null ? "-" : Money.format(a.amount());
                text(g, amt, bx2 + lw2 - 8 - wb(amt, 0.6f), ty + 1, a.winner() == null ? DIM : GREEN, 0.6f, true);
            }
            if (Auction.history.isEmpty()) text(g, "Finished auctions show up here", bx2 + 10, ry, DIM, 0.6f, false);
        }

        // ---- pick an item ----
        int rx = fx + 10 + lw + 8, rw = fx + fw - 10 - rx, rpH = contentBottom() - BID_H - 8 - top;
        box(g, rx, top, rw, rpH, PANEL, BORDER);
        caps(g, "PICK AN ITEM", rx + 10, top + 9, TEXT);
        if (!Auction.item.isEmpty()) text(g, Auction.itemName, rx + rw - 10 - wb(Auction.itemName, 0.7f), top + 9, acc, 0.7f, true);
        searchField(g, rx + 10, top + 24, rw - 20, 18, itemSearch, "Search every item and block...");

        int[] gr = aGrid();
        List<Integer> items = filteredItems();
        int cols = Math.max(1, gr[2] / CELL), rows = Math.max(1, gr[3] / CELL);
        int totalRows = (items.size() + cols - 1) / cols;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, totalRows - rows)));
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = (scroll + r) * cols + c;
                if (idx >= items.size()) break;
                int x = gr[0] + c * CELL, y = gr[1] + r * CELL;
                var st = allItems.get(items.get(idx));
                boolean hot = mx >= x && mx < x + CELL && my >= y && my < y + CELL;
                boolean sel = !Auction.item.isEmpty() && Auction.item.getItem() == st.getItem();
                if (sel) box(g, x, y, CELL - 2, CELL - 2, alpha(acc, 60), acc);
                else if (hot) round(g, x, y, CELL - 2, CELL - 2, 3, 0xFF1E1A2E);
                itemIcon(g, st, x + 2, y + 2, 1.25f);
            }
        }
        scrollbar(g, gr[0] + gr[2] + 4, gr[1], gr[3], rows, totalRows);
        text(g, items.size() + " items", rx + 10, top + rpH - 12, DIM, 0.64f, false);

        // ---- TOP BIDDERS: under the item picker ----
        int tby = top + rpH + 8;
        box(g, rx, tby, rw, BID_H, PANEL, BORDER);
        caps(g, "TOP BIDDERS", rx + 10, tby + 8, TEXT);
        List<java.util.Map.Entry<String, Long>> topBids = Auction.sortedBids();
        int bRow = 15, bry = tby + 24, bRows = Math.max(0, (tby + BID_H - 4 - bry) / bRow);
        for (int i = 0; i < Math.min(bRows, topBids.size()); i++) {
            var e = topBids.get(i);
            int ty = bry + i * bRow;
            if (i == 0) round(g, rx + 4, ty - 2, rw - 8, bRow - 1, 3, alpha(acc, 40));   // the leader
            face(g, e.getKey(), rx + 8, ty, 10);
            text(g, e.getKey(), rx + 22, ty + 1, TEXT, 0.64f, true);
            String amt = Money.format(e.getValue());
            text(g, amt, rx + rw - 10 - wb(amt, 0.64f), ty + 1, GREEN, 0.64f, true);
        }
        if (topBids.isEmpty()) text(g, Auction.running ? "Waiting for bids..." : "No bids yet", rx + 10, bry, DIM, 0.64f, false);
    }

    private static int onAccentFor(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 > 170 ? 0xFF111111 : 0xFFFFFFFF;
    }

    // ================= PROFIT PANEL =================
    private void drawPanels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = fx + 10, y = contentY();
        caps(g, "PROFIT PANEL", x, y + 4, TEXT);
        text(g, "Turn the Balance HUD panels on or off. They appear and disappear on your screen right away.",
                x, y + 16, DIM, 0.72f, false);
        String[] names = {"PROFIT TRACKER", "RAKEBACK", "BALANCE"};
        String[] about = {"The big profit panel with the chart, top winners / losers and recent bets",
                "Who is close to rakeback and how much you owe them",
                "Your balance box with your name"};
        boolean[] on = {Panels.profit, Panels.rakeback, Panels.balance};
        int[] colors = {GREEN, GOLD, PURPLE};
        for (int i = 0; i < panelButtons.size(); i++) {
            Button b = panelButtons.get(i);
            int bx = b.getX(), by = b.getY(), bw = b.getWidth(), bh = b.getHeight();
            boolean hover = over(b, mouseX, mouseY);
            box(g, bx, by, bw, bh, hover ? 0xFF181526 : PANEL, BORDER);
            if (on[i]) glow(g, bx + bw / 6, by + bh / 2, bw / 6, bh / 2, colors[i], 30);
            g.fill(bx, by, bx + 2, by + bh, on[i] ? colors[i] : BORDER);
            caps(g, names[i], bx + 14, by + 10, on[i] ? TEXT : MUTED);
            text(g, about[i], bx + 14, by + 24, DIM, 0.72f, false);
            // switch
            int sw = 30, sh = 14, sx = bx + bw - sw - 14, sy = by + (bh - sh) / 2;
            round(g, sx, sy, sw, sh, sh / 2, on[i] ? PURPLE : 0xFF2A2640);         // rounded pill track
            int kd = sh - 4, kx = on[i] ? sx + sw - kd - 2 : sx + 2;
            round(g, kx, sy + 2, kd, kd, kd / 2, on[i] ? FRAME : 0xFF6B6684);       // round knob, no white
            String st = on[i] ? "ON" : "OFF";
            text(g, st, sx - 8 - wb(st, 0.75f), sy + 3, on[i] ? PURPLE_LIGHT : DIM, 0.75f, true);
        }
        if (settingsButton != null) pill(g, settingsButton, false, mouseX, mouseY);
        int ny = panelButtons.isEmpty() ? y + 40 : panelButtons.get(panelButtons.size() - 1).getY() + 56;
        text(g, "These switches control the Balance HUD mod, so it needs its latest update installed too.",
                x, ny, DIM, 0.72f, false);
    }

    private void stat(GuiGraphicsExtractor g, String label, String value, int x, int y, int color) {
        caps(g, label, x, y, MUTED);
        text(g, value, x, y + 13, color, 1.15f, true);
    }
}
