package com.dee.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtString;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * text_display 편집 화면.
 * 위: 프리뷰(고정) / 가운데: 스크롤 박스(텍스트 + 접이식 섹션들) / 아래: [고급 NBT] [적용] [삭제] [취소](고정)
 * 모든 입력은 즉시 Draft 에 저장되므로 init() 이 다시 불려도 값이 유지된다.
 * 섹션 접힘 상태와 스크롤 위치도 Draft 에 있어서 화면을 다시 만들어도 그대로 유지된다.
 */
public class TextDisplayScreen extends Screen {
    private static final int ROW_H = 20;
    private static final int GAP = 4;
    private static final int PITCH = ROW_H + GAP;
    private static final int ADV_W = 36;
    private static final int POS_LABEL_W = 12;

    /** 스크롤 박스 안쪽 여백 / 오른쪽 스크롤바용 공간 / 휠 한 칸당 이동 px */
    private static final int PAD = 4;
    private static final int SCROLL_RESERVE = 10;
    private static final int SCROLL_STEP = 20;

    /** Draft 의 섹션 번호 */
    private static final int SECTION_STYLE = 0;
    private static final int SECTION_SCALE = 1;
    private static final int SECTION_POSITION = 2;

    private static final int COLOR_OK = 0xFFE0E0E0;
    private static final int COLOR_ERR = 0xFFFF5555;
    private static final int COLOR_STYLED = 0xFFFFD27F;

    /** 값 이름은 lang 파일의 dee.align.<값>, dee.billboard.<값> 키에서 가져온다. */
    private static final String[] ALIGN = {"center", "left", "right"};
    private static final String[] BILLBOARD = {"fixed", "vertical", "horizontal", "center"};

    /** contentY 는 스크롤 박스 내용 기준 y (스크롤 0 일 때의 위치) */
    private record Label(Text text, int x, int contentY) {}

    private record Entry(ClickableWidget widget, int contentY) {}

    private final Draft draft;
    private final List<Label> labels = new ArrayList<>();
    /** 스크롤 박스 안에 있는 위젯들. 화면에 자동으로 그려지지 않고 render() 에서 직접 그린다. */
    private final List<Entry> entries = new ArrayList<>();
    private final Set<Object> scrollWidgets = Collections.newSetFromMap(new IdentityHashMap<>());

    /** 스크롤 박스 / 프리뷰 좌표. init() 에서 계산한다. */
    private int boxL;
    private int boxR;
    private int boxW;
    private int boxTop;
    private int boxBottom;
    private int previewTop;
    private int previewBottom;
    /** 스크롤 박스 내용 전체 높이 */
    private int contentH;

    private int left;
    /** 라벨 글자 너비에 맞춰 init() 에서 계산한다. (언어마다 길이가 다르므로) */
    private int labelW;
    private int scaleLabelW;
    private int rotLabelW;
    private int contentW;

    private TextFieldWidget textField;
    /** 삭제 버튼을 한 번 눌러 확인 대기 중인지 */
    private boolean deleteArmed;
    private boolean draggingBar;

    public TextDisplayScreen(Draft draft) {
        super(Text.translatable(draft.isCreating() ? "dee.screen.title_new" : "dee.screen.title"));
        this.draft = draft;
    }

    @Override
    protected void init() {
        labels.clear();
        entries.clear();
        scrollWidgets.clear();

        labelW = Math.max(40, widest("dee.label.text", "dee.label.text_nbt", "dee.label.line_width",
                "dee.label.background", "dee.label.glow") + 6);
        rotLabelW = Math.max(20, widest("dee.label.yaw", "dee.label.pitch") + 6);
        scaleLabelW = Math.max(36, textRenderer.getWidth(Text.translatable("dee.label.scale", "X")) + 4);

        // ---- 고정 영역 계산 ----
        boxW = Math.min(width - 10, 372);
        boxL = (width - boxW) / 2;
        boxR = boxL + boxW;
        left = boxL + PAD;
        contentW = boxW - PAD - SCROLL_RESERVE;
        int colW = (contentW - GAP) / 2;
        int rightX = left + colW + GAP;
        int thirdW = (contentW - 2 * GAP) / 3;
        int quarterW = (contentW - 3 * GAP) / 4;

        int bottomY = height - 6 - ROW_H;
        previewTop = 20;
        int avail = bottomY - GAP - previewTop - GAP; // 프리뷰 + 스크롤 박스가 나눠 쓰는 높이
        previewBottom = previewTop + Math.max(24, Math.min(100, avail * 2 / 5));
        boxTop = previewBottom + GAP;
        boxBottom = bottomY - GAP;

        // ---- 스크롤 박스 내용 ----
        int cy = 0;

        // 텍스트 [고급]
        boolean advanced = draft.isAdvancedText();
        addLabel(Text.translatable(advanced ? "dee.label.text_nbt" : "dee.label.text"), left, cy);
        textField = addScroll(new TextFieldWidget(textRenderer, left + labelW, 0,
                contentW - labelW - GAP - ADV_W, ROW_H, Text.empty()), cy);
        if (advanced) {
            // 고급: text NBT 를 SNBT 그대로 편집한다. 예) {translate:"my.key"}, {text:"a",color:"red"}
            textField.setMaxLength(4096);
            NbtElement element = draft.get("text");
            textField.setText(element == null ? "" : element.toString());
            textField.setPlaceholder(Text.literal("{translate:\"key\"}"));
            textField.setChangedListener(this::onAdvancedTextEdit);
        } else {
            textField.setMaxLength(1024);
            textField.setText(currentText().getString().replace("\n", "\\n"));
            if (draft.get("text") != null && !(draft.get("text") instanceof NbtString)) {
                textField.setEditableColor(COLOR_STYLED);
                textField.setTooltip(Tooltip.of(Text.translatable("dee.tooltip.styled_text")));
            }
            textField.setChangedListener(s -> {
                textField.setEditableColor(COLOR_OK);
                setPlainText(s);
            });
        }
        setInitialFocus(textField);

        addScroll(ButtonWidget.builder(advancedLabel(), b -> {
                    draft.setAdvancedText(!draft.isAdvancedText());
                    clearAndInit(); // 입력칸을 모드에 맞게 다시 만든다 (값은 Draft 에 있으므로 유지됨)
                })
                .dimensions(left + contentW - ADV_W, 0, ADV_W, ROW_H)
                .tooltip(Tooltip.of(Text.translatable("dee.tooltip.advanced")))
                .build(), cy);
        cy += PITCH;

        // ▼ 스타일
        cy = addSection(SECTION_STYLE, "dee.section.style", cy);
        if (draft.isSectionOpen(SECTION_STYLE)) {
            // 줄 너비 | 불투명도
            addLabel(Text.translatable("dee.label.line_width"), left, cy);
            TextFieldWidget lineWidth = addScroll(new TextFieldWidget(textRenderer,
                    left + labelW, 0, colW - labelW, ROW_H, Text.empty()), cy);
            lineWidth.setTextPredicate(s -> s.matches("\\d{0,5}"));
            lineWidth.setText(String.valueOf(Math.max(1, draft.getInt("line_width", 200))));
            lineWidth.setChangedListener(s -> {
                if (!s.isEmpty()) draft.setInt("line_width", Math.max(1, Integer.parseInt(s)));
            });
            addScroll(new OpacitySlider(rightX, 0, colW, ROW_H), cy);
            cy += PITCH;

            // 정렬 | 빌보드
            addScroll(cycleButton(left, colW, "dee.option.alignment", "alignment", "center", ALIGN, "dee.align."), cy);
            addScroll(cycleButton(rightX, colW, "dee.option.billboard", "billboard", "fixed", BILLBOARD, "dee.billboard."), cy);
            cy += PITCH;

            // 그림자 | 투시 | 기본 배경
            addScroll(toggleButton(left, thirdW, "dee.option.shadow", "shadow"), cy);
            addScroll(toggleButton(left + thirdW + GAP, thirdW, "dee.option.see_through", "see_through"), cy);
            addScroll(toggleButton(left + 2 * (thirdW + GAP), thirdW, "dee.option.default_background", "default_background"), cy);
            cy += PITCH;

            // 배경색 | 발광색
            addLabel(Text.translatable("dee.label.background"), left, cy);
            addHexField(left + labelW, cy, colW - labelW, "background", true);
            addLabel(Text.translatable("dee.label.glow"), rightX, cy);
            addHexField(rightX + labelW, cy, colW - labelW, "glow_color_override", false);
            cy += PITCH;
        }

        // ▶ 크기
        cy = addSection(SECTION_SCALE, "dee.section.scale", cy);
        if (draft.isSectionOpen(SECTION_SCALE)) {
            float[] scale = draft.getScale();
            for (int i = 0; i < 3; i++) {
                int cx = left + i * (thirdW + GAP);
                addLabel(Text.translatable("dee.label.scale", String.valueOf("XYZ".charAt(i))), cx, cy);
                TextFieldWidget f = addScroll(new TextFieldWidget(textRenderer,
                        cx + scaleLabelW, 0, thirdW - scaleLabelW, ROW_H, Text.empty()), cy);
                f.setTextPredicate(s -> s.matches("-?\\d{0,3}(\\.\\d{0,4})?"));
                f.setText(formatFloat(scale[i]));
                final int axis = i;
                f.setChangedListener(s -> {
                    try {
                        float v = Float.parseFloat(s);
                        float[] cur = draft.getScale();
                        cur[axis] = v;
                        draft.setScale(cur[0], cur[1], cur[2]);
                    } catch (NumberFormatException ignored) {
                        // "-", "." 처럼 아직 완성되지 않은 입력은 무시
                    }
                });
            }
            cy += PITCH;
        }

        // ▼ 위치·방향
        cy = addSection(SECTION_POSITION, "dee.section.position", cy);
        if (draft.isSectionOpen(SECTION_POSITION)) {
            // 위치 X / Y / Z + [위치 지정]
            double[] pos = draft.getPos();
            for (int i = 0; i < 3; i++) {
                int cx = left + i * (quarterW + GAP);
                addLabel(Text.literal("XYZ".substring(i, i + 1)), cx, cy);
                TextFieldWidget f = addScroll(new TextFieldWidget(textRenderer,
                        cx + POS_LABEL_W, 0, quarterW - POS_LABEL_W, ROW_H, Text.empty()), cy);
                f.setTextPredicate(s -> s.matches("-?\\d{0,8}(\\.\\d{0,4})?"));
                f.setText(formatDouble(pos[i]));
                final int axis = i;
                f.setChangedListener(s -> {
                    try {
                        draft.setPosAxis(axis, Double.parseDouble(s));
                    } catch (NumberFormatException ignored) {
                        // "-" 처럼 아직 완성되지 않은 입력은 무시
                    }
                });
            }
            addScroll(ButtonWidget.builder(Text.translatable("dee.button.place"),
                            b -> DisplayEditClient.beginPlacement(client, draft))
                    .dimensions(left + 3 * (quarterW + GAP), 0, quarterW, ROW_H)
                    .tooltip(Tooltip.of(Text.translatable("dee.tooltip.place")))
                    .build(), cy);
            cy += PITCH;

            // 방향 Yaw | Pitch
            float[] rot = draft.getRot();
            String[] rotLabels = {"dee.label.yaw", "dee.label.pitch"};
            for (int i = 0; i < 2; i++) {
                int cx = i == 0 ? left : rightX;
                addLabel(Text.translatable(rotLabels[i]), cx, cy);
                TextFieldWidget f = addScroll(new TextFieldWidget(textRenderer,
                        cx + rotLabelW, 0, colW - rotLabelW, ROW_H, Text.empty()), cy);
                f.setTextPredicate(s -> s.matches("-?\\d{0,4}(\\.\\d{0,3})?"));
                f.setText(formatDouble(rot[i]));
                final int axis = i;
                f.setChangedListener(s -> {
                    try {
                        draft.setRotAxis(axis, Float.parseFloat(s));
                    } catch (NumberFormatException ignored) {
                        // "-" 처럼 아직 완성되지 않은 입력은 무시
                    }
                });
            }
            cy += PITCH;
        }

        contentH = Math.max(0, cy - GAP);
        draft.setScroll(Math.max(0, Math.min(draft.getScroll(), maxScroll())));
        relayout();

        // ---- 고정 버튼: [고급 NBT…] [적용/생성] [삭제] [취소] ----
        int btnW = (boxW - 3 * GAP) / 4;
        addDrawableChild(ButtonWidget.builder(Text.translatable("dee.button.advanced_nbt"),
                        b -> client.setScreen(new AdvancedNbtScreen(draft)))
                .dimensions(boxL, bottomY, btnW, ROW_H).build());
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable(draft.isCreating() ? "dee.button.create" : "dee.button.apply"), b -> apply())
                .dimensions(boxL + (btnW + GAP), bottomY, btnW, ROW_H).build());
        ButtonWidget deleteButton = addDrawableChild(ButtonWidget.builder(deleteLabel(), b -> {
                    if (!deleteArmed) {
                        // 첫 클릭: 확인 상태로 바꾼다. 한 번 더 눌러야 삭제된다.
                        deleteArmed = true;
                        b.setMessage(deleteLabel());
                    } else {
                        deleteEntity();
                    }
                })
                .dimensions(boxL + 2 * (btnW + GAP), bottomY, btnW, ROW_H)
                .tooltip(Tooltip.of(Text.translatable("dee.tooltip.delete")))
                .build());
        deleteButton.active = !draft.isCreating(); // 아직 없는 엔티티는 지울 수 없다
        addDrawableChild(ButtonWidget.builder(Text.translatable("dee.button.cancel"), b -> close())
                .dimensions(boxL + 3 * (btnW + GAP), bottomY, btnW, ROW_H).build());
    }

    private Text deleteLabel() {
        return Text.translatable(deleteArmed ? "dee.button.delete_confirm" : "dee.button.delete")
                .formatted(Formatting.RED);
    }

    /** /kill 명령으로 엔티티를 지운다. (OP 권한 필요) 편집 중이던 변경 사항은 버려진다. */
    private void deleteEntity() {
        var handler = client.getNetworkHandler();
        if (handler != null) {
            handler.sendChatCommand("kill " + draft.uuid());
        }
        close();
    }

    private void apply() {
        var handler = client.getNetworkHandler();
        if (handler != null) {
            for (String command : draft.buildCommands()) {
                handler.sendChatCommand(command);
            }
        }
        close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------
    // 스크롤 박스
    // ------------------------------------------------------------------

    /** 접기/펼치기 머리줄. 누르면 Draft 에 상태를 저장하고 화면을 다시 만든다. */
    private int addSection(int index, String titleKey, int cy) {
        boolean open = draft.isSectionOpen(index);
        Text title = Text.literal(open ? "▼ " : "▶ ").append(Text.translatable(titleKey));
        addScroll(ButtonWidget.builder(title, b -> {
                    draft.toggleSection(index);
                    clearAndInit();
                })
                .dimensions(left, 0, contentW, ROW_H).build(), cy);
        return cy + PITCH;
    }

    /** 스크롤 박스에 위젯을 넣는다. y 는 내용 기준 위치(contentY)이고 실제 y 는 relayout() 이 정한다. */
    private <T extends ClickableWidget> T addScroll(T widget, int contentY) {
        entries.add(new Entry(widget, contentY));
        scrollWidgets.add(widget);
        return addSelectableChild(widget);
    }

    private int maxScroll() {
        return Math.max(0, contentH + 2 * PAD - (boxBottom - boxTop));
    }

    private int originY() {
        return boxTop + PAD - (int) Math.round(draft.getScroll());
    }

    /** 스크롤 위치에 맞춰 위젯 y 를 옮기고, 박스 밖으로 완전히 나간 위젯은 숨긴다(클릭도 막힘). */
    private void relayout() {
        int origin = originY();
        for (Entry e : entries) {
            ClickableWidget w = e.widget();
            int y = origin + e.contentY();
            w.setY(y);
            boolean visible = y + w.getHeight() > boxTop && y < boxBottom;
            w.visible = visible;
            w.active = visible;
        }
    }

    private void scrollTo(double value) {
        draft.setScroll(Math.max(0, Math.min(maxScroll(), value)));
        relayout();
    }

    private boolean inBox(double x, double y) {
        return x >= boxL && x < boxR && y >= boxTop && y < boxBottom;
    }

    private int thumbHeight() {
        int boxH = boxBottom - boxTop;
        return Math.max(16, boxH * boxH / (contentH + 2 * PAD));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (inBox(mouseX, mouseY) && maxScroll() > 0) {
            scrollTo(draft.getScroll() - verticalAmount * SCROLL_STEP);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 스크롤바 잡기
        if (button == 0 && maxScroll() > 0 && inBox(mouseX, mouseY) && mouseX >= boxR - SCROLL_RESERVE + 1) {
            draggingBar = true;
            dragBarTo(mouseY);
            return true;
        }
        // 박스 밖 클릭은 (일부만 보이는) 스크롤 위젯으로 넘기지 않는다
        if (!inBox(mouseX, mouseY)) {
            for (Element child : children()) {
                if (!scrollWidgets.contains(child) && child.mouseClicked(mouseX, mouseY, button)) {
                    setFocused(child);
                    if (button == 0) setDragging(true);
                    return true;
                }
            }
            setFocused(null);
            return false;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (draggingBar) {
            dragBarTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingBar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void dragBarTo(double mouseY) {
        int boxH = boxBottom - boxTop;
        int thumbH = thumbHeight();
        double ratio = (mouseY - boxTop - thumbH / 2.0) / Math.max(1, boxH - thumbH);
        scrollTo(ratio * maxScroll());
    }

    // ------------------------------------------------------------------
    // 위젯 헬퍼
    // ------------------------------------------------------------------

    private void addLabel(Text text, int x, int contentY) {
        labels.add(new Label(text, x, contentY));
    }

    /** 주어진 번역 키들 중 가장 넓은 글자 너비(px) */
    private int widest(String... keys) {
        int w = 0;
        for (String key : keys) w = Math.max(w, textRenderer.getWidth(Text.translatable(key)));
        return w;
    }

    private ButtonWidget cycleButton(int x, int w, String titleKey, String key, String fallback,
                                     String[] values, String valuePrefix) {
        return ButtonWidget.builder(cycleLabel(titleKey, key, fallback, values, valuePrefix), b -> {
            int next = (indexOf(values, draft.getString(key, fallback)) + 1) % values.length;
            draft.setString(key, values[next]);
            b.setMessage(cycleLabel(titleKey, key, fallback, values, valuePrefix));
        }).dimensions(x, 0, w, ROW_H).build();
    }

    private Text cycleLabel(String titleKey, String key, String fallback, String[] values, String valuePrefix) {
        String current = draft.getString(key, fallback);
        Text value = indexOf(values, current) >= 0
                ? Text.translatable(valuePrefix + current)
                : Text.literal(current);
        return Text.translatable("dee.option.entry", Text.translatable(titleKey), value);
    }

    private ButtonWidget toggleButton(int x, int w, String titleKey, String key) {
        return ButtonWidget.builder(toggleLabel(titleKey, key), b -> {
            draft.setBool(key, !draft.getBool(key, false));
            b.setMessage(toggleLabel(titleKey, key));
        }).dimensions(x, 0, w, ROW_H).build();
    }

    private Text toggleLabel(String titleKey, String key) {
        return Text.translatable("dee.option.entry", Text.translatable(titleKey),
                Text.translatable(draft.getBool(key, false) ? "dee.state.on" : "dee.state.off"));
    }

    /** #RRGGBB / #AARRGGBB 입력칸. 비우면 키를 삭제(=기본값)한다. */
    private void addHexField(int x, int contentY, int w, String key, boolean withAlpha) {
        TextFieldWidget f = addScroll(new TextFieldWidget(textRenderer, x, 0, w, ROW_H, Text.empty()), contentY);
        f.setMaxLength(9);
        f.setTextPredicate(s -> s.matches("#?[0-9a-fA-F]{0,8}"));
        f.setPlaceholder(Text.literal(withAlpha ? "#AARRGGBB" : "#RRGGBB"));
        if (draft.has(key)) {
            int v = draft.getInt(key, 0);
            f.setText(withAlpha ? String.format(Locale.ROOT, "#%08X", v)
                    : String.format(Locale.ROOT, "#%06X", v & 0xFFFFFF));
        }
        f.setChangedListener(s -> {
            if (s.isEmpty()) {
                draft.remove(key);
                f.setEditableColor(COLOR_OK);
                return;
            }
            String hex = s.startsWith("#") ? s.substring(1) : s;
            boolean ok = hex.length() == 6 || hex.length() == 8;
            if (ok) {
                long v = Long.parseLong(hex, 16);
                if (withAlpha && hex.length() == 6) v |= 0xFF000000L;
                draft.setInt(key, (int) v);
            }
            f.setEditableColor(ok ? COLOR_OK : COLOR_ERR);
        });
    }

    private final class OpacitySlider extends SliderWidget {
        OpacitySlider(int x, int y, int w, int h) {
            super(x, y, w, h, Text.empty(), draft.getUnsignedByte("text_opacity", 255) / 255.0);
            updateMessage();
        }

        private int opacity() {
            return (int) Math.round(value * 255);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.translatable("dee.option.opacity", opacity()));
        }

        @Override
        protected void applyValue() {
            draft.setUnsignedByte("text_opacity", opacity());
        }
    }

    private Text advancedLabel() {
        Text label = Text.translatable("dee.button.advanced");
        return draft.isAdvancedText() ? label.copy().formatted(Formatting.YELLOW) : label;
    }

    /** 고급 모드 입력: SNBT 로 파싱되고 텍스트 컴포넌트로도 유효할 때만 저장한다. 비우면 빈 텍스트. */
    private void onAdvancedTextEdit(String s) {
        if (s.isBlank()) {
            draft.set("text", NbtString.of(""));
            textField.setEditableColor(COLOR_OK);
            return;
        }
        var parsed = Draft.parseSnbt(s);
        boolean ok = parsed.isPresent()
                && TextCodecs.CODEC.parse(NbtOps.INSTANCE, parsed.get()).result().isPresent();
        if (ok) draft.set("text", parsed.get());
        textField.setEditableColor(ok ? COLOR_OK : COLOR_ERR);
    }

    // ------------------------------------------------------------------
    // 텍스트 변환
    // ------------------------------------------------------------------

    private Text currentText() {
        NbtElement element = draft.get("text");
        if (element == null) return Text.empty();
        return TextCodecs.CODEC.parse(NbtOps.INSTANCE, element).result()
                .orElseGet(() -> Text.literal(element.toString()));
    }

    /** 입력칸의 한 줄 문자열을 text NBT 로 저장. "\n" 을 입력하면 줄바꿈이 된다. */
    private void setPlainText(String raw) {
        String s = raw.replace("\\n", "\n");
        NbtElement element = TextCodecs.CODEC.encodeStart(NbtOps.INSTANCE, Text.literal(s)).result()
                .orElseGet(() -> NbtString.of(s));
        draft.set("text", element);
    }

    // ------------------------------------------------------------------
    // 렌더링
    // ------------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 고정 위젯(하단 버튼)과 배경
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 6, 0xFFFFFFFF);
        drawPreview(context);

        // 스크롤 박스: 박스 밖에서는 호버 효과가 생기지 않도록 마우스 좌표를 치운다
        context.fill(boxL - 1, boxTop - 1, boxR + 1, boxBottom + 1, 0xFF000000);
        context.fill(boxL, boxTop, boxR, boxBottom, 0xA0000000);

        boolean hover = inBox(mouseX, mouseY);
        int mx = hover ? mouseX : -10000;
        int my = hover ? mouseY : -10000;

        context.enableScissor(boxL, boxTop, boxR, boxBottom);
        for (Entry e : entries) {
            if (e.widget().visible) e.widget().render(context, mx, my, delta);
        }
        int origin = originY();
        for (Label l : labels) {
            int y = origin + l.contentY();
            if (y + ROW_H > boxTop && y < boxBottom) {
                context.drawText(textRenderer, l.text(), l.x(), y + 6, 0xFFB0B0B0, false);
            }
        }
        context.disableScissor();

        drawScrollbar(context);
    }

    private void drawScrollbar(DrawContext context) {
        int max = maxScroll();
        if (max <= 0) return;
        int boxH = boxBottom - boxTop;
        int x1 = boxR - 6;
        int x2 = boxR - 2;
        int thumbH = thumbHeight();
        int thumbY = boxTop + (int) Math.round((boxH - thumbH) * (draft.getScroll() / max));
        context.fill(x1, boxTop + 1, x2, boxBottom - 1, 0x40FFFFFF);
        context.fill(x1, thumbY, x2, thumbY + thumbH, draggingBar ? 0xFFFFFFFF : 0xFFA0A0A0);
    }

    /** 대략적인 미리보기. 줄 너비·정렬·배경·불투명도·그림자·크기(X,Y)가 반영된다. */
    private void drawPreview(DrawContext context) {
        int x1 = boxL;
        int y1 = previewTop;
        int x2 = boxR;
        int y2 = previewBottom;

        context.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, 0xFF000000);
        context.fill(x1, y1, x2, y2, 0xFF7A8F7A);

        List<OrderedText> lines = textRenderer.wrapLines(currentText(), Math.max(1, draft.getInt("line_width", 200)));
        if (lines.isEmpty()) return;

        int lineH = textRenderer.fontHeight + 1;
        int textW = 0;
        for (OrderedText line : lines) textW = Math.max(textW, textRenderer.getWidth(line));
        int bgW = textW + 2;
        int bgH = lines.size() * lineH + 1;

        float[] scale = draft.getScale();
        float sx = Math.max(0.05f, Math.abs(scale[0]));
        float sy = Math.max(0.05f, Math.abs(scale[1]));
        float fit = Math.min(1f, Math.min((boxW - 12f) / (bgW * sx), (y2 - y1 - 8f) / (bgH * sy)));
        fit = Math.max(fit, 0.01f);

        int bg = draft.getBool("default_background", false) ? 0x40000000 : draft.getInt("background", 0x40000000);
        int color = (draft.getUnsignedByte("text_opacity", 255) << 24) | 0xFFFFFF;
        boolean shadow = draft.getBool("shadow", false);
        String align = draft.getString("alignment", "center");

        context.enableScissor(x1, y1, x2, y2);
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.translate((x1 + x2) / 2f, (y1 + y2) / 2f, 0f);
        matrices.scale(sx * fit, sy * fit, 1f);
        matrices.translate(-bgW / 2f, -bgH / 2f, 0f);

        context.fill(0, 0, bgW, bgH, bg);
        for (int i = 0; i < lines.size(); i++) {
            OrderedText line = lines.get(i);
            int w = textRenderer.getWidth(line);
            int dx = switch (align) {
                case "left" -> 0;
                case "right" -> textW - w;
                default -> (textW - w) / 2;
            };
            context.drawText(textRenderer, line, 1 + dx, 1 + i * lineH, color, shadow);
        }

        matrices.pop();
        context.disableScissor();
    }

    // ------------------------------------------------------------------

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) return i;
        }
        return -1;
    }

    /** 좌표 표시용. 소수 3자리까지 보여 준다. (저장된 값 자체는 반올림하지 않는다) */
    private static String formatDouble(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? s + "0" : s;
    }

    private static String formatFloat(float v) {
        String s = String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? s + "0" : s;
    }
}