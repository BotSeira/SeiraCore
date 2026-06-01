package xyz.zcraft.seira.util;

public class TimeDurationParser {
    public static boolean isTimeRange(String input) {
        try {
            TimeDurationParser.parseRange(input);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return true;
    }

    public static TimeRange parseRange(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Input cannot be null or empty");
        }

        if (input.trim().equals("-")) {
            return TimeRange.ALL;
        }

        String[] parts = input.trim().split("-", -1);

        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid range format. Expected 'start-end': " + input);
        }

        Integer start = parseTimeSegment(parts[0]);
        Integer end = parseTimeSegment(parts[1]);

        return new TimeRange(start, end);
    }

    private static Integer parseTimeSegment(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }

        time = time.trim();

        if (time.contains(":")) {
            String[] timeParts = time.split(":");
            int minutes = Integer.parseInt(timeParts[0]);
            int seconds = Integer.parseInt(timeParts[1]);
            return (minutes * 60) + seconds;
        } else {
            return Integer.parseInt(time);
        }
    }

    public record TimeRange(Integer startSeconds, Integer endSeconds) {
        public static final TimeRange ALL = new TimeRange(0, Integer.MAX_VALUE);

        public String toQueryString() {
            if (this == ALL) return "";

            StringBuilder query = new StringBuilder();
            if (startSeconds != null) {
                query.append("&start=").append(startSeconds);
            }
            if (endSeconds != null) {
                query.append("&end=").append(endSeconds);
            }
            return query.toString();
        }
    }
}