package com.watchonce.core;

/** A discovered chained dependency: step {@code stepIndex} at {@code location} is read from an earlier response. */
public record DependencyInfo(int stepIndex, String location, int sourceStepIndex, String sourceJsonPath) {}
