package com.dee.client;

import com.dee.DisplayEntityEditor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 에디터 모드의 편집 단축키. 다른 에디터와 같은 관습이라 Ctrl / Shift 와 글자를 물리 키로 직접 읽는다.
 *   Ctrl+Z 되돌리기 · Ctrl+Shift+Z / Ctrl+Y 다시 실행 · Ctrl+X 잘라내기 · Ctrl+C 복사 · Ctrl+V 붙여넣기
 *   Ctrl+D 복제 · Ctrl+A 모두 선택 · Ctrl+G 그룹 · Ctrl+Shift+G 그룹 해제 · Delete 삭제
 */
final class EditorShortcuts {
    private EditorShortcuts() {}

    private static final int[] KEYS = {
            InputConstants.KEY_Z, InputConstants.KEY_Y, InputConstants.KEY_X, InputConstants.KEY_C,
            InputConstants.KEY_V, InputConstants.KEY_D, InputConstants.KEY_A, InputConstants.KEY_G,
            InputConstants.KEY_DELETE
    };
    /** 지난 틱에 눌려 있었는지. 새로 눌린 순간만 한 번 처리하기 위해 기억한다. */
    private static final boolean[] held = new boolean[KEYS.length];

    private static Method isKeyDown;
    private static int isKeyDownShape; // 1: (int key)  2: (Window, int key)  3: (long handle, int key)
    private static boolean lookedUp;
    private static boolean warned;

    /**
     * 물리 키가 눌려 있는지. InputConstants.isKeyDown 은 버전마다 인자가 달라서(키만 받거나, Window/핸들과 키를 받는다)
     * 메서드 모양을 보고 맞는 호출을 고른다. 쓸 수 있는 모양이 없으면 한 번 경고를 남기고 항상 false 를 돌려준다.
     */
    static boolean isDown(Minecraft client, int key) {
        if (!lookedUp) {
            lookedUp = true;
            for (Method m : InputConstants.class.getDeclaredMethods()) {
                if (!m.getName().equals("isKeyDown") || !Modifier.isStatic(m.getModifiers())
                        || m.getReturnType() != boolean.class) continue;
                Class<?>[] p = m.getParameterTypes();
                int shape = 0;
                if (p.length == 1 && p[0] == int.class) shape = 1;
                else if (p.length == 2 && p[1] == int.class && p[0].isInstance(client.getWindow())) shape = 2;
                else if (p.length == 2 && p[0] == long.class && p[1] == int.class) shape = 3;
                if (shape != 0) {
                    try {
                        m.setAccessible(true);
                        isKeyDown = m;
                        isKeyDownShape = shape;
                        break;
                    } catch (RuntimeException ignored) {
                        // 다음 후보를 본다
                    }
                }
            }
        }
        if (isKeyDown == null) {
            if (!warned) {
                warned = true;
                DisplayEntityEditor.LOGGER.warn("InputConstants.isKeyDown 의 사용 가능한 시그니처를 찾지 못해 편집 단축키가 꺼집니다");
            }
            return false;
        }
        try {
            Object window = client.getWindow();
            Object result;
            if (isKeyDownShape == 1) {
                result = isKeyDown.invoke(null, key);
            } else if (isKeyDownShape == 2) {
                result = isKeyDown.invoke(null, window, key);
            } else {
                long handle = (Long) window.getClass().getMethod("handle").invoke(window);
                result = isKeyDown.invoke(null, handle, key);
            }
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | RuntimeException e) {
            isKeyDown = null; // 호출에 실패하면 더는 시도하지 않는다
            return false;
        }
    }

    private static boolean helpHeld;

    /** 도움말 키(H)가 이번 틱에 새로 눌렸는지. (도움말 토글용) */
    static boolean helpPressed(Minecraft client) {
        boolean now = isDown(client, InputConstants.KEY_H);
        boolean fresh = now && !helpHeld;
        helpHeld = now;
        return fresh;
    }

    /**
     * 편집 단축키의 "Ctrl" 이자 다중 선택 키. 둘은 같은 키(조작 설정의 "다중 선택" 키바인딩)를 쓴다.
     * 같은 키를 쓰는 바닐라 동작(기본 Ctrl = 달리기)과 겹쳐 isDown 이 안 올 수 있어서,
     * 바인딩이 기본값(왼쪽 Ctrl)이면 물리 키도 함께 본다.
     */
    static boolean ctrlDown(Minecraft client) {
        KeyMapping multi = DisplayEditClient.MULTI_SELECT;
        if (multi == null) return false;
        return multi.isDown() || (multi.isDefault() && isDown(client, InputConstants.KEY_LCONTROL));
    }

    /** 다중 선택 키를 누르는 동안 막는 바닐라 이동 키. (Ctrl+A / Ctrl+D 가 플레이어를 움직이지 않게) */
    private static KeyMapping[] movementKeys(Minecraft client) {
        return new KeyMapping[]{
                client.options.keyUp, client.options.keyDown, client.options.keyLeft, client.options.keyRight,
                client.options.keyJump, client.options.keyShift, client.options.keySprint
        };
    }

    private static boolean suppressing;

    /**
     * 에디터 모드에서 다중 선택 키를 누르고 있으면 이동/점프/웅크리기/달리기 입력을 끈다.
     * 틱 시작(바닐라 입력 처리 전)에 매 틱 불러야 한다. 단축키는 물리 키를 직접 읽으므로 영향이 없다.
     */
    static void suppressMovement(Minecraft client, boolean modifierHeld) {
        if (modifierHeld) {
            suppressing = true;
            for (KeyMapping k : movementKeys(client)) k.setDown(false);
        } else {
            releaseMovement();
        }
    }

    /** 막았던 키를 실제 키보드 상태에 맞춰 되돌린다. (Ctrl 을 뗄 때 / 에디터 모드가 끝날 때 / UI 가 열릴 때) */
    static void releaseMovement() {
        if (!suppressing) return;
        suppressing = false;
        KeyMapping.setAll();
    }

    static boolean shiftDown(Minecraft client) {
        return isDown(client, InputConstants.KEY_LSHIFT) || isDown(client, InputConstants.KEY_RSHIFT);
    }

    /**
     * 눌림 상태만 맞춰 둔다. UI 가 열려 있거나 드래그 중처럼 단축키를 처리하지 않는 동안에도 호출해야,
     * 그때 누르고 있던 키가 나중에 "새로 눌림"으로 잘못 처리되지 않는다.
     */
    static void sync(Minecraft client) {
        for (int i = 0; i < KEYS.length; i++) held[i] = isDown(client, KEYS[i]);
    }

    /** 이번 틱에 새로 눌린 단축키를 처리한다. */
    static void poll(Minecraft client) {
        boolean ctrl = ctrlDown(client);
        boolean shift = shiftDown(client);
        for (int i = 0; i < KEYS.length; i++) {
            boolean now = isDown(client, KEYS[i]);
            boolean fresh = now && !held[i];
            held[i] = now;
            if (!fresh) continue;

            int key = KEYS[i];
            if (key == InputConstants.KEY_DELETE) {
                if (!ctrl) EditorOps.deleteSelection(client, false);
                continue;
            }
            if (!ctrl) continue;
            if (key == InputConstants.KEY_Z) {
                if (shift) EditorOps.redo(client);
                else EditorOps.undo(client);
            } else if (key == InputConstants.KEY_Y) {
                EditorOps.redo(client);
            } else if (key == InputConstants.KEY_X) {
                EditorOps.deleteSelection(client, true);
            } else if (key == InputConstants.KEY_C) {
                EditorOps.copy(client);
            } else if (key == InputConstants.KEY_V) {
                EditorOps.paste(client);
            } else if (key == InputConstants.KEY_D) {
                EditorOps.duplicate(client);
            } else if (key == InputConstants.KEY_A) {
                GizmoEditor.selectAllNearby(client);
            } else if (key == InputConstants.KEY_G) {
                if (shift) GizmoEditor.ungroupSelection();
                else GizmoEditor.groupSelection();
            }
        }
    }
}