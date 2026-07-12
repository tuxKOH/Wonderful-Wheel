package com.wheel.app.search;

import com.wheel.app.model.Models.Wheel;
import com.wheel.app.model.Models.WheelOption;

import java.util.Comparator;
import java.util.List;

public final class WheelSearch {
    private WheelSearch() {}

    public record Result(Wheel wheel, int score) {}

    public static List<Result> search(List<Wheel> wheels, String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        return wheels.stream()
            .map(w -> new Result(w, q.isEmpty() ? 1 : score(w, q)))
            .filter(r -> r.score > 0)
            .sorted(Comparator.<Result>comparingInt(Result::score).reversed()
                .thenComparing(r -> -r.wheel.updatedAt))
            .toList();
    }

    private static int score(Wheel wheel, String q) {
        int score = textScore(wheel.name, q, 100, 60, 40);
        for (WheelOption option : wheel.options) {
            score += textScore(option.text, q, 35, 20, 10);
        }
        return score;
    }

    private static int textScore(String text, String q, int exact, int prefix, int contains) {
        String s = text == null ? "" : text.toLowerCase();
        if (s.equals(q)) return exact;
        if (s.startsWith(q)) return prefix;
        if (s.contains(q)) return contains;
        return 0;
    }
}
