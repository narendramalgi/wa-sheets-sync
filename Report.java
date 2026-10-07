package com.example.washeets;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Quick check that a message is the nightly accounts report (the sheet script does the full parsing). */
public final class Report {
    private static final Set<String> LABELS = new HashSet<>(Arrays.asList(
            "pp", "petpooja", "z", "zomato", "s", "swiggy", "cash",
            "accg", "gpay", "googlepay", "accp", "phonepe"));
    private static final Pattern LINE =
            Pattern.compile("^\\s*[*_~]*([A-Za-z][A-Za-z .]*?)[*_~]*\\s*[-–—:=]\\s*\\S.*$");
    public static final int MIN_FIELDS = 3;

    private Report() {}

    public static boolean looksLikeReport(CharSequence text) {
        if (text == null) return false;
        Set<String> found = new HashSet<>();
        for (String line : text.toString().split("\\r?\\n")) {
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String key = m.group(1).toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (LABELS.contains(key)) found.add(key);
        }
        return found.size() >= MIN_FIELDS;
    }
}
