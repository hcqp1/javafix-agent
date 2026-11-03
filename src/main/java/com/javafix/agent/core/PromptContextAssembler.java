package com.javafix.agent.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects bounded, phase-relevant history for a single AgentLoop prompt. */
public final class PromptContextAssembler {

    public static final int MAX_CONTEXT_CHARS = 10_000;
    private static final int MAX_ENTRY_CHARS = 1_800;
    private static final int RECENT_PHASE_ENTRIES = 4;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_.$#]{2,}");
    private static final Pattern CHINESE_RUN = Pattern.compile("[\u4e00-\u9fff]{2,}");
    private static final Pattern FAILED_TEST_RESULT = Pattern.compile(
            "测试未通过|测试环境失败|COMPILATION ERROR|\\[ERROR]"
                    + "|Tests run:\\s*\\d+\\s*,\\s*Failures:\\s*[1-9]\\d*",
            Pattern.CASE_INSENSITIVE);

    private record Candidate(int priority, int sequence, String section, String text, int maxChars) { }

    public String assemble(Phase phase, String symptom, List<String> transcript,
                           List<String> priorTaskContext, List<String> failedAttempts) {
        List<String> currentTranscript = transcript == null ? List.of() : List.copyOf(transcript);
        List<String> sameTaskContext = priorTaskContext == null ? List.of() : List.copyOf(priorTaskContext);
        List<String> attempts = failedAttempts == null ? List.of() : List.copyOf(failedAttempts);
        Set<String> terms = terms(symptom);
        List<Candidate> candidates = new ArrayList<>();

        for (int i = 0; i < currentTranscript.size(); i++) {
            String entry = currentTranscript.get(i);
            Phase entryPhase = phaseOf(entry);
            boolean failedVerification = phase == Phase.FIX && entryPhase == Phase.VERIFY
                    && isFailedTestEntry(entry);
            if (entryPhase != null && entryPhase.ordinal() > phase.ordinal() && !failedVerification) continue;
            int priority = 0;
            int maxChars = MAX_ENTRY_CHARS;
            if (failedVerification) {
                priority = 1_150;
                maxChars = 1_000;
            } else if (entryPhase != null && entryPhase.ordinal() < phase.ordinal()
                    && isPhaseConclusion(entry)) {
                priority = 1000 + entryPhase.ordinal();
                maxChars = 700;
            } else if (entryPhase == phase && isRecentInPhase(currentTranscript, i, phase)) {
                priority = 1_200 + recentPhaseRank(currentTranscript, i, phase);
                maxChars = 1_000;
            } else if (isFailedTestEntry(entry)) {
                priority = 900 + Math.min(20, relevance(entry, terms)) * 2;
                maxChars = 800;
            } else {
                int overlap = relevance(entry, terms);
                if (overlap > 0) priority = 700 + Math.min(20, overlap) * 2;
                else if (i >= currentTranscript.size() - 2) priority = 300 + i - currentTranscript.size() + 2;
                maxChars = 600;
            }
            if (priority > 0) candidates.add(new Candidate(priority, i, "阶段证据", entry, maxChars));
        }

        int sequence = currentTranscript.size();
        int priorStart = Math.max(0, sameTaskContext.size() - 8);
        for (int i = priorStart; i < sameTaskContext.size(); i++) {
            String entry = sameTaskContext.get(i);
            int overlap = relevance(entry, terms);
            if (overlap > 0 || i >= sameTaskContext.size() - 2) {
                candidates.add(new Candidate(600 + Math.min(20, overlap) * 2 + i - priorStart, sequence++,
                        "本任务此前上下文", entry, 600));
            }
        }
        int attemptStart = Math.max(0, attempts.size() - 2);
        for (int i = attemptStart; i < attempts.size(); i++) {
            candidates.add(new Candidate(1_100 + i - attemptStart, sequence++, "失败尝试/修复记录", attempts.get(i), 600));
        }

        candidates.sort(Comparator.comparingInt(Candidate::priority).reversed()
                .thenComparingInt(Candidate::sequence));
        List<Candidate> selected = new ArrayList<>();
        int remaining = MAX_CONTEXT_CHARS;
        for (Candidate candidate : candidates) {
            if (remaining <= 0) break;
            String entry = truncateEntry(candidate.text(), candidate.maxChars());
            String rendered = "[" + candidate.section() + "]\n" + entry;
            if (rendered.length() > remaining) {
                if (remaining > 32) {
                    rendered = rendered.substring(0, remaining - 24) + "\n...(context truncated)";
                    selected.add(new Candidate(candidate.priority(), candidate.sequence(),
                            candidate.section(), rendered.substring(rendered.indexOf('\n') + 1), candidate.maxChars()));
                }
                break;
            }
            selected.add(new Candidate(candidate.priority(), candidate.sequence(),
                    candidate.section(), entry, candidate.maxChars()));
            remaining -= rendered.length() + 1;
        }

        selected.sort(Comparator.comparingInt(Candidate::priority).reversed()
                .thenComparingInt(Candidate::sequence));
        StringBuilder context = new StringBuilder();
        for (Candidate candidate : selected) {
            String rendered = "[" + candidate.section() + "]\n" + candidate.text();
            if (context.length() > 0) context.append('\n');
            if (context.length() + rendered.length() > MAX_CONTEXT_CHARS) {
                int available = MAX_CONTEXT_CHARS - context.length();
                if (available > 32) context.append(rendered, 0, available - 24).append("\n...(context truncated)");
                break;
            }
            context.append(rendered);
        }
        return context.toString();
    }

    private static boolean isRecentInPhase(List<String> entries, int index, Phase phase) {
        return recentPhaseRank(entries, index, phase) > 0;
    }

    private static int recentPhaseRank(List<String> entries, int index, Phase phase) {
        int laterEntries = 0;
        for (int i = index + 1; i < entries.size(); i++) {
            if (phaseOf(entries.get(i)) == phase) laterEntries++;
        }
        return laterEntries < RECENT_PHASE_ENTRIES ? RECENT_PHASE_ENTRIES - laterEntries : 0;
    }

    private static boolean isPhaseConclusion(String entry) {
        int firstLineEnd = entry.indexOf('\n');
        return firstLineEnd >= 0 && entry.startsWith("阶段结论：", firstLineEnd + 1);
    }

    private static Phase phaseOf(String entry) {
        if (entry == null || !entry.startsWith("[")) return null;
        int end = entry.indexOf(']');
        if (end < 2) return null;
        String label = entry.substring(1, end);
        for (Phase phase : Phase.values()) {
            if (phase.label().equals(label)) return phase;
        }
        return null;
    }

    private static String truncateEntry(String value, int maxChars) {
        int limit = Math.min(MAX_ENTRY_CHARS, maxChars);
        if (value.length() <= limit) return value;
        int side = (limit - 40) / 2;
        return value.substring(0, side) + "\n...(entry truncated)...\n"
                + value.substring(value.length() - side);
    }

    private static boolean isFailedTestEntry(String entry) {
        int observationStart = entry.indexOf("\n观察：");
        if (observationStart < 0) return false;
        String action = entry.substring(0, observationStart);
        if (!action.contains("run_tests") && !action.contains("shell")) return false;
        String observation = entry.substring(observationStart + "\n观察：".length());
        return FAILED_TEST_RESULT.matcher(observation).find();
    }

    private static int relevance(String text, Set<String> terms) {
        String normalized = text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) if (normalized.contains(term)) score++;
        return score;
    }

    private static Set<String> terms(String symptom) {
        Set<String> terms = new HashSet<>();
        if (symptom == null) return terms;
        Matcher identifiers = IDENTIFIER.matcher(symptom);
        while (identifiers.find()) terms.add(identifiers.group().toLowerCase(Locale.ROOT));
        Matcher chinese = CHINESE_RUN.matcher(symptom);
        while (chinese.find()) {
            String run = chinese.group();
            for (int i = 0; i + 1 < run.length(); i++) terms.add(run.substring(i, i + 2));
        }
        return terms;
    }
}
