package com.dee.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemGroup;
import org.lwjgl.glfw.GLFW;
import net.minecraft.util.Formatting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 크리에이티브 인벤토리(CreativeModeInventoryScreen)와 같은 모양/배치의 "고르기" 화면.
 * 바닐라의 탭(CreativeModeTabs), 배경/탭/스크롤러 텍스처, 9x5 슬롯 격자, 검색 탭을 그대로 쓴다.
 * 다만 아이템을 집어 드는 대신, 슬롯을 누르면 그 항목의 ID 를 onPick 으로 넘기고 parent 화면으로 돌아간다.
 *
 * - BLOCK 모드: 블록이 되는 항목만 보여 준다. (탭에 없는 블록, 아이템이 없는 물·불 같은 블록은 검색 탭에서 검색하면 나온다)
 * - ITEM 모드: 모든 아이템. (탭에 없는 숨은 아이템도 검색하면 나온다)
 * - 같은 아이템의 변형(포션, 마법 부여 책 등)은 하나로 합친다. ID 만 돌려주기 때문이다.
 * - 검색: 이름/ID (단어를 띄어 쓰면 모두 포함), "#태그" 검색도 바닐라처럼 된다.
 */
public class ItemPickerScreen extends Screen {
    public enum Mode { BLOCK, ITEM }

    /** stack 이 비어 있으면(아이템이 없는 블록) color 로 대신 그린다. */
    private record Entry(Identifier id, Text name, ItemStack stack, int color, String searchKey) {}

    // ---- 바닐라 CreativeModeInventoryScreen 과 같은 상수 ----
    private static final Identifier SCROLLER_SPRITE = Identifier.ofVanilla("container/creative_inventory/scroller");
    private static final Identifier SCROLLER_DISABLED_SPRITE = Identifier.ofVanilla("container/creative_inventory/scroller_disabled");
    private static final Identifier[] UNSELECTED_TOP_TABS = tabSprites("top_unselected");
    private static final Identifier[] SELECTED_TOP_TABS = tabSprites("top_selected");
    private static final Identifier[] UNSELECTED_BOTTOM_TABS = tabSprites("bottom_unselected");
    private static final Identifier[] SELECTED_BOTTOM_TABS = tabSprites("bottom_selected");
    private static final int IMAGE_W = 195;
    private static final int IMAGE_H = 136;
    private static final int NUM_ROWS = 5;
    private static final int NUM_COLS = 9;
    private static final int SLOTS = NUM_ROWS * NUM_COLS;
    private static final int COLOR_CURRENT = 0xFFFFD27F;

    private final Screen parent;
    private final Mode mode;
    /** 지금 입력칸에 있는 값. 슬롯에 테두리로 표시하고, 처음 열 때 그 항목이 있는 탭으로 간다. (없으면 null) */
    private final Identifier currentId;
    private final Consumer<Identifier> onPick;

    private boolean built;
    private final Map<ItemGroup, List<Entry>> byTab = new LinkedHashMap<>();
    private final List<ItemGroup> shownTabs = new ArrayList<>();
    /** 검색 탭의 기본 목록 / 검색할 때만 같이 뒤지는 숨은 항목들 */
    private List<Entry> searchList = List.of();
    private List<Entry> hiddenEntries = List.of();

    private ItemGroup selectedTab;
    /** 지금 슬롯 격자에 보여 주는 목록 */
    private List<Entry> view = List.of();
    private float scrollOffs;
    private boolean scrolling;
    private TextFieldWidget searchBox;

    private int leftPos;
    private int topPos;

    public ItemPickerScreen(Screen parent, Mode mode, Identifier currentId, Consumer<Identifier> onPick) {
        super(Text.translatable(mode == Mode.BLOCK ? "dee.picker.title.block" : "dee.picker.title.item"));
        this.parent = parent;
        this.mode = mode;
        this.currentId = currentId;
        this.onPick = onPick;
    }

    private static Identifier[] tabSprites(String kind) {
        Identifier[] out = new Identifier[7];
        for (int i = 0; i < out.length; i++) {
            out[i] = Identifier.ofVanilla("container/creative_inventory/tab_" + kind + "_" + (i + 1));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 목록 만들기
    // ------------------------------------------------------------------

    /** 탭 내용을 (필요하면) 다시 만들고, 탭마다 고를 수 있는 항목 목록을 만든다. 화면을 처음 열 때 한 번만 한다. */
    private void buildEntries() {
        ClientPlayerEntity player = client.player;
        // 바닐라 크리에이티브 화면이 열릴 때와 같은 호출. (탭 내용이 아직 안 만들어졌으면 만든다)
        ItemGroups.updateDisplayContext(player.networkHandler.getEnabledFeatures(),
                player.isCreativeLevelTwoOp(), player.getWorld().getRegistryManager());

        byTab.clear();
        shownTabs.clear();
        for (ItemGroup tab : ItemGroups.getGroups()) {
            if (tab.getType() != ItemGroup.Type.CATEGORY) continue; // 핫바/인벤토리 탭은 쓰지 않는다
            List<Entry> list = toEntries(tab.getDisplayStacks(), new HashSet<>());
            if (list.isEmpty()) continue; // 고를 게 없는 탭(블록 모드의 음식 탭 등)은 숨긴다
            byTab.put(tab, list);
            shownTabs.add(tab);
        }

        ItemGroup search = ItemGroups.getSearchGroup();
        Set<Object> seen = new HashSet<>();
        searchList = toEntries(search.getDisplayStacks(), seen);
        hiddenEntries = hiddenEntries(seen);
        byTab.put(search, searchList);
        shownTabs.add(search);
    }

    /** 탭의 아이템들을 항목으로. 블록 모드는 블록이 되는 것만, 같은 블록/아이템은 한 번만. */
    private List<Entry> toEntries(java.util.Collection<ItemStack> stacks, Set<Object> seen) {
        List<Entry> out = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            if (mode == Mode.BLOCK) {
                Block block = Block.getBlockFromItem(stack.getItem());
                if (block.getDefaultState().isAir() || !seen.add(block)) continue;
                out.add(blockEntry(block, stack));
            } else {
                if (!seen.add(stack.getItem())) continue;
                out.add(itemEntry(stack.getItem(), stack));
            }
        }
        return out;
    }

    /** 어느 크리에이티브 탭에도 없는 블록/아이템. (물, 불, 디버그 막대기 등) 검색할 때만 나온다. */
    private List<Entry> hiddenEntries(Set<Object> seen) {
        List<Entry> out = new ArrayList<>();
        if (mode == Mode.BLOCK) {
            for (Block block : Registries.BLOCK) {
                if (block.getDefaultState().isAir() || seen.contains(block)) continue;
                Item item = block.asItem();
                out.add(blockEntry(block, item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item)));
            }
        } else {
            for (Item item : Registries.ITEM) {
                if (item == Items.AIR || seen.contains(item)) continue;
                out.add(itemEntry(item, new ItemStack(item)));
            }
        }
        return out;
    }

    private static Entry blockEntry(Block block, ItemStack stack) {
        Identifier id = Registries.BLOCK.getId(block);
        Text name = block.getName();
        return new Entry(id, name, stack, block.getDefaultMapColor().color & 0xFFFFFF, searchKey(name, id));
    }

    private static Entry itemEntry(Item item, ItemStack stack) {
        Identifier id = Registries.ITEM.getId(item);
        Text name = stack.getName();
        return new Entry(id, name, stack, 0, searchKey(name, id));
    }

    private static String searchKey(Text name, Identifier id) {
        return (name.getString() + " " + id).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 화면 구성
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        if (!built) {
            built = true;
            buildEntries();
            chooseInitialTab();
        }

        leftPos = (width - IMAGE_W) / 2;
        topPos = Math.max(34, (height - IMAGE_H) / 2 - 8);

        // 검색칸은 바닐라와 같은 위치/모양. 화면을 다시 만들어도(창 크기 변경) 검색어는 유지한다.
        String previous = searchBox == null ? "" : searchBox.getText();
        searchBox = new TextFieldWidget(textRenderer, leftPos + 82, topPos + 6, 80, 9, Text.translatable("itemGroup.search"));
        searchBox.setMaxLength(50);
        searchBox.setDrawsBackground(false);
        searchBox.setEditableColor(-1);
        searchBox.setText(previous);
        searchBox.setChangedListener(s -> {
            if (selectedTab.getType() == ItemGroup.Type.SEARCH) {
                refreshView();
                scrollOffs = 0.0F;
            }
        });
        addSelectableChild(searchBox);
        configureSearchBox();
        refreshView();
        scrollOffs = clamp01(scrollOffs);

        int cancelW = 100;
        int cancelY = Math.min(height - 26, topPos + IMAGE_H + 32 + 4);
        addDrawableChild(ButtonWidget.builder(Text.translatable("dee.button.cancel"), b -> close())
                .dimensions((width - cancelW) / 2, cancelY, cancelW, 20).build());
    }

    /** 처음 열 때: 지금 값이 들어 있는 탭(없으면 바닐라 기본 탭)을 고르고, 그 항목이 보이게 스크롤한다. */
    private void chooseInitialTab() {
        ItemGroup chosen = null;
        int index = -1;
        if (currentId != null) {
            for (ItemGroup tab : shownTabs) {
                if (tab.getType() != ItemGroup.Type.CATEGORY) continue;
                index = indexOf(byTab.get(tab), currentId);
                if (index >= 0) {
                    chosen = tab;
                    break;
                }
            }
        }
        if (chosen == null) {
            chosen = shownTabs.get(0);
        }
        selectedTab = chosen;
        view = byTab.get(chosen);
        scrollOffs = 0.0F;
        if (index >= 0 && canScroll()) {
            int row = index / NUM_COLS - 2; // 현재 항목이 격자 가운데쯤 오게
            scrollOffs = clamp01((float) Math.max(row, 0) / rowCount());
        }
    }

    private static int indexOf(List<Entry> list, Identifier id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    /** 검색 탭에서만 검색칸을 보이고 포커스를 준다. (바닐라 selectTab 과 같은 처리) */
    private void configureSearchBox() {
        if (selectedTab.getType() == ItemGroup.Type.SEARCH) {
            searchBox.setVisible(true);
            searchBox.setFocusUnlocked(false);
            setFocused(searchBox);
        } else {
            searchBox.setFocusUnlocked(true);
            setFocused(null);
            searchBox.setVisible(false);
        }
    }

    private void selectTab(ItemGroup tab) {
        ItemGroup old = selectedTab;
        selectedTab = tab;
        if (tab.getType() != ItemGroup.Type.SEARCH) {
            configureSearchBox();
            searchBox.setText(""); // 검색 탭이 아니면 검색어를 비운다
        } else {
            configureSearchBox();
            if (old != tab) searchBox.setText("");
        }
        refreshView();
        scrollOffs = 0.0F;
    }

    // ------------------------------------------------------------------
    // 검색
    // ------------------------------------------------------------------

    private void refreshView() {
        if (selectedTab.getType() == ItemGroup.Type.SEARCH) {
            String query = searchBox.getText().trim();
            view = query.isEmpty() ? searchList : search(query);
        } else {
            view = byTab.getOrDefault(selectedTab, List.of());
        }
    }

    private List<Entry> search(String query) {
        List<Entry> pool = new ArrayList<>(searchList);
        pool.addAll(hiddenEntries);
        List<Entry> out = new ArrayList<>();
        String q = query.toLowerCase(Locale.ROOT);

        if (q.startsWith("#")) {
            // #태그 검색: 바닐라와 같이 태그 ID 에 검색어가 들어 있는 태그들 중 하나에 속하는 항목
            Predicate<Identifier> matcher = tagMatcher(q.substring(1).trim());
            for (Entry e : pool) {
                if (e.stack().isEmpty()) continue;
                if (e.stack().getRegistryEntry().streamTags().anyMatch(tag -> matcher.test(tag.id()))) out.add(e);
            }
            return out;
        }

        String[] tokens = q.split("\\s+");
        for (Entry e : pool) {
            boolean match = true;
            for (String token : tokens) {
                if (!e.searchKey().contains(token)) {
                    match = false;
                    break;
                }
            }
            if (match) out.add(e);
        }
        return out;
    }

    private static Predicate<Identifier> tagMatcher(String term) {
        int colon = term.indexOf(':');
        if (colon == -1) return id -> id.getPath().contains(term);
        String namespace = term.substring(0, colon).trim();
        String path = term.substring(colon + 1).trim();
        return id -> id.getNamespace().contains(namespace) && id.getPath().contains(path);
    }

    // ------------------------------------------------------------------
    // 스크롤 (바닐라 ItemPickerMenu 와 같은 계산)
    // ------------------------------------------------------------------

    private int rowCount() {
        return (view.size() + NUM_COLS - 1) / NUM_COLS - NUM_ROWS;
    }

    private int rowIndex() {
        return Math.max((int) (scrollOffs * rowCount() + 0.5F), 0);
    }

    private boolean canScroll() {
        return selectedTab.hasScrollbar() && view.size() > SLOTS;
    }

    private static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    private boolean insideScrollbar(double mx, double my) {
        int x = leftPos + 175;
        int y = topPos + 18;
        return mx >= x && my >= y && mx < x + 14 && my < y + 112 && selectedTab.hasScrollbar();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
        if (!canScroll()) return false;
        scrollOffs = clamp01(scrollOffs - (float) (verticalAmount / rowCount()));
        return true;
    }

    // ------------------------------------------------------------------
    // 마우스
    // ------------------------------------------------------------------

    private static boolean isHovering(int x, int y, int w, int h, double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 마우스가 가리키는 슬롯 칸 번호(0~44). 없으면 -1. */
    private int slotAt(double mx, double my) {
        double rx = mx - (leftPos + 9);
        double ry = my - (topPos + 18);
        if (rx < 0 || ry < 0) return -1;
        int col = (int) (rx / 18);
        int row = (int) (ry / 18);
        if (col >= NUM_COLS || row >= NUM_ROWS) return -1;
        if (rx - col * 18 >= 16 || ry - row * 18 >= 16) return -1; // 슬롯 사이 틈
        return row * NUM_COLS + col;
    }

    /** 마우스가 가리키는 항목의 view 번호. 없으면 -1. */
    private int hoveredIndex(double mx, double my) {
        int slot = slotAt(mx, my);
        if (slot < 0) return -1;
        int index = rowIndex() * NUM_COLS + slot;
        return index < view.size() ? index : -1;
    }

    private int tabX(ItemGroup tab) {
        int pos = tab.getColumn();
        int x = 27 * pos;
        if (tab.isSpecial()) {
            x = IMAGE_W - 27 * (7 - pos) + 1;
        }
        return x;
    }

    private int tabY(ItemGroup tab) {
        return tab.getRow() == ItemGroup.Row.TOP ? -32 : IMAGE_H;
    }

    private boolean tabClicked(ItemGroup tab, double xm, double ym) {
        int x = tabX(tab);
        int y = tabY(tab);
        return xm >= x && xm <= x + 26 && ym >= y && ym <= y + 32;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (insideScrollbar(mouseX, mouseY)) {
                scrolling = canScroll();
                return true;
            }
            int index = hoveredIndex(mouseX, mouseY);
            if (index >= 0) {
                onPick.accept(view.get(index).id());
                client.setScreen(parent);
                return true;
            }
            for (ItemGroup tab : shownTabs) {
                if (tabClicked(tab, mouseX - leftPos, mouseY - topPos)) return true; // 선택은 놓을 때 (바닐라와 동일)
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            scrolling = false;
            for (ItemGroup tab : shownTabs) {
                if (tabClicked(tab, mouseX - leftPos, mouseY - topPos)) {
                    selectTab(tab);
                    return true;
                }
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (scrolling) {
            int top = topPos + 18;
            int bottom = top + 112;
            scrollOffs = clamp01(((float) mouseY - top - 7.5F) / (bottom - top - 15.0F));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------
    // 렌더링
    // ------------------------------------------------------------------

    @Override
    public void render(DrawContext graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta); // 배경 + [취소] 버튼
        graphics.drawCenteredTextWithShadow(textRenderer, title, width / 2, 2, 0xFFFFFFFF);

        // 선택 안 된 탭 -> 본체 -> 스크롤러 -> 검색칸 -> 선택된 탭 순서는 바닐라와 같다
        for (ItemGroup tab : shownTabs) {
            if (tab != selectedTab) drawTab(graphics, mouseX, mouseY, tab);
        }
        graphics.drawTexture(RenderLayer::getGuiTextured, selectedTab.getTexture(),
                leftPos, topPos, 0.0F, 0.0F, IMAGE_W, IMAGE_H, 256, 256);

        searchBox.render(graphics, mouseX, mouseY, delta);
        if (selectedTab.hasScrollbar()) {
            Identifier sprite = canScroll() ? SCROLLER_SPRITE : SCROLLER_DISABLED_SPRITE;
            graphics.drawGuiTexture(RenderLayer::getGuiTextured, sprite,
                    leftPos + 175, topPos + 18 + (int) ((112 - 17) * scrollOffs), 12, 15);
        }
        drawTab(graphics, mouseX, mouseY, selectedTab);
        if (selectedTab.shouldRenderName()) {
            graphics.drawText(textRenderer, selectedTab.getDisplayName(), leftPos + 8, topPos + 6, -12566464, false);
        }

        drawSlots(graphics, mouseX, mouseY);
    }

    private void drawSlots(DrawContext graphics, int mouseX, int mouseY) {
        if (view.isEmpty()) {
            graphics.drawCenteredTextWithShadow(textRenderer, Text.translatable("dee.picker.empty"),
                    leftPos + 9 + NUM_COLS * 9, topPos + 18 + NUM_ROWS * 9 - textRenderer.fontHeight / 2, 0xFF404040);
        }

        int hovered = hoveredIndex(mouseX, mouseY);
        int first = rowIndex() * NUM_COLS;
        for (int slot = 0; slot < SLOTS; slot++) {
            int index = first + slot;
            if (index >= view.size()) break;
            Entry e = view.get(index);
            int x = leftPos + 9 + (slot % NUM_COLS) * 18;
            int y = topPos + 18 + (slot / NUM_COLS) * 18;

            if (e.id().equals(currentId)) {
                // 지금 입력칸에 있는 항목: 노란 테두리
                graphics.fill(x - 1, y - 1, x + 17, y, COLOR_CURRENT);
                graphics.fill(x - 1, y + 16, x + 17, y + 17, COLOR_CURRENT);
                graphics.fill(x - 1, y, x, y + 16, COLOR_CURRENT);
                graphics.fill(x + 16, y, x + 17, y + 16, COLOR_CURRENT);
            }
            if (!e.stack().isEmpty()) {
                graphics.drawItem(e.stack(), x, y);
            } else {
                // 아이템이 없는 블록(물, 불 등)은 대표색 사각형
                int rgb = e.color() != 0 ? e.color() : 0x808080;
                graphics.fill(x + 1, y + 1, x + 15, y + 15, 0xFF000000);
                graphics.fill(x + 2, y + 2, x + 14, y + 14, 0xFF000000 | rgb);
            }
            if (index == hovered) {
                graphics.fill(x, y, x + 16, y + 16, 0x80FFFFFF);
            }
        }

        if (hovered >= 0) {
            Entry e = view.get(hovered);
            if (mode == Mode.ITEM && !e.stack().isEmpty()) {
                graphics.drawItemTooltip(textRenderer, e.stack(), mouseX, mouseY);
            } else {
                // 블록 모드: 고르게 될 블록의 이름과 ID (아이템 이름과 다를 수 있어 아이템 툴팁을 쓰지 않는다)
                graphics.drawTooltip(textRenderer,
                        List.of(e.name(), Text.literal(e.id().toString()).formatted(Formatting.GRAY)),
                        Optional.empty(), mouseX, mouseY);
            }
            return;
        }

        for (ItemGroup tab : shownTabs) {
            if (isHovering(leftPos + tabX(tab) + 3, topPos + tabY(tab) + 3, 21, 27, mouseX, mouseY)) {
                graphics.drawTooltip(textRenderer, tab.getDisplayName(), mouseX, mouseY);
                break;
            }
        }
    }

    private void drawTab(DrawContext graphics, int mouseX, int mouseY, ItemGroup tab) {
        boolean selected = tab == selectedTab;
        boolean top = tab.getRow() == ItemGroup.Row.TOP;
        int x = leftPos + tabX(tab);
        int y = topPos - (top ? 28 : -(IMAGE_H - 4));
        Identifier[] sprites = top
                ? (selected ? SELECTED_TOP_TABS : UNSELECTED_TOP_TABS)
                : (selected ? SELECTED_BOTTOM_TABS : UNSELECTED_BOTTOM_TABS);
        graphics.drawGuiTexture(RenderLayer::getGuiTextured,
                sprites[Math.max(0, Math.min(tab.getColumn(), sprites.length - 1))], x, y, 26, 32);
        graphics.drawItem(tab.getIcon(), x + 13 - 8, y + 16 - 8 + (top ? 1 : -1));
    }
}