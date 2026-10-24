package com.dee.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.math.Transformation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.BlockDisplayEntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.Matrix3x2fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    private record Label(Component text, int x, int contentY) {}

    private record Entry(AbstractWidget widget, int contentY) {}

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
    /** [고르기] 버튼 너비. 글자 너비에 맞춰 init() 에서 계산한다. */
    private int pickW;

    private EditBox textField;
    private EditBox blockField;
    private EditBox propsField;
    private EditBox itemField;
    private EditBox profileField;
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
    /** 블록 디스플레이가 쓰는 것과 같은 블록 모델 해석기. */
    private BlockModelResolver blockModelResolver;
    /** 삭제 버튼을 한 번 눌러 확인 대기 중인지 */
    private boolean deleteArmed;
    private boolean draggingBar;

    // ---- 오른쪽 파트 목록 (에디터 모드에서 여러 개/그룹을 선택하고 연 경우에만) ----
    private static final int PANEL_W = 128;
    /** 선택된 파트들. 2개 이상일 때만 패널을 보여 준다. */
    private List<UUID> parts = List.of();
    private int partPage;
    private boolean partPageInit;
    private int partPages = 1;

    public DisplayEditScreen(Draft draft) {
        super(titleFor(draft));
        this.draft = draft;
    }

    private static Component titleFor(Draft draft) {
        String suffix = switch (draft.type()) {
            case BLOCK -> "_block";
            case ITEM -> "_item";
            default -> "";
        };
        return Component.translatable((draft.isCreating() ? "dee.screen.title_new" : "dee.screen.title") + suffix);
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
        pickW = Math.max(ADV_W, font.width(Component.translatable("dee.button.pick")) + 12);
        rotLabelW = Math.max(20, widest("dee.label.yaw", "dee.label.pitch") + 6);
        scaleLabelW = Math.max(36, font.width(Component.translatable("dee.label.scale", "X")) + 4);

        // ---- 고정 영역 계산 ----
        parts = computeParts();
        boolean panel = parts.size() >= 2;
        int usable = panel ? width - PANEL_W - 8 : width; // 폼이 쓸 수 있는 가로 (패널이 있으면 오른쪽을 비운다)
        boxW = Math.min(usable - 10, 372);
        boxL = (usable - boxW) / 2;
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
                addLabel(Component.translatable("dee.label.scale", String.valueOf("XYZ".charAt(i))), cx, cy);
                EditBox f = addScroll(new EditBox(font,
                        cx + scaleLabelW, 0, thirdW - scaleLabelW, ROW_H, Component.empty()), cy);
                f.setValue(formatFloat(scale[i]));
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
                addLabel(Component.literal("XYZ".substring(i, i + 1)), cx, cy);
                EditBox f = addScroll(new EditBox(font,
                        cx + POS_LABEL_W, 0, quarterW - POS_LABEL_W, ROW_H, Component.empty()), cy);
                f.setValue(formatDouble(pos[i]));
                final int axis = i;
                bindFilter(f, s -> s.matches("-?\\d{0,8}(\\.\\d{0,4})?"), s -> {
                    try {
                        draft.setPosAxis(axis, Double.parseDouble(s));
                    } catch (NumberFormatException ignored) {
                        // "-" 처럼 아직 완성되지 않은 입력은 무시
                    }
                });
            }
            addScroll(Button.builder(Component.translatable("dee.button.place"),
                            b -> DisplayEditClient.beginPlacement(minecraft, draft))
                    .bounds(left + 3 * (quarterW + GAP), 0, quarterW, ROW_H)
                    .tooltip(Tooltip.create(Component.translatable("dee.tooltip.place")))
                    .build(), cy);
            cy += PITCH;

            // 자동 정렬 (block_display 만)
            if (draft.canAutoAlign()) {
                addScroll(Button.builder(autoAlignLabel(), b -> {
                            draft.setAutoAlign(!draft.isAutoAlign());
                            rebuildWidgets(); // 맞춰진 좌표를 입력칸에 반영
                        })
                        .bounds(left, 0, contentW, ROW_H)
                        .tooltip(Tooltip.create(Component.translatable("dee.tooltip.auto_align")))
                        .build(), cy);
                cy += PITCH;
            }

            // 방향 Yaw | Pitch
            float[] rot = draft.getRot();
            String[] rotLabels = {"dee.label.yaw", "dee.label.pitch"};
            for (int i = 0; i < 2; i++) {
                int cx = i == 0 ? left : rightX;
                addLabel(Component.translatable(rotLabels[i]), cx, cy);
                EditBox f = addScroll(new EditBox(font,
                        cx + rotLabelW, 0, colW - rotLabelW, ROW_H, Component.empty()), cy);
                f.setValue(formatDouble(rot[i]));
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
                Component name = Component.translatable("dee.type." + type.key());
                boolean selected = draft.type() == type;
                int w = font.width(name) + 14;
                addRenderableWidget(Button.builder(
                                selected ? name.copy().withStyle(style -> style.withColor(0xFFFF55)) : name,
                                b -> {
                                    if (draft.type() != type) {
                                        draft.setType(type);
                                        draft.setScroll(0);
                                        rebuildWidgets(); // 종류에 맞는 입력칸으로 다시 만든다
                                    }
                                })
                        .bounds(tx, 4, w, 16).build());
                tx += w + 2;
            }
        }

        // ---- 고정 버튼: [고급 NBT…] [적용/생성] [삭제] [취소] ----
        int btnW = (boxW - 3 * GAP) / 4;
        addRenderableWidget(Button.builder(Component.translatable("dee.button.advanced_nbt"),
                        b -> minecraft.gui.setScreen(new AdvancedNbtScreen(draft)))
                .bounds(boxL, bottomY, btnW, ROW_H).build());
        addRenderableWidget(Button.builder(
                        Component.translatable(draft.isCreating() ? "dee.button.create" : "dee.button.apply"), b -> apply())
                .bounds(boxL + (btnW + GAP), bottomY, btnW, ROW_H).build());
        if (draft.isCreating()) {
            // 아직 없는 엔티티는 지울 수 없으므로, 그 자리에 [에디터 모드] 버튼을 둔다
            addRenderableWidget(Button.builder(Component.translatable("dee.button.editor_mode"),
                            b -> DisplayEditClient.beginEditorFromDraft(minecraft, draft))
                    .bounds(boxL + 2 * (btnW + GAP), bottomY, btnW, ROW_H)
                    .tooltip(Tooltip.create(Component.translatable("dee.tooltip.editor_mode")))
                    .build());
        } else {
            addRenderableWidget(Button.builder(deleteLabel(), b -> {
                        if (!deleteArmed) {
                            // 첫 클릭: 확인 상태로 바꾼다. 한 번 더 눌러야 삭제된다.
                            deleteArmed = true;
                            b.setMessage(deleteLabel());
                        } else {
                            deleteEntity();
                        }
                    })
                    .bounds(boxL + 2 * (btnW + GAP), bottomY, btnW, ROW_H)
                    .tooltip(Tooltip.create(Component.translatable("dee.tooltip.delete")))
                    .build());
        }
        addRenderableWidget(Button.builder(Component.translatable("dee.button.cancel"), b -> onClose())
                .bounds(boxL + 3 * (btnW + GAP), bottomY, btnW, ROW_H).build());

        if (panel) addPartsPanel();
    }

    // ------------------------------------------------------------------
    // 오른쪽 파트 목록
    // ------------------------------------------------------------------

    /** 에디터 모드의 선택에 이 엔티티가 들어 있고 선택이 2개 이상이면 그 목록, 아니면 빈 목록. */
    private List<UUID> computeParts() {
        if (draft.isCreating() || !GizmoEditor.isActive()) return List.of();
        List<UUID> ids = GizmoEditor.partIds();
        if (ids.size() < 2) return List.of();
        String current = draft.uuid();
        for (UUID id : ids) {
            if (id.toString().equals(current)) return ids;
        }
        return List.of();
    }

    private int panelX() {
        return width - PANEL_W - 4;
    }

    private void addPartsPanel() {
        int px = panelX();
        int btnY = height - 6 - ROW_H;

        // 맨 아래: [그룹으로 묶기] / [그룹 해제]
        boolean grouped = GizmoEditor.selectionGroup() != null;
        addRenderableWidget(Button.builder(
                        Component.translatable(grouped ? "dee.button.ungroup" : "dee.button.group"), b -> {
                            if (grouped) GizmoEditor.ungroupSelection();
                            else GizmoEditor.groupSelection();
                            rebuildWidgets();
                        })
                .bounds(px, btnY, PANEL_W, ROW_H)
                .tooltip(Tooltip.create(Component.translatable(grouped ? "dee.tooltip.ungroup" : "dee.tooltip.group")))
                .build());

        // 목록 (한 페이지에 들어가는 만큼만, 넘치면 [<] [>] 로 넘긴다)
        int rowH = 18;
        int pitch = rowH + 2;
        int listTop = 24 + 14;
        int pagerH = 16;
        int pagerY = btnY - GAP - pagerH;
        int perPage = Math.max(1, (pagerY - GAP - listTop) / pitch);
        partPages = (parts.size() + perPage - 1) / perPage;

        String current = draft.uuid();
        if (!partPageInit) {
            partPageInit = true;
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i).toString().equals(current)) {
                    partPage = i / perPage; // 지금 편집 중인 파트가 있는 페이지부터 보여 준다
                    break;
                }
            }
        }
        partPage = Math.max(0, Math.min(partPage, partPages - 1));

        int from = partPage * perPage;
        int to = Math.min(parts.size(), from + perPage);
        for (int i = from; i < to; i++) {
            UUID id = parts.get(i);
            String text = font.plainSubstrByWidth((i + 1) + ". " + partLabel(id), PANEL_W - 10);
            Component label = Component.literal(text);
            if (id.toString().equals(current)) {
                label = label.copy().withStyle(style -> style.withColor(0xFFFF55)); // 지금 편집 중
            }
            addRenderableWidget(Button.builder(label, b -> switchPart(id))
                    .bounds(px, listTop + (i - from) * pitch, PANEL_W, rowH).build());
        }

        if (partPages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                        partPage = Math.max(0, partPage - 1);
                        rebuildWidgets();
                    })
                    .bounds(px, pagerY, 24, pagerH).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                        partPage = Math.min(partPages - 1, partPage + 1);
                        rebuildWidgets();
                    })
                    .bounds(px + PANEL_W - 24, pagerY, 24, pagerH).build());
        }
    }

    /** 목록에 보여 줄 파트 이름: 텍스트는 내용, 블록/아이템은 ID. */
    private String partLabel(UUID id) {
        Display e = GizmoEditor.find(minecraft, id);
        if (e == null) return "?";
        CompoundTag nbt = EntityNbt.save(e);
        if (e instanceof Display.BlockDisplay) {
            return shortId(nbt.getCompoundOrEmpty("block_state").getStringOr("Name", "block"));
        }
        if (e instanceof Display.ItemDisplay) {
            return shortId(nbt.getCompoundOrEmpty("item").getStringOr("id", "item"));
        }
        Tag element = nbt.get("text");
        if (element != null) {
            Component c = ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, element).result().orElse(null);
            if (c != null) {
                String t = c.getString().replace('\n', ' ').trim();
                if (!t.isEmpty()) return t;
            }
        }
        return "text";
    }

    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** 목록에서 다른 파트를 눌렀을 때: 지금 파트의 바뀐 내용을 먼저 적용하고, 그 파트의 편집 화면으로 간다. */
    private void switchPart(UUID id) {
        if (id.toString().equals(draft.uuid())) return;
        Display target = GizmoEditor.find(minecraft, id);
        if (target == null) return;
        sendPending();
        DisplayEditClient.openEditorScreen(minecraft, target);
    }

    private Component panelTitle() {
        DisplayGroups.Group group = GizmoEditor.selectionGroup();
        String title = group != null
                ? Component.translatable("dee.panel.title_group", group.name, parts.size()).getString()
                : Component.translatable("dee.panel.title", parts.size()).getString();
        return Component.literal(font.plainSubstrByWidth(title, PANEL_W - 8));
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
            EditBox f = addScroll(new EditBox(font, left, 0, fieldW, ROW_H, Component.empty()), cy);
            f.setMaxLength(64);
            f.setHint(Component.translatable("dee.hint.tag"));
            f.setValue(rows.get(i));
            bindFilter(f, s -> s.matches("[A-Za-z0-9_.+\\-]*"), s -> draft.setTagRow(index, s));
            if (i == focusTagRow) setFocused(f);

            addScroll(Button.builder(Component.literal("\u00D7").withStyle(style -> style.withColor(0xFF5555)), b -> {
                        draft.removeTagRow(index);
                        rebuildWidgets();
                    })
                    .bounds(left + fieldW + GAP, 0, ROW_H, ROW_H)
                    .tooltip(Tooltip.create(Component.translatable("dee.tooltip.tag_remove")))
                    .build(), cy);
            cy += PITCH;
        }
        focusTagRow = -1;

        addScroll(Button.builder(Component.literal("+"), b -> {
                    draft.addTagRow();
                    focusTagRow = draft.getTagRows().size() - 1;
                    draft.setScroll(Double.MAX_VALUE); // init() 이 최대 스크롤로 줄여 주므로 새 줄이 보인다
                    rebuildWidgets();
                })
                .bounds(left, 0, contentW, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("dee.tooltip.tag_add")))
                .build(), cy);
        return cy + PITCH;
    }

    /** 맨 윗줄(텍스트): 텍스트 입력칸 + [고급]. 다음 줄의 contentY 를 돌려준다. */
    private int addTextRow(int cy) {
        // 텍스트 [고급]
        boolean advanced = draft.isAdvancedText();
        addLabel(Component.translatable(advanced ? "dee.label.text_nbt" : "dee.label.text"), left, cy);
        textField = addScroll(new EditBox(font, left + labelW, 0,
                contentW - labelW - GAP - ADV_W, ROW_H, Component.empty()), cy);
        if (advanced) {
            // 고급: text NBT 를 SNBT 그대로 편집한다. 예) {translate:"my.key"}, {text:"a",color:"red"}
            textField.setMaxLength(4096);
            Tag element = draft.get("text");
            textField.setValue(element == null ? "" : toListForm(element).toString());
            textField.setHint(Component.literal("{translate:\"key\"}"));
            textField.setResponder(this::onAdvancedTextEdit);
        } else {
            textField.setMaxLength(1024);
            textField.setValue(currentText().getString().replace("\n", "\\n"));
            if (draft.get("text") != null && !(draft.get("text") instanceof StringTag)) {
                textField.setTextColor(COLOR_STYLED);
                textField.setTooltip(Tooltip.create(Component.translatable("dee.tooltip.styled_text")));
            }
            textField.setResponder(s -> {
                textField.setTextColor(COLOR_OK);
                setPlainText(s);
            });
        }
        setInitialFocus(textField);

        addScroll(Button.builder(advancedLabel(), b -> {
                    draft.setAdvancedText(!draft.isAdvancedText());
                    rebuildWidgets(); // 입력칸을 모드에 맞게 다시 만든다 (값은 Draft 에 있으므로 유지됨)
                })
                .bounds(left + contentW - ADV_W, 0, ADV_W, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("dee.tooltip.advanced")))
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
        addLabel(Component.translatable("dee.label.item"), left, cy);
        itemField = addScroll(new EditBox(font, left + labelW, 0,
                contentW - labelW - GAP - pickW, ROW_H, Component.empty()), cy);
        addScroll(pickButton("dee.tooltip.pick_item", this::openItemPicker), cy);
        itemField.setMaxLength(256);
        itemField.setHint(Component.literal("minecraft:diamond_sword"));
        itemField.setTooltip(Tooltip.create(Component.translatable("dee.tooltip.item")));
        itemField.setValue(draft.getItemId());
        itemField.setResponder(s -> updateItem());

        Identifier id = parseItemId(draft.getItemId());
        itemField.setTextColor(id != null ? COLOR_OK : COLOR_ERR);
        boolean head = id != null && BuiltInRegistries.ITEM.getValue(id) == Items.PLAYER_HEAD;
        profileShown = head;
        cy += PITCH;

        profileField = null;
        if (head) {
            addLabel(Component.translatable("dee.label.item_profile"), left, cy);
            profileField = addScroll(new EditBox(font, left + labelW, 0,
                    contentW - labelW, ROW_H, Component.empty()), cy);
            profileField.setMaxLength(2048);
            profileField.setHint(Component.translatable("dee.hint.item_profile"));
            profileField.setTooltip(Tooltip.create(Component.translatable("dee.tooltip.item_profile")));
            profileField.setValue(ProfileText.format(draft.getItemProfile()));
            profileField.setResponder(this::updateProfile);
            cy += PITCH;
        }

        refreshPreviewStack();
        setInitialFocus(itemField);
        if (refocusItem) {
            // 스킨 칸이 생기거나 사라져서 화면을 다시 만들었을 때, 입력하던 칸과 커서 위치를 되돌린다.
            refocusItem = false;
            setFocused(itemField);
            itemField.setCursorPosition(itemCursor);
        }
        return cy;
    }

    /** 입력칸 오른쪽의 [고르기] 버튼. 누르면 전체 블록/아이템 목록 화면이 열린다. */
    private Button pickButton(String tooltipKey, Runnable action) {
        return Button.builder(Component.translatable("dee.button.pick"), b -> action.run())
                .bounds(left + contentW - pickW, 0, pickW, ROW_H)
                .tooltip(Tooltip.create(Component.translatable(tooltipKey)))
                .build();
    }

    private void openBlockPicker() {
        minecraft.gui.setScreen(new ItemPickerScreen(this, ItemPickerScreen.Mode.BLOCK,
                parseBlockId(blockField.getValue()), this::pickBlock));
    }

    private void openItemPicker() {
        minecraft.gui.setScreen(new ItemPickerScreen(this, ItemPickerScreen.Mode.ITEM,
                parseItemId(itemField.getValue()), id -> draft.setItemId(id.toString())));
    }

    /**
     * 목록에서 고른 블록을 Draft 에 저장한다. 다른 블록이 되므로 속성은 기본값으로 초기화된다.
     * 목록 화면이 닫히면 이 화면의 init() 이 다시 불려 입력칸과 미리보기가 Draft 에서 채워진다.
     */
    private void pickBlock(Identifier id) {
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        Tag tag = BlockState.CODEC.encodeStart(NbtOps.INSTANCE, block.defaultBlockState()).result().orElse(null);
        if (tag != null) draft.set("block_state", tag);
    }

    /** "oak_stairs" / "minecraft:oak_stairs" -> Identifier. 없는 블록이면 null. */
    private static Identifier parseBlockId(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        Identifier id = Identifier.tryParse(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
        return id != null && BuiltInRegistries.BLOCK.containsKey(id) ? id : null;
    }

    /** 아이템 ID 칸 입력 처리. 없는 아이템이면 빨간색이고 저장되지 않는다. */
    private void updateItem() {
        Identifier id = parseItemId(itemField.getValue());
        itemField.setTextColor(id != null ? COLOR_OK : COLOR_ERR);
        if (id == null) return;

        draft.setItemId(id.toString());
        refreshPreviewStack();

        boolean head = BuiltInRegistries.ITEM.getValue(id) == Items.PLAYER_HEAD;
        if (head != profileShown) {
            pendingRebuild = true;
            refocusItem = true;
            itemCursor = itemField.getCursorPosition();
        }
    }

    /** 스킨 칸 입력 처리. 비우면 기본 스킨(profile 제거), 형식이 틀리면 빨간색이고 저장되지 않는다. */
    private void updateProfile(String text) {
        if (text.isBlank()) {
            draft.setItemProfile(null);
            profileField.setTextColor(COLOR_OK);
        } else {
            var parsed = ProfileText.parse(text);
            if (parsed.isPresent()) draft.setItemProfile(parsed.get());
            profileField.setTextColor(parsed.isPresent() ? COLOR_OK : COLOR_ERR);
        }
        refreshPreviewStack();
    }

    /** "diamond_sword" / "minecraft:diamond_sword" -> Identifier. 없는 아이템이나 air 면 null. */
    private static Identifier parseItemId(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        Identifier id = Identifier.tryParse(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return null;
        return BuiltInRegistries.ITEM.getValue(id) == Items.AIR ? null : id;
    }

    /** Draft 의 item 으로 미리보기용 ItemStack 을 다시 만든다. (코덱으로 읽는다) */
    private void refreshPreviewStack() {
        previewStack = ItemStack.EMPTY;
        Tag item = draft.get("item");
        if (item == null || minecraft == null || minecraft.level == null) return;
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, minecraft.level.registryAccess());
        previewStack = ItemStack.CODEC.parse(ops, item).result().orElse(ItemStack.EMPTY);
    }

    @Override
    public void tick() {
        super.tick();
        if (pendingRebuild) {
            pendingRebuild = false;
            rebuildWidgets();
        }
    }

    /**
     * 맨 윗줄(블록): block_name 입력칸 + 그 아래 block_properties 입력칸.
     * 예) block_name = oak_stairs, block_properties = facing=east,half=top
     * 두 칸을 합쳐 block_state 를 만든다. (속성이 없으면 "이름" 문자열, 있으면 {Name:..,Properties:{..}})
     */
    private int addBlockRow(int cy) {
        Tag element = draft.get("block_state");

        addLabel(Component.translatable("dee.label.block"), left, cy);
        blockField = addScroll(new EditBox(font, left + labelW, 0,
                contentW - labelW - GAP - pickW, ROW_H, Component.empty()), cy);
        addScroll(pickButton("dee.tooltip.pick_block", this::openBlockPicker), cy);
        blockField.setMaxLength(256);
        blockField.setHint(Component.literal("minecraft:oak_stairs"));
        blockField.setTooltip(Tooltip.create(Component.translatable("dee.tooltip.block")));
        BlockState current = element == null ? null : decodeState(element);
        blockField.setValue(current != null
                ? BuiltInRegistries.BLOCK.getKey(current.getBlock()).toString() : blockNameOf(element));

        addLabel(Component.translatable("dee.label.block_properties"), left, cy + PITCH);
        propsField = addScroll(new EditBox(font, left + labelW, 0,
                contentW - labelW, ROW_H, Component.empty()), cy + PITCH);
        propsField.setMaxLength(1024);
        propsField.setHint(Component.literal("facing=east,half=top"));
        propsField.setValue(current != null ? propertiesOf(current) : "");

        previewState = current;
        blockField.setResponder(s -> updateBlockState());
        propsField.setResponder(s -> updateBlockState());
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
        String name = blockField.getValue().trim();

        // 이름만으로 먼저 검사
        Block block = name.isEmpty() ? null : decodeBlock(StringTag.valueOf(name), name);
        blockField.setTextColor(block != null ? COLOR_OK : COLOR_ERR);
        if (block == null) {
            propsField.setTextColor(COLOR_OK);
            return;
        }

        // 속성을 하나씩 적용 (블록의 속성 정의로 검사)
        CompoundTag properties = parseProperties(propsField.getValue());
        BlockState state = properties == null ? null : block.defaultBlockState();
        if (state != null) {
            for (String key : properties.keySet()) {
                Property<?> property = block.getStateDefinition().getProperty(key);
                state = property == null ? null : withValue(state, property, properties.getStringOr(key, ""));
                if (state == null) break;
            }
        }

        Tag tag = state == null ? null
                : BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).result().orElse(null);
        if (tag == null) {
            propsField.setTextColor(COLOR_ERR);
            return;
        }
        propsField.setTextColor(COLOR_OK);
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
        String name = blockField.getValue().trim();
        String text = propsField.getValue();
        int cursor = propsField.getCursorPosition();
        String key = name + '\u0000' + text + '\u0000' + cursor;
        if (key.equals(propsTipKey)) return;
        propsTipKey = key;
        propsField.setTooltip(Tooltip.create(buildPropsTooltip(name, text, cursor)));
    }

    private Component buildPropsTooltip(String name, String text, int cursor) {
        Block block = name.isEmpty() ? null : decodeBlock(StringTag.valueOf(name), name);
        if (block == null) return Component.translatable("dee.tooltip.block_properties");

        Collection<Property<?>> defs = block.getStateDefinition().getProperties();
        if (defs.isEmpty()) return Component.translatable("dee.tooltip.props.none", block.getName());

        CompoundTag entered = parseProperties(text);
        String[] active = activeSegment(text, cursor);
        BlockState defaults = block.defaultBlockState();

        MutableComponent out = Component.translatable("dee.tooltip.props.header", block.getName())
                .withStyle(ChatFormatting.GOLD);
        out.append(Component.literal("\n").append(Component.translatable("dee.tooltip.props.legend"))
                .withStyle(ChatFormatting.DARK_GRAY));

        List<String> known = new ArrayList<>();
        for (Property<?> property : defs) {
            known.add(property.getName());
            out.append(propertyRow(property, defaults, entered, active));
        }

        // 오류 안내
        if (entered == null) {
            out.append(Component.literal("\n").append(Component.translatable("dee.tooltip.props.syntax"))
                    .withStyle(ChatFormatting.RED));
        } else {
            for (String key : entered.keySet()) {
                if (!known.contains(key)) {
                    out.append(Component.literal("\n").append(Component.translatable("dee.tooltip.props.unknown_key", key))
                            .withStyle(ChatFormatting.RED));
                }
            }
        }
        return out;
    }

    /**
     * 속성 한 줄: "▶ facing: north south* west east".
     * 초록 = 입력한 값, 노랑 = 지금 입력 중인 글자로 시작하는 후보, 회색 = 그 외, * = 기본값, 빨강 = 쓸 수 없는 값.
     */
    private static <T extends Comparable<T>> Component propertyRow(
            Property<T> property, BlockState defaults, CompoundTag entered, String[] active) {
        String pname = property.getName();
        String enteredValue = entered != null && entered.contains(pname) ? entered.getStringOr(pname, "") : null;
        T def = defaults.getValue(property);

        // 커서가 있는 줄: 키 입력 중이면 접두어가 맞는 속성, "키=값" 이면 그 속성
        String activeKey = active[0];
        String typed = active[1];
        boolean isActive = !activeKey.isEmpty()
                && (typed == null ? pname.startsWith(activeKey) : pname.equals(activeKey));
        String prefix = typed != null && isActive ? typed : "";

        MutableComponent line = Component.literal("\n");
        line.append(Component.literal(isActive ? "\u25B6 " : "  ").withStyle(ChatFormatting.YELLOW));
        line.append(Component.literal(pname).withStyle(isActive ? ChatFormatting.WHITE : ChatFormatting.GRAY));
        line.append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY));

        List<T> values = new ArrayList<>(property.getPossibleValues());
        if (values.size() > MAX_LISTED_VALUES) {
            line.append(Component.translatable("dee.tooltip.props.range",
                            property.getName(values.get(0)), property.getName(values.get(values.size() - 1)), values.size())
                    .withStyle(ChatFormatting.GRAY));
            if (enteredValue != null) {
                boolean ok = property.getValue(enteredValue).isPresent();
                line.append(Component.literal(" = " + enteredValue).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
            return line;
        }

        boolean first = true;
        for (T value : values) {
            String vn = property.getName(value);
            if (!first) line.append(Component.literal(" "));
            first = false;
            ChatFormatting color = vn.equals(enteredValue) ? ChatFormatting.GREEN
                    : (!prefix.isEmpty() && vn.startsWith(prefix)) ? ChatFormatting.YELLOW
                      : ChatFormatting.GRAY;
            line.append(Component.literal(vn + (value.equals(def) ? "*" : "")).withStyle(color));
        }
        if (enteredValue != null && property.getValue(enteredValue).isEmpty()) {
            line.append(Component.literal(" \u2717 " + enteredValue).withStyle(ChatFormatting.RED));
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
        Optional<T> parsed = property.getValue(value);
        return parsed.isPresent() ? state.setValue(property, parsed.get()) : null;
    }

    /** 기본값과 다른 속성만 "k=v,k=v" 로. */
    private static String propertiesOf(BlockState state) {
        BlockState defaults = state.getBlock().defaultBlockState();
        StringBuilder sb = new StringBuilder();
        for (Property<?> property : state.getBlock().getStateDefinition().getProperties()) {
            String entry = nonDefault(state, defaults, property);
            if (entry == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(entry);
        }
        return sb.toString();
    }

    private static <T extends Comparable<T>> String nonDefault(BlockState state, BlockState defaults, Property<T> property) {
        T value = state.getValue(property);
        return value.equals(defaults.getValue(property)) ? null : property.getName() + "=" + property.getName(value);
    }

    private static BlockState decodeState(Tag tag) {
        return BlockState.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(null);
    }

    /** "facing=east,half=top" (앞뒤 [ ] 는 있어도 됨) -> {facing:"east",half:"top"}. 문법 오류면 null. */
    private static CompoundTag parseProperties(String text) {
        String s = text.trim();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        CompoundTag out = new CompoundTag();
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
    private static String stringOf(Tag tag) {
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("v", tag);
        return wrapper.getStringOr("v", "");
    }

    /** block_state 에서 이름 부분. ("이름[k=v]" 문자열이든 {Name:..} 이든) */
    private static String blockNameOf(Tag tag) {
        if (tag == null) return "";
        if (tag instanceof CompoundTag state) return state.getStringOr("Name", "");
        String raw = stringOf(tag);
        int open = raw.indexOf('[');
        return open >= 0 ? raw.substring(0, open) : raw;
    }

    /**
     * 코덱으로 읽어 블록을 얻는다. 읽을 수 없으면 null.
     * 코덱은 없는 블록 이름을 공기로 읽어 버리므로, 이름이 air 로 끝나지 않는데 공기면 없는 블록으로 본다.
     */
    private static Block decodeBlock(Tag tag, String name) {
        Block block = BlockState.CODEC.parse(NbtOps.INSTANCE, tag).result().map(BlockState::getBlock).orElse(null);
        if (block != null && block.defaultBlockState().isAir() && !name.endsWith("air")) return null;
        return block;
    }

    /** 스타일 섹션(텍스트). 다음 줄의 contentY 를 돌려준다. */
    private int addTextStyle(int cy, int colW, int rightX, int thirdW) {
        // 줄 너비 | 불투명도
        addLabel(Component.translatable("dee.label.line_width"), left, cy);
        EditBox lineWidth = addScroll(new EditBox(font,
                left + labelW, 0, colW - labelW, ROW_H, Component.empty()), cy);
        lineWidth.setValue(String.valueOf(Math.max(1, draft.getInt("line_width", 200))));
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
        addLabel(Component.translatable("dee.label.background"), left, cy);
        addHexField(left + labelW, cy, colW - labelW, "background", true);
        addLabel(Component.translatable("dee.label.glow"), rightX, cy);
        addHexField(rightX + labelW, cy, colW - labelW, "glow_color_override", false);
        cy += PITCH;
        return cy;
    }

    /** 스타일 섹션(아이템): 빌보드 | 발광색, 그 아래 표시 방식 */
    private int addItemStyle(int cy, int colW, int rightX) {
        addScroll(cycleButton(left, colW, "dee.option.billboard", "billboard", "fixed", BILLBOARD, "dee.billboard."), cy);
        addLabel(Component.translatable("dee.label.glow"), rightX, cy);
        addHexField(rightX + labelW, cy, colW - labelW, "glow_color_override", false);
        cy += PITCH;
        addScroll(cycleButton(left, contentW, "dee.option.item_context", "item_display", "none",
                ITEM_CONTEXT, "dee.item_context."), cy);
        return cy + PITCH;
    }

    /** 스타일 섹션(블록): 빌보드 | 발광색 */
    private int addBlockStyle(int cy, int colW, int rightX) {
        addScroll(cycleButton(left, colW, "dee.option.billboard", "billboard", "fixed", BILLBOARD, "dee.billboard."), cy);
        addLabel(Component.translatable("dee.label.glow"), rightX, cy);
        addHexField(rightX + labelW, cy, colW - labelW, "glow_color_override", false);
        return cy + PITCH;
    }

    private Component deleteLabel() {
        return Component.translatable(deleteArmed ? "dee.button.delete_confirm" : "dee.button.delete")
                .withStyle(style -> style.withColor(0xFF5555));
    }

    /** /kill 명령으로 엔티티를 지운다. (OP 권한 필요) 편집 중이던 변경 사항은 버려진다. */
    private void deleteEntity() {
        try {
            Display entity = GizmoEditor.find(minecraft, UUID.fromString(draft.uuid()));
            if (entity != null) {
                // 되돌리기 기록이 붙는 삭제 (그룹에서도 빠진다)
                EditorOps.delete(List.of(entity), Component.translatable("dee.history.delete"));
                onClose();
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // 아래의 기본 삭제로 처리한다
        }
        var handler = minecraft.getConnection();
        if (handler != null) {
            handler.sendCommand("kill " + draft.uuid());
        }
        try {
            DisplayGroups.forget(UUID.fromString(draft.uuid())); // 그룹에서도 뺀다
        } catch (IllegalArgumentException ignored) {
            // UUID 형식이 아니면 그룹에 없다
        }
        onClose();
    }

    /** 바뀐 내용을 명령으로 보낸다. (화면은 닫지 않는다) */
    private void sendPending() {
        if (minecraft.getConnection() == null) return;
        if (draft.isCreating()) {
            // 새로 만들기: UUID 를 정해 summon 해야 되돌리기로 지울 수 있다
            UUID id = UUID.randomUUID();
            GizmoEditor.send(draft.buildSummonCommand(id));
            EditorOps.recordCreate(id);
            return;
        }
        List<String> commands = draft.buildCommands();
        if (commands.isEmpty()) return;
        try {
            Display entity = GizmoEditor.find(minecraft, UUID.fromString(draft.uuid()));
            if (entity != null) EditorOps.recordEdit(entity); // 바뀌기 전 모습을 기록 (Ctrl+Z 용)
        } catch (IllegalArgumentException ignored) {
            // UUID 형식이 아니면 기록만 건너뛴다
        }
        for (String command : commands) GizmoEditor.send(command);
    }

    private void apply() {
        sendPending();
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // 스크롤 박스
    // ------------------------------------------------------------------

    /** 접기/펼치기 머리줄. 누르면 Draft 에 상태를 저장하고 화면을 다시 만든다. */
    private int addSection(int index, String titleKey, int cy) {
        boolean open = draft.isSectionOpen(index);
        Component title = Component.literal(open ? "▼ " : "▶ ").append(Component.translatable(titleKey));
        addScroll(Button.builder(title, b -> {
                    draft.toggleSection(index);
                    rebuildWidgets();
                })
                .bounds(left, 0, contentW, ROW_H).build(), cy);
        return cy + PITCH;
    }

    /** 스크롤 박스에 위젯을 넣는다. y 는 내용 기준 위치(contentY)이고 실제 y 는 relayout() 이 정한다. */
    private <T extends AbstractWidget> T addScroll(T widget, int contentY) {
        entries.add(new Entry(widget, contentY));
        scrollWidgets.add(widget);
        return addWidget(widget);
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
            AbstractWidget w = e.widget();
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

    // 1.21.9 부터 마우스 입력은 MouseButtonEvent 로 전달된다. (26.3 은 SDL 이라 버튼 번호가 달라서 상수를 쓴다)
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        boolean left = event.button() == InputConstants.MOUSE_BUTTON_LEFT;
        // 스크롤바 잡기
        if (left && maxScroll() > 0 && inBox(mouseX, mouseY) && mouseX >= boxR - SCROLL_RESERVE + 1) {
            draggingBar = true;
            dragBarTo(mouseY);
            return true;
        }
        // 박스 밖 클릭은 (일부만 보이는) 스크롤 위젯으로 넘기지 않는다
        if (!inBox(mouseX, mouseY)) {
            for (GuiEventListener child : children()) {
                if (!scrollWidgets.contains(child) && child.mouseClicked(event, doubleClick)) {
                    setFocused(child);
                    if (left) setDragging(true);
                    return true;
                }
            }
            setFocused(null);
            return false;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingBar) {
            dragBarTo(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingBar = false;
        return super.mouseReleased(event);
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

    private void addLabel(Component text, int x, int contentY) {
        labels.add(new Label(text, x, contentY));
    }

    /** 주어진 번역 키들 중 가장 넓은 글자 너비(px) */
    private int widest(String... keys) {
        int w = 0;
        for (String key : keys) w = Math.max(w, font.width(Component.translatable(key)));
        return w;
    }

    private Button cycleButton(int x, int w, String titleKey, String key, String fallback,
                               String[] values, String valuePrefix) {
        return Button.builder(cycleLabel(titleKey, key, fallback, values, valuePrefix), b -> {
            int next = (indexOf(values, draft.getString(key, fallback)) + 1) % values.length;
            draft.setString(key, values[next]);
            b.setMessage(cycleLabel(titleKey, key, fallback, values, valuePrefix));
        }).bounds(x, 0, w, ROW_H).build();
    }

    private Component cycleLabel(String titleKey, String key, String fallback, String[] values, String valuePrefix) {
        String current = draft.getString(key, fallback);
        Component value = indexOf(values, current) >= 0
                ? Component.translatable(valuePrefix + current)
                : Component.literal(current);
        return Component.translatable("dee.option.entry", Component.translatable(titleKey), value);
    }

    private Button toggleButton(int x, int w, String titleKey, String key) {
        return Button.builder(toggleLabel(titleKey, key), b -> {
            draft.setBool(key, !draft.getBool(key, false));
            b.setMessage(toggleLabel(titleKey, key));
        }).bounds(x, 0, w, ROW_H).build();
    }

    private Component autoAlignLabel() {
        return Component.translatable("dee.option.entry", Component.translatable("dee.option.auto_align"),
                Component.translatable(draft.isAutoAlign() ? "dee.state.on" : "dee.state.off"));
    }

    private Component toggleLabel(String titleKey, String key) {
        return Component.translatable("dee.option.entry", Component.translatable(titleKey),
                Component.translatable(draft.getBool(key, false) ? "dee.state.on" : "dee.state.off"));
    }

    /** #RRGGBB / #AARRGGBB 입력칸. 비우면 키를 삭제(=기본값)한다. */
    private void addHexField(int x, int contentY, int w, String key, boolean withAlpha) {
        EditBox f = addScroll(new EditBox(font, x, 0, w, ROW_H, Component.empty()), contentY);
        f.setMaxLength(9);
        f.setHint(Component.literal(withAlpha ? "#AARRGGBB" : "#RRGGBB"));
        if (draft.has(key)) {
            int v = draft.getInt(key, 0);
            f.setValue(withAlpha ? String.format(Locale.ROOT, "#%08X", v)
                    : String.format(Locale.ROOT, "#%06X", v & 0xFFFFFF));
        }
        bindFilter(f, s -> s.matches("#?[0-9a-fA-F]{0,8}"), s -> {
            if (s.isEmpty()) {
                draft.remove(key);
                f.setTextColor(COLOR_OK);
                return;
            }
            String hex = s.startsWith("#") ? s.substring(1) : s;
            boolean ok = hex.length() == 6 || hex.length() == 8;
            if (ok) {
                long v = Long.parseLong(hex, 16);
                if (withAlpha && hex.length() == 6) v |= 0xFF000000L;
                draft.setInt(key, (int) v);
            }
            f.setTextColor(ok ? COLOR_OK : COLOR_ERR);
        });
    }

    private final class OpacitySlider extends AbstractSliderButton {
        OpacitySlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), draft.getUnsignedByte("text_opacity", 255) / 255.0);
            updateMessage();
        }

        private int opacity() {
            return (int) Math.round(value * 255);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("dee.option.opacity", opacity()));
        }

        @Override
        protected void applyValue() {
            draft.setUnsignedByte("text_opacity", opacity());
        }
    }

    /**
     * 입력 필터. 26.1 에서 EditBox#setFilter 가 제거되어, 응답자(responder) 안에서 직접 걸러낸다.
     * 허용되지 않는 입력이 들어오면 마지막으로 허용된 값으로 되돌린다.
     */
    private static void bindFilter(EditBox box, Predicate<String> filter, Consumer<String> onValid) {
        String[] last = {filter.test(box.getValue()) ? box.getValue() : ""};
        boolean[] reverting = {false};
        box.setResponder(s -> {
            if (reverting[0]) return;
            if (!filter.test(s)) {
                reverting[0] = true;
                try {
                    box.setValue(last[0]);
                } finally {
                    reverting[0] = false;
                }
                return;
            }
            last[0] = s;
            onValid.accept(s);
        });
    }

    private Component advancedLabel() {
        Component label = Component.translatable("dee.button.advanced");
        return draft.isAdvancedText() ? label.copy().withStyle(style -> style.withColor(0xFFFF55)) : label;
    }

    /** 고급 모드 입력: SNBT 로 파싱되고 텍스트 컴포넌트로도 유효할 때만 저장한다. 비우면 빈 텍스트. */
    private void onAdvancedTextEdit(String s) {
        if (s.isBlank()) {
            draft.set("text", StringTag.valueOf(""));
            textField.setTextColor(COLOR_OK);
            return;
        }
        var parsed = Draft.parseSnbt(s);
        boolean ok = parsed.isPresent()
                && ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, parsed.get()).result().isPresent();
        if (ok) draft.set("text", parsed.get());
        textField.setTextColor(ok ? COLOR_OK : COLOR_ERR);
    }

    // ------------------------------------------------------------------
    // 텍스트 변환
    // ------------------------------------------------------------------

    /**
     * 게임은 [a,b,c] 를 {a..., extra:[b,c]} 로 합쳐서 저장하므로,
     * 고급 입력칸에 보여 줄 때만 원래의 리스트 형태로 되돌린다. (의미는 동일)
     */
    private static Tag toListForm(Tag element) {
        if (element instanceof CompoundTag compound
                && compound.get("extra") instanceof ListTag extra
                && !extra.isEmpty()) {
            CompoundTag head = compound.copy();
            head.remove("extra");
            ListTag out = new ListTag();
            out.add(head);
            for (int i = 0; i < extra.size(); i++) out.add(extra.get(i));
            return out;
        }
        return element;
    }

    private Component currentText() {
        Tag element = draft.get("text");
        if (element == null) return Component.empty();
        return ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, element).result()
                .orElseGet(() -> Component.literal(element.toString()));
    }

    /** 입력칸의 한 줄 문자열을 text NBT 로 저장. "\n" 을 입력하면 줄바꿈이 된다. */
    private void setPlainText(String raw) {
        String s = raw.replace("\\n", "\n");
        Tag element = ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, Component.literal(s)).result()
                .orElseGet(() -> StringTag.valueOf(s));
        draft.set("text", element);
    }

    // ------------------------------------------------------------------
    // 렌더링
    // ------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // 커서 이동/입력에 맞춰 속성 툴팁 내용을 갱신 (바뀐 경우에만 다시 만든다)
        refreshPropsTooltip();
        // 오른쪽 파트 목록의 바탕 (버튼보다 먼저 그린다)
        if (parts.size() >= 2) {
            int px = panelX();
            context.fill(px - 1, 23, px + PANEL_W + 1, height - 5, 0xFF000000);
            context.fill(px, 24, px + PANEL_W, height - 6, 0xA0000000);
        }
        // 고정 위젯(하단 버튼)과 배경
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(font, titleFor(draft), boxL + boxW / 2, 6, 0xFFFFFFFF);
        if (parts.size() >= 2) {
            int px = panelX();
            context.text(font, panelTitle(), px + 4, 28, 0xFFFFFFFF, false);
            if (partPages > 1) {
                int btnY = height - 6 - ROW_H;
                int pagerY = btnY - GAP - 16;
                context.centeredText(font, Component.literal((partPage + 1) + " / " + partPages),
                        px + PANEL_W / 2, pagerY + 4, 0xFFB0B0B0);
            }
        }
        drawPreview(context);

        // 스크롤 박스: 박스 밖에서는 호버 효과가 생기지 않도록 마우스 좌표를 치운다
        context.fill(boxL - 1, boxTop - 1, boxR + 1, boxBottom + 1, 0xFF000000);
        context.fill(boxL, boxTop, boxR, boxBottom, 0xA0000000);

        boolean hover = inBox(mouseX, mouseY);
        int mx = hover ? mouseX : -10000;
        int my = hover ? mouseY : -10000;

        context.enableScissor(boxL, boxTop, boxR, boxBottom);
        for (Entry e : entries) {
            if (e.widget().visible) e.widget().extractRenderState(context, mx, my, delta);
        }
        int origin = originY();
        for (Label l : labels) {
            int y = origin + l.contentY();
            if (y + ROW_H > boxTop && y < boxBottom) {
                context.text(font, l.text(), l.x(), y + 6, 0xFFB0B0B0, false);
            }
        }
        context.disableScissor();

        drawScrollbar(context);
    }

    private void drawScrollbar(GuiGraphicsExtractor context) {
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

    /** 인벤토리 블록 아이콘과 같은 등각 시점. (Z 반전은 GUI 의 y 축이 아래로 향하기 때문) */
    private static final Quaternionf BLOCK_VIEW = new Quaternionf()
            .rotateZ((float) Math.PI)
            .rotateX((float) Math.toRadians(-30))
            .rotateY((float) Math.toRadians(45));

    /**
     * 블록 미리보기: 블록 디스플레이 엔티티가 그리는 방식 그대로(BlockState 의 실제 블록 모델) 렌더링한다.
     * 아이템 모델이 아니므로 문, 묘목, 울타리 같은 블록도 월드에 놓였을 때의 모습으로 보이고,
     * facing/half 등 속성도 반영된다. 크기(X,Y,Z)도 반영된다.
     */
    private void drawBlockPreview(GuiGraphicsExtractor context, int x1, int y1, int x2, int y2) {
        BlockState state = previewState;
        if (state == null) return;

        float[] scale = draft.getScale();
        float sx = Math.max(0.05f, Math.abs(scale[0]));
        float sy = Math.max(0.05f, Math.abs(scale[1]));
        float sz = Math.max(0.05f, Math.abs(scale[2]));

        int ry2 = y2 - font.lineHeight - 4; // 아래 이름 글자 자리 제외
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

        if (blockModelResolver == null) {
            blockModelResolver = new BlockModelResolver(minecraft.getModelManager());
        }
        BlockDisplayEntityRenderState renderState = new BlockDisplayEntityRenderState();
        renderState.entityType = EntityTypes.BLOCK_DISPLAY;
        blockModelResolver.update(renderState.blockModel, state, DisplayRenderer.BLOCK_DISPLAY_CONTEXT);

        context.enableScissor(x1, y1, x2, y2);
        if (renderState.hasSubState()) {
            // 블록은 엔티티 원점에서 0~1 범위이므로, 크기를 적용한 뒤 중심이 원점에 오도록 옮긴다.
            Transformation transformation = new Transformation(
                    new Vector3f(-sx / 2, -sy / 2, -sz / 2), new Quaternionf(),
                    new Vector3f(sx, sy, sz), new Quaternionf());
            renderState.renderState = new Display.RenderState(
                    Display.GenericInterpolator.constant(transformation),
                    Display.BillboardConstraints.FIXED, -1,
                    Display.FloatInterpolator.constant(0f), Display.FloatInterpolator.constant(0f), -1);
            renderState.interpolationProgress = 1f;
            context.entity(renderState, pxPerUnit, new Vector3f(), BLOCK_VIEW, null, x1, y1, x2, ry2);
        } else {
            // 모델이 없는 블록(물, 불 등)은 대표색 사각형으로 대체
            int w = Math.max(1, Math.round(pxPerUnit * sx));
            int h = Math.max(1, Math.round(pxPerUnit * sy));
            int bx = (x1 + x2) / 2 - w / 2;
            int by = (y1 + ry2) / 2 - h / 2;
            int rgb = state.getBlock().defaultMapColor().col & 0xFFFFFF;
            if (rgb != 0) {
                context.fill(bx - 1, by - 1, bx + w + 1, by + h + 1, 0xFF000000);
                context.fill(bx, by, bx + w, by + h, 0xFF000000 | rgb);
            }
        }
        context.centeredText(font, state.getBlock().getName(), (x1 + x2) / 2, y2 - font.lineHeight - 2, 0xFFFFFFFF);
        context.disableScissor();
    }

    /**
     * 아이템 미리보기: 아이템의 GUI 아이콘 모습. (플레이어 헤드는 스킨이 반영된다)
     * 실제 디스플레이는 '표시 방식'에 따라 모양이 조금 달라질 수 있다. 크기(X,Y)가 반영된다.
     */
    private void drawItemPreview(GuiGraphicsExtractor context, int x1, int y1, int x2, int y2) {
        ItemStack stack = previewStack;
        if (stack.isEmpty()) return;

        float[] scale = draft.getScale();
        float sx = Math.max(0.05f, Math.abs(scale[0]));
        float sy = Math.max(0.05f, Math.abs(scale[1]));
        float base = 48f; // 크기 1.0 일 때 한 변 (px)
        float availH = y2 - y1 - 8f - font.lineHeight; // 아래 이름 글자 자리 제외
        float fit = Math.min(1f, Math.min((boxW - 12f) / (base * sx), availH / (base * sy)));
        fit = Math.max(fit, 0.01f);

        context.enableScissor(x1, y1, x2, y2);
        // 아이템 아이콘은 16x16 이므로 목표 크기에 맞게 확대한다.
        float k = base * fit / 16f;
        float cy = (y1 + y2 - font.lineHeight) / 2f;
        Matrix3x2fStack m = context.pose();
        m.pushMatrix();
        m.translate((x1 + x2) / 2f, cy);
        m.scale(sx * k, sy * k);
        m.translate(-8f, -8f);
        context.item(stack, 0, 0);
        m.popMatrix();
        context.centeredText(font, stack.getHoverName(), (x1 + x2) / 2, y2 - font.lineHeight - 2, 0xFFFFFFFF);
        context.disableScissor();
    }

    /** 대략적인 미리보기. 줄 너비·정렬·배경·불투명도·그림자·크기(X,Y)가 반영된다. */
    private void drawPreview(GuiGraphicsExtractor context) {
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

        List<FormattedCharSequence> lines = font.split(currentText(), Math.max(1, draft.getInt("line_width", 200)));
        if (lines.isEmpty()) return;

        int lineH = font.lineHeight + 1;
        int textW = 0;
        for (FormattedCharSequence line : lines) textW = Math.max(textW, font.width(line));
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
        Matrix3x2fStack matrices = context.pose();
        matrices.pushMatrix();
        matrices.translate((x1 + x2) / 2f, (y1 + y2) / 2f);
        matrices.scale(sx * fit, sy * fit);
        matrices.translate(-bgW / 2f, -bgH / 2f);

        context.fill(0, 0, bgW, bgH, bg);
        for (int i = 0; i < lines.size(); i++) {
            FormattedCharSequence line = lines.get(i);
            int w = font.width(line);
            int dx = switch (align) {
                case "left" -> 0;
                case "right" -> textW - w;
                default -> (textW - w) / 2;
            };
            context.text(font, line, 1 + dx, 1 + i * lineH, color, shadow);
        }

        matrices.popMatrix();
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