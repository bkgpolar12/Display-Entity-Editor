package com.dee.client;

import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 일반(비고급) 텍스트 입력칸용 서식 모델.
 * 입력칸에 보이는 문자열(줄바꿈은 "\n" 두 글자)과 글자마다 하나씩 대응하는 서식 목록을 같이 들고 있다.
 * 글자를 고치면 {@link #onTextChanged} 가 서식 목록을 맞춰 주고, {@link #toTag} 로 text NBT 를 만든다.
 *
 * - 색 / 볼드 / 기울임 / 밑줄 / 취소선 / 난독화: 툴바로 편집한다.
 * - 폰트 · 그림자색 · 클릭/호버 이벤트 · insertion: 건드리지 않고 그대로 보존한다. ({@link Fmt#extra})
 * - translate / keybind / score / selector / nbt 처럼 내용이 평범한 문자열이 아닌 부분: "잠긴 글자"({@link #LOCK}) 한 글자로 보여 주고,
 *   원래 컴포넌트는 그대로 들고 있다가 저장할 때 되돌려 넣는다. 일반 모드에서는 수정할 수 없고(고급에서만), 지우거나 잘라내 붙여넣어 옮길 수는 있다.
 */
public final class RichText {
    /**
     * 잠긴 부분을 입력칸에서 대신 보여 주는 글자. 🔒(U+1F512)는 마인크래프트 기본 폰트가 그리지 못하는 영역이라
     * 기본 폰트(유니폰트)가 그리는 BMP 글자를 쓴다. 마음에 안 들면 여기만 바꾸면 된다.
     */
    public static final char LOCK = '\u26BF';

    public enum Flag { BOLD, ITALIC, UNDERLINED, STRIKETHROUGH, OBFUSCATED }

    /**
     * 글자 하나의 서식. color 가 null 이면 기본색.
     * extra: 툴바가 다루지 않는 나머지 스타일(font, shadow_color, 이벤트, insertion)을 그대로 보관한 것.
     * lock: null 이 아니면 이 글자는 "잠긴 글자"이고, 저장할 때 이 컴포넌트(상속받은 스타일이 이미 합쳐져 있음)로 대체된다.
     */
    public record Fmt(TextColor color, boolean bold, boolean italic, boolean underlined,
                      boolean strikethrough, boolean obfuscated, Style extra, Component lock) {
        public static final Fmt NONE = new Fmt(null, false, false, false, false, false, Style.EMPTY, null);

        static Fmt locked(Component component) {
            return new Fmt(null, false, false, false, false, false, Style.EMPTY, component);
        }

        public boolean isLocked() {
            return lock != null;
        }

        /** 툴바가 다루는 서식(색/볼드/기울임/밑줄/취소선/난독화)만 지운다. extra 는 남긴다. */
        public Fmt cleared() {
            return new Fmt(null, false, false, false, false, false, extra, lock);
        }

        public boolean has(Flag flag) {
            return switch (flag) {
                case BOLD -> bold;
                case ITALIC -> italic;
                case UNDERLINED -> underlined;
                case STRIKETHROUGH -> strikethrough;
                case OBFUSCATED -> obfuscated;
            };
        }

        public Fmt with(Flag flag, boolean on) {
            return switch (flag) {
                case BOLD -> new Fmt(color, on, italic, underlined, strikethrough, obfuscated, extra, lock);
                case ITALIC -> new Fmt(color, bold, on, underlined, strikethrough, obfuscated, extra, lock);
                case UNDERLINED -> new Fmt(color, bold, italic, on, strikethrough, obfuscated, extra, lock);
                case STRIKETHROUGH -> new Fmt(color, bold, italic, underlined, on, obfuscated, extra, lock);
                case OBFUSCATED -> new Fmt(color, bold, italic, underlined, strikethrough, on, extra, lock);
            };
        }

        public Fmt withColor(TextColor newColor) {
            return new Fmt(newColor, bold, italic, underlined, strikethrough, obfuscated, extra, lock);
        }

        /** text NBT 에 들어갈 스타일. (꺼진 항목은 아예 넣지 않는다) */
        Style style() {
            Style s = extra;
            if (color != null) s = s.withColor(color);
            if (bold) s = s.withBold(true);
            if (italic) s = s.withItalic(true);
            if (underlined) s = s.withUnderlined(true);
            if (strikethrough) s = s.withStrikethrough(true);
            if (obfuscated) s = s.withObfuscated(true);
            return s;
        }

        /**
         * 입력칸 안에서 보여 줄 스타일. 볼드/난독화는 글자 폭이 달라져 커서·선택 영역이 어긋나므로 뺀다.
         * (프리뷰에는 전부 반영된다) 잠긴 글자는 금색.
         */
        Style editorStyle() {
            if (lock != null) return Style.EMPTY.withColor(0xFFAA00);
            Style s = Style.EMPTY;
            if (color != null) s = s.withColor(color);
            if (italic) s = s.withItalic(true);
            if (underlined) s = s.withUnderlined(true);
            if (strikethrough) s = s.withStrikethrough(true);
            return s;
        }
    }

    /** 입력칸에 보이는 문자열 (줄바꿈 = 백슬래시 + n, 잠긴 부분 = LOCK 한 글자) */
    private final StringBuilder text = new StringBuilder();
    /** text 의 글자마다 하나씩 */
    private final List<Fmt> fmts = new ArrayList<>();
    /** 가장 최근에 지워진 잠긴 컴포넌트들. 잘라내기 → 붙여넣기로 옮길 때 붙여넣은 LOCK 글자에 다시 연결한다. */
    private final LinkedList<Component> stash = new LinkedList<>();

    private RichText() {}

    public static RichText plain(String shown) {
        RichText out = new RichText();
        out.text.append(shown);
        out.fmts.addAll(Collections.nCopies(shown.length(), Fmt.NONE));
        return out;
    }

    /** text NBT 를 모델로 읽는다. null 이면 빈 텍스트. 컴포넌트로 읽을 수 없는 값이면 empty. */
    public static Optional<RichText> fromTag(Tag tag) {
        if (tag == null) return Optional.of(new RichText());
        Optional<Component> parsed = ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, tag).result();
        if (parsed.isEmpty()) return Optional.empty();
        RichText out = new RichText();
        out.walk(parsed.get(), Style.EMPTY);
        return Optional.of(out);
    }

    private void walk(Component component, Style parent) {
        Style effective = component.getStyle().applyTo(parent);

        // 평범한 문자열이 아닌 부분(translate, keybind, score ...)은 통째로 잠긴 글자 하나로 둔다.
        // 부모에게서 상속받던 스타일은 미리 합쳐 두므로 나중에 어디에 넣어도 똑같이 보인다.
        if (!(component.getContents() instanceof PlainTextContents plain)) {
            MutableComponent baked = component.copy();
            baked.setStyle(effective);
            text.append(LOCK);
            fmts.add(Fmt.locked(baked));
            return;
        }

        // 툴바가 다루는 6가지를 뺀 나머지 스타일(font, shadow_color, 이벤트 등)은 그대로 들고 간다.
        Style rest = effective.withColor((TextColor) null).withBold(null).withItalic(null)
                .withUnderlined(null).withStrikethrough(null).withObfuscated(null);
        Fmt fmt = new Fmt(effective.getColor(), effective.isBold(), effective.isItalic(),
                effective.isUnderlined(), effective.isStrikethrough(), effective.isObfuscated(), rest, null);
        for (char c : plain.text().toCharArray()) {
            if (c == '\n') {
                text.append("\\n");
                fmts.add(fmt);
                fmts.add(fmt);
            } else {
                text.append(c);
                fmts.add(fmt);
            }
        }
        for (Component sibling : component.getSiblings()) {
            walk(sibling, effective);
        }
    }

    // ---- 조회 ----

    public String text() {
        return text.toString();
    }

    public int length() {
        return text.length();
    }

    public Fmt at(int index) {
        return index >= 0 && index < fmts.size() ? fmts.get(index) : Fmt.NONE;
    }

    public boolean hasLocks() {
        for (Fmt f : fmts) if (f.isLocked()) return true;
        return false;
    }

    /** 잠긴 부분마다 "⚿ {translate:"key"}" 같은 한 줄 설명. (툴팁용) */
    public List<String> lockSummaries() {
        List<String> out = new ArrayList<>();
        for (Fmt f : fmts) {
            if (!f.isLocked()) continue;
            String snbt = ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, f.lock()).result()
                    .map(Tag::toString).orElse("?");
            if (snbt.length() > 60) snbt = snbt.substring(0, 60) + "…";
            out.add(LOCK + " " + snbt);
        }
        return out;
    }

    /** [from, to) 의 (잠기지 않은) 글자가 모두 조건을 만족하는지. 잠긴 글자는 건너뛰고, 대상이 하나도 없으면 false. */
    public boolean all(int from, int to, Predicate<Fmt> predicate) {
        int a = Math.max(0, from);
        int b = Math.min(fmts.size(), to);
        boolean any = false;
        for (int i = a; i < b; i++) {
            Fmt f = fmts.get(i);
            if (f.isLocked()) continue;
            any = true;
            if (!predicate.test(f)) return false;
        }
        return any;
    }

    // ---- 수정 ----

    /** [from, to) 의 서식을 바꾼다. 잠긴 글자는 건드리지 않는다. */
    public void apply(int from, int to, UnaryOperator<Fmt> change) {
        int a = Math.max(0, from);
        int b = Math.min(fmts.size(), to);
        for (int i = a; i < b; i++) {
            Fmt f = fmts.get(i);
            if (!f.isLocked()) fmts.set(i, change.apply(f));
        }
    }

    /**
     * 입력칸의 글자가 바뀌었을 때 서식 목록을 맞춘다.
     * 앞/뒤에서 같은 부분을 빼고 남은 가운데가 바뀐 부분이다.
     * 새로 들어간 글자는 바뀐 자리 바로 앞 글자의 서식을 이어받는다. (맨 앞이면 뒤 글자, 바꿔 쓴 거면 지워진 첫 글자)
     * 잠긴 글자는 서식을 이어주지 않는다. 지워진 잠긴 글자는 보관해 두었다가, 새로 들어온 LOCK 글자(붙여넣기)에 순서대로 다시 연결한다.
     */
    public void onTextChanged(String now) {
        String old = text.toString();
        if (old.equals(now)) return;

        int max = Math.min(old.length(), now.length());
        int prefix = 0;
        while (prefix < max && old.charAt(prefix) == now.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < max - prefix
                && old.charAt(old.length() - 1 - suffix) == now.charAt(now.length() - 1 - suffix)) suffix++;

        int removedEnd = old.length() - suffix;
        int insertedEnd = now.length() - suffix;

        Fmt inherit = Fmt.NONE;
        if (removedEnd > prefix && !fmts.get(prefix).isLocked()) inherit = fmts.get(prefix);
        else if (prefix > 0 && !fmts.get(prefix - 1).isLocked()) inherit = fmts.get(prefix - 1);
        else if (removedEnd < fmts.size() && !fmts.get(removedEnd).isLocked()) inherit = fmts.get(removedEnd);

        List<Component> removedLocks = new ArrayList<>();
        for (int i = prefix; i < removedEnd; i++) {
            if (fmts.get(i).isLocked()) removedLocks.add(fmts.get(i).lock());
        }
        if (!removedLocks.isEmpty()) {
            stash.clear();
            stash.addAll(removedLocks);
        }

        List<Fmt> inserted = new ArrayList<>();
        for (int i = prefix; i < insertedEnd; i++) {
            if (now.charAt(i) == LOCK && !stash.isEmpty()) inserted.add(Fmt.locked(stash.removeFirst()));
            else inserted.add(inherit);
        }

        text.replace(prefix, removedEnd, now.substring(prefix, insertedEnd));
        fmts.subList(prefix, removedEnd).clear();
        fmts.addAll(prefix, inserted);
    }

    // ---- 변환 ----

    /** 같은 서식끼리 묶은 한 덩어리. 줄바꿈은 실제 "\n" 으로 바꿔 둔다. 잠긴 글자는 늘 혼자 한 덩어리(text 는 비어 있음). */
    private record Run(StringBuilder text, Fmt fmt) {
        Component toComponent() {
            if (fmt.isLocked()) return fmt.lock();
            return Component.literal(text.toString()).withStyle(fmt.style());
        }
    }

    private List<Run> runs() {
        List<Run> runs = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            Fmt fmt = fmts.get(i);
            if (fmt.isLocked()) {
                runs.add(new Run(new StringBuilder(), fmt));
                i++;
                continue;
            }
            char c = text.charAt(i);
            String piece;
            int len;
            if (c == '\\' && i + 1 < n && text.charAt(i + 1) == 'n') {
                piece = "\n";
                len = 2;
            } else {
                piece = String.valueOf(c);
                len = 1;
            }
            Run last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (last != null && !last.fmt().isLocked() && last.fmt().equals(fmt)) last.text().append(piece);
            else runs.add(new Run(new StringBuilder(piece), fmt));
            i += len;
        }
        return runs;
    }

    /** 서식이 없으면 그냥 문자열 컴포넌트, 한 덩어리면 그 덩어리 하나, 여럿이면 빈 루트 + 덩어리들(extra). */
    public Component toComponent() {
        List<Run> runs = runs();
        if (runs.isEmpty()) return Component.literal("");
        if (runs.size() == 1) return runs.get(0).toComponent();
        MutableComponent root = Component.empty();
        for (Run run : runs) root.append(run.toComponent());
        return root;
    }

    /** text NBT 로. 서식이 전혀 없으면 예전처럼 StringTag 가 된다. */
    public Tag toTag() {
        Component component = toComponent();
        return ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, component).result()
                .orElseGet(() -> StringTag.valueOf(component.getString()));
    }

    /** EditBox 의 포매터용. shown 은 입력칸에 실제로 보이는 부분이고 offset 은 그 부분이 전체 문자열에서 시작하는 위치. */
    public FormattedCharSequence formatForEditBox(String shown, int offset) {
        List<FormattedCharSequence> parts = new ArrayList<>();
        int i = 0;
        while (i < shown.length()) {
            Fmt fmt = at(offset + i);
            int j = i + 1;
            while (j < shown.length() && at(offset + j).equals(fmt)) j++;
            parts.add(FormattedCharSequence.forward(shown.substring(i, j), fmt.editorStyle()));
            i = j;
        }
        return FormattedCharSequence.composite(parts);
    }
}