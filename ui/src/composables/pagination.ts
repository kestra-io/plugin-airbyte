import { formatNumber } from "./format";

export interface PageEnvelope {
    size: number;
    total: number;
    totalCapped?: boolean;
}

// While capped the real total is unknown, so one more page is always offered.
export function pagerTotal(page?: PageEnvelope | null): number {
    if (!page) return 0;
    return page.totalCapped ? page.total + page.size : page.total;
}

export function totalLabel(page?: PageEnvelope | null): string {
    if (!page) return "";
    return `${formatNumber(page.total)}${page.totalCapped ? "+" : ""}`;
}