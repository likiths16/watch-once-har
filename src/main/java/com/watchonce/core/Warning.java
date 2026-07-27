package com.watchonce.core;

/** A soft issue surfaced during generalization — the workflow is still usable, but a human should look. */
public record Warning(int stepIndex, String location, String message) {}
