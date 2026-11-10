import { describe, expect, test } from "vitest";
import { pagerTotal, totalLabel } from "../../src/composables/pagination";

describe("pagination", () => {
    test("an exact total is used as is", () => {
        const page = { size: 50, total: 120 };
        expect(pagerTotal(page)).toBe(120);
        expect(totalLabel(page)).toBe("120");
    });

    test("a capped total shows a plus and always leaves a next page", () => {
        const page = { size: 50, total: 10000, totalCapped: true };
        expect(pagerTotal(page)).toBe(10050);
        expect(totalLabel(page)).toBe("10,000+");
    });

    test("no data gives an empty pager", () => {
        expect(pagerTotal(undefined)).toBe(0);
        expect(totalLabel(undefined)).toBe("");
    });
});