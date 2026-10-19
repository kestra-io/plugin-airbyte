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

export function isCloudTask(task?: Record<string, any>): boolean {
    return String(task?.type ?? "").startsWith("io.kestra.plugin.airbyte.cloud.");
}

function settingKeys(task?: Record<string, any>): string[] {
    const type = String(task?.type ?? "");
    if (isCloudTask(task)) return CLOUD;
    return type.endsWith(".CheckStatus") ? SELF_HOSTED_CHECK : SELF_HOSTED_SYNC;
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
        settings.push({ key, label: key, value: String(value) });
    }

    const autoAssets = task.assets?.enableAuto;
    if (
        !isCloudTask(task) &&
        !String(task.type).endsWith(".CheckStatus") &&
        autoAssets !== undefined
    ) {
        settings.push({
            key: "assets.enableAuto",
            label: "assets.enableAuto",
            value: String(autoAssets),
        });
    }

    const auth = authMethod(task);
    if (auth) settings.push({ key: "authentication", label: "authentication", value: auth });
    return settings;
}