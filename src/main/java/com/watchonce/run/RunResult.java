package com.watchonce.run;

import java.util.List;

public record RunResult(String workflowName, boolean success, List<RunStepLog> steps) {}
