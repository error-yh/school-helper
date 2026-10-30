package io.github.hzkitty.utils;

/** Local trial lifecycle adaptation; does not change recognition algorithms. */
public final class ResourceCleanup {
    private ResourceCleanup() { }

    /** Attempt every close even when an earlier resource fails. */
    public static void closeAll(AutoCloseable... resources) {
        RuntimeException failure = null;
        for (AutoCloseable resource : resources) {
            if (resource == null) continue;
            try {
                resource.close();
            } catch (Exception e) {
                if (failure == null) failure = new IllegalStateException("Failed to close OCR resources", e);
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    /** Preserve a constructor's original failure while cleaning partial ownership. */
    public static void closeAfterFailure(Throwable failure, AutoCloseable... resources) {
        try {
            closeAll(resources);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
