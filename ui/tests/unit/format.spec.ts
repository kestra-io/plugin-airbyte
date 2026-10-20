import { describe, expect, test } from "vitest";
import { formatBytes, formatDuration, safeHttpUrl } from "../../src/composables/format";

describe("format", () => {
    test("formats ISO-8601 and numeric durations", () => {
        expect(formatDuration("PT3M15S")).toBe("3 min 15 s");
        expect(formatDuration("PT0.25S")).toBe("250 ms");
        expect(formatDuration(42)).toBe("42 s");
        expect(formatDuration(undefined)).toBe("—");
    });

    test("formats bytes", () => {
        expect(formatBytes(512)).toBe("512 B");
        expect(formatBytes(1_048_576)).toBe("1.00 MB");
        expect(formatBytes(null)).toBe("—");
    });

    test("only lets http(s) links through", () => {
        expect(safeHttpUrl("https://airbyte.example.com/a?b=1")).toBe(
            "https://airbyte.example.com/a?b=1",
        );
        expect(safeHttpUrl("javascript:alert(1)")).toBeUndefined();
        expect(safeHttpUrl("not a url")).toBeUndefined();
        expect(safeHttpUrl(42)).toBeUndefined();
    });
});