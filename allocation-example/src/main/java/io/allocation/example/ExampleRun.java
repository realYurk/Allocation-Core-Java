package io.allocation.example;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * What the demo did, so another part of the host — a page, a test, a script — can look it up.
 *
 * <p>In a real integration this is the engine's own repository; the example keeps the last run id in memory
 * because the point of the demo is to be followable, not to be a system of record.
 */
@Component
public class ExampleRun {

    private final AtomicReference<String> runId = new AtomicReference<>();
    private volatile String reconcileStatus;
    private volatile String reconcileDifferences;

    public String runId() {
        return runId.get();
    }

    void ran(String id) {
        runId.set(id);
    }

    public String reconcileStatus() {
        return reconcileStatus;
    }

    void reconciled(String status, String differencesPath) {
        this.reconcileStatus = status;
        this.reconcileDifferences = differencesPath;
    }

    public String reconcileDifferences() {
        return reconcileDifferences;
    }
}
