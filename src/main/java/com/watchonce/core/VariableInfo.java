package com.watchonce.core;

/** A discovered user-input slot: the two example values that made the diff flag it as one. */
public record VariableInfo(String name, String exampleValue1, String exampleValue2) {}
