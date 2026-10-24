package com.dee.client;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.MinecraftClient;


/**
 * 에디터 모드의 편집 단축키. 다른 에디터와 같은 관습이라 Ctrl / Shift 와 글자를 물리 키로 직접 읽는다.
 *   Ctrl+Z 되돌리기 · Ctrl+Shift+Z / Ctrl+Y 다시 실행 · Ctrl+X 잘라내기 · Ctrl+C 복사 · Ctrl+V 붙여넣기
 *   Ctrl+D 복제 · Ctrl+A 모두 선택 · Ctrl+G 그룹 · Ctrl+Shift+G 그룹 해제 · Delete 삭제
 */
public final class EditorShortcuts {
    private EditorShortcuts() {}

    private static final int[] KEYS = {
            GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_C,
            GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_G,
            GLFW.GLFW_KEY_DELETE
    };
    /** 지난 틱에 눌려 있었는지. 새로 눌린 순간만 한 번 처리하기 위해 기억한다. */
    private static final boolean[] held = new boolean[KEYS.length];

    /** 물리 키가 눌려 있는지. (1.21.5: InputUtil.isKeyPressed(창 핸들, 키)) */
    public static boolean isDown(MinecraftClient client, int key) {
        return InputUtil.isKeyPressed(client.getWindow().getHandle(), key);
    }

    private static boolean helpHeld;

    /** 도움말 키(H)가 이번 틱에 새로 눌렸는지. (도움말 토글용) */
    public static boolean helpPressed(MinecraftClient client) {
        boolean now = isDown(client, GLFW.GLFW_KEY_H);
        boolean fresh = now && !helpHeld;
        helpHeld = now;
        return fresh;
    }

    /**
     * 편집 단축키의 "Ctrl" 이자 다중 선택 키. 둘은 같은 키(조작 설정의 "다중 선택" 키바인딩)를 쓴다.
     * 같은 키를 쓰는 바닐라 동작(기본 Ctrl = 달리기)과 겹쳐 isDown 이 안 올 수 있어서,
     * 바인딩이 기본값(왼쪽 Ctrl)이면 물리 키도 함께 본다.
     */
    public static boolean ctrlDown(MinecraftClient client) {
        KeyBinding multi = DisplayEditClient.MULTI_SELECT;
        if (multi == null) return false;
        return multi.isPressed() || (multi.isDefault() && isDown(client, GLFW.GLFW_KEY_LEFT_CONTROL));
    }

    /** 다중 선택 키를 누르는 동안 막는 바닐라 이동 키. (Ctrl+A / Ctrl+D 가 플레이어를 움직이지 않게) */
    private static KeyBinding[] movementKeys(MinecraftClient client) {
        return new KeyBinding[]{
                client.options.forwardKey, client.options.backKey, client.options.leftKey, client.options.rightKey,
                client.options.jumpKey, client.options.sneakKey, client.options.sprintKey
        };
    }

    private static boolean suppressing;

    /**
     * 에디터 모드에서 다중 선택 키를 누르고 있으면 이동/점프/웅크리기/달리기 입력을 끈다.
     * 틱 시작(바닐라 입력 처리 전)에 매 틱 불러야 한다. 단축키는 물리 키를 직접 읽으므로 영향이 없다.
     */
    public static void suppressMovement(MinecraftClient client, boolean modifierHeld) {
        if (modifierHeld) {
            suppressing = true;
            for (KeyBinding k : movementKeys(client)) k.setPressed(false);
        } else {
            releaseMovement();
        }
    }

    /** 막았던 키를 실제 키보드 상태에 맞춰 되돌린다. (Ctrl 을 뗄 때 / 에디터 모드가 끝날 때 / UI 가 열릴 때) */
    public static void releaseMovement() {
        if (!suppressing) return;
        suppressing = false;
        KeyBinding.updatePressedStates();
    }

    public static boolean shiftDown(MinecraftClient client) {
        return isDown(client, GLFW.GLFW_KEY_LEFT_SHIFT) || isDown(client, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    /**
     * 눌림 상태만 맞춰 둔다. UI 가 열려 있거나 드래그 중처럼 단축키를 처리하지 않는 동안에도 호출해야,
     * 그때 누르고 있던 키가 나중에 "새로 눌림"으로 잘못 처리되지 않는다.
     */
    public static void sync(MinecraftClient client) {
        for (int i = 0; i < KEYS.length; i++) held[i] = isDown(client, KEYS[i]);
    }

    /** 이번 틱에 새로 눌린 단축키를 처리한다. */
    public static void poll(MinecraftClient client) {
        boolean ctrl = ctrlDown(client);
        boolean shift = shiftDown(client);
        for (int i = 0; i < KEYS.length; i++) {
            boolean now = isDown(client, KEYS[i]);
            boolean fresh = now && !held[i];
            held[i] = now;
            if (!fresh) continue;

            int key = KEYS[i];
            if (key == GLFW.GLFW_KEY_DELETE) {
                if (!ctrl) EditorOps.deleteSelection(client, false);
                continue;
            }
            if (!ctrl) continue;
            if (key == GLFW.GLFW_KEY_Z) {
                if (shift) EditorOps.redo(client);
                else EditorOps.undo(client);
            } else if (key == GLFW.GLFW_KEY_Y) {
                EditorOps.redo(client);
            } else if (key == GLFW.GLFW_KEY_X) {
                EditorOps.deleteSelection(client, true);
            } else if (key == GLFW.GLFW_KEY_C) {
                EditorOps.copy(client);
            } else if (key == GLFW.GLFW_KEY_V) {
                EditorOps.paste(client);
            } else if (key == GLFW.GLFW_KEY_D) {
                EditorOps.duplicate(client);
            } else if (key == GLFW.GLFW_KEY_A) {
                GizmoEditor.selectAllNearby(client);
            } else if (key == GLFW.GLFW_KEY_G) {
                if (shift) GizmoEditor.ungroupSelection();
                else GizmoEditor.groupSelection();
            }
        }
    }
}