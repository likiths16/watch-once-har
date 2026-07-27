package com.watchonce.core;

import java.util.List;

public record Workflow(
        String name,
        List<WorkflowStep> steps,
        List<VariableInfo> variables,
        List<DependencyInfo> dependencies,
        List<Warning> warnings
) {}
