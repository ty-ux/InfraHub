package com.infrahub.release.service.executor;

/**
 * SPI interface for release executors.
 * Implementations handle deployment to different targets (SSH, Docker, Kubernetes).
 */
public interface ReleaseExecutor {

    /**
     * Returns the unique name of this executor type.
     * Used for executor selection and configuration.
     *
     * @return executor type name (e.g., "SSH", "DOCKER", "KUBERNETES")
     */
    String getName();

    /**
     * Validates the executor configuration.
     * Called before execution to ensure all required fields are present.
     *
     * @param context execution context containing order, service, env, version, artifact, config
     * @return validation result with success flag and error message if invalid
     */
    ValidationResult validate(ReleaseExecutionContext context);

    /**
     * Executes the release deployment.
     *
     * @param context execution context containing all deployment information
     * @return execution result with success flag, message, and logs
     */
    ReleaseExecutionResult execute(ReleaseExecutionContext context);

    /**
     * Validation result returned by executor validate method.
     */
    record ValidationResult(boolean isValid, String errorMessage) {
        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }
    }
}
