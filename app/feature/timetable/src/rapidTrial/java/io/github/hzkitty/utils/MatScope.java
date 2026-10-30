package io.github.hzkitty.utils;

import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Local trial adaptation: releases internal OpenCV data deterministically.
 * Only newly created Mats are owned; input Mats must never be registered.
 * Outside an OCR scope, utility return values retain their caller-owned contract.
 */
public final class MatScope implements AutoCloseable {
    private static final ThreadLocal<MatScope> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Integer> LAST_RELEASED = new ThreadLocal<>();
    private final MatScope parent;
    private final List<Mat> owned = new ArrayList<>();
    private final Set<Mat> identities = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    private MatScope() {
        parent = CURRENT.get();
        CURRENT.set(this);
    }

    public static MatScope open() {
        return new MatScope();
    }

    public static <T extends Mat> T own(T mat) {
        MatScope scope = CURRENT.get();
        if (scope != null && mat != null && scope.identities.add(mat)) scope.owned.add(mat);
        return mat;
    }

    /** Lists are registered before JNI fills them, including exceptional returns. */
    public static <T extends Mat> List<T> ownedList() {
        return new ArrayList<T>() {
            @Override public boolean add(T mat) { own(mat); return super.add(mat); }
            @Override public void add(int index, T mat) { own(mat); super.add(index, mat); }
            @Override public boolean addAll(java.util.Collection<? extends T> mats) {
                for (T mat : mats) own(mat);
                return super.addAll(mats);
            }
        };
    }

    /** Narrow trial diagnostic: handles currently owned by this thread's scopes. */
    public static int activeOwnedCount() {
        int count = 0;
        for (MatScope scope = CURRENT.get(); scope != null; scope = scope.parent) count += scope.owned.size();
        return count;
    }

    public static int lastReleasedCount() {
        Integer count = LAST_RELEASED.get();
        return count == null ? 0 : count;
    }

    @Override public void close() {
        if (closed) return;
        if (CURRENT.get() != this) throw new IllegalStateException("Mat scopes must close on their owning thread in order");
        closed = true;
        RuntimeException failure = null;
        int released = 0;
        try {
            for (int i = owned.size() - 1; i >= 0; i--) {
                try {
                    owned.get(i).release();
                    released++;
                } catch (RuntimeException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
        } finally {
            owned.clear();
            identities.clear();
            if (parent == null) CURRENT.remove();
            else CURRENT.set(parent);
            LAST_RELEASED.set(released);
        }
        if (failure != null) throw failure;
    }
}
