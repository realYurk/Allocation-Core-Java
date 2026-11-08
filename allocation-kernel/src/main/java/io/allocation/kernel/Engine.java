package io.allocation.kernel;

/**
 * Identity of this engine.
 *
 * <p>Lives in one place because the version ends up in three of them: the CLI's {@code version}
 * command, the {@code engine_version} column of every persisted run, and any run summary written to
 * disk. Three copies of a string literal is three chances for them to disagree.
 *
 * <p>Bumping {@link #VERSION} is a promise about behaviour, not about source layout: see
 * {@code docs/API.md} §8 for what a major, minor and patch change each guarantee.
 */
public final class Engine {

    public static final String NAME = "allocation-core-java";

    /** Reported by the CLI and recorded on every run. */
    public static final String VERSION = "1.0.0";

    private Engine() {
    }
}
