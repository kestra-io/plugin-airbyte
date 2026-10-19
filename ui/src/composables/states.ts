// Kestra's State.Type values, split into "still going" and "done".
export const RUNNING_STATES = [
    "CREATED",
    "SUBMITTED",
    "RUNNING",
    "PAUSED",
    "RESTARTED",
    "KILLING",
    "QUEUED",
    "RETRYING",
    "BREAKPOINT",
    "RESUBMITTED",
];

export const TERMINAL_STATES = [
    "SUCCESS",
    "WARNING",
    "FAILED",
    "KILLED",
    "CANCELLED",
    "SKIPPED",
    "RETRIED",
];