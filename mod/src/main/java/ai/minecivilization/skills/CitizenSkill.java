package ai.minecivilization.skills;

/**
 * Deterministic citizen skill.
 *
 * <p>Every skill has: preconditions ({@link #canStart}), progress
 * ({@link #tick} returning RUNNING), completion (COMPLETED) and failure
 * (FAILED + {@link SkillFailure} with code/message/recoverable).
 * Timeouts and retry policy are enforced by the skill state machine —
 * never by the LLM.</p>
 */
public interface CitizenSkill {
    SkillType type();

    boolean canStart(SkillContext context);

    void start(SkillContext context);

    SkillResult tick(SkillContext context);

    void cancel(SkillContext context);

    /** Human-readable progress for debugging/inspection, e.g. "mining 0.6". */
    default String progressLabel(SkillContext context) {
        return "";
    }
}
