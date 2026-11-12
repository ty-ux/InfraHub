package com.infrahub.release.service.executor.kubernetes;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.infrahub.release.dto.KubernetesExecutionConfig;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The pure configuration policy of the Kubernetes executor: whether a stored payload is a usable
 * configuration, decided from the payload text alone.
 *
 * <p>Nothing here opens a file, reads a kubeconfig, resolves a manifest or contacts a cluster, and
 * nothing here knows about any host but the one that runs the backend. That is what allows the same
 * rules to gate a configuration when it is written and when it is enabled, while the per-release
 * pre-flight - file present and readable, manifest within the accepted scope - stays in
 * {@link ManifestValidator} and still runs before every release.</p>
 *
 * <p>A payload is held to the JSON types it declares: a numeric {@code namespace} is not read back as
 * text, so the type an operator typed is the type the executor gets. The presence rules - the fields
 * a configuration must carry - are the ones {@link KubernetesReleaseExecutor#validate} applies before
 * a release, computed by {@link #configIssues} for both callers, so a configuration cannot pass one
 * gate and fail the other.</p>
 *
 * <p>An issue names the field and what is wrong with it, and never repeats a value from the payload:
 * a kubeconfig path may carry a host name and a manifest content may carry anything at all, so
 * neither is quoted back into a message.</p>
 */
public final class KubernetesConfigPolicy {

    /**
     * Reads the payload as a JSON tree. Strict on the two points that decide whether the stored text
     * says what its author meant: trailing content after the object is refused, and no value is
     * coerced into another type - the per-field checks below read the JSON types as declared.
     */
    private static final ObjectMapper PAYLOAD_READER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final String KUBECONFIG_PATH = "kubeconfigPath";
    private static final String KUBE_CONTEXT = "kubeContext";
    private static final String NAMESPACE = "namespace";
    private static final String MANIFEST_PATH = "manifestPath";
    private static final String MANIFEST_CONTENT = "manifestContent";
    private static final String WORKLOAD_NAME = "workloadName";
    private static final String ROLLOUT_TIMEOUT_SECONDS = "rolloutTimeoutSeconds";
    private static final String LOG_TAIL_LINES = "logTailLines";

    /** Fields that name a path; whether the file exists is decided per release, not here. */
    private static final Set<Field> PATH_FIELDS = Set.of(Field.KUBECONFIG_PATH, Field.MANIFEST_PATH);

    private static final Set<Field> POSITIVE_INTEGER_FIELDS = Set.of(
            Field.ROLLOUT_TIMEOUT_SECONDS, Field.LOG_TAIL_LINES);

    /** Every field the policy type-checks; an unknown field is left alone and carried through. */
    private static final List<Field> TEXT_FIELDS = List.of(
            Field.KUBECONFIG_PATH, Field.KUBE_CONTEXT, Field.NAMESPACE,
            Field.MANIFEST_PATH, Field.MANIFEST_CONTENT, Field.WORKLOAD_NAME);

    private KubernetesConfigPolicy() {
    }

    /** The fields a problem can be reported against, named as the payload names them. */
    public enum Field {

        PAYLOAD("payload"),
        KUBECONFIG_PATH(KubernetesConfigPolicy.KUBECONFIG_PATH),
        KUBE_CONTEXT(KubernetesConfigPolicy.KUBE_CONTEXT),
        NAMESPACE(KubernetesConfigPolicy.NAMESPACE),
        MANIFEST_PATH(KubernetesConfigPolicy.MANIFEST_PATH),
        MANIFEST_CONTENT(KubernetesConfigPolicy.MANIFEST_CONTENT),
        /** The manifest as a whole: neither a path nor content was configured. */
        MANIFEST(KubernetesConfigPolicy.MANIFEST_PATH + "/" + KubernetesConfigPolicy.MANIFEST_CONTENT),
        WORKLOAD_NAME(KubernetesConfigPolicy.WORKLOAD_NAME),
        ROLLOUT_TIMEOUT_SECONDS(KubernetesConfigPolicy.ROLLOUT_TIMEOUT_SECONDS),
        LOG_TAIL_LINES(KubernetesConfigPolicy.LOG_TAIL_LINES);

        private final String jsonKey;

        Field(String jsonKey) {
            this.jsonKey = jsonKey;
        }

        public String jsonKey() {
            return jsonKey;
        }
    }

    /** What is wrong with a field. The reason is carried, not a message, so each caller can word it. */
    public enum Reason {
        EMPTY,
        NOT_JSON,
        NOT_JSON_OBJECT,
        MISSING,
        NOT_NON_BLANK_TEXT,
        NOT_ABSOLUTE_PATH,
        NOT_POSITIVE_INTEGER
    }

    public record Issue(Field field, Reason reason) {
    }

    /**
     * The reasons {@code payload} cannot be stored or enabled as a Kubernetes configuration; empty
     * when it can.
     *
     * <p>Structural failures - empty text, text that is not JSON, JSON that is not an object - are
     * reported on their own, because no field can be named before the object exists. Field problems
     * are then reported together, so a configuration missing several things names all of them.</p>
     */
    public static List<Issue> payloadIssues(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of(new Issue(Field.PAYLOAD, Reason.EMPTY));
        }

        JsonNode root;
        try {
            root = PAYLOAD_READER.readTree(payload);
        } catch (Exception e) {
            return List.of(new Issue(Field.PAYLOAD, Reason.NOT_JSON));
        }
        if (root == null || !root.isObject()) {
            return List.of(new Issue(Field.PAYLOAD, Reason.NOT_JSON_OBJECT));
        }

        List<Issue> issues = new ArrayList<>();
        for (Field field : TEXT_FIELDS) {
            JsonNode node = root.get(field.jsonKey());
            if (node == null || node.isNull()) {
                continue;
            }
            // A blank string says the field was left empty, which is how the executor reads it - so it
            // is not a type error, it is absence, and the presence rules below decide. Any other type
            // is refused: a numeric namespace is not read back as its digits.
            if (!node.isTextual()) {
                issues.add(new Issue(field, Reason.NOT_NON_BLANK_TEXT));
            }
        }

        for (Field field : PATH_FIELDS) {
            JsonNode node = root.get(field.jsonKey());
            if (node == null || !node.isTextual() || node.asText().isBlank()) {
                continue;
            }
            if (!isAbsolutePath(node.asText())) {
                issues.add(new Issue(field, Reason.NOT_ABSOLUTE_PATH));
            }
        }

        for (Field field : POSITIVE_INTEGER_FIELDS) {
            JsonNode node = root.get(field.jsonKey());
            if (node == null || node.isNull()) {
                continue;
            }
            if (!node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() <= 0) {
                issues.add(new Issue(field, Reason.NOT_POSITIVE_INTEGER));
            }
        }

        if (!issues.isEmpty()) {
            return List.copyOf(issues);
        }

        // Every known field now carries the type the executor expects, so the payload reads back as
        // the typed configuration the executor gate reads, and that gate supplies the presence rules.
        KubernetesExecutionConfig config;
        try {
            config = PAYLOAD_READER.readValue(payload, KubernetesExecutionConfig.class);
        } catch (Exception e) {
            return List.of(new Issue(Field.PAYLOAD, Reason.NOT_JSON));
        }

        issues.addAll(configIssues(config));
        return List.copyOf(issues);
    }

    /**
     * The fields a typed configuration is missing. This is the gate the executor applies before a
     * release, so a configuration that is written here is one that gate can accept.
     */
    static List<Issue> configIssues(KubernetesExecutionConfig config) {
        List<Issue> issues = new ArrayList<>();
        if (!config.hasKubeconfigPath()) {
            issues.add(new Issue(Field.KUBECONFIG_PATH, Reason.MISSING));
        }
        if (!config.hasKubeContext()) {
            issues.add(new Issue(Field.KUBE_CONTEXT, Reason.MISSING));
        }
        if (!config.hasNamespace()) {
            issues.add(new Issue(Field.NAMESPACE, Reason.MISSING));
        }
        if (!config.hasManifestPath() && !config.hasManifestContent()) {
            issues.add(new Issue(Field.MANIFEST, Reason.MISSING));
        }
        return List.copyOf(issues);
    }

    /**
     * Whether the backend host recognizes {@code value} as an absolute path. Only the form of the
     * path is read - the file itself is never looked at, so a configuration can be written for a host
     * that receives the file later.
     */
    private static boolean isAbsolutePath(String value) {
        try {
            return Path.of(value).isAbsolute();
        } catch (InvalidPathException e) {
            return false;
        }
    }
}