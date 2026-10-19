export const MISSING = "—";

export function formatNumber(value?: number | null): string {
    return typeof value === "number" ? value.toLocaleString("en-US") : MISSING;
}

export function formatBytes(value?: number | null): string {
    if (typeof value !== "number") return MISSING;
    const units = ["B", "KB", "MB", "GB", "TB"];
    let unit = 0;
    let scaled = value;
    while (scaled >= 1024 && unit < units.length - 1) {
        scaled /= 1024;
        unit++;
    }
    return `${scaled.toFixed(unit === 0 ? 0 : 2)} ${units[unit]}`;
}

// Outputs carry the duration as an ISO-8601 string ("PT3M15.5S") or as seconds, depending on the task.
export function formatDuration(value?: string | number | null): string {
    if (value === undefined || value === null || value === "") return MISSING;

    let seconds: number;
    if (typeof value === "number") {
        seconds = value;
    } else {
        const match = /^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(value);
        if (!match) return value;
        seconds =
            Number(match[1] ?? 0) * 86400 +
            Number(match[2] ?? 0) * 3600 +
            Number(match[3] ?? 0) * 60 +
            Number(match[4] ?? 0);
    }

    if (seconds < 1) return `${Math.round(seconds * 1000)} ms`;
    if (seconds < 60) return `${seconds.toFixed(seconds % 1 === 0 ? 0 : 1)} s`;
    const minutes = Math.floor(seconds / 60);
    const rest = Math.round(seconds % 60);
    if (minutes < 60) return `${minutes} min ${rest} s`;
    return `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
}

export function formatStatus(value?: string | null): string {
    return value ? value.toUpperCase() : MISSING;
}

// Task outputs are untrusted: only explicit http(s) links may reach an href (KPS-13).
export function safeHttpUrl(value?: unknown): string | undefined {
    if (typeof value !== "string") return undefined;
    try {
        const url = new URL(value);
        return url.protocol === "http:" || url.protocol === "https:" ? url.href : undefined;
    } catch {
        return undefined;
    }
}