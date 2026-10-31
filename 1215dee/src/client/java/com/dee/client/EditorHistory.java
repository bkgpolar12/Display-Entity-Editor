package com.dee.client;

import com.dee.DisplayEntityEditor;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 되돌리기 / 다시 실행 기록. 항목 하나는 "되돌리는 동작"과 "다시 하는 동작"을 담은 두 개의 Runnable 이다.
 * 새 동작을 기록하면 다시 실행 기록은 지워진다. (여러 단계를 연달아 되돌리고 다시 실행할 수 있다)
 * 기록이 바뀐 시각(changedAt)과 목록(undoLabels/redoLabels)은 HUD 의 기록 패널이 읽는다.
 */
final class EditorHistory {
    private EditorHistory() {}

    private static final int MAX = 100;

    private record Entry(Text label, Runnable undo, Runnable redo) {}

    private static final Deque<Entry> UNDO = new ArrayDeque<>();
    private static final Deque<Entry> REDO = new ArrayDeque<>();
    private static long changedAt;

    private static void touch() {
        changedAt = System.currentTimeMillis();
    }

    static void push(Text label, Runnable undo, Runnable redo) {
        UNDO.addFirst(new Entry(label, undo, redo));
        REDO.clear();
        while (UNDO.size() > MAX) UNDO.removeLast();
        touch();
    }

    /** 가장 최근 동작을 되돌린다. 되돌릴 게 없으면 null, 있으면 그 동작의 이름. */
    static Text undo() {
        touch();
        Entry e = UNDO.pollFirst();
        if (e == null) return null;
        // 동작 하나가 실패해도 기록 전체가 망가지지 않게, 실패한 항목은 버리고 계속한다
        try {
            e.undo().run();
        } catch (RuntimeException ex) {
            DisplayEntityEditor.LOGGER.warn("되돌리기 실패, 기록에서 제외합니다", ex);
            return null;
        }
        REDO.addFirst(e);
        return e.label();
    }

    static Text redo() {
        touch();
        Entry e = REDO.pollFirst();
        if (e == null) return null;
        try {
            e.redo().run();
        } catch (RuntimeException ex) {
            DisplayEntityEditor.LOGGER.warn("다시 실행 실패, 기록에서 제외합니다", ex);
            return null;
        }
        UNDO.addFirst(e);
        return e.label();
    }

    static void clear() {
        UNDO.clear();
        REDO.clear();
    }

    /** 기록이 마지막으로 바뀐(또는 되돌리기/다시 실행을 시도한) 시각. (System.currentTimeMillis) */
    static long changedAt() {
        return changedAt;
    }

    static int undoCount() {
        return UNDO.size();
    }

    static int redoCount() {
        return REDO.size();
    }

    /** 되돌릴 수 있는 동작 이름. 가장 최근 것부터 최대 max 개. */
    static List<Text> undoLabels(int max) {
        return labels(UNDO, max);
    }

    /** 다시 실행할 수 있는 동작 이름. 바로 다음에 다시 실행될 것부터 최대 max 개. */
    static List<Text> redoLabels(int max) {
        return labels(REDO, max);
    }

    private static List<Text> labels(Deque<Entry> deque, int max) {
        List<Text> out = new ArrayList<>(max);
        for (Entry e : deque) {
            if (out.size() >= max) break;
            out.add(e.label());
        }
        return out;
    }
}