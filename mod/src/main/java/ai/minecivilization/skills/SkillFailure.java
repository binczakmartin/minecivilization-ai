package ai.minecivilization.skills;

/**
 * Structured skill failure — mirrors the Python SkillFailure schema.
 * Example: {"code": "TARGET_UNREACHABLE", "message": "...", "recoverable": true}
 */
public final class SkillFailure {
    public final String code;
    public final String message;
    public final boolean recoverable;

    public SkillFailure(String code, String message, boolean recoverable) {
        this.code = code;
        this.message = message;
        this.recoverable = recoverable;
    }

    public static SkillFailure unreachable(String detail) {
        return new SkillFailure("TARGET_UNREACHABLE", detail, true);
    }

    public static SkillFailure notFound(String detail) {
        return new SkillFailure("TARGET_NOT_FOUND", detail, true);
    }

    public static SkillFailure missing(String detail) {
        return new SkillFailure("MISSING_RESOURCE", detail, true);
    }

    public static SkillFailure timeout(String detail) {
        return new SkillFailure("TIMEOUT", detail, true);
    }

    public static SkillFailure notImplemented(String what) {
        return new SkillFailure("NOT_IMPLEMENTED", what + " is not implemented yet", true);
    }

    @Override
    public String toString() {
        return code + ": " + message + (recoverable ? " (recoverable)" : " (fatal)");
    }
}
