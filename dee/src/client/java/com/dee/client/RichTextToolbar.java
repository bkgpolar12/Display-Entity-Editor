package com.dee.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * 텍스트 입력칸에서 글자를 드래그해 블록 지정하면, 그 위에 뜨는 서식 툴바.
 * 마인크래프트 기본 16색 / 색상 선택기 / 볼드 / 기울임 / 밑줄 / 취소선 / 난독화 / 서식 지우기.
 * 위젯이 아니라 화면이 직접 부른다: update() → render() 와 mouseClicked/Dragged/Released.
 */
public final class RichTextToolbar {
    private static final ChatFormatting[] PALETTE = {
            ChatFormatting.BLACK, ChatFormatting.DARK_BLUE, ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA,
            ChatFormatting.DARK_RED, ChatFormatting.DARK_PURPLE, ChatFormatting.GOLD, ChatFormatting.GRAY,
            ChatFormatting.DARK_GRAY, ChatFormatting.BLUE, ChatFormatting.GREEN, ChatFormatting.AQUA,
            ChatFormatting.RED, ChatFormatting.LIGHT_PURPLE, ChatFormatting.YELLOW, ChatFormatting.WHITE
    };
    private static final TextColor[] COLORS = new TextColor[PALETTE.length];

    static {
        for (int i = 0; i < PALETTE.length; i++) COLORS[i] = TextColor.fromLegacyFormat(PALETTE[i]);
    }

    private enum Action {
        CUSTOM("dee.rich.custom_color"), BOLD("dee.rich.bold"), ITALIC("dee.rich.italic"),
        UNDERLINED("dee.rich.underline"), STRIKETHROUGH("dee.rich.strikethrough"),
        OBFUSCATED("dee.rich.obfuscated"), CLEAR("dee.rich.clear");

        final String tipKey;

        Action(String tipKey) {
            this.tipKey = tipKey;
        }
    }

    private static final Action[] ACTIONS = Action.values();

    // ---- 크기 ----
    private static final int CELL = 10;       // 기본 색 칸 한 변
    private static final int COLS = 8;        // 기본 색은 8 x 2
    private static final int BTN_W = 18;
    private static final int BTN_H = 2 * CELL;
    private static final int PAD = 3;
    private static final int GAP = 4;
    private static final int BAR_W = PAD + COLS * CELL + GAP + ACTIONS.length * BTN_W + (ACTIONS.length - 1) + PAD;
    private static final int BAR_H = 2 * PAD + 2 * CELL;

    private static final int SV = 64;         // 채도/명도 사각형 한 변
    private static final int HUE_W = 10;
    private static final int RIGHT_W = 62;
    private static final int POP_W = 4 + SV + 4 + HUE_W + 6 + RIGHT_W + 4;
    private static final int POP_H = 4 + SV + 4;
    private static final int STEP = 2;        // 그라데이션을 그리는 칸 크기(px)

    private static final int COL_PANEL = 0xFF2B2B2B;
    private static final int COL_BTN = 0xFF444444;
    private static final int COL_HOVER = 0xFF666666;
    private static final int COL_ACTIVE = 0xFF3A6EA5;
    private static final int COL_OK = 0xFFE0E0E0;
    private static final int COL_ERR = 0xFFFF5555;

    private final Font font;
    private final EditBox field;
    private final RichText rich;
    private final Runnable onChange;
    private final Consumer<EditBox> focuser;
    private final int screenW;
    private final int screenH;
    private final EditBox hexBox;

    /** 선택 범위 [selFrom, selTo). 선택이 없으면 -1. */
    private int selFrom = -1;
    private int selTo = -1;
    /** 선택이 시작되기 직전의 커서 위치와, 선택의 기준점이 커서보다 왼쪽인지 (EditBox 가 기준점을 알려 주지 않아서 추정한다) */
    private int lastCursor;
    private boolean anchorLeft = true;

    private int barX;
    private int barY;
    private int popX;
    private int popY;

    private boolean pickerOpen;
    private float hue;
    private float sat = 1f;
    private float val = 1f;
    private boolean dragSv;
    private boolean dragHue;
    private boolean updatingHex;

    /**
     * @param field    서식을 입힐 텍스트 입력칸
     * @param onChange 서식이 바뀐 뒤 부른다. (Draft 에 저장)
     * @param focuser  화면의 setFocused. 색상 선택기의 16진수 칸과 텍스트 칸 사이에서 포커스를 옮길 때 쓴다.
     */
    public RichTextToolbar(Font font, EditBox field, RichText rich, int screenW, int screenH,
                           Runnable onChange, Consumer<EditBox> focuser) {
        this.font = font;
        this.field = field;
        this.rich = rich;
        this.screenW = screenW;
        this.screenH = screenH;
        this.onChange = onChange;
        this.focuser = focuser;

        this.hexBox = new EditBox(font, 0, 0, RIGHT_W, 16, Component.empty());
        hexBox.setMaxLength(7);
        hexBox.visible = false;
        hexBox.active = false;
        hexBox.setResponder(s -> {
            if (updatingHex) return;
            String hex = s.startsWith("#") ? s.substring(1) : s;
            boolean ok = hex.matches("[0-9a-fA-F]{6}");
            hexBox.setTextColor(ok ? COL_OK : COL_ERR);
            if (ok) setFromRgb(Integer.parseInt(hex, 16));
        });
    }

    /** 화면에 addWidget 으로 등록할 것. (그리기는 이 툴바가 직접 한다) */
    public EditBox hexBox() {
        return hexBox;
    }

    // ------------------------------------------------------------------
    // 선택 추적 / 위치
    // ------------------------------------------------------------------

    /** 매 프레임 그리기 전에 부른다. 입력칸의 선택 범위를 읽는다. */
    public void update() {
        if (pickerOpen) {
            if (!field.visible) closePicker();
            return;
        }
        boolean wasSelecting = selFrom >= 0;
        String value = field.getValue();
        String selected = field.getHighlighted();
        int cursor = field.getCursorPosition();

        if (!field.isFocused() || selected.isEmpty() || value.length() != rich.length()) {
            if (selected.isEmpty()) lastCursor = cursor;
            selFrom = -1;
            selTo = -1;
            return;
        }

        // 선택은 [cursor - n, cursor] 이거나 [cursor, cursor + n]. 글자가 맞는 쪽을 고르고, 둘 다 맞으면 처음 추정을 유지한다.
        int n = selected.length();
        boolean leftOk = cursor - n >= 0 && value.startsWith(selected, cursor - n);
        boolean rightOk = cursor + n <= value.length() && value.startsWith(selected, cursor);
        if (leftOk && rightOk) {
            if (!wasSelecting) anchorLeft = lastCursor <= cursor;
        } else if (leftOk) {
            anchorLeft = true;
        } else if (rightOk) {
            anchorLeft = false;
        } else {
            selFrom = -1;
            selTo = -1;
            return;
        }
        selFrom = anchorLeft ? cursor - n : cursor;
        selTo = selFrom + n;
    }

    /** 툴바가 왜 안 뜨는지 확인하는 진단용. 화면 왼쪽 아래에 한 줄로 그린다. (원인 찾으면 화면에서 호출을 지우세요) */
    public String debugLine() {
        return String.format(Locale.ROOT,
                "toolbar: focused=%s sel='%s' cursor=%d valueLen=%d richLen=%d range=[%d,%d) visible=%s shown=%s",
                field.isFocused(), field.getHighlighted(), field.getCursorPosition(),
                field.getValue().length(), rich.length(), selFrom, selTo, field.visible, isShown());
    }

    private boolean isShown() {
        return selFrom >= 0 && selTo > selFrom && selTo <= rich.length() && field.visible;
    }

    /** 바는 선택 시작 글자 위에(공간이 없으면 입력칸 아래에), 색상 선택기는 바와 겹치지 않는 쪽에 둔다. */
    private void layout() {
        int fieldX = field.getX();
        int textX = fieldX + 4 + font.width(field.getValue().substring(0, Math.min(selFrom, field.getValue().length())));
        int maxX = Math.max(fieldX, fieldX + field.getWidth() - BAR_W);
        barX = clamp(Math.max(fieldX, Math.min(textX, maxX)), 2, Math.max(2, screenW - BAR_W - 2));

        boolean above = field.getY() - BAR_H - 2 >= 2;
        barY = above ? field.getY() - BAR_H - 2 : field.getY() + field.getHeight() + 2;

        popX = clamp(barX, 2, Math.max(2, screenW - POP_W - 2));
        popY = above ? field.getY() + field.getHeight() + 2 : barY + BAR_H + 2;
        popY = clamp(popY, 2, Math.max(2, screenH - POP_H - 2));

        if (pickerOpen) {
            hexBox.setX(popX + 4 + SV + 4 + HUE_W + 6);
            hexBox.setY(popY + 22);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------
    // 서식 적용
    // ------------------------------------------------------------------

    private void commit() {
        onChange.run();
    }

    /** 기본 색 칸: 이미 그 색이면 색을 지우고, 아니면 그 색으로. */
    private void toggleColor(TextColor color) {
        boolean already = rich.all(selFrom, selTo, f -> Objects.equals(f.color(), color));
        rich.apply(selFrom, selTo, f -> f.withColor(already ? null : color));
        commit();
    }

    private void toggleFlag(RichText.Flag flag) {
        boolean already = rich.all(selFrom, selTo, f -> f.has(flag));
        rich.apply(selFrom, selTo, f -> f.with(flag, !already));
        commit();
    }

    private void clearFormat() {
        rich.apply(selFrom, selTo, RichText.Fmt::cleared);
        commit();
    }

    private static RichText.Flag flagOf(Action action) {
        return switch (action) {
            case BOLD -> RichText.Flag.BOLD;
            case ITALIC -> RichText.Flag.ITALIC;
            case UNDERLINED -> RichText.Flag.UNDERLINED;
            case STRIKETHROUGH -> RichText.Flag.STRIKETHROUGH;
            case OBFUSCATED -> RichText.Flag.OBFUSCATED;
            default -> null;
        };
    }

    private boolean isActive(Action action) {
        if (action == Action.CUSTOM) return pickerOpen;
        RichText.Flag flag = flagOf(action);
        return flag != null && rich.all(selFrom, selTo, f -> f.has(flag));
    }

    // ------------------------------------------------------------------
    // 색상 선택기
    // ------------------------------------------------------------------

    private void openPicker() {
        TextColor first = rich.at(selFrom).color();
        boolean same = rich.all(selFrom, selTo, f -> Objects.equals(f.color(), first));
        setFromRgb(same && first != null ? first.getValue() : 0xFFFFFF);
        pickerOpen = true;
        hexBox.visible = true;
        hexBox.active = true;
        hexBox.setTextColor(COL_OK);
        writeHex();
    }

    private void closePicker() {
        boolean wasOpen = pickerOpen;
        pickerOpen = false;
        dragSv = false;
        dragHue = false;
        hexBox.visible = false;
        hexBox.active = false;
        if (wasOpen) focuser.accept(field); // 선택이 그대로 남아 있는 텍스트 칸으로 되돌린다
    }

    private void applyPicked() {
        rich.apply(selFrom, selTo, f -> f.withColor(TextColor.fromRgb(currentRgb())));
        commit();
        closePicker();
    }

    private int currentRgb() {
        return hsvToRgb(hue, sat, val);
    }

    private void setFromRgb(int rgb) {
        float[] hsv = rgbToHsv(rgb);
        if (hsv[1] > 0f && hsv[2] > 0f) hue = hsv[0]; // 무채색이면 색조는 그대로 둔다
        sat = hsv[1];
        val = hsv[2];
    }

    private void writeHex() {
        updatingHex = true;
        try {
            hexBox.setValue(String.format(Locale.ROOT, "#%06X", currentRgb()));
            hexBox.setTextColor(COL_OK);
        } finally {
            updatingHex = false;
        }
    }

    private int svX() { return popX + 4; }
    private int svY() { return popY + 4; }
    private int hueX() { return popX + 4 + SV + 4; }
    private int rightX() { return popX + 4 + SV + 4 + HUE_W + 6; }
    private int buttonsY() { return popY + POP_H - 4 - 18; }

    private void setSv(double mx, double my) {
        sat = (float) Math.max(0, Math.min(1, (mx - svX()) / (SV - 1)));
        val = (float) (1 - Math.max(0, Math.min(1, (my - svY()) / (SV - 1))));
        writeHex();
    }

    private void setHue(double my) {
        hue = (float) Math.max(0, Math.min(0.999, (my - svY()) / (SV - 1)));
        writeHex();
    }

    private static int hsvToRgb(float h, float s, float v) {
        float h6 = (h - (float) Math.floor(h)) * 6f;
        int i = (int) h6;
        float f = h6 - i;
        float p = v * (1 - s);
        float q = v * (1 - s * f);
        float t = v * (1 - s * (1 - f));
        float r;
        float g;
        float b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    private static float[] rgbToHsv(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h;
        if (d == 0f) h = 0f;
        else if (max == r) h = (((g - b) / d) % 6f) / 6f;
        else if (max == g) h = (((b - r) / d) + 2f) / 6f;
        else h = (((r - g) / d) + 4f) / 6f;
        if (h < 0f) h += 1f;
        return new float[]{h, max == 0f ? 0f : d / max, max};
    }

    // ------------------------------------------------------------------
    // 입력
    // ------------------------------------------------------------------

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 바 안의 칸: 0~15 = 기본 색, 100 + n = 버튼, -1 = 없음 */
    private int hit(double mx, double my) {
        int sx = barX + PAD;
        int sy = barY + PAD;
        if (in(mx, my, sx, sy, COLS * CELL, 2 * CELL)) {
            return ((int) ((my - sy) / CELL)) * COLS + (int) ((mx - sx) / CELL);
        }
        int ax = sx + COLS * CELL + GAP;
        for (int i = 0; i < ACTIONS.length; i++) {
            if (in(mx, my, ax + i * (BTN_W + 1), sy, BTN_W, BTN_H)) return 100 + i;
        }
        return -1;
    }

    /** 이 툴바가 클릭을 처리했으면 true. (그러면 화면은 그 클릭을 더 처리하지 않아 텍스트 칸의 포커스·선택이 유지된다) */
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!isShown() || event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        double mx = event.x();
        double my = event.y();
        layout();

        if (pickerOpen) {
            if (in(mx, my, popX, popY, POP_W, POP_H)) {
                popupClicked(event, doubleClick, mx, my);
                return true;
            }
            closePicker(); // 바깥을 누르면 닫는다 (클릭은 그대로 전달)
        }

        if (!in(mx, my, barX, barY, BAR_W, BAR_H)) return false;
        int h = hit(mx, my);
        if (h >= 0 && h < COLORS.length) {
            toggleColor(COLORS[h]);
        } else if (h >= 100) {
            Action action = ACTIONS[h - 100];
            switch (action) {
                case CUSTOM -> openPicker();
                case CLEAR -> clearFormat();
                default -> toggleFlag(flagOf(action));
            }
        }
        return true; // 바의 빈틈을 눌러도 선택이 풀리지 않게
    }

    private void popupClicked(MouseButtonEvent event, boolean doubleClick, double mx, double my) {
        if (in(mx, my, svX(), svY(), SV, SV)) {
            dragSv = true;
            setSv(mx, my);
        } else if (in(mx, my, hueX(), svY(), HUE_W, SV)) {
            dragHue = true;
            setHue(my);
        } else if (in(mx, my, hexBox.getX(), hexBox.getY(), hexBox.getWidth(), hexBox.getHeight())) {
            hexBox.mouseClicked(event, doubleClick);
            focuser.accept(hexBox);
        } else if (in(mx, my, rightX(), buttonsY(), 30, 18)) {
            applyPicked();
        } else if (in(mx, my, rightX() + 32, buttonsY(), 30, 18)) {
            closePicker();
        }
    }

    public boolean mouseDragged(MouseButtonEvent event) {
        if (dragSv) {
            setSv(event.x(), event.y());
            return true;
        }
        if (dragHue) {
            setHue(event.y());
            return true;
        }
        return false;
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        boolean was = dragSv || dragHue;
        dragSv = false;
        dragHue = false;
        return was;
    }

    // ------------------------------------------------------------------
    // 그리기
    // ------------------------------------------------------------------

    private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        g.fill(x, y, x + w, y + h, COL_PANEL);
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover, boolean active) {
        g.fill(x, y, x + w, y + h, active ? COL_ACTIVE : hover ? COL_HOVER : COL_BTN);
    }

    private static Component label(Action action) {
        return switch (action) {
            case BOLD -> Component.literal("B").withStyle(ChatFormatting.BOLD);
            case ITALIC -> Component.literal("I").withStyle(ChatFormatting.ITALIC);
            case UNDERLINED -> Component.literal("U").withStyle(ChatFormatting.UNDERLINE);
            case STRIKETHROUGH -> Component.literal("S").withStyle(ChatFormatting.STRIKETHROUGH);
            case OBFUSCATED -> Component.literal("?").withStyle(ChatFormatting.OBFUSCATED);
            case CLEAR -> Component.literal("\u00D7").withStyle(ChatFormatting.RED);
            case CUSTOM -> Component.empty();
        };
    }

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (!isShown()) return;
        layout();
        int hover = hit(mouseX, mouseY);
        Component tip = null;

        // ---- 바 ----
        panel(g, barX, barY, BAR_W, BAR_H);
        int sx = barX + PAD;
        int sy = barY + PAD;
        for (int i = 0; i < COLORS.length; i++) {
            int cx = sx + (i % COLS) * CELL;
            int cy = sy + (i / COLS) * CELL;
            g.fill(cx, cy, cx + CELL, cy + CELL, 0xFF000000 | COLORS[i].getValue());
            if (isCurrentColor(i)) {
                outline(g, cx, cy, CELL, CELL, 0xFFFFFFFF);
            } else if (hover == i) {
                outline(g, cx, cy, CELL, CELL, 0xFFAAAAAA);
            }
            if (hover == i) tip = Component.literal(String.format(Locale.ROOT, "#%06X", COLORS[i].getValue()));
        }
        int ax = sx + COLS * CELL + GAP;
        for (int i = 0; i < ACTIONS.length; i++) {
            Action action = ACTIONS[i];
            int bx = ax + i * (BTN_W + 1);
            boolean over = hover == 100 + i;
            button(g, bx, sy, BTN_W, BTN_H, over, isActive(action));
            if (action == Action.CUSTOM) {
                int x0 = bx + 3;
                int y0 = sy + 4;
                g.fill(x0, y0, x0 + 6, y0 + 6, 0xFFFF5555);
                g.fill(x0 + 6, y0, x0 + 12, y0 + 6, 0xFFFFFF55);
                g.fill(x0, y0 + 6, x0 + 6, y0 + 12, 0xFF5555FF);
                g.fill(x0 + 6, y0 + 6, x0 + 12, y0 + 12, 0xFF55FF55);
            } else {
                g.centeredText(font, label(action), bx + BTN_W / 2, sy + (BTN_H - font.lineHeight) / 2 + 1, 0xFFFFFFFF);
            }
            if (over) tip = Component.translatable(action.tipKey);
        }

        // ---- 색상 선택기 ----
        if (pickerOpen) renderPicker(g, mouseX, mouseY, delta);

        // ---- 툴팁 ----
        if (tip != null) {
            int w = font.width(tip) + 6;
            int x = clamp(mouseX + 6, 2, Math.max(2, screenW - w - 2));
            int y = mouseY - 16 >= 2 ? mouseY - 16 : mouseY + 14;
            g.fill(x - 1, y - 1, x + w + 1, y + 13, 0xFF000000);
            g.fill(x, y, x + w, y + 12, 0xFF1C1C1C);
            g.text(font, tip, x + 3, y + 2, 0xFFFFFFFF, false);
        }
    }

    private boolean isCurrentColor(int index) {
        TextColor color = COLORS[index];
        return rich.all(selFrom, selTo, f -> Objects.equals(f.color(), color));
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void renderPicker(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        panel(g, popX, popY, POP_W, POP_H);
        int svX = svX();
        int svY = svY();

        // 채도(→) / 명도(↑) 사각형. 2px 칸으로 그린다.
        for (int cy = 0; cy < SV; cy += STEP) {
            float v = 1f - (cy + STEP / 2f) / SV;
            for (int cx = 0; cx < SV; cx += STEP) {
                float s = (cx + STEP / 2f) / SV;
                g.fill(svX + cx, svY + cy, svX + cx + STEP, svY + cy + STEP, 0xFF000000 | hsvToRgb(hue, s, v));
            }
        }
        // 색조 막대
        int hx = hueX();
        for (int cy = 0; cy < SV; cy += STEP) {
            g.fill(hx, svY + cy, hx + HUE_W, svY + cy + STEP, 0xFF000000 | hsvToRgb((cy + STEP / 2f) / SV, 1f, 1f));
        }

        // 표시기
        int mx = svX + Math.round(sat * (SV - 1));
        int my = svY + Math.round((1f - val) * (SV - 1));
        g.fill(mx - 3, my - 3, mx + 3, my + 3, 0xFF000000);
        g.fill(mx - 2, my - 2, mx + 2, my + 2, 0xFFFFFFFF);
        g.fill(mx - 1, my - 1, mx + 1, my + 1, 0xFF000000 | currentRgb());
        int hy = svY + Math.round(hue * (SV - 1));
        g.fill(hx - 1, hy - 1, hx + HUE_W + 1, hy + 2, 0xFF000000);
        g.fill(hx, hy, hx + HUE_W, hy + 1, 0xFFFFFFFF);

        // 오른쪽: 미리보기 / 16진수 / [적용] [취소]
        int rx = rightX();
        g.fill(rx - 1, popY + 3, rx + RIGHT_W + 1, popY + 19, 0xFF000000);
        g.fill(rx, popY + 4, rx + RIGHT_W, popY + 18, 0xFF000000 | currentRgb());
        hexBox.extractRenderState(g, mouseX, mouseY, delta);

        int by = buttonsY();
        boolean overApply = in(mouseX, mouseY, rx, by, 30, 18);
        boolean overCancel = in(mouseX, mouseY, rx + 32, by, 30, 18);
        button(g, rx, by, 30, 18, overApply, false);
        button(g, rx + 32, by, 30, 18, overCancel, false);
        g.centeredText(font, Component.translatable("dee.rich.apply"), rx + 15, by + (18 - font.lineHeight) / 2 + 1, 0xFFFFFFFF);
        g.centeredText(font, Component.translatable("dee.rich.cancel"), rx + 47, by + (18 - font.lineHeight) / 2 + 1, 0xFFFFFFFF);
    }

    // ------------------------------------------------------------------
    // 입력칸 안에서도 색이 보이게 (EditBox 포매터)
    // ------------------------------------------------------------------

    /**
     * 입력칸 글자에 색/기울임/밑줄/취소선을 입혀 보여 준다.
     * EditBox 의 포매터 API 이름이 버전마다 달라서(setFormatter / addFormatter) 리플렉션으로 붙인다.
     * 붙이지 못해도 기능은 그대로 동작하고, 입력칸 안에서만 서식이 안 보인다. (프리뷰에는 항상 보인다)
     */
    public static void attachFormatter(EditBox box, RichText rich) {
        BiFunction<String, Integer, FormattedCharSequence> format = rich::formatForEditBox;
        try {
            EditBox.class.getMethod("setFormatter", BiFunction.class).invoke(box, format);
            return;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 아래에서 addFormatter 방식을 시도한다
        }
        for (Method method : EditBox.class.getMethods()) {
            if (!method.getName().equals("addFormatter") || method.getParameterCount() != 1) continue;
            Class<?> type = method.getParameterTypes()[0];
            if (!type.isInterface() || !hasFormatMethod(type)) continue;
            InvocationHandler handler = (proxy, m, args) -> {
                if (m.getDeclaringClass() == Object.class) {
                    return switch (m.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> "RichTextFormatter";
                    };
                }
                try {
                    return format.apply((String) args[0], ((Number) args[1]).intValue());
                } catch (RuntimeException e) {
                    return FormattedCharSequence.forward((String) args[0], Style.EMPTY);
                }
            };
            Object formatter = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
            try {
                method.invoke(box, formatter);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 못 붙여도 동작에는 지장이 없다
            }
            return;
        }
    }

    /** (String, int) -> FormattedCharSequence 하나짜리 인터페이스인지 */
    private static boolean hasFormatMethod(Class<?> type) {
        int abstractMethods = 0;
        boolean matches = false;
        for (Method m : type.getMethods()) {
            if (!java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
            abstractMethods++;
            Class<?>[] p = m.getParameterTypes();
            matches = p.length == 2 && p[0] == String.class && p[1] == int.class
                    && m.getReturnType() == FormattedCharSequence.class;
        }
        return abstractMethods == 1 && matches;
    }
}