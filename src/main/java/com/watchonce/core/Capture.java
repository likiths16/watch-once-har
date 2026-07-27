package com.watchonce.core;

import java.util.List;

/** A single HAR file, parsed down to the API calls we care about, in original order. */
public record Capture(String name, List<CapturedRequest> requests) {}
