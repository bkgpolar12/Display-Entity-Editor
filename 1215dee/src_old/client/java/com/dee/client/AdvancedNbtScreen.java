package com.dee.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.nbt.NbtElement;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 메인 화면에서 다루지 않는 나머지 NBT 키(디스플레이 종류별)를 SNBT 문자열로 수정하는 화면.
 * 빈 칸 = 값 제거, 빨간 글씨 = 문법 오류(저장 안 됨). 마우스 휠로 스크롤.
 */
public class AdvancedNbtScreen extends Screen {
    /** 메인 화면에서 다루지 않는 키들. DisplayEditMod.ALLOWED 와 맞춰서 유지하세요. */
    private static final List<String> TEXT_KEYS = List.of(
            "text", "transformation", "brightness", "view_range",
            "shadow_radius", "shadow_strength", "width", "height",
            "interpolation_duration", "start_interpolation", "teleport_duration"
    );
    private static final List<String> BLOCK_KEYS = List.of(
            "block_state", "transformation", "brightness", "view_range",
            "shadow_radius", "shadow_strength", "width", "height",
            "interpolation_duration", "start_interpolation", "teleport_duration"
    );

    private static final List<String> ITEM_KEYS = List.of(
            "item", "transformation", "brightness", "view_range",
            "shadow_radius", "shadow_strength", "width", "height",
            "interpolation_duration", "start_interpolation", "teleport_duration"
    );

    private static final int LABEL_W = 130;
    private static final int ROW_H = 20;
    private static final int GAP = 4;
    private static final int PITCH = ROW_H + GAP;
    private static final int COLOR_OK = 0xFFE0E0E0;
    private static final int COLOR_ERR = 0xFFFF5555;

    private final Draft draft;
    /** 디스플레이 종류에 맞는 키 목록 */
    private final List<String> keys;
    private final List<TextFieldWidget> fields = new ArrayList<>();

    private int left;
    private int contentW;
    private int listTop;
    private int visibleRows;
    private int firstRow;

    public AdvancedNbtScreen(Draft draft) {
        super(Text.translatable("dee.advanced.title"));
        this.draft = draft;
        this.keys = switch (draft.type()) {
            case BLOCK -> BLOCK_KEYS;
            case ITEM -> ITEM_KEYS;
            default -> TEXT_KEYS;
        };
    }

    @Override
    protected void init() {
        fields.clear();

        contentW = Math.min(width - 20, 400);
        left = (width - contentW) / 2;
        listTop = 30;
        int bottomY = height - 6 - ROW_H;
        int listBottom = bottomY - 6;
        visibleRows = Math.max(1, (listBottom - listTop + GAP) / PITCH);
        firstRow = Math.min(firstRow, Math.max(0, keys.size() - visibleRows));

        for (String key : keys) {
            TextFieldWidget f = addDrawableChild(new TextFieldWidget(
                    textRenderer, left + LABEL_W, 0, contentW - LABEL_W, ROW_H, Text.empty()));
            f.setMaxLength(4096);
            f.setPlaceholder(Text.translatable("dee.advanced.none"));
            NbtElement value = draft.get(key);
            if (value != null) f.setText(value.toString());
            f.setChangedListener(s -> onEdit(key, f, s));
            fields.add(f);
        }

        addDrawableChild(ButtonWidget.builder(Text.translatable("dee.button.done"), b -> close())
                .dimensions(width / 2 - 50, bottomY, 100, ROW_H).build());

        relayout();
    }

    private void onEdit(String key, TextFieldWidget field, String text) {
        if (text.isBlank()) {
            draft.remove(key);
            field.setEditableColor(COLOR_OK);
            return;
        }
        Optional<NbtElement> parsed = Draft.parseSnbt(text);
        if (parsed.isPresent()) {
            draft.set(key, parsed.get());
            field.setEditableColor(COLOR_OK);
        } else {
            field.setEditableColor(COLOR_ERR);
        }
    }

    /** 스크롤 위치에 맞춰 보이는 줄만 표시한다. */
    private void relayout() {
        for (int i = 0; i < fields.size(); i++) {
            TextFieldWidget f = fields.get(i);
            boolean visible = i >= firstRow && i < firstRow + visibleRows;
            f.visible = visible;
            f.active = visible;
            if (visible) f.setY(listTop + (i - firstRow) * PITCH);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int max = Math.max(0, keys.size() - visibleRows);
        int next = Math.max(0, Math.min(max, firstRow - (int) Math.signum(verticalAmount)));
        if (next != firstRow) {
            firstRow = next;
            relayout();
        }
        return true;
    }

    /** 닫으면 메인 화면으로 돌아간다. (새로 만들어야 입력칸에 바뀐 값이 반영된다) */
    @Override
    public void close() {
        client.setScreen(new DisplayEditScreen(draft));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 6, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("dee.advanced.hint"),
                width / 2, 17, 0xFFA0A0A0);

        for (int i = firstRow; i < Math.min(keys.size(), firstRow + visibleRows); i++) {
            int y = listTop + (i - firstRow) * PITCH;
            context.drawText(textRenderer, keys.get(i), left, y + 6, 0xFFB0B0B0, false);
        }
    }
}
