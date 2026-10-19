import { formatDuration } from "./format";

export interface Setting {
    key: string;
    label: string;
    value: string;
}

const SELF_HOSTED_SYNC = [
    "connectionId",
    "url",
    "webappUrl",
    "wait",
    "pollFrequency",
    "maxDuration",
    "onActiveSync",
    "onPartialSuccess",
];
const SELF_HOSTED_CHECK = [
    "jobId",
    "url",
    "webappUrl",
    "pollFrequency",
    "maxDuration",
    "onPartialSuccess",
];
const CLOUD = ["connectionId", "wait", "pollFrequency", "maxDuration"];

const LABELS: Record<string, string> = {
    connectionId: "Connection ID",
    jobId: "Job ID",
    url: "Airbyte URL",
    webappUrl: "Web app URL",
    wait: "Wait for completion",
    pollFrequency: "Poll frequency",
    maxDuration: "Max duration",
    onActiveSync: "On active sync",
    onPartialSuccess: "On partial success",
    "assets.enableAuto": "Automatic assets",
    authentication: "Authentication",
};

const DURATION_KEYS = ["pollFrequency", "maxDuration"];

export function isCloudTask(task?: Record<string, any>): boolean {
    return String(task?.type ?? "").startsWith("io.kestra.plugin.airbyte.cloud.");
}

function settingKeys(task?: Record<string, any>): string[] {
    const type = String(task?.type ?? "");
    if (isCloudTask(task)) return CLOUD;
    return type.endsWith(".CheckStatus") ? SELF_HOSTED_CHECK : SELF_HOSTED_SYNC;
}

// The two rows the topology card shows before a run: what identifies the work, and where it runs.
export function headlineSettings(task?: Record<string, any>): Setting[] {
    if (!task) return [];
    const keys = isCloudTask(task)
        ? ["connectionId"]
        : String(task.type).endsWith(".CheckStatus")
          ? ["jobId", "url"]
          : ["connectionId", "url"];
    return keys.map((key) => ({
        key,
        label: LABELS[key] ?? key,
        value: task[key] === undefined || task[key] === null ? "—" : String(task[key]),
    }));
}

// Only the authentication method is reported: credential values (token, password, client ID and
// secret) are deliberately never read.
export function authMethod(task?: Record<string, any>): string | undefined {
    if (!task) return undefined;
    if (isCloudTask(task)) {
        if (task.token) return "Bearer token";
        if (task.clientId || task.clientSecret) return "Client credentials";
        if (task.username || task.password) return "Basic auth";
        return undefined;
    }
    if (task.applicationCredentials) return "Application credentials";
    if (task.token) return "Bearer token";
    if (task.username || task.password) return "Basic auth";
    return undefined;
}

// Values come from the unrendered task, so expressions are shown as written. Unset properties are omitted.
export function taskSettings(task?: Record<string, any>): Setting[] {
    if (!task) return [];

    const settings: Setting[] = [];
    for (const key of settingKeys(task)) {
        const value = task[key];
        if (value === undefined || value === null || typeof value === "object") continue;
        // Literal ISO durations are shown readably; expressions stay as written.
        const text = String(value);
        const shown =
            DURATION_KEYS.includes(key) && /^PT?\d/.test(text) ? formatDuration(text) : text;
        settings.push({ key, label: LABELS[key] ?? key, value: shown });
    }

    const autoAssets = task.assets?.enableAuto;
    if (
        !isCloudTask(task) &&
        !String(task.type).endsWith(".CheckStatus") &&
        autoAssets !== undefined
    ) {
        settings.push({
            key: "assets.enableAuto",
            label: LABELS["assets.enableAuto"],
            value: String(autoAssets),
        });
    }

    const auth = authMethod(task);
    if (auth) settings.push({ key: "authentication", label: LABELS.authentication, value: auth });
    return settings;
}