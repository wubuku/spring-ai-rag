package com.springairag.core.skill;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Attempt-local Skill loading state.
 *
 * <p>It is intentionally not a Spring singleton. A failed model candidate or
 * retry must not carry loaded Skill state into another attempt.</p>
 */
public final class RuntimeSkillLoadSession {

    public static final String CONTEXT_KEY = "rag.chat.skill-load-session";

    private final int maxLoads;
    private final int maxReferenceReads;
    private final int maxReferenceCharacters;
    private final Set<String> loadedSkills = new LinkedHashSet<>();
    private int referenceReads;
    private int referenceCharacters;

    public RuntimeSkillLoadSession(
            int maxLoads,
            int maxReferenceReads,
            int maxReferenceCharacters) {
        this.maxLoads = Math.max(1, maxLoads);
        this.maxReferenceReads = Math.max(1, maxReferenceReads);
        this.maxReferenceCharacters = Math.max(1, maxReferenceCharacters);
    }

    public synchronized boolean markLoaded(String skillName) {
        if (loadedSkills.contains(skillName)) {
            return true;
        }
        if (loadedSkills.size() >= maxLoads) {
            return false;
        }
        return loadedSkills.add(skillName);
    }

    public synchronized boolean isLoaded(String skillName) {
        return loadedSkills.contains(skillName);
    }

    public synchronized boolean reserveReference(int characters) {
        if (referenceReads >= maxReferenceReads
                || characters < 0
                || referenceCharacters > maxReferenceCharacters - characters) {
            return false;
        }
        referenceReads++;
        referenceCharacters += characters;
        return true;
    }

    public synchronized List<String> loadedSkills() {
        return List.copyOf(loadedSkills);
    }

    public synchronized int referenceReads() {
        return referenceReads;
    }

    public synchronized int referenceCharacters() {
        return referenceCharacters;
    }
}
