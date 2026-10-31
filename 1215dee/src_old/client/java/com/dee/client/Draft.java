package com.dee.client;

import net.minecraft.text.Text;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Uuids;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtString;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.StringNbtReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import java.util.Properties;

/**
 * 편집 중인 text_display / block_display NBT 사본.
 * 화면이 다시 만들어져도(창 크기 변경, 고급 화면 왕복) 값이 유지되도록
 * 모든 입력은 위젯이 아니라 여기에 즉시 저장한다.
 */
public final class Draft {
    private final String uuid;
    private final NbtCompound original;
    private final NbtCompound working;

    /** 디스플레이 종류. entityId 는 summon 에 쓰는 엔티티 ID, key 는 lang 키(dee.type.<key>)에 쓴다. */
    public enum Type {
        TEXT("minecraft:text_display"),
        BLOCK("minecraft:block_display"),
        ITEM("minecraft:item_display");

        private final String entityId;

        Type(String entityId) {
            this.entityId = entityId;
        }

        public String entityId() {
            return entityId;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** text_display 에만 있는 키들. 종류를 바꿀 때 지운다. */
    private static final List<String> TEXT_ONLY_KEYS = List.of(
            "text", "line_width", "background", "text_opacity",
            "alignment", "shadow", "see_through", "default_background");

    /** 새로 만드는 중에만 바꿀 수 있다. */
    private Type type;

    /** 텍스트 칸을 SNBT(고급) 모드로 보여 줄지. 화면이 다시 만들어져도 유지된다. */
    private boolean advancedText;

    /**
     * block_display 의 위치를 블록 칸(정수 좌표)에 맞출지. block_display 는 엔티티 위치가 블록의 모서리라서
     * 정수 좌표면 바닐라 블록 격자와 딱 맞는다. (scale 1, translation 0 기준)
     */
    private boolean autoAlign = loadAutoAlign();

    private static final String AUTO_ALIGN_KEY = "auto_align";

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("dee.properties");
    }

    /** 마지막으로 저장된 자동 정렬 값. 파일이 없거나 읽기 실패면 켜짐(기본값). */
    private static boolean loadAutoAlign() {
        Path file = configFile();
        if (!Files.exists(file)) return true;
        try (InputStream in = Files.newInputStream(file)) {
            Properties props = new Properties();
            props.load(in);
            return Boolean.parseBoolean(props.getProperty(AUTO_ALIGN_KEY, "true"));
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    private static void saveAutoAlign(boolean value) {
        Path file = configFile();
        Properties props = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
            props.setProperty(AUTO_ALIGN_KEY, Boolean.toString(value));
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "DisplayEntityEditor");
            }
        } catch (IOException | RuntimeException ignored) {
            // 저장에 실패해도 편집은 계속할 수 있다
        }
    }

    /** 새로 만드는 중인지. true 면 적용할 때 summon 명령을 만든다. */
    private final boolean creating;
    /** 엔티티 위치 (x, y, z). NBT 의 Pos 와 별개로 여기서 관리한다. */
    private final double[] pos = new double[3];
    private final double[] originalPos = new double[3];
    /** 바라보는 방향 (yaw, pitch). NBT 의 Rotation 과 별개로 여기서 관리한다. */
    private final float[] rot = new float[2];
    private final float[] originalRot = new float[2];

    /** 기존 엔티티 편집용 */
    public Draft(String uuid, NbtCompound nbt, Type type) {
        this(uuid, nbt, type, false);
        NbtList list = nbt.getListOrEmpty("Pos");
        for (int i = 0; i < 3 && i < list.size(); i++) {
            if (list.get(i) instanceof AbstractNbtNumber n) {
                pos[i] = n.doubleValue();
                originalPos[i] = pos[i];
            }
        }
        NbtList rotation = nbt.getListOrEmpty("Rotation");
        for (int i = 0; i < 2 && i < rotation.size(); i++) {
            if (rotation.get(i) instanceof AbstractNbtNumber n) {
                rot[i] = n.floatValue();
                originalRot[i] = rot[i];
            }
        }
    }

    private Draft(String uuid, NbtCompound nbt, Type type, boolean creating) {
        this.uuid = uuid;
        this.type = type;
        this.creating = creating;
        this.original = nbt.copy();
        this.working = nbt.copy();
        // 서식이 있는 텍스트({translate:..} 등)면 처음부터 고급 모드로 연다
        NbtElement text = nbt.get("text");
        this.advancedText = type == Type.TEXT && text != null && !(text instanceof NbtString);

        // 엔티티의 Tags (/tag 로 다루는 스코어보드 태그)
        NbtList tags = nbt.getListOrEmpty("Tags");
        for (int i = 0; i < tags.size(); i++) tagRows.add(tags.getString(i, ""));
        sectionOpen[SECTION_TAGS] = !tagRows.isEmpty(); // 태그가 이미 있으면 펼쳐서 보여 준다
    }

    /** 새 text_display 를 만들기 위한 초안. (x, y, z) 가 처음 위치가 된다. */
    public static Draft forNew(double x, double y, double z) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("text", "Text");
        nbt.putString("billboard", "center");

        // transformation 은 일부 키만 있으면 거부될 수 있어서 항등 변환을 전부 채워 둔다
        NbtCompound transformation = new NbtCompound();
        transformation.put("left_rotation", floats(0f, 0f, 0f, 1f));
        transformation.put("right_rotation", floats(0f, 0f, 0f, 1f));
        transformation.put("translation", floats(0f, 0f, 0f));
        transformation.put("scale", floats(1f, 1f, 1f));
        nbt.put("transformation", transformation);

        Draft draft = new Draft("", nbt, Type.TEXT, true);
        draft.setPos(x, y, z);
        return draft;
    }

    private static NbtList floats(float... values) {
        NbtList list = new NbtList();
        for (float v : values) list.add(NbtFloat.of(v));
        return list;
    }

    public boolean isCreating() {
        return creating;
    }

    public Type type() {
        return type;
    }

    /**
     * 새로 만드는 중에 종류를 바꾼다. 종류에 맞지 않는 키는 지우고 기본값을 채운다.
     * (transformation, 위치, 방향처럼 공통인 값은 그대로 둔다)
     */
    public void setType(Type newType) {
        if (!creating || newType == type) return;
        type = newType;

        for (String key : TEXT_ONLY_KEYS) working.remove(key);
        working.remove("block_state");
        working.remove("item");
        working.remove("item_display");
        working.remove("billboard");
        advancedText = false;

        switch (newType) {
            case BLOCK -> {
                NbtCompound state = new NbtCompound();
                state.putString("Name", "minecraft:stone");
                working.put("block_state", state);
            }
            case ITEM -> {
                NbtCompound item = new NbtCompound();
                item.putString("id", "minecraft:stone");
                item.putInt("count", 1);
                working.put("item", item);
                working.putString("billboard", "center");
            }
            default -> {
                working.putString("text", "Text");
                working.putString("billboard", "center");
            }
        }
        if (aligning()) setPos(pos[0], pos[1], pos[2]);
    }

    // ---- item_display 의 item ({id:"..",count:1,components:{..}}) ----

    public String getItemId() {
        return working.getCompoundOrEmpty("item").getString("id", "");
    }

    /** 아이템 ID 만 바꾼다. components 등 나머지는 그대로 둔다. */
    public void setItemId(String id) {
        NbtCompound item = working.getCompoundOrEmpty("item").copy();
        item.putString("id", id);
        if (!item.contains("count")) item.putInt("count", 1);
        working.put("item", item);
    }

    /** 플레이어 헤드의 profile 컴포넌트 값. 없으면 null. */
    public NbtElement getItemProfile() {
        return working.getCompoundOrEmpty("item").getCompoundOrEmpty("components").get("minecraft:profile");
    }

    /** profile 컴포넌트를 바꾼다. null 이면 제거한다. */
    public void setItemProfile(NbtElement profile) {
        NbtCompound item = working.getCompoundOrEmpty("item").copy();
        NbtCompound components = item.getCompoundOrEmpty("components").copy();
        if (profile == null) {
            components.remove("minecraft:profile");
        } else {
            components.put("minecraft:profile", profile);
        }
        if (components.isEmpty()) {
            item.remove("components");
        } else {
            item.put("components", components);
        }
        working.put("item", item);
    }

    // ---- 위치 ----

    public double[] getPos() {
        return pos.clone();
    }

    public void setPos(double x, double y, double z) {
        pos[0] = x;
        pos[1] = y;
        pos[2] = z;
        if (aligning()) snapToBlock();
    }

    public void setPosAxis(int axis, double value) {
        pos[axis] = aligning() ? alignedAxis(axis, value) : value;
    }

    // ---- 자동 정렬 (block_display 전용) ----

    /** 자동 정렬 토글을 보여 줄지. block_display 일 때만 true. */
    public boolean canAutoAlign() {
        return type == Type.BLOCK;
    }

    public boolean isAutoAlign() {
        return autoAlign;
    }

    /** 켜면 현재 위치를 바로 블록 칸으로 맞춘다. */
    public void setAutoAlign(boolean autoAlign) {
        this.autoAlign = autoAlign;
        saveAutoAlign(autoAlign);
        if (aligning()) snapToBlock();
    }

    private boolean aligning() {
        return autoAlign && type == Type.BLOCK;
    }

    private void snapToBlock() {
        for (int i = 0; i < 3; i++) pos[i] = alignedAxis(i, pos[i]);
    }

    /**
     * 눈에 보이는 블록의 모서리가 블록 칸(정수)에 오도록 엔티티 위치를 맞춘다.
     * 보이는 블록의 가장 작은 모서리 = 엔티티 위치 + translation + min(0, scale). (히트박스가 아니라 모습 기준)
     * translation 0, scale 1 이면 엔티티 위치가 정수일 때와 같다.
     * 회전(엔티티 방향, left/right_rotation)이 있으면 모서리를 정할 수 없어서 엔티티 위치만 정수로 맞춘다.
     */
    private double alignedAxis(int axis, double value) {
        if (!hasNoRotation()) return Math.floor(value);
        double offset = translationOf(axis) + Math.min(0f, getScale()[axis]);
        return Math.floor(value + offset + 1e-6) - offset;
    }

    private double translationOf(int axis) {
        NbtList list = working.getCompoundOrEmpty("transformation").getListOrEmpty("translation");
        return axis < list.size() && list.get(axis) instanceof AbstractNbtNumber n ? n.floatValue() : 0.0;
    }

    private boolean hasNoRotation() {
        if (rot[0] != 0f || rot[1] != 0f) return false;
        NbtCompound t = working.getCompoundOrEmpty("transformation");
        for (String key : new String[]{"left_rotation", "right_rotation"}) {
            NbtElement q = t.get(key);
            if (q == null) continue;
            if (!(q instanceof NbtList list) || list.size() != 4) return false;
            float[] identity = {0f, 0f, 0f, 1f};
            for (int i = 0; i < 4; i++) {
                if (!(list.get(i) instanceof AbstractNbtNumber n) || n.floatValue() != identity[i]) return false;
            }
        }
        return true;
    }

    // ---- 방향 (yaw, pitch) ----

    public float[] getRot() {
        return rot.clone();
    }

    public void setRotAxis(int axis, float value) {
        rot[axis] = value;
    }

    private static String coord(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private String rotString() {
        return String.format(Locale.ROOT, "%.3f %.3f", rot[0], rot[1]);
    }

    private String posString() {
        return coord(pos[0]) + " " + coord(pos[1]) + " " + coord(pos[2]);
    }

    // ---- 화면 상태 (접기/펼치기, 스크롤). 화면이 다시 만들어져도 유지하려고 여기에 둔다 ----

    /** 섹션 접힘 상태 기본값: [스타일 펼침, 크기 접힘, 위치·방향 펼침, 태그 접힘] */
    private final boolean[] sectionOpen = {true, false, true, false};
    private double scroll;

    /** 섹션 번호 (DisplayEditScreen 과 맞춰야 한다) */
    public static final int SECTION_TAGS = 3;

    // ---- 태그 (Tags) ----
    // 입력칸 한 줄 = 한 항목. 비어 있는 줄도 화면을 다시 만들 때 유지해야 해서 따로 들고 있고,
    // working 의 Tags 에는 비어 있지 않은 값만 중복 없이 넣는다.

    private final List<String> tagRows = new ArrayList<>();

    public List<String> getTagRows() {
        return List.copyOf(tagRows);
    }

    /** 빈 줄 하나 추가. (비어 있으면 저장되지 않으므로 NBT 는 그대로) */
    public void addTagRow() {
        tagRows.add("");
    }

    public void setTagRow(int index, String value) {
        if (index < 0 || index >= tagRows.size()) return;
        tagRows.set(index, value);
        syncTags();
    }

    public void removeTagRow(int index) {
        if (index < 0 || index >= tagRows.size()) return;
        tagRows.remove(index);
        syncTags();
    }

    /** 입력 줄들에서 비어 있지 않은 값만, 중복 없이 순서대로. */
    private LinkedHashSet<String> currentTags() {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String row : tagRows) {
            String tag = row.trim();
            if (!tag.isEmpty()) unique.add(tag);
        }
        return unique;
    }

    private void syncTags() {
        LinkedHashSet<String> unique = currentTags();
        // 원래 Tags 가 없었고 지금도 비어 있으면 키 자체를 두지 않는다. (불필요한 명령 방지)
        if (unique.isEmpty() && !original.contains("Tags")) {
            working.remove("Tags");
            return;
        }
        NbtList list = new NbtList();
        for (String tag : unique) list.add(NbtString.of(tag));
        working.put("Tags", list); // 모두 지웠으면 빈 리스트 -> data merge 가 리스트를 통째로 교체한다
    }

    public boolean isSectionOpen(int index) {
        return sectionOpen[index];
    }

    public void toggleSection(int index) {
        sectionOpen[index] = !sectionOpen[index];
    }

    public double getScroll() {
        return scroll;
    }

    public void setScroll(double scroll) {
        this.scroll = scroll;
    }

    public boolean isAdvancedText() {
        return advancedText;
    }

    public void setAdvancedText(boolean advancedText) {
        this.advancedText = advancedText;
    }

    public String uuid() {
        return uuid;
    }

    // ---- 기본 getter / setter ----

    public boolean has(String key) {
        return working.contains(key);
    }

    public NbtElement get(String key) {
        return working.get(key);
    }

    public void set(String key, NbtElement value) {
        working.put(key, value);
    }

    public void remove(String key) {
        working.remove(key);
    }

    public String getString(String key, String fallback) {
        return working.getString(key, fallback);
    }

    public void setString(String key, String value) {
        working.putString(key, value);
    }

    public int getInt(String key, int fallback) {
        return working.getInt(key, fallback);
    }

    public void setInt(String key, int value) {
        working.putInt(key, value);
    }

    public boolean getBool(String key, boolean fallback) {
        return working.getBoolean(key, fallback);
    }

    public void setBool(String key, boolean value) {
        working.putBoolean(key, value);
    }

    /** byte 를 0~255 로 읽는다 (text_opacity 는 -1 이 255 = 완전 불투명) */
    public int getUnsignedByte(String key, int fallback) {
        return working.getByte(key, (byte) fallback) & 0xFF;
    }

    public void setUnsignedByte(String key, int value) {
        working.putByte(key, (byte) value);
    }

    // ---- transformation.scale ----

    public float[] getScale() {
        float[] scale = {1f, 1f, 1f};
        NbtList list = working.getCompoundOrEmpty("transformation").getListOrEmpty("scale");
        for (int i = 0; i < 3 && i < list.size(); i++) {
            if (list.get(i) instanceof AbstractNbtNumber n) scale[i] = n.floatValue();
        }
        return scale;
    }

    public void setScale(float x, float y, float z) {
        NbtCompound transformation = working.getCompoundOrEmpty("transformation").copy();
        NbtList list = new NbtList();
        list.add(NbtFloat.of(x));
        list.add(NbtFloat.of(y));
        list.add(NbtFloat.of(z));
        transformation.put("scale", list);
        working.put("transformation", transformation);
    }

    /** 새로 만들기 전용: 지정한 UUID 로 summon 한다. (에디터 모드 진입 시 엔티티를 먼저 만들어 두기 위함) */
    public String buildSummonCommand(UUID id) {
        NbtCompound summoned = working.copy();
        summoned.put("Rotation", floats(rot[0], rot[1]));
        summoned.put("UUID", new NbtIntArray(Uuids.toIntArray(id)));
        if (creating && aligning()) snapToBlock(); // 만드는 중에 scale/translation 을 바꿨어도 마지막에 맞춘다
        return "summon " + type.entityId() + " " + posString() + " " + summoned;
    }

    // ---- 서버로 보낼 명령 ----

    /**
     * 원본과 달라진 키는 data merge 한 번으로, 사라진 키는 data remove 로 만든다.
     * 슬래시 없는 명령 문자열 목록을 돌려준다.
     */
    public List<String> buildCommands() {
        // 새로 만드는 경우: 현재 값 전부를 담아 summon 한 번으로 끝낸다
        if (creating) {
            if (aligning()) snapToBlock(); // 만드는 중에 scale/translation 을 바꿨어도 마지막에 맞춘다
            NbtCompound summoned = working.copy();
            summoned.put("Rotation", floats(rot[0], rot[1]));
            return List.of("summon " + type.entityId() + " " + posString() + " " + summoned);
        }

        List<String> commands = new ArrayList<>();

        NbtCompound changed = new NbtCompound();
        Map<String, NbtElement> replaced = new LinkedHashMap<>();
        for (String key : working.getKeys()) {
            if (key.equals("Tags")) continue; // /tag add|remove 로 따로 처리한다
            NbtElement now = working.get(key);
            if (now != null && !Objects.equals(now, original.get(key))) {
                // block_state 는 merge 하면 예전 Properties 가 남아서(컴파운드끼리 합쳐짐) 새 블록에 없는 속성 때문에
                // 통째로 무시된다. item 도 예전 components(profile 등)가 남아 버린다.
                // 그래서 아래에서 data modify ... set 으로 통째로 교체한다.
                if (key.equals("block_state") || key.equals("item")) {
                    replaced.put(key, now);
                    continue;
                }
                changed.put(key, now.copy());
            }
        }
        if (!changed.isEmpty()) {
            commands.add("data merge entity " + uuid + " " + changed);
        }
        for (Map.Entry<String, NbtElement> entry : replaced.entrySet()) {
            commands.add("data modify entity " + uuid + " " + entry.getKey() + " set value " + entry.getValue());
        }

        for (String key : original.getKeys()) {
            if (key.equals("Tags")) continue;
            if (!working.contains(key)) {
                commands.add("data remove entity " + uuid + " " + key);
            }
        }

        // 태그는 리스트를 통째로 바꾸지 않고, 바뀐 것만 /tag 로 추가/삭제한다.
        // (서버의 기존 태그를 클라이언트가 못 읽는 경우에도 기존 태그를 지우지 않는다)
        NbtList originalList = original.getListOrEmpty("Tags");
        LinkedHashSet<String> before = new LinkedHashSet<>();
        for (int i = 0; i < originalList.size(); i++) before.add(originalList.getString(i, ""));
        LinkedHashSet<String> after = currentTags();
        for (String tag : before) {
            if (!tag.isEmpty() && !after.contains(tag)) commands.add("tag " + uuid + " remove " + tag);
        }
        for (String tag : after) {
            if (!before.contains(tag)) commands.add("tag " + uuid + " add " + tag);
        }

        // 위치/방향이 바뀌었으면 tp (teleport_duration 이 있으면 부드럽게 이동한다)
        if (!Arrays.equals(pos, originalPos) || !Arrays.equals(rot, originalRot)) {
            commands.add("tp " + uuid + " " + posString() + " " + rotString());
        }
        return commands;
    }

    // ---- SNBT 파싱 ----

    /** "1.5f", "{text:'a'}" 같은 SNBT 값 하나를 파싱한다. 실패하면 empty. */
    public static Optional<NbtElement> parseSnbt(String snbt) {
        try {
            NbtCompound wrapper = StringNbtReader.readCompound("{v:" + snbt + "}");
            return Optional.ofNullable(wrapper.get("v"));
        } catch (CommandSyntaxException e) {
            return Optional.empty();
        }
    }
}