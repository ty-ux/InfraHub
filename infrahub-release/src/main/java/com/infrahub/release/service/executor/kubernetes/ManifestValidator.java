package com.infrahub.release.service.executor.kubernetes;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.composer.ComposerException;
import org.yaml.snakeyaml.constructor.Construct;
import org.yaml.snakeyaml.constructor.ConstructorException;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.parser.ParserException;
import org.yaml.snakeyaml.reader.ReaderException;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;
import org.yaml.snakeyaml.scanner.ScannerException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Pre-flight policy for the Kubernetes executor's own inputs: the kubeconfig binding and the manifest.
 *
 * <p>Everything here decides whether the executor is allowed to start kubectl at all; a rejection is
 * reported as a message and ends the release before any child process exists. Accepted manifests are
 * returned as a content snapshot so that exactly the reviewed bytes are the ones handed to
 * {@code kubectl apply -f -} - the file is never re-read by the child.</p>
 *
 * <p>A manifest object is accepted only as a <em>complete</em> resource type - an allowed
 * {@code apiVersion} together with the kind that belongs to it - so the resource a release acts on is
 * pinned by both halves of its type. This is a bounded allowlist of what this executor will send, not
 * a Kubernetes schema validation: nothing here checks spec fields, and it is not a substitute for
 * RBAC, admission control or a network policy.</p>
 */
final class ManifestValidator {

    /**
     * The complete resource types this executor accepts in this iteration: kind and apiVersion
     * together, because a kind on its own does not identify a type. A kind outside this map, and an
     * allowed kind carrying a different version or API group, are both refused. Secret is deliberately
     * outside this policy.
     */
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "Deployment", "apps/v1",
            "Service", "v1",
            "ConfigMap", "v1");

    private static final String DEPLOYMENT_KIND = "Deployment";
    private static final String DEPLOYMENT_API_VERSION = "apps/v1";

    /**
     * Structural bounds of the YAML parser: an accepted manifest is a small document, so a deep,
     * long or alias-heavy one is rejected rather than parsed. Duplicate keys are rejected because a
     * map that silently keeps the last of two {@code metadata} blocks is not a manifest to act on.
     */
    private static final int MAX_NESTING_DEPTH = 50;
    private static final int MAX_ALIASES = 50;
    private static final int MAX_CODE_POINTS = 1_000_000;

    /**
     * Fixed failure categories. A rejection names one of these instead of repeating anything the
     * manifest said: the parser's own messages quote the offending keys, values, tags and the lines
     * around them, and none of that may reach a release log.
     */
    private static final String PARSE_FAILURE = "parse failure";
    private static final String DUPLICATE_KEY = "duplicate key";
    private static final String FORBIDDEN_TAG = "forbidden tag";
    private static final String STRUCTURE_LIMIT = "structure or size limit";
    private static final String SCALAR_CONSTRUCTION_FAILURE = "scalar construction failure";
    private static final String INVALID_CHARACTER = "invalid character";
    private static final String UNCLASSIFIED = "unclassified YAML failure";

    /**
     * Standard YAML tags this parser will construct - exactly the set {@link SafeConstructor} knows
     * how to build. Any other tag, whether a global one such as {@code !!java.util.Date} or a local
     * one, is refused by type before a constructor is chosen, so the refusal never has to name the
     * tag or repeat the node it was attached to.
     */
    private static final Set<Tag> STANDARD_TAGS = Set.of(
            Tag.BINARY, Tag.BOOL, Tag.FLOAT, Tag.INT, Tag.MAP, Tag.MERGE,
            Tag.NULL, Tag.OMAP, Tag.PAIRS, Tag.SEQ, Tag.SET, Tag.STR, Tag.TIMESTAMP);

    private ManifestValidator() {
    }

    /**
     * Confirms {@code kubeconfigPath} is an absolute path to a readable regular file.
     *
     * <p>The file is never read: only its type and readability are inspected, so no credential
     * material from the kubeconfig can enter a log, an error message or a result.</p>
     *
     * @return null when the path is usable, otherwise the reason it is not
     */
    static String validateKubeconfigPath(String kubeconfigPath) {
        if (isBlank(kubeconfigPath)) {
            return "kubeconfigPath must be configured";
        }
        Path path;
        try {
            path = Path.of(kubeconfigPath);
        } catch (InvalidPathException e) {
            return "kubeconfigPath is not a valid file path: " + kubeconfigPath;
        }
        if (!path.isAbsolute()) {
            return "kubeconfigPath must be an absolute path: " + kubeconfigPath;
        }
        if (!Files.exists(path)) {
            return "kubeconfigPath does not exist: " + path;
        }
        if (!Files.isRegularFile(path)) {
            return "kubeconfigPath is not a regular file: " + path;
        }
        if (!Files.isReadable(path)) {
            return "kubeconfigPath is not readable: " + path;
        }
        return null;
    }

    /**
     * Applies {@link #validateContent} to the decoded content of {@code manifestPath}.
     *
     * @return the validated snapshot, or the reason the manifest is not acceptable
     */
    static Result validatePath(String manifestPath, String expectedNamespace) {
        if (isBlank(manifestPath)) {
            return Result.rejected("manifestPath must be configured");
        }
        Path path;
        try {
            path = Path.of(manifestPath);
        } catch (InvalidPathException e) {
            return Result.rejected("manifestPath is not a valid file path: " + manifestPath);
        }
        if (!path.isAbsolute()) {
            return Result.rejected("manifestPath must be an absolute path: " + manifestPath);
        }
        if (!Files.exists(path)) {
            return Result.rejected("manifestPath does not exist: " + path);
        }
        if (!Files.isRegularFile(path)) {
            return Result.rejected("manifestPath is not a regular file: " + path);
        }
        if (!Files.isReadable(path)) {
            return Result.rejected("manifestPath is not readable: " + path);
        }

        String content;
        try {
            // strict decoding, so the snapshot that is validated is exactly the snapshot that is sent
            content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
        } catch (CharacterCodingException e) {
            return Result.rejected("manifestPath is not valid UTF-8 text: " + path);
        } catch (IOException e) {
            return Result.rejected("manifestPath could not be read: " + path + " (" + e.getClass().getSimpleName() + ")");
        }
        return validateContent(content, expectedNamespace);
    }

    /**
     * Validates manifest content as a multi-document YAML stream of namespaced Kubernetes objects.
     *
     * <p>Every document must be a mapping whose {@code kind} and {@code apiVersion} form one of the
     * accepted resource types, with a non-blank {@code metadata.name} and an explicit
     * {@code metadata.namespace} equal to the namespace the executor targets. An empty document set, a
     * parse error, a duplicate object identity, a cluster-scoped resource and a {@code List} are all
     * rejected.</p>
     *
     * @return the snapshot to apply, or the reason the manifest is not acceptable
     */
    static Result validateContent(String manifestContent, String expectedNamespace) {
        if (isBlank(manifestContent)) {
            return Result.rejected("manifestContent must not be blank when no manifestPath is configured");
        }
        if (isBlank(expectedNamespace)) {
            return Result.rejected("namespace must be configured before the manifest can be scoped");
        }

        List<Object> documents;
        try {
            documents = StreamSupport.stream(newParser().loadAll(manifestContent).spliterator(), false)
                    .filter(document -> document != null)
                    .toList();
        } catch (RuntimeException e) {
            // This catch is the parse-and-construct boundary and nothing else. YAMLException covers
            // the loader's own failures, but a standard tag can also make the constructor raise a
            // plain runtime exception - !!int over a scalar that is not a number raises
            // NumberFormatException, which quotes the scalar it could not convert - and that must be
            // turned into the same controlled rejection instead of escaping to the caller. Error is
            // deliberately not caught: a VM failure is not an input rejection and must not be
            // reported as one. The document checks below run outside this catch.
            return Result.rejected("manifest is not valid YAML: " + yamlFailure(e));
        }

        if (documents.isEmpty()) {
            return Result.rejected("manifest contains no YAML documents");
        }

        Set<ResourceKind> seen = new HashSet<>();
        for (Object document : documents) {
            if (!(document instanceof Map<?, ?> documentMap)) {
                return Result.rejected("manifest document " + (seen.size() + 1)
                        + " is not a Kubernetes object (expected a YAML mapping, found "
                        + document.getClass().getSimpleName() + ")");
            }

            Optional<String> kind = scalarString(documentMap, "kind");
            if (kind.isEmpty()) {
                return Result.rejected("manifest document " + (seen.size() + 1) + " has no non-empty 'kind'");
            }
            String requiredApiVersion = ALLOWED_TYPES.get(kind.get());
            if (requiredApiVersion == null) {
                return Result.rejected("manifest declares kind '" + kind.get()
                        + "', which this executor does not allow (allowed: " + describeAllowedTypes() + ")");
            }

            Optional<String> apiVersion = scalarString(documentMap, "apiVersion");
            if (apiVersion.isEmpty()) {
                return Result.rejected("kind '" + kind.get() + "' has no non-empty 'apiVersion'; this executor"
                        + " accepts " + kind.get() + " only as '" + requiredApiVersion + "'");
            }
            if (!requiredApiVersion.equals(apiVersion.get())) {
                return Result.rejected("kind '" + kind.get() + "' must declare apiVersion '" + requiredApiVersion
                        + "' but this manifest declares '" + apiVersion.get() + "'");
            }

            Object metadataNode = documentMap.get("metadata");
            if (!(metadataNode instanceof Map<?, ?> metadata)) {
                return Result.rejected(kind.get() + " has no 'metadata' mapping");
            }
            Optional<String> name = scalarString(metadata, "name");
            if (name.isEmpty()) {
                return Result.rejected(kind.get() + " has no non-empty 'metadata.name'");
            }

            Optional<String> namespace = scalarString(metadata, "namespace");
            if (namespace.isEmpty()) {
                return Result.rejected(kind.get() + "/" + name.get()
                        + " has no explicit 'metadata.namespace'; a cluster-scoped resource is not accepted");
            }
            if (!expectedNamespace.equals(namespace.get())) {
                return Result.rejected(kind.get() + "/" + name.get() + " is scoped to namespace '" + namespace.get()
                        + "' but the executor targets '" + expectedNamespace + "'");
            }

            ResourceKind identity = new ResourceKind(kind.get(), namespace.get(), name.get());
            if (!seen.add(identity)) {
                return Result.rejected("manifest declares " + kind.get() + "/" + name.get() + " in namespace '"
                        + namespace.get() + "' more than once");
            }
        }

        return Result.valid(manifestContent);
    }

    /**
     * Lists the identity of every {@code apps/v1} Deployment a content snapshot declares, in document
     * order.
     *
     * <p>Lets the executor confirm that the Deployment it will watch after the apply is one of the
     * objects that was actually applied - and that the rollout target is the same complete resource
     * type the policy accepted, not a Deployment of some other version. The snapshot is already
     * validated, so this is a read; a Deployment whose apiVersion differs was refused before this
     * point.</p>
     *
     * @return the {@code metadata.name} of each accepted Deployment in the snapshot
     */
    static List<String> deploymentNames(String manifestContent) {
        List<String> names = new ArrayList<>();
        for (Object document : StreamSupport.stream(newParser().loadAll(manifestContent).spliterator(), false)
                .filter(document -> document != null)
                .toList()) {
            if (document instanceof Map<?, ?> documentMap && isAcceptedDeployment(documentMap)
                    && documentMap.get("metadata") instanceof Map<?, ?> metadata) {
                scalarString(metadata, "name").ifPresent(names::add);
            }
        }
        return List.copyOf(names);
    }

    private static boolean isAcceptedDeployment(Map<?, ?> documentMap) {
        return DEPLOYMENT_KIND.equals(scalarString(documentMap, "kind").orElse(null))
                && DEPLOYMENT_API_VERSION.equals(scalarString(documentMap, "apiVersion").orElse(null));
    }

    /**
     * The representer is only needed to satisfy the constructor - this parser never dumps. The loader
     * options must be passed to the constructor explicitly: the shorter overloads do not forward them
     * and would silently accept a manifest whose duplicate keys resolve to whichever value came last,
     * which is not the document a reviewer read.
     *
     * <p>The tag inspector is relaxed here on purpose. Its default refusal is a coarse pre-filter
     * whose message quotes the tag it rejected, and the tags that could slip past it are still
     * refused by {@link StrictConstructor}, by type and without text - so tag policy has exactly one
     * place to read. Nothing is weakened by this: {@link SafeConstructor} cannot construct an
     * application type in the first place.</p>
     */
    private static Yaml newParser() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(MAX_ALIASES);
        options.setNestingDepthLimit(MAX_NESTING_DEPTH);
        options.setCodePointLimit(MAX_CODE_POINTS);
        options.setTagInspector(tag -> true);
        DumperOptions dumperOptions = new DumperOptions();
        return new Yaml(new StrictConstructor(options), new Representer(dumperOptions), dumperOptions, options,
                new FlattenedTagResolver());
    }

    /**
     * Resolves plain scalars only, so a Kubernetes manifest never needs a type tag to be parsed: the
     * application-specific tags that would otherwise resolve to Java objects are read as plain text
     * and rejected later by the per-field checks.
     */
    private static final class FlattenedTagResolver extends Resolver {

        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO");
            addImplicitResolver(Tag.INT, INT, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
            addImplicitResolver(Tag.MERGE, MERGE, "<");
            addImplicitResolver(Tag.NULL, NULL, "~nN\u0000");
            addImplicitResolver(Tag.NULL, EMPTY, null);
        }
    }

    /**
     * Refuses every node whose tag is not a standard YAML tag, before the constructor is asked to
     * build it. The decision is made from the tag's identity, so the tag - which is manifest text and
     * may itself carry a sensitive name - is never quoted back.
     */
    private static final class StrictConstructor extends SafeConstructor {

        StrictConstructor(LoaderOptions options) {
            super(options);
        }

        @Override
        protected Construct getConstructor(Node node) {
            if (!STANDARD_TAGS.contains(node.getTag())) {
                throw new ForbiddenTagException(node.getStartMark());
            }
            return super.getConstructor(node);
        }
    }

    /**
     * Raised for a tag this executor will not construct. It carries the position of the offending node
     * and nothing else: the tag value is manifest text too, so it is deliberately not part of the
     * exception.
     */
    private static final class ForbiddenTagException extends YAMLException {

        private final transient Mark mark;

        ForbiddenTagException(Mark mark) {
            super("manifest uses a tag this executor does not accept");
            this.mark = mark;
        }

        Mark mark() {
            return mark;
        }
    }

    /** Reads a scalar field as text; a non-string or blank value is treated as absent. */
    private static Optional<String> scalarString(Map<?, ?> map, String key) {
        return map.get(key) instanceof String value && !value.isBlank() ? Optional.of(value) : Optional.empty();
    }

    private static String describeAllowedTypes() {
        return ALLOWED_TYPES.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .collect(Collectors.joining(", "));
    }

    /**
     * Describes a parse failure by category, exception type and position - never by text the parser
     * built out of the manifest.
     *
     * <p>Neither {@link Throwable#getMessage()} nor {@link MarkedYAMLException#getProblem()} is read
     * here: a duplicate key quotes the key, an unusable tag quotes the tag, and a marked failure
     * quotes the lines around it, all of which may carry a manifest value. The exception's type
     * identifies the failure instead, and the position is taken as line and column numbers only -
     * {@link Mark#toString()} embeds the input it points at and is not used. When no type can be
     * placed, a fixed generic category is returned rather than falling back to the original message.
     */
    private static String yamlFailure(Throwable throwable) {
        Throwable diagnosed = diagnosedCause(throwable);
        String category = diagnosed == null ? null
                : isLoaderLimit(diagnosed) ? STRUCTURE_LIMIT : categoryOf(diagnosed);
        if (category == null) {
            return UNCLASSIFIED;
        }
        return category + " (" + diagnosed.getClass().getSimpleName() + positionOf(diagnosed) + ")";
    }

    /**
     * Picks the exception that explains the failure: a specific diagnostic where there is one, and
     * otherwise the plain {@link YAMLException} that the loader's own alias, nesting-depth and
     * code-point limits raise. Wrappers are followed, because a specific cause explains a failure
     * better than the generic exception thrown around it.
     */
    private static Throwable diagnosedCause(Throwable throwable) {
        Throwable loaderLimit = null;
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (isLoaderLimit(current)) {
                if (loaderLimit == null) {
                    loaderLimit = current;
                }
            } else if (categoryOf(current) != null) {
                return current;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return loaderLimit;
    }

    /** The exact class, not a subclass, is what the loader's own bounds raise. */
    private static boolean isLoaderLimit(Throwable throwable) {
        return throwable.getClass().equals(YAMLException.class);
    }

    /**
     * Maps an exception type to a fixed category, or null when the type carries no category of its
     * own. The order matters: {@link DuplicateKeyException} is a {@link ConstructorException}.
     */
    private static String categoryOf(Throwable throwable) {
        if (throwable instanceof ForbiddenTagException) {
            return FORBIDDEN_TAG;
        }
        if (throwable instanceof DuplicateKeyException) {
            return DUPLICATE_KEY;
        }
        if (throwable instanceof ReaderException) {
            return INVALID_CHARACTER;
        }
        if (throwable instanceof ScannerException || throwable instanceof ParserException
                || throwable instanceof ComposerException || throwable instanceof ConstructorException) {
            return PARSE_FAILURE;
        }
        if (throwable instanceof NumberFormatException) {
            return SCALAR_CONSTRUCTION_FAILURE;
        }
        if (!(throwable instanceof YAMLException)) {
            // A runtime failure a standard constructor raised while building a node - an unusable
            // base64 scalar, a node whose shape cannot carry the tag it was given. It is not a YAML
            // type of its own, so it is filed under the generic parse-failure category; the type is
            // still named in the rejection, which is what makes the failure locatable.
            return PARSE_FAILURE;
        }
        return null;
    }

    /**
     * The location of a failure as line, column or offset numbers only. {@link Mark#toString()} embeds
     * the input it points at, so a mark is never rendered directly.
     */
    private static String positionOf(Throwable throwable) {
        if (throwable instanceof ForbiddenTagException forbidden) {
            return position(forbidden.mark());
        }
        if (throwable instanceof MarkedYAMLException marked) {
            return position(marked.getProblemMark());
        }
        if (throwable instanceof ReaderException reader) {
            return " at offset " + reader.getPosition();
        }
        return "";
    }

    private static String position(Mark mark) {
        return mark == null ? "" : " at line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Object identity inside one content snapshot: the pair a duplicate would collide on. */
    private record ResourceKind(String kind, String namespace, String name) {
    }

    record Result(String content, String rejection) {

        static Result valid(String content) {
            return new Result(content, null);
        }

        static Result rejected(String rejection) {
            return new Result(null, rejection);
        }

        boolean isValid() {
            return rejection == null;
        }
    }
}