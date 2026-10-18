package com.dee.client;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.state.property.Property;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * text_display / block_display 편집 화면. 새로 만들 때는 왼쪽 위 버튼으로 종류를 고른다.
 * 위: 프리뷰(고정) / 가운데: 스크롤 박스(텍스트 + 접이식 섹션들) / 아래: [고급 NBT] [적용] [삭제] [취소](고정)
 * 모든 입력은 즉시 Draft 에 저장되므로 init() 이 다시 불려도 값이 유지된다.
 * 섹션 접힘 상태와 스크롤 위치도 Draft 에 있어서 화면을 다시 만들어도 그대로 유지된다.
 */
public class DisplayEditScreen extends Screen {
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
    private static final int SECTION_TAGS = Draft.SECTION_TAGS;

    private static final int COLOR_OK = 0xFFE0E0E0;
    private static final int COLOR_ERR = 0xFFFF5555;
    private static final int COLOR_STYLED = 0xFFFFD27F;

    /** 값 이름은 lang 파일의 dee.align.<값>, dee.billboard.<값> 키에서 가져온다. */
    private static final String[] ALIGN = {"center", "left", "right"};
    private static final String[] BILLBOARD = {"fixed", "vertical", "horizontal", "center"};
    /** item_display 의 표시 방식. 값 이름은 lang 파일의 dee.item_context.<값> 키에서 가져온다. */
    private static final String[] ITEM_CONTEXT = {"none", "thirdperson_lefthand", "thirdperson_righthand",
            "firstperson_lefthand", "firstperson_righthand", "head", "gui", "ground", "fixed", "on_shelf"};

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
    private TextFieldWidget blockField;
    private TextFieldWidget propsField;
    private TextFieldWidget itemField;
    private TextFieldWidget profileField;
    /** 미리보기에 쓰는 아이템. item 이 올바를 때만 갱신된다. */
    private ItemStack previewStack = ItemStack.EMPTY;
    /** 지금 화면에 스킨 칸이 만들어져 있는지 (아이템이 플레이어 헤드일 때만 만든다) */
    private boolean profileShown;
    /** 아이템 칸에서 입력하다 스킨 칸이 생기거나 사라져야 할 때, 입력 중이 아닌 틱에 화면을 다시 만든다. */
    private boolean pendingRebuild;
    private boolean refocusItem;
    private int itemCursor;
    /** 속성 칸 툴팁을 마지막으로 만든 입력 상태. 같으면 다시 만들지 않는다. */
    private String propsTipKey = "";
    /** 미리보기에 쓰는 블록 상태(속성 포함). block_state 가 올바를 때만 갱신된다. */
    private BlockState previewState;
    /** 삭제 버튼을 한 번 눌러 확인 대기 중인지 */
    private boolean deleteArmed;
    private boolean draggingBar;

    public DisplayEditScreen(Draft draft) {
        super(titleFor(draft));
        this.draft = draft;
    }

    private static Text titleFor(Draft draft) {
        String suffix = switch (draft.type()) {
            case BLOCK -> "_block";
            case ITEM -> "_item";
            default -> "";
        };
        return Text.translatable((draft.isCreating() ? "dee.screen.title_new" : "dee.screen.title") + suffix);
    }

    @Override
    protected void init() {
        labels.clear();
        entries.clear();
        scrollWidgets.clear();

        int widestLabel = switch (draft.type()) {
            case BLOCK -> widest("dee.label.block", "dee.label.block_properties", "dee.label.glow");
            case ITEM -> widest("dee.label.item", "dee.label.item_profile", "dee.label.glow");
            default -> widest("dee.label.text", "dee.label.text_nbt", "dee.label.line_width",
                    "dee.label.background", "dee.label.glow");
        };
        labelW = Math.max(40, widestLabel + 6);
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
        previewTop = 24;
        int avail = bottomY - GAP - previewTop - GAP; // 프리뷰 + 스크롤 박스가 나눠 쓰는 높이
        previewBottom = previewTop + Math.max(24, Math.min(100, avail * 2 / 5));
        boxTop = previewBottom + GAP;
        boxBottom = bottomY - GAP;

        // ---- 스크롤 박스 내용 ----
        int cy = 0;

        cy = switch (draft.type()) {
            case BLOCK -> addBlockRow(cy);
            case ITEM -> addItemRow(cy);
            default -> addTextRow(cy);
        };

        // ▼ 스타일
        cy = addSection(SECTION_STYLE, "dee.section.style", cy);
        if (draft.isSectionOpen(SECTION_STYLE)) {
            cy = switch (draft.type()) {
                case BLOCK -> addBlockStyle(cy, colW, rightX);
                case ITEM -> addItemStyle(cy, colW, rightX);
                default -> addTextStyle(cy, colW, rightX, thirdW);
            };
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
                f.setText(formatFloat(scale[i]));
                final int axis = i;
                bindFilter(f, s -> s.matches("-?\\d{0,3}(\\.\\d{0,4})?"), s -> {
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
                f.setText(formatDouble(pos[i]));
                final int axis = i;
                bindFilter(f, s -> s.matches("-?\\d{0,8}(\\.\\d{0,4})?"), s -> {
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

            // 자동 정렬 (block_display 만)
            if (draft.canAutoAlign()) {
                addScroll(ButtonWidget.builder(autoAlignLabel(), b -> {
                            draft.setAutoAlign(!draft.isAutoAlign());
                            clearAndInit(); // 맞춰진 좌표를 입력칸에 반영
                        })
                        .dimensions(left, 0, contentW, ROW_H)
                        .tooltip(Tooltip.of(Text.translatable("dee.tooltip.auto_align")))
                        .build(), cy);
                cy += PITCH;
            }

            // 방향 Yaw | Pitch
            float[] rot = draft.getRot();
            String[] rotLabels = {"dee.label.yaw", "dee.label.pitch"};
            for (int i = 0; i < 2; i++) {
                int cx = i == 0 ? left : rightX;
                addLabel(Text.translatable(rotLabels[i]), cx, cy);
                TextFieldWidget f = addScroll(new TextFieldWidget(textRenderer,
                        cx + rotLabelW, 0, colW - rotLabelW, ROW_H, Text.empty()), cy);
                f.setText(formatDouble(rot[i]));
                final int axis = i;
                bindFilter(f, s -> s.matches("-?\\d{0,4}(\\.\\d{0,3})?"), s -> {
                    try {
                        draft.setRotAxis(axis, Float.parseFloat(s));
                    } catch (NumberFormatException ignored) {
                        // "-" 처럼 아직 완성되지 않은 입력은 무시
                    }
                });
            }
            cy += PITCH;
        }

        // ▶ 태그
        cy = addSection(SECTION_TAGS, "dee.section.tags", cy);
        if (draft.isSectionOpen(SECTION_TAGS)) {
            cy = addTagRows(cy);
        }

        contentH = Math.max(0, cy - GAP);
        draft.setScroll(Math.max(0, Math.min(draft.getScroll(), maxScroll())));
        relayout();

        // ---- 왼쪽 위: 종류 선택 (새로 만들 때만) ----
        if (draft.isCreating()) {
            int tx = 6;
            for (Draft.Type type : Draft.Type.values()) {
                Text name = Text.translatable("dee.type." + type.key());
                boolean selected = draft.type() == type;
                int w = textRenderer.getWidth(name) + 14;
                addDrawableChild(ButtonWidget.builder(
                                selected ? name.copy().styled(style -> style.withColor(0xFFFF55)) : name,
                                b -> {
                                    if (draft.type() != type) {
                                        draft.setType(type);
                                        draft.setScroll(0);
                                        clearAndInit(); // 종류에 맞는 입력칸으로 다시 만든다
                                    }
                                })
                        .dimensions(tx, 4, w, 16).build());
                tx += w + 2;
            }
        }

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

    /** 새로 추가한 태그 줄. 화면을 다시 만든 뒤 여기에 포커스를 준다. (-1 이면 없음) */
    private int focusTagRow = -1;

    /**
     * 태그 섹션: 태그마다 [입력칸][x] 한 줄, 맨 아래에 [+] 버튼.
     * 태그는 /tag 와 같은 규칙(영문자, 숫자, _ - . +)만 입력할 수 있고, 비어 있는 줄은 저장되지 않는다.
     */
    private int addTagRows(int cy) {
        List<String> rows = draft.getTagRows();
        int fieldW = contentW - ROW_H - GAP;
        for (int i = 0; i < rows.size(); i++) {
            final int index = i;
            TextFieldWidget f = addScroll(new TextFieldWidget(textRenderer, left, 0, fieldW, ROW_H, Text.empty()), cy);
            f.setMaxLength(64);
            f.setPlaceholder(Text.translatable("dee.hint.tag"));
            f.setText(rows.get(i));
            bindFilter(f, s -> s.matches("[A-Za-z0-9_.+\\-]*"), s -> draft.setTagRow(index, s));
            if (i == focusTagRow) setFocused(f);

            addScroll(ButtonWidget.builder(Text.literal("\u00D7").styled(style -> style.withColor(0xFF5555)), b -> {
                        draft.removeTagRow(index);
                        clearAndInit();
                    })
                    .dimensions(left + fieldW + GAP, 0, ROW_H, ROW_H)
                    .tooltip(Tooltip.of(Text.translatable("dee.tooltip.tag_remove")))
                    .build(), cy);
            cy += PITCH;
        }
        focusTagRow = -1;

        addScroll(ButtonWidget.builder(Text.literal("+"), b -> {
                    draft.addTagRow();
                    focusTagRow = draft.getTagRows().size() - 1;
                    draft.setScroll(Double.MAX_VALUE); // init() 이 최대 스크롤로 줄여 주므로 새 줄이 보인다
                    clearAndInit();
                })
                .dimensions(left, 0, contentW, ROW_H)
                .tooltip(Tooltip.of(Text.translatable("dee.tooltip.tag_add")))
                .build(), cy);
        return cy + PITCH;
    }

    /** 맨 윗줄(텍스트): 텍스트 입력칸 + [고급]. 다음 줄의 contentY 를 돌려준다. */
    private int addTextRow(int cy) {
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
        return cy;
    }

    /**
     * 맨 윗줄(아이템): item 입력칸. 아이템이 플레이어 헤드일 때만 그 아래에 스킨 입력칸이 생긴다.
     * 예) 아이템 = player_head, 스킨 = Notch (플레이어 이름) 또는 텍스처 값/URL/해시
     * item NBT 는 {id:"minecraft:player_head",count:1,components:{"minecraft:profile":..}} 모양으로 저장한다.
     */
    private int addItemRow(int cy) {
        addLabel(Text.translatable("dee.label.item"), left, cy);
        itemField = addScroll(new TextFieldWidget(textRenderer, left + labelW, 0,
                contentW - labelW, ROW_H, Text.empty()), cy);
        itemField.setMaxLength(256);
        itemField.setPlaceholder(Text.literal("minecraft:diamond_sword"));
        itemField.setTooltip(Tooltip.of(Text.translatable("dee.tooltip.item")));
        itemField.setText(draft.getItemId());
        itemField.setChangedListener(s -> updateItem());

        Identifier id = parseItemId(draft.getItemId());
        itemField.setEditableColor(id != null ? COLOR_OK : COLOR_ERR);
        boolean head = id != null && Registries.ITEM.get(id) == Items.PLAYER_HEAD;
        profileShown = head;
        cy += PITCH;

        profileField = null;
        if (head) {
            addLabel(Text.translatable("dee.label.item_profile"), left, cy);
            profileField = addScroll(new TextFieldWidget(textRenderer, left + labelW, 0,
                    contentW - labelW, ROW_H, Text.empty()), cy);
            profileField.setMaxLength(2048);
            profileField.setPlaceholder(Text.translatable("dee.hint.item_profile"));
            profileField.setTooltip(Tooltip.of(Text.translatable("dee.tooltip.item_profile")));
            profileField.setText(ProfileText.format(draft.getItemProfile()));
            profileField.setChangedListener(this::updateProfile);
            cy += PITCH;
        }

        refreshPreviewStack();
        setInitialFocus(itemField);
        if (refocusItem) {
            // 스킨 칸이 생기거나 사라져서 화면을 다시 만들었을 때, 입력하던 칸과 커서 위치를 되돌린다.
            refocusItem = false;
            setFocused(itemField);
            itemField.setCursor(itemCursor, false);
        }
        return cy;
    }

    /** 아이템 ID 칸 입력 처리. 없는 아이템이면 빨간색이고 저장되지 않는다. */
    private void updateItem() {
        Identifier id = parseItemId(itemField.getText());
        itemField.setEditableColor(id != null ? COLOR_OK : COLOR_ERR);
        if (id == null) return;

        draft.setItemId(id.toString());
        refreshPreviewStack();

        boolean head = Registries.ITEM.get(id) == Items.PLAYER_HEAD;
        if (head != profileShown) {
            pendingRebuild = true;
            refocusItem = true;
            itemCursor = itemField.getCursor();
        }
    }

    /** 스킨 칸 입력 처리. 비우면 기본 스킨(profile 제거), 형식이 틀리면 빨간색이고 저장되지 않는다. */
    private void updateProfile(String text) {
        if (text.isBlank()) {
            draft.setItemProfile(null);
            profileField.setEditableColor(COLOR_OK);
        } else {
            var parsed = ProfileText.parse(text);
            if (parsed.isPresent()) draft.setItemProfile(parsed.get());
            profileField.setEditableColor(parsed.isPresent() ? COLOR_OK : COLOR_ERR);
        }
        refreshPreviewStack();
    }

    /** "diamond_sword" / "minecraft:diamond_sword" -> Identifier. 없는 아이템이나 air 면 null. */
    private static Identifier parseItemId(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        Identifier id = Identifier.tryParse(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
        if (id == null || !Registries.ITEM.containsId(id)) return null;
        return Registries.ITEM.get(id) == Items.AIR ? null : id;
    }

    /** Draft 의 item 으로 미리보기용 ItemStack 을 다시 만든다. (코덱으로 읽는다) */
    private void refreshPreviewStack() {
        previewStack = ItemStack.EMPTY;
        NbtElement item = draft.get("item");
        if (item == null || client == null || client.world == null) return;
        RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, client.world.getRegistryManager());
        previewStack = ItemStack.CODEC.parse(ops, item).result().orElse(ItemStack.EMPTY);
    }

    @Override
    public void tick() {
        super.tick();
        if (pendingRebuild) {
            pendingRebuild = false;
            clearAndInit();
        }
    }

    /**
     * 맨 윗줄(블록): block_name 입력칸 + 그 아래 block_properties 입력칸.
     * 예) block_name = oak_stairs, block_properties = facing=east,half=top
     * 두 칸을 합쳐 block_state 를 만든다. (속성이 없으면 "이름" 문자열, 있으면 {Name:..,Properties:{..}})
     */
    private int addBlockRow(int cy) {
        NbtElement element = draft.get("block_state");

        addLabel(Text.translatable("dee.label.block"), left, cy);
        blockField = addScroll(new TextFieldWidget(textRenderer, left + labelW, 0,
                contentW - labelW, ROW_H, Text.empty()), cy);
        blockField.setMaxLength(256);
        blockField.setPlaceholder(Text.literal("minecraft:oak_stairs"));
        blockField.setTooltip(Tooltip.of(Text.translatable("dee.tooltip.block")));
        BlockState current = element == null ? null : decodeState(element);
        blockField.setText(current != null
                ? Registries.BLOCK.getId(current.getBlock()).toString() : blockNameOf(element));

        addLabel(Text.translatable("dee.label.block_properties"), left, cy + PITCH);
        propsField = addScroll(new TextFieldWidget(textRenderer, left + labelW, 0,
                contentW - labelW, ROW_H, Text.empty()), cy + PITCH);
        propsField.setMaxLength(1024);
        propsField.setPlaceholder(Text.literal("facing=east,half=top"));
        propsField.setText(current != null ? propertiesOf(current) : "");

        previewState = current;
        blockField.setChangedListener(s -> updateBlockState());
        propsField.setChangedListener(s -> updateBlockState());
        propsTipKey = "";
        refreshPropsTooltip();
        setInitialFocus(blockField);
        return cy + 2 * PITCH;
    }

    /**
     * 두 입력칸으로 BlockState 를 만들고, block_state NBT 는 게임의 BlockState 코덱이 직접 인코딩한 값을 쓴다.
     * (버전마다 키 이름이 달라질 수 있으므로 우리가 직접 {Name:..} 같은 모양을 만들지 않는다)
     * 없는 블록이면 이름 칸이, 없는 속성/값이면 속성 칸이 빨간색이고 저장되지 않는다.
     */
    private void updateBlockState() {
        String name = blockField.getText().trim();

        // 이름만으로 먼저 검사
        Block block = name.isEmpty() ? null : decodeBlock(NbtString.of(name), name);
        blockField.setEditableColor(block != null ? COLOR_OK : COLOR_ERR);
        if (block == null) {
            propsField.setEditableColor(COLOR_OK);
            return;
        }

        // 속성을 하나씩 적용 (블록의 속성 정의로 검사)
        NbtCompound properties = parseProperties(propsField.getText());
        BlockState state = properties == null ? null : block.getDefaultState();
        if (state != null) {
            for (String key : properties.getKeys()) {
                Property<?> property = block.getStateManager().getProperty(key);
                state = property == null ? null : withValue(state, property, properties.getString(key, ""));
                if (state == null) break;
            }
        }

        NbtElement tag = state == null ? null
                : BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).result().orElse(null);
        if (tag == null) {
            propsField.setEditableColor(COLOR_ERR);
            return;
        }
        propsField.setEditableColor(COLOR_OK);
        draft.set("block_state", tag);
        previewState = state;
    }


    // ------------------------------------------------------------------
    // 속성 칸 툴팁 (NBTac 의 자동완성 힌트처럼, 입력 중인 블록의 속성/가능한 값을 보여 준다)
    // ------------------------------------------------------------------

    /** 값이 이 개수를 넘으면 전부 나열하지 않고 "처음 ~ 끝" 으로 줄인다. (age, note 등) */
    private static final int MAX_LISTED_VALUES = 10;

    /** 이름/속성 칸/커서 위치가 바뀌었을 때만 툴팁을 새로 만든다. */
    private void refreshPropsTooltip() {
        if (propsField == null || blockField == null) return;
        String name = blockField.getText().trim();
        String text = propsField.getText();
        int cursor = propsField.getCursor();
        String key = name + '\u0000' + text + '\u0000' + cursor;
        if (key.equals(propsTipKey)) return;
        propsTipKey = key;
        propsField.setTooltip(Tooltip.of(buildPropsTooltip(name, text, cursor)));
    }

    private Text buildPropsTooltip(String name, String text, int cursor) {
        Block block = name.isEmpty() ? null : decodeBlock(NbtString.of(name), name);
        if (block == null) return Text.translatable("dee.tooltip.block_properties");

        Collection<Property<?>> defs = block.getStateManager().getProperties();
        if (defs.isEmpty()) return Text.translatable("dee.tooltip.props.none", block.getName());

        NbtCompound entered = parseProperties(text);
        String[] active = activeSegment(text, cursor);
        BlockState defaults = block.getDefaultState();

        MutableText out = Text.translatable("dee.tooltip.props.header", block.getName())
                .formatted(Formatting.GOLD);
        out.append(Text.literal("\n").append(Text.translatable("dee.tooltip.props.legend"))
                .formatted(Formatting.DARK_GRAY));

        List<String> known = new ArrayList<>();
        for (Property<?> property : defs) {
            known.add(property.getName());
            out.append(propertyRow(property, defaults, entered, active));
        }

        // 오류 안내
        if (entered == null) {
            out.append(Text.literal("\n").append(Text.translatable("dee.tooltip.props.syntax"))
                    .formatted(Formatting.RED));
        } else {
            for (String key : entered.getKeys()) {
                if (!known.contains(key)) {
                    out.append(Text.literal("\n").append(Text.translatable("dee.tooltip.props.unknown_key", key))
                            .formatted(Formatting.RED));
                }
            }
        }
        return out;
    }

    /**
     * 속성 한 줄: "▶ facing: north south* west east".
     * 초록 = 입력한 값, 노랑 = 지금 입력 중인 글자로 시작하는 후보, 회색 = 그 외, * = 기본값, 빨강 = 쓸 수 없는 값.
     */
    private static <T extends Comparable<T>> Text propertyRow(
            Property<T> property, BlockState defaults, NbtCompound entered, String[] active) {
        String pname = property.getName();
        String enteredValue = entered != null && entered.contains(pname) ? entered.getString(pname, "") : null;
        T def = defaults.get(property);

        // 커서가 있는 줄: 키 입력 중이면 접두어가 맞는 속성, "키=값" 이면 그 속성
        String activeKey = active[0];
        String typed = active[1];
        boolean isActive = !activeKey.isEmpty()
                && (typed == null ? pname.startsWith(activeKey) : pname.equals(activeKey));
        String prefix = typed != null && isActive ? typed : "";

        MutableText line = Text.literal("\n");
        line.append(Text.literal(isActive ? "\u25B6 " : "  ").formatted(Formatting.YELLOW));
        line.append(Text.literal(pname).formatted(isActive ? Formatting.WHITE : Formatting.GRAY));
        line.append(Text.literal(": ").formatted(Formatting.DARK_GRAY));

        List<T> values = new ArrayList<>(property.getValues());
        if (values.size() > MAX_LISTED_VALUES) {
            line.append(Text.translatable("dee.tooltip.props.range",
                            property.name(values.get(0)), property.name(values.get(values.size() - 1)), values.size())
                    .formatted(Formatting.GRAY));
            if (enteredValue != null) {
                boolean ok = property.parse(enteredValue).isPresent();
                line.append(Text.literal(" = " + enteredValue).formatted(ok ? Formatting.GREEN : Formatting.RED));
            }
            return line;
        }

        boolean first = true;
        for (T value : values) {
            String vn = property.name(value);
            if (!first) line.append(Text.literal(" "));
            first = false;
            Formatting color = vn.equals(enteredValue) ? Formatting.GREEN
                    : (!prefix.isEmpty() && vn.startsWith(prefix)) ? Formatting.YELLOW
                      : Formatting.GRAY;
            line.append(Text.literal(vn + (value.equals(def) ? "*" : "")).formatted(color));
        }
        if (enteredValue != null && property.parse(enteredValue).isEmpty()) {
            line.append(Text.literal(" \u2717 " + enteredValue).formatted(Formatting.RED));
        }
        return line;
    }

    /** 커서가 있는 "키=값" 조각을 {키, 값}으로. '=' 가 아직 없으면 값은 null. 앞뒤 [ ] 는 무시한다. */
    private static String[] activeSegment(String text, int cursor) {
        int c = Math.max(0, Math.min(cursor, text.length()));
        int start = text.lastIndexOf(',', c - 1) + 1;
        int end = text.indexOf(',', c);
        if (end < 0) end = text.length();
        String segment = text.substring(start, end).replace("[", "").replace("]", "").trim();
        int eq = segment.indexOf('=');
        if (eq < 0) return new String[]{segment, null};
        return new String[]{segment.substring(0, eq).trim(), segment.substring(eq + 1).trim()};
    }

    /** 속성 하나를 값 문자열로 바꿔 적용한다. 없는 값이면 null. */
    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.parse(value);
        return parsed.isPresent() ? state.with(property, parsed.get()) : null;
    }

    /** 기본값과 다른 속성만 "k=v,k=v" 로. */
    private static String propertiesOf(BlockState state) {
        BlockState defaults = state.getBlock().getDefaultState();
        StringBuilder sb = new StringBuilder();
        for (Property<?> property : state.getBlock().getStateManager().getProperties()) {
            String entry = nonDefault(state, defaults, property);
            if (entry == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(entry);
        }
        return sb.toString();
    }

    private static <T extends Comparable<T>> String nonDefault(BlockState state, BlockState defaults, Property<T> property) {
        T value = state.get(property);
        return value.equals(defaults.get(property)) ? null : property.getName() + "=" + property.name(value);
    }

    private static BlockState decodeState(NbtElement tag) {
        return BlockState.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(null);
    }

    /** "facing=east,half=top" (앞뒤 [ ] 는 있어도 됨) -> {facing:"east",half:"top"}. 문법 오류면 null. */
    private static NbtCompound parseProperties(String text) {
        String s = text.trim();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        NbtCompound out = new NbtCompound();
        if (s.isBlank()) return out;
        for (String pair : s.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) return null;
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (key.isEmpty() || value.isEmpty()) return null;
            out.putString(key, value);
        }
        return out;
    }

    /** 문자열 태그의 내용. 문자열이 아니면 "". */
    private static String stringOf(NbtElement tag) {
        NbtCompound wrapper = new NbtCompound();
        wrapper.put("v", tag);
        return wrapper.getString("v", "");
    }

    /** block_state 에서 이름 부분. ("이름[k=v]" 문자열이든 {Name:..} 이든) */
    private static String blockNameOf(NbtElement tag) {
        if (tag == null) return "";
        if (tag instanceof NbtCompound state) return state.getString("Name", "");
        String raw = stringOf(tag);
        int open = raw.indexOf('[');
        return open >= 0 ? raw.substring(0, open) : raw;
    }

    /**
     * 코덱으로 읽어 블록을 얻는다. 읽을 수 없으면 null.
     * 코덱은 없는 블록 이름을 공기로 읽어 버리므로, 이름이 air 로 끝나지 않는데 공기면 없는 블록으로 본다.
     */
    private static Block decodeBlock(NbtElement tag, String name) {
        // 1.21.5 의 BlockState 코덱은 {Name:..,Properties:{..}} 컴파운드만 읽고 문자열 이름은 읽지 못한다.
        // 그래서 이름 문자열은 레지스트리에서 직접 찾는다.
        if (tag instanceof NbtString) return BlockStateText.findBlock(name).orElse(null);
        Block block = BlockState.CODEC.parse(NbtOps.INSTANCE, tag).result().map(BlockState::getBlock).orElse(null);
        if (block != null && block.getDefaultState().isAir() && !name.endsWith("air")) return null;
        return block;
    }

    /** 스타일 섹션(텍스트). 다음 줄의 contentY 를 돌려준다. */
    private int addTextStyle(int cy, int colW, int rightX, int thirdW) {
        // 줄 너비 | 불투명도
        addLabel(Text.translatable("dee.label.line_width"), left, cy);
        TextFieldWidget lineWidth = addScroll(new TextFieldWidget(textRenderer,
                left + labelW, 0, colW - labelW, ROW_H, Text.empty()), cy);
        lineWidth.setText(String.valueOf(Math.max(1, draft.getInt("line_width", 200))));
        bindFilter(lineWidth, s -> s.matches("\\d{0,5}"), s -> {
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
        return cy;
    }

    /** 스타일 섹션(아이템): 빌보드 | 발광색, 그 아래 표시 방식 */
    private int addItemStyle(int cy, int colW, int rightX) {
        addScroll(cycleButton(left, colW, "dee.option.billboard", "billboard", "fixed", BILLBOARD, "dee.billboard."), cy);
        addLabel(Text.translatable("dee.label.glow"), rightX, cy);
        addHexField(rightX + labelW, cy, colW - labelW, "glow_color_override", false);
        cy += PITCH;
        addScroll(cycleButton(left, contentW, "dee.option.item_context", "item_display", "none",
                ITEM_CONTEXT, "dee.item_context."), cy);
        return cy + PITCH;
    }

    /** 스타일 섹션(블록): 빌보드 | 발광색 */
    private int addBlockStyle(int cy, int colW, int rightX) {
        addScroll(cycleButton(left, colW, "dee.option.billboard", "billboard", "fixed", BILLBOARD, "dee.billboard."), cy);
        addLabel(Text.translatable("dee.label.glow"), rightX, cy);
        addHexField(rightX + labelW, cy, colW - labelW, "glow_color_override", false);
        return cy + PITCH;
    }

    private Text deleteLabel() {
        return Text.translatable(deleteArmed ? "dee.button.delete_confirm" : "dee.button.delete")
                .styled(style -> style.withColor(0xFF5555));
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

    // 1.21.5 의 마우스 입력은 (x, y, button) 으로 전달된다. (1.21.9 부터 MouseButtonEvent 로 바뀐다)
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean left = button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
        // 스크롤바 잡기
        if (left && maxScroll() > 0 && inBox(mouseX, mouseY) && mouseX >= boxR - SCROLL_RESERVE + 1) {
            draggingBar = true;
            dragBarTo(mouseY);
            return true;
        }
        // 박스 밖 클릭은 (일부만 보이는) 스크롤 위젯으로 넘기지 않는다
        if (!inBox(mouseX, mouseY)) {
            for (Element child : children()) {
                if (!scrollWidgets.contains(child) && child.mouseClicked(mouseX, mouseY, button)) {
                    setFocused(child);
                    if (left) setDragging(true);
                    return true;
                }
            }
            setFocused(null);
            return false;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingBar) {
            dragBarTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
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

    private Text autoAlignLabel() {
        return Text.translatable("dee.option.entry", Text.translatable("dee.option.auto_align"),
                Text.translatable(draft.isAutoAlign() ? "dee.state.on" : "dee.state.off"));
    }

    private Text toggleLabel(String titleKey, String key) {
        return Text.translatable("dee.option.entry", Text.translatable(titleKey),
                Text.translatable(draft.getBool(key, false) ? "dee.state.on" : "dee.state.off"));
    }

    /** #RRGGBB / #AARRGGBB 입력칸. 비우면 키를 삭제(=기본값)한다. */
    private void addHexField(int x, int contentY, int w, String key, boolean withAlpha) {
        TextFieldWidget f = addScroll(new TextFieldWidget(textRenderer, x, 0, w, ROW_H, Text.empty()), contentY);
        f.setMaxLength(9);
        f.setPlaceholder(Text.literal(withAlpha ? "#AARRGGBB" : "#RRGGBB"));
        if (draft.has(key)) {
            int v = draft.getInt(key, 0);
            f.setText(withAlpha ? String.format(Locale.ROOT, "#%08X", v)
                    : String.format(Locale.ROOT, "#%06X", v & 0xFFFFFF));
        }
        bindFilter(f, s -> s.matches("#?[0-9a-fA-F]{0,8}"), s -> {
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

    /**
     * 입력 필터. 1.21.5 의 TextFieldWidget 은 setTextPredicate 를 지원하므로 그대로 쓴다.
     * 허용되지 않는 입력은 아예 들어가지 않고, 허용되는 입력만 onValid 로 전달된다.
     */
    private static void bindFilter(TextFieldWidget box, Predicate<String> filter, Consumer<String> onValid) {
        box.setTextPredicate(filter);
        box.setChangedListener(onValid);
    }

    private Text advancedLabel() {
        Text label = Text.translatable("dee.button.advanced");
        return draft.isAdvancedText() ? label.copy().styled(style -> style.withColor(0xFFFF55)) : label;
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
        // 커서 이동/입력에 맞춰 속성 툴팁 내용을 갱신 (바뀐 경우에만 다시 만든다)
        refreshPropsTooltip();
        // 고정 위젯(하단 버튼)과 배경
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, titleFor(draft), width / 2, 6, 0xFFFFFFFF);
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

    /**
     * 인벤토리 블록 아이콘과 같은 등각 시점. (GUI 블록 모델의 display.gui 회전 30, 225, 0 과 같다)
     * 1.21.5 의 DrawContext 는 y 가 아래로 향하므로, 그릴 때 scale(px, -px, px) 로 뒤집어 준다.
     */
    private static final Quaternionf BLOCK_VIEW = new Quaternionf()
            .rotationXYZ((float) Math.toRadians(30), (float) Math.toRadians(225), 0f);

    /**
     * 블록 미리보기: 블록 디스플레이 엔티티가 그리는 방식과 같이 BlockRenderManager#renderBlockAsEntity 로
     * BlockState 의 실제 블록 모델을 그린다. 문, 묘목, 울타리 같은 블록도 월드에 놓였을 때의 모습으로 보이고,
     * facing/half 등 속성과 크기(X,Y,Z)도 반영된다.
     */
    private void drawBlockPreview(DrawContext context, int x1, int y1, int x2, int y2) {
        BlockState state = previewState;
        if (state == null) return;

        float[] scale = draft.getScale();
        float sx = Math.max(0.05f, Math.abs(scale[0]));
        float sy = Math.max(0.05f, Math.abs(scale[1]));
        float sz = Math.max(0.05f, Math.abs(scale[2]));

        int ry2 = y2 - textRenderer.fontHeight - 4; // 아래 이름 글자 자리 제외
        float availW = (x2 - x1) - 12f;
        float availH = (ry2 - y1) - 8f;

        // 회전한 상자(sx*sy*sz)가 화면에서 차지하는 폭/높이를 구해 영역에 맞춘다.
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        Vector3f v = new Vector3f();
        for (int i = 0; i < 8; i++) {
            v.set((i & 1) == 0 ? -sx / 2 : sx / 2, (i & 2) == 0 ? -sy / 2 : sy / 2, (i & 4) == 0 ? -sz / 2 : sz / 2);
            BLOCK_VIEW.transform(v);
            minX = Math.min(minX, v.x);
            maxX = Math.max(maxX, v.x);
            minY = Math.min(minY, v.y);
            maxY = Math.max(maxY, v.y);
        }
        float pxPerUnit = Math.max(0.5f, Math.min(48f, Math.min(availW / (maxX - minX), availH / (maxY - minY))));

        context.enableScissor(x1, y1, x2, y2);
        if (state.getRenderType() != BlockRenderType.INVISIBLE) {
            context.draw(); // 앞서 그린 배경을 먼저 내보낸다
            MatrixStack matrices = context.getMatrices();
            matrices.push();
            matrices.translate((x1 + x2) / 2f, (y1 + ry2) / 2f, 100f);
            matrices.scale(pxPerUnit, -pxPerUnit, pxPerUnit);
            matrices.multiply(BLOCK_VIEW);
            matrices.scale(sx, sy, sz);
            // 블록은 엔티티 원점에서 0~1 범위이므로 중심이 원점에 오도록 옮긴다.
            matrices.translate(-0.5f, -0.5f, -0.5f);

            VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();
            BlockRenderManager blockRenderer = client.getBlockRenderManager();
            blockRenderer.renderBlockAsEntity(state, matrices, consumers,
                    LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
            consumers.draw();
            matrices.pop();
        } else {
            // 모델이 없는 블록(물 등)은 대표색 사각형으로 대체
            int w = Math.max(1, Math.round(pxPerUnit * sx));
            int h = Math.max(1, Math.round(pxPerUnit * sy));
            int bx = (x1 + x2) / 2 - w / 2;
            int by = (y1 + ry2) / 2 - h / 2;
            int rgb = state.getBlock().getDefaultMapColor().color & 0xFFFFFF;
            if (rgb != 0) {
                context.fill(bx - 1, by - 1, bx + w + 1, by + h + 1, 0xFF000000);
                context.fill(bx, by, bx + w, by + h, 0xFF000000 | rgb);
            }
        }
        context.drawCenteredTextWithShadow(textRenderer, state.getBlock().getName(), (x1 + x2) / 2, y2 - textRenderer.fontHeight - 2, 0xFFFFFFFF);
        context.disableScissor();
    }

    /**
     * 아이템 미리보기: 아이템의 GUI 아이콘 모습. (플레이어 헤드는 스킨이 반영된다)
     * 실제 디스플레이는 '표시 방식'에 따라 모양이 조금 달라질 수 있다. 크기(X,Y)가 반영된다.
     */
    private void drawItemPreview(DrawContext context, int x1, int y1, int x2, int y2) {
        ItemStack stack = previewStack;
        if (stack.isEmpty()) return;

        float[] scale = draft.getScale();
        float sx = Math.max(0.05f, Math.abs(scale[0]));
        float sy = Math.max(0.05f, Math.abs(scale[1]));
        float base = 48f; // 크기 1.0 일 때 한 변 (px)
        float availH = y2 - y1 - 8f - textRenderer.fontHeight; // 아래 이름 글자 자리 제외
        float fit = Math.min(1f, Math.min((boxW - 12f) / (base * sx), availH / (base * sy)));
        fit = Math.max(fit, 0.01f);

        context.enableScissor(x1, y1, x2, y2);
        // 아이템 아이콘은 16x16 이므로 목표 크기에 맞게 확대한다.
        float k = base * fit / 16f;
        float cy = (y1 + y2 - textRenderer.fontHeight) / 2f;
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.translate((x1 + x2) / 2f, cy, 0f);
        matrices.scale(sx * k, sy * k, 1f);
        matrices.translate(-8f, -8f, 0f);
        context.drawItem(stack, 0, 0);
        context.draw();
        matrices.pop();
        context.drawCenteredTextWithShadow(textRenderer, stack.getName(), (x1 + x2) / 2, y2 - textRenderer.fontHeight - 2, 0xFFFFFFFF);
        context.disableScissor();
    }

    /** 대략적인 미리보기. 줄 너비·정렬·배경·불투명도·그림자·크기(X,Y)가 반영된다. */
    private void drawPreview(DrawContext context) {
        int x1 = boxL;
        int y1 = previewTop;
        int x2 = boxR;
        int y2 = previewBottom;

        context.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, 0xFF000000);
        context.fill(x1, y1, x2, y2, 0xFF7A8F7A);

        if (draft.type() == Draft.Type.BLOCK) {
            drawBlockPreview(context, x1, y1, x2, y2);
            return;
        }
        if (draft.type() == Draft.Type.ITEM) {
            drawItemPreview(context, x1, y1, x2, y2);
            return;
        }

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
        context.draw(); // 배경이 글자를 덮지 않도록 먼저 내보낸다
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