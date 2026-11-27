package com.aether.config.diagnostic;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Locale;

/**
 * Suggests closest matching configuration keys using Levenshtein edit distance.
 */
public final class SpellingSuggester {

    private SpellingSuggester() {
    }

    public static @Nullable String suggest(String unknownKey, Collection<String> candidates) {
        if (unknownKey == null || unknownKey.isEmpty() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        String target = unknownKey.toLowerCase(Locale.ROOT);
        int maxDist = Math.max(1, Math.min(3, target.length() / 2));

        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            int dist = levenshtein(target, candidate.toLowerCase(Locale.ROOT));
            if (dist <= maxDist && dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                );
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}
