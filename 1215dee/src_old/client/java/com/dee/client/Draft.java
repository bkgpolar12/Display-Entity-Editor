package com.dee.client;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.nbt.StringNbtReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 편집 중인 text_display NBT 사본.
 * 화면이 다시 만들어져도(창 크기 변경, 고급 화면 왕복) 값이 유지되도록
 * 모든 입력은 위젯이 아니라 여기에 즉시 저장한다.
 */
public final class Draft {
    private final String uuid;
    private final NbtCompound original;
    private final NbtCompound working;

    /** 텍스트 칸을 SNBT(고급) 모드로 보여 줄지. 화면이 다시 만들어져도 유지된다. */
    private boolean advancedText;

    /** 새로 만드는 중인지. true 면 적용할 때 summon 명령을 만든다. */
    private final boolean creating;
    /** 엔티티 위치 (x, y, z). NBT 의 Pos 와 별개로 여기서 관리한다. */
    private final double[] pos = new double[3];
    private final double[] originalPos = new double[3];
    /** 바라보는 방향 (yaw, pitch). NBT 의 Rotation 과 별개로 여기서 관리한다. */
    private final float[] rot = new float[2];
    private final float[] originalRot = new float[2];

    /** 기존 엔티티 편집용 */
    public Draft(String uuid, NbtCompound nbt) {
        this(uuid, nbt, false);
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

    private Draft(String uuid, NbtCompound nbt, boolean creating) {
        this.uuid = uuid;
        this.creating = creating;
        this.original = nbt.copy();
        this.working = nbt.copy();
        // 서식이 있는 텍스트({translate:..} 등)면 처음부터 고급 모드로 연다
        NbtElement text = nbt.get("text");
        this.advancedText = text != null && !(text instanceof NbtString);
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

        Draft draft = new Draft("", nbt, true);
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

    // ---- 위치 ----

    public double[] getPos() {
        return pos.clone();
    }

    public void setPos(double x, double y, double z) {
        pos[0] = x;
        pos[1] = y;
        pos[2] = z;
    }

    public void setPosAxis(int axis, double value) {
        pos[axis] = value;
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

    /** 섹션 접힘 상태 기본값: [스타일 펼침, 크기 접힘, 위치·방향 펼침] */
    private final boolean[] sectionOpen = {true, false, true};
    private double scroll;

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

    // ---- 서버로 보낼 명령 ----

    /**
     * 원본과 달라진 키는 data merge 한 번으로, 사라진 키는 data remove 로 만든다.
     * 슬래시 없는 명령 문자열 목록을 돌려준다.
     */
    public List<String> buildCommands() {
        // 새로 만드는 경우: 현재 값 전부를 담아 summon 한 번으로 끝낸다
        if (creating) {
            NbtCompound summoned = working.copy();
            summoned.put("Rotation", floats(rot[0], rot[1]));
            return List.of("summon minecraft:text_display " + posString() + " " + summoned);
        }

        List<String> commands = new ArrayList<>();

        NbtCompound changed = new NbtCompound();
        for (String key : working.getKeys()) {
            NbtElement now = working.get(key);
            if (now != null && !Objects.equals(now, original.get(key))) {
                changed.put(key, now.copy());
            }
        }
        if (!changed.isEmpty()) {
            commands.add("data merge entity " + uuid + " " + changed);
        }

        for (String key : original.getKeys()) {
            if (!working.contains(key)) {
                commands.add("data remove entity " + uuid + " " + key);
            }
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