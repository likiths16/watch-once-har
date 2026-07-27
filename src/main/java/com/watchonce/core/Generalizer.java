package com.watchonce.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns two demonstrations of the same task (two {@link Capture}s) into one {@link Workflow}.
 *
 * <p>Two things happen for every value that appears in a request, in this order:
 * <ol>
 *   <li><b>Dependency check</b> — does this value show up in an earlier response, in both
 *       demos, at the same (step, JSON path)? If so it's a {@link ValuePart.ResponseRef}:
 *       resolved from the live response at run time, never asked of the user. This runs
 *       before the variable check because the brief's stated rule is "dependency wins":
 *       a value copied from a response can't be supplied by the user, even if it also
 *       happens to differ across the two demos.</li>
 *   <li><b>Variable check</b> — if no dependency match, does the value differ between the
 *       two demos? If so it's a {@link ValuePart.Variable} (a named input slot, shared
 *       across every occurrence of the same value pair). If not, it's a fixed
 *       {@link ValuePart.Literal}.</li>
 * </ol>
 *
 * <p>Alignment between the two demos is index-based (request i in demo 1 pairs with
 * request i in demo 2) — see {@code decisions.md} for why this was chosen over sequence
 * alignment, and what that costs.
 */
public final class Generalizer {

    /** Values shorter than this are never treated as dependency matches (too many false positives: "1", "ok", booleans). */
    private static final int MIN_MATCH_LEN = 4;

    private static final Set<String> HEADER_BLOCKLIST = Set.of(
            "host", "content-length", "connection", "accept-encoding", "keep-alive",
            "upgrade-insecure-requests", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site",
            "sec-fetch-user", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
            "dnt", "pragma", "cache-control", "if-none-match", "if-modified-since", "priority",
            "user-agent",
            // Content-Type is structural (dictated by the body encoding, not workflow data) and
            // is re-derived by the run engine from the body template's own kind. Diffing it here
            // was actively harmful: "application/json" is short and generic enough to spuriously
            // match nearly any prior JSON response's own Content-Type header, misclassifying a
            // constant as a chained dependency (found via a real end-to-end run, not a unit test).
            "content-type"
    );

    private static final Pattern BRACKET_SUFFIX = Pattern.compile("\\[\\d+]");
    private static final Pattern NON_IDENTIFIER = Pattern.compile("[^A-Za-z0-9_]");

    private Generalizer() {}

    // ---- data captured about an earlier response, for dependency matching -------------

    private record ResponseLeaf(int stepIndex, String jsonPath, String value) {}

    private record Match(ResponseLeaf leaf, boolean exact) {}

    private record VariableCandidate(String valueA, String valueB, String location) {}

    /** Mutable accumulators threaded through the whole diff pass. */
    private record Ctx(
            List<Warning> warnings,
            List<DependencyInfo> dependencies,
            LinkedHashMap<String, VariableCandidate> placeholders,
            Map<String, String> pairKeyToPlaceholder
    ) {}

    public static Result<Workflow, String> generalize(Capture a, Capture b, String workflowName) {
        List<CapturedRequest> ra = a.requests();
        List<CapturedRequest> rb = b.requests();
        if (ra.size() != rb.size()) {
            return Result.err("The two demos have a different number of API calls (%d vs %d) — ".formatted(ra.size(), rb.size())
                    + "they don't look like the same task. Please redo the second demo following the same steps.");
        }

        List<ResponseLeaf> depIndexA = new ArrayList<>();
        List<ResponseLeaf> depIndexB = new ArrayList<>();
        Ctx ctx = new Ctx(new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>(), new HashMap<>());
        List<WorkflowStep> steps = new ArrayList<>();

        for (int i = 0; i < ra.size(); i++) {
            CapturedRequest reqA = ra.get(i);
            CapturedRequest reqB = rb.get(i);
            if (!reqA.method().equals(reqB.method())) {
                return Result.err("Step %d: HTTP method differs (%s vs %s) — demos don't look like the same task."
                        .formatted(i, reqA.method(), reqB.method()));
            }

            Result<WorkflowStep, String> stepResult = buildStep(reqA, reqB, depIndexA, depIndexB, ctx);
            if (stepResult instanceof Result.Err<WorkflowStep, String> err) {
                return Result.err(err.error());
            }
            steps.add(((Result.Ok<WorkflowStep, String>) stepResult).value());

            depIndexA.addAll(flattenResponseLeaves(reqA));
            depIndexB.addAll(flattenResponseLeaves(reqB));
        }

        Map<String, String> finalNames = assignVariableNames(ctx.placeholders());
        List<WorkflowStep> renamedSteps = steps.stream().map(s -> renameStep(s, finalNames)).toList();
        List<VariableInfo> variables = ctx.placeholders().entrySet().stream()
                .map(e -> new VariableInfo(finalNames.get(e.getKey()), e.getValue().valueA(), e.getValue().valueB()))
                .toList();

        return Result.ok(new Workflow(workflowName, renamedSteps, variables, ctx.dependencies(), ctx.warnings()));
    }

    // ---- per-step diff ------------------------------------------------------------------

    private static Result<WorkflowStep, String> buildStep(
            CapturedRequest reqA, CapturedRequest reqB,
            List<ResponseLeaf> depIndexA, List<ResponseLeaf> depIndexB, Ctx ctx) {

        int stepIndex = reqA.index();

        TemplatedValue origin = diffValue(reqA.origin(), reqB.origin(), depIndexA, depIndexB, "origin", stepIndex, ctx);

        String[] segA = reqA.path().split("/", -1);
        String[] segB = reqB.path().split("/", -1);
        if (segA.length != segB.length) {
            return Result.err("Step %d: URL path shape differs (%s vs %s) — demos don't look like the same task."
                    .formatted(stepIndex, reqA.path(), reqB.path()));
        }
        List<ValuePart> pathParts = new ArrayList<>();
        for (int s = 0; s < segA.length; s++) {
            if (s > 0) {
                pathParts.add(new ValuePart.Literal("/"));
            }
            TemplatedValue seg = diffValue(segA[s], segB[s], depIndexA, depIndexB, "path.segment[" + s + "]", stepIndex, ctx);
            pathParts.addAll(seg.parts());
        }
        TemplatedValue path = mergeLiterals(pathParts);

        Map<String, TemplatedValue> query = diffKeyedParams(reqA.query(), reqB.query(), "query", stepIndex, depIndexA, depIndexB, ctx);
        Map<String, TemplatedValue> headers = diffHeaders(reqA.requestHeaders(), reqB.requestHeaders(), stepIndex, depIndexA, depIndexB, ctx);
        BodyTemplate body = diffBody(reqA, reqB, stepIndex, depIndexA, depIndexB, ctx);

        return Result.ok(new WorkflowStep(stepIndex, reqA.method(), origin, path, query, headers, body));
    }

    private static Map<String, TemplatedValue> diffKeyedParams(
            List<QueryParam> pa, List<QueryParam> pb, String locationPrefix, int stepIndex,
            List<ResponseLeaf> depIndexA, List<ResponseLeaf> depIndexB, Ctx ctx) {

        Map<String, List<String>> mapA = groupByName(pa);
        Map<String, List<String>> mapB = groupByName(pb);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(mapA.keySet());
        names.addAll(mapB.keySet());

        Map<String, TemplatedValue> result = new LinkedHashMap<>();
        for (String name : names) {
            List<String> va = mapA.getOrDefault(name, List.of());
            List<String> vb = mapB.getOrDefault(name, List.of());
            int n = Math.max(va.size(), vb.size());
            boolean duplicated = n > 1;
            for (int k = 0; k < n; k++) {
                String valA = k < va.size() ? va.get(k) : null;
                String valB = k < vb.size() ? vb.get(k) : null;
                String key = duplicated ? name + "[" + k + "]" : name;
                TemplatedValue tv = diffValue(valA, valB, depIndexA, depIndexB, locationPrefix + "." + key, stepIndex, ctx);
                result.put(key, tv);
            }
        }
        return result;
    }

    private static Map<String, List<String>> groupByName(List<QueryParam> params) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (QueryParam p : params) {
            m.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(p.value());
        }
        return m;
    }

    private static Map<String, TemplatedValue> diffHeaders(
            List<Header> ha, List<Header> hb, int stepIndex,
            List<ResponseLeaf> depIndexA, List<ResponseLeaf> depIndexB, Ctx ctx) {

        Map<String, String> displayNameByLower = new LinkedHashMap<>();
        Map<String, String> valuesA = new LinkedHashMap<>();
        Map<String, String> valuesB = new LinkedHashMap<>();
        for (Header h : ha) {
            String lower = h.name().toLowerCase(Locale.ROOT);
            if (HEADER_BLOCKLIST.contains(lower)) continue;
            displayNameByLower.putIfAbsent(lower, h.name());
            valuesA.putIfAbsent(lower, h.value());
        }
        for (Header h : hb) {
            String lower = h.name().toLowerCase(Locale.ROOT);
            if (HEADER_BLOCKLIST.contains(lower)) continue;
            displayNameByLower.putIfAbsent(lower, h.name());
            valuesB.putIfAbsent(lower, h.value());
        }

        LinkedHashSet<String> lowerNames = new LinkedHashSet<>();
        lowerNames.addAll(valuesA.keySet());
        lowerNames.addAll(valuesB.keySet());

        Map<String, TemplatedValue> result = new LinkedHashMap<>();
        for (String lower : lowerNames) {
            String displayName = displayNameByLower.get(lower);
            if (lower.equals("cookie")) {
                result.put(displayName, diffCookieHeader(valuesA.get(lower), valuesB.get(lower), stepIndex, depIndexA, depIndexB, ctx));
            } else {
                TemplatedValue tv = diffValue(valuesA.get(lower), valuesB.get(lower), depIndexA, depIndexB,
                        "header." + displayName, stepIndex, ctx);
                result.put(displayName, tv);
            }
        }
        return result;
    }

    private static TemplatedValue diffCookieHeader(
            String cookieA, String cookieB, int stepIndex,
            List<ResponseLeaf> depIndexA, List<ResponseLeaf> depIndexB, Ctx ctx) {

        Map<String, String> mapA = parseCookieCrumbs(cookieA);
        Map<String, String> mapB = parseCookieCrumbs(cookieB);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(mapA.keySet());
        names.addAll(mapB.keySet());

        List<ValuePart> combined = new ArrayList<>();
        boolean first = true;
        for (String name : names) {
            TemplatedValue tv = diffValue(mapA.get(name), mapB.get(name), depIndexA, depIndexB,
                    "header.Cookie:" + name, stepIndex, ctx);
            if (!first) combined.add(new ValuePart.Literal("; "));
            combined.add(new ValuePart.Literal(name + "="));
            combined.addAll(tv.parts());
            first = false;
        }
        return mergeLiterals(combined);
    }

    /** Collapses adjacent {@link ValuePart.Literal} parts into one, so e.g. path-segment joins ("/","api","/","status") read as a single literal. */
    private static TemplatedValue mergeLiterals(List<ValuePart> parts) {
        List<ValuePart> out = new ArrayList<>();
        StringBuilder pending = null;
        for (ValuePart p : parts) {
            if (p instanceof ValuePart.Literal l) {
                if (pending == null) pending = new StringBuilder();
                pending.append(l.text());
            } else {
                if (pending != null) {
                    out.add(new ValuePart.Literal(pending.toString()));
                    pending = null;
                }
                out.add(p);
            }
        }
        if (pending != null) {
            out.add(new ValuePart.Literal(pending.toString()));
        }
        if (out.isEmpty()) {
            out.add(new ValuePart.Literal(""));
        }
        return new TemplatedValue(List.copyOf(out));
    }

    private static Map<String, String> parseCookieCrumbs(String cookieHeaderValue) {
        Map<String, String> out = new LinkedHashMap<>();
        if (cookieHeaderValue == null || cookieHeaderValue.isBlank()) {
            return out;
        }
        for (String crumb : cookieHeaderValue.split(";")) {
            String trimmed = crumb.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return out;
    }

    private static BodyTemplate diffBody(
            CapturedRequest reqA, CapturedRequest reqB, int stepIndex,
            List<ResponseLeaf> depIndexA, List<ResponseLeaf> depIndexB, Ctx ctx) {

        boolean hasA = reqA.hasRequestBody();
        boolean hasB = reqB.hasRequestBody();
        if (!hasA && !hasB) {
            return new BodyTemplate.NoBody();
        }

        String mime = reqA.requestBodyMimeType() != null ? reqA.requestBodyMimeType() : reqB.requestBodyMimeType();
        String mimeLower = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        boolean jsonish = mimeLower.contains("json") || reqA.requestBodyJson() != null || reqB.requestBodyJson() != null;

        if (jsonish) {
            JsonTemplate template = diffJson(reqA.requestBodyJson(), reqB.requestBodyJson(), "body", stepIndex, depIndexA, depIndexB, ctx);
            return new BodyTemplate.Json(template);
        }
        if (mimeLower.contains("x-www-form-urlencoded")) {
            List<QueryParam> fa = hasA ? parseFormEncoded(reqA.requestBodyRaw()) : List.of();
            List<QueryParam> fb = hasB ? parseFormEncoded(reqB.requestBodyRaw()) : List.of();
            return new BodyTemplate.Form(diffKeyedParams(fa, fb, "body", stepIndex, depIndexA, depIndexB, ctx));
        }
        TemplatedValue tv = diffValue(reqA.requestBodyRaw(), reqB.requestBodyRaw(), depIndexA, depIndexB, "body", stepIndex, ctx);
        return new BodyTemplate.Raw(tv, mime);
    }

    private static List<QueryParam> parseFormEncoded(String raw) {
        List<QueryParam> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            String name = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            out.add(new QueryParam(name, value));
        }
        return out;
    }

    // ---- JSON body diffing ---------------------------------------------------------------

    private static JsonTemplate diffJson(
            JsonNode na, JsonNode nb, String location, int stepIndex,
            List<ResponseLeaf> depIndexA, List<ResponseLeaf> depIndexB, Ctx ctx) {

        boolean aAbsent = na == null || na.isMissingNode() || na.isNull();
        boolean bAbsent = nb == null || nb.isMissingNode() || nb.isNull();
        if (aAbsent && bAbsent) {
            return new JsonTemplate.NullTemplate();
        }

        boolean aContainer = !aAbsent && (na.isObject() || na.isArray());
        boolean bContainer = !bAbsent && (nb.isObject() || nb.isArray());

        if (aContainer && bContainer && na.getNodeType() == nb.getNodeType()) {
            if (na.isObject()) {
                LinkedHashSet<String> keys = new LinkedHashSet<>();
                na.fieldNames().forEachRemaining(keys::add);
                nb.fieldNames().forEachRemaining(keys::add);
                Map<String, JsonTemplate> fields = new LinkedHashMap<>();
                for (String key : keys) {
                    fields.put(key, diffJson(na.get(key), nb.get(key), location + "." + key, stepIndex, depIndexA, depIndexB, ctx));
                }
                return new JsonTemplate.ObjectTemplate(fields);
            } else {
                if (na.size() != nb.size()) {
                    ctx.warnings().add(new Warning(stepIndex, location,
                            "array length differs between demos (%d vs %d items) — using demo 1's array as a fixed literal; edit the workflow manually for variable-length arrays."
                                    .formatted(na.size(), nb.size())));
                    return literalJsonTemplate(na, stepIndex, depIndexA, ctx, location);
                }
                List<JsonTemplate> items = new ArrayList<>();
                for (int i = 0; i < na.size(); i++) {
                    items.add(diffJson(na.get(i), nb.get(i), location + "[" + i + "]", stepIndex, depIndexA, depIndexB, ctx));
                }
                return new JsonTemplate.ArrayTemplate(items);
            }
        }

        if (!aContainer && !bContainer) {
            String va = aAbsent ? null : na.asText();
            String vb = bAbsent ? null : nb.asText();
            JsonTemplate.LeafType type = inferLeafType(aAbsent ? nb : na);
            TemplatedValue tv = diffValue(va, vb, depIndexA, depIndexB, location, stepIndex, ctx);
            return new JsonTemplate.LeafTemplate(tv, type);
        }

        ctx.warnings().add(new Warning(stepIndex, location,
                "value shape differs between demos (plain value vs object/array) — generalized as demo 1's literal; verify manually."));
        return literalJsonTemplate(aAbsent ? nb : na, stepIndex, aAbsent ? depIndexB : depIndexA, ctx, location);
    }

    /** One-sided conversion of a JSON subtree into a template: still dependency-aware, but never a variable (nothing to diff against). */
    private static JsonTemplate literalJsonTemplate(
            JsonNode node, int stepIndex, List<ResponseLeaf> depIndex, Ctx ctx, String location) {

        if (node == null || node.isMissingNode() || node.isNull()) {
            return new JsonTemplate.NullTemplate();
        }
        if (node.isObject()) {
            Map<String, JsonTemplate> fields = new LinkedHashMap<>();
            var it = node.fields();
            while (it.hasNext()) {
                var e = it.next();
                fields.put(e.getKey(), literalJsonTemplate(e.getValue(), stepIndex, depIndex, ctx, location + "." + e.getKey()));
            }
            return new JsonTemplate.ObjectTemplate(fields);
        }
        if (node.isArray()) {
            List<JsonTemplate> items = new ArrayList<>();
            for (int i = 0; i < node.size(); i++) {
                items.add(literalJsonTemplate(node.get(i), stepIndex, depIndex, ctx, location + "[" + i + "]"));
            }
            return new JsonTemplate.ArrayTemplate(items);
        }
        String value = node.asText();
        Match m = findMatch(value, depIndex);
        TemplatedValue tv;
        if (m != null) {
            ctx.dependencies().add(new DependencyInfo(stepIndex, location, m.leaf().stepIndex(), m.leaf().jsonPath()));
            tv = toTemplatedValue(splitAroundMatch(value, m));
        } else {
            tv = TemplatedValue.literal(value);
        }
        return new JsonTemplate.LeafTemplate(tv, inferLeafType(node));
    }

    private static JsonTemplate.LeafType inferLeafType(JsonNode n) {
        if (n == null) return JsonTemplate.LeafType.STRING;
        if (n.isBoolean()) return JsonTemplate.LeafType.BOOLEAN;
        if (n.isNumber()) return JsonTemplate.LeafType.NUMBER;
        return JsonTemplate.LeafType.STRING;
    }

    // ---- the core per-value decision: dependency, then variable, then literal -----------

    private static TemplatedValue diffValue(
            String valueA, String valueB,
            List<ResponseLeaf> depIndexA, List<ResponseLeaf> depIndexB,
            String location, int stepIndex, Ctx ctx) {

        if (valueA == null && valueB == null) {
            return TemplatedValue.literal("");
        }

        if (valueA == null || valueB == null) {
            String only = valueA != null ? valueA : valueB;
            List<ResponseLeaf> idx = valueA != null ? depIndexA : depIndexB;
            Match m = findMatch(only, idx);
            if (m != null) {
                ctx.dependencies().add(new DependencyInfo(stepIndex, location, m.leaf().stepIndex(), m.leaf().jsonPath()));
                ctx.warnings().add(new Warning(stepIndex, location,
                        "present in only one demo, but matches an earlier response — treated as a dependency."));
                return toTemplatedValue(splitAroundMatch(only, m));
            }
            ctx.warnings().add(new Warning(stepIndex, location,
                    "present in only one demo — kept as a fixed literal since it can't be confirmed stable across runs."));
            return TemplatedValue.literal(only);
        }

        Match ma = findMatch(valueA, depIndexA);
        Match mb = findMatch(valueB, depIndexB);

        if (ma != null && mb != null && ma.leaf().stepIndex() == mb.leaf().stepIndex() && ma.leaf().jsonPath().equals(mb.leaf().jsonPath())) {
            ctx.dependencies().add(new DependencyInfo(stepIndex, location, ma.leaf().stepIndex(), ma.leaf().jsonPath()));
            var splitA = splitAroundMatch(valueA, ma);
            var splitB = splitAroundMatch(valueB, mb);
            if (!splitA.prefix().equals(splitB.prefix()) || !splitA.suffix().equals(splitB.suffix())) {
                ctx.warnings().add(new Warning(stepIndex, location,
                        "text surrounding the referenced value differs between demos ('%s…%s' vs '%s…%s') — using demo 1's wrapping text."
                                .formatted(splitA.prefix(), splitA.suffix(), splitB.prefix(), splitB.suffix())));
            }
            return toTemplatedValue(splitA);
        }

        if (ma != null || mb != null) {
            boolean sameValue = valueA.equals(valueB);
            ctx.warnings().add(new Warning(stepIndex, location,
                    "matches an earlier response in only one of the two demos (inconsistent source) — could not confirm a dependency; "
                            + (sameValue ? "treated as a fixed literal." : "treated as a variable — please double-check its example values.")));
            if (sameValue) {
                return TemplatedValue.literal(valueA);
            }
            return registerVariable(valueA, valueB, location, ctx);
        }

        if (valueA.equals(valueB)) {
            return TemplatedValue.literal(valueA);
        }
        return registerVariable(valueA, valueB, location, ctx);
    }

    private static TemplatedValue registerVariable(String valueA, String valueB, String location, Ctx ctx) {
        String pairKey = valueA + " " + valueB;
        String placeholder = ctx.pairKeyToPlaceholder().get(pairKey);
        if (placeholder == null) {
            placeholder = "__var_" + ctx.placeholders().size();
            ctx.placeholders().put(placeholder, new VariableCandidate(valueA, valueB, location));
            ctx.pairKeyToPlaceholder().put(pairKey, placeholder);
        }
        return new TemplatedValue(List.of(new ValuePart.Variable(placeholder)));
    }

    // ---- dependency matching --------------------------------------------------------------

    private static Match findMatch(String value, List<ResponseLeaf> index) {
        if (value == null || value.length() < MIN_MATCH_LEN) return null;
        Match best = null;
        for (ResponseLeaf leaf : index) {
            String lv = leaf.value();
            if (lv == null || lv.length() < MIN_MATCH_LEN) continue;
            boolean exact = value.equals(lv);
            boolean substring = !exact && value.contains(lv);
            if (!exact && !substring) continue;
            if (best == null || isBetter(leaf, exact, best)) {
                best = new Match(leaf, exact);
            }
        }
        return best;
    }

    private static boolean isBetter(ResponseLeaf candidate, boolean candidateExact, Match currentBest) {
        if (candidateExact != currentBest.exact()) return candidateExact;
        int lenCmp = Integer.compare(candidate.value().length(), currentBest.leaf().value().length());
        if (lenCmp != 0) return lenCmp > 0;
        return candidate.stepIndex() >= currentBest.leaf().stepIndex();
    }

    private record MatchSplit(String prefix, ValuePart.ResponseRef ref, String suffix) {}

    private static MatchSplit splitAroundMatch(String value, Match m) {
        int idx = value.indexOf(m.leaf().value());
        String prefix = value.substring(0, idx);
        String suffix = value.substring(idx + m.leaf().value().length());
        return new MatchSplit(prefix, new ValuePart.ResponseRef(m.leaf().stepIndex(), m.leaf().jsonPath()), suffix);
    }

    private static TemplatedValue toTemplatedValue(MatchSplit s) {
        List<ValuePart> parts = new ArrayList<>();
        if (!s.prefix().isEmpty()) parts.add(new ValuePart.Literal(s.prefix()));
        parts.add(s.ref());
        if (!s.suffix().isEmpty()) parts.add(new ValuePart.Literal(s.suffix()));
        return new TemplatedValue(List.copyOf(parts));
    }

    // ---- flattening a response into leaves for later dependency matching -----------------

    private static List<ResponseLeaf> flattenResponseLeaves(CapturedRequest req) {
        List<ResponseLeaf> out = new ArrayList<>();
        if (req.responseBodyJson() != null) {
            JsonPaths.flatten(req.responseBodyJson())
                    .forEach((path, value) -> out.add(new ResponseLeaf(req.index(), path, value)));
        }
        for (Header h : req.responseHeaders()) {
            out.add(new ResponseLeaf(req.index(), "header:" + h.name(), h.value()));
            if (h.name().equalsIgnoreCase("set-cookie")) {
                String first = h.value().split(";", 2)[0].trim();
                int eq = first.indexOf('=');
                if (eq > 0) {
                    String cname = first.substring(0, eq).trim();
                    String cval = first.substring(eq + 1).trim();
                    out.add(new ResponseLeaf(req.index(), "cookie:" + cname, cval));
                }
            }
        }
        return out;
    }

    // ---- variable naming --------------------------------------------------------------

    private static Map<String, String> assignVariableNames(LinkedHashMap<String, VariableCandidate> placeholders) {
        Set<String> used = new HashSet<>();
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : placeholders.entrySet()) {
            String base = deriveBaseName(entry.getValue().location());
            String name = base;
            int n = 2;
            while (!used.add(name)) {
                name = base + "_" + n;
                n++;
            }
            result.put(entry.getKey(), name);
        }
        return result;
    }

    private static String deriveBaseName(String location) {
        String token;
        if (location.startsWith("body.") || location.startsWith("body[")) {
            String rest = location.substring(4);
            int lastDot = rest.lastIndexOf('.');
            token = lastDot >= 0 ? rest.substring(lastDot + 1) : rest;
        } else if (location.startsWith("query.")) {
            token = location.substring("query.".length());
        } else if (location.startsWith("header.Cookie:")) {
            token = location.substring("header.Cookie:".length());
        } else if (location.startsWith("header.")) {
            token = location.substring("header.".length());
        } else if (location.startsWith("path.segment[")) {
            String num = location.replaceAll("\\D", "");
            int segIdx = num.isEmpty() ? 0 : Integer.parseInt(num);
            token = "pathParam" + (segIdx + 1);
        } else if (location.equals("body")) {
            token = "body";
        } else {
            token = location;
        }
        token = BRACKET_SUFFIX.matcher(token).replaceAll("");
        token = NON_IDENTIFIER.matcher(token).replaceAll("_");
        if (token.isEmpty() || Character.isDigit(token.charAt(0))) {
            token = "var_" + token;
        }
        return token;
    }

    // ---- rewriting placeholder variable names to their final assigned names -------------

    private static WorkflowStep renameStep(WorkflowStep step, Map<String, String> rename) {
        return new WorkflowStep(
                step.index(),
                step.method(),
                renameTemplatedValue(step.origin(), rename),
                renameTemplatedValue(step.path(), rename),
                renameMapValues(step.query(), rename),
                renameMapValues(step.headers(), rename),
                renameBody(step.body(), rename)
        );
    }

    private static Map<String, TemplatedValue> renameMapValues(Map<String, TemplatedValue> map, Map<String, String> rename) {
        Map<String, TemplatedValue> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, renameTemplatedValue(v, rename)));
        return out;
    }

    private static BodyTemplate renameBody(BodyTemplate body, Map<String, String> rename) {
        return switch (body) {
            case BodyTemplate.NoBody nb -> nb;
            case BodyTemplate.Json j -> new BodyTemplate.Json(renameJson(j.template(), rename));
            case BodyTemplate.Form f -> new BodyTemplate.Form(renameMapValues(f.fields(), rename));
            case BodyTemplate.Raw r -> new BodyTemplate.Raw(renameTemplatedValue(r.value(), rename), r.mimeType());
        };
    }

    private static JsonTemplate renameJson(JsonTemplate t, Map<String, String> rename) {
        return switch (t) {
            case JsonTemplate.NullTemplate n -> n;
            case JsonTemplate.LeafTemplate l -> new JsonTemplate.LeafTemplate(renameTemplatedValue(l.value(), rename), l.type());
            case JsonTemplate.ObjectTemplate o -> {
                Map<String, JsonTemplate> fields = new LinkedHashMap<>();
                o.fields().forEach((k, v) -> fields.put(k, renameJson(v, rename)));
                yield new JsonTemplate.ObjectTemplate(fields);
            }
            case JsonTemplate.ArrayTemplate arr -> new JsonTemplate.ArrayTemplate(
                    arr.items().stream().map(v -> renameJson(v, rename)).toList());
        };
    }

    private static TemplatedValue renameTemplatedValue(TemplatedValue tv, Map<String, String> rename) {
        List<ValuePart> parts = tv.parts().stream()
                .map(p -> p instanceof ValuePart.Variable v
                        ? new ValuePart.Variable(rename.getOrDefault(v.name(), v.name()))
                        : p)
                .toList();
        return new TemplatedValue(parts);
    }
}
