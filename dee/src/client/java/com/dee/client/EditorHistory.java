package com.dee.client;

import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 되돌리기 / 다시 실행 기록. 항목 하나는 "되돌리는 동작"과 "다시 하는 동작"을 담은 두 개의 Runnable 이다.
 * 새 동작을 기록하면 다시 실행 기록은 지워진다.
 */
final class EditorHistory {
    private EditorHistory() {}

    private static final int MAX = 100;

    private record Entry(Component label, Runnable undo, Runnable redo) {}

    private static final Deque<Entry> UNDO = new ArrayDeque<>();
    private static final Deque<Entry> REDO = new ArrayDeque<>();

    static void push(Component label, Runnable undo, Runnable redo) {
        UNDO.addFirst(new Entry(label, undo, redo));
        REDO.clear();
        while (UNDO.size() > MAX) UNDO.removeLast();
    }

    /** 가장 최근 동작을 되돌린다. 되돌릴 게 없으면 null, 있으면 그 동작의 이름. */
    static Component undo() {
        Entry e = UNDO.pollFirst();
        if (e == null) return null;
        e.undo().run();
        REDO.addFirst(e);
        return e.label();
    }

    static Component redo() {
        Entry e = REDO.pollFirst();
        if (e == null) return null;
        e.redo().run();
        UNDO.addFirst(e);
        return e.label();
    }

    static void clear() {
        UNDO.clear();
        REDO.clear();
    }
}