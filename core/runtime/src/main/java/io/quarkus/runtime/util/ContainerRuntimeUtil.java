package io.quarkus.runtime.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import io.smallrye.common.os.OS;
import io.smallrye.config.SmallRyeConfig;

public final class ContainerRuntimeUtil {

    private static final Logger log = Logger.getLogger(ContainerRuntimeUtil.class);
    private static final String CONTAINER_EXECUTABLE = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class)
            .getOptionalValue("quarkus.native.container-runtime", String.class).orElse(null);

    /**
     * Static variable is not used because the class gets loaded by different classloaders at
     * runtime and the container runtime would be detected again and again unnecessarily.
     */
    private static final String CONTAINER_RUNTIME_SYS_PROP = "quarkus-local-container-runtime";

    private ContainerRuntimeUtil() {
    }

    /**
     * @return {@link ContainerRuntime#DOCKER} if it's available, or {@link ContainerRuntime#PODMAN}
     *         if the podman
     *         executable exists in the environment or if the docker executable is an alias to podman,
     *         or {@link ContainerRuntime#UNAVAILABLE} if no container runtime is available and the required arg is false.
     * @throws IllegalStateException if no container runtime was found to build the image
     */
    public static ContainerRuntime detectContainerRuntime() {
        return detectContainerRuntime(true);
    }

    public static ContainerRuntime detectContainerRuntime(boolean required) {
        final ContainerRuntime containerRuntime = loadContainerRuntimeFromSystemProperty();
        if (containerRuntime != null) {
            return containerRuntime;
        } else {
            // Docker version 19.03.14, build 5eb3275d40
            String dockerVersionOutput;
            boolean dockerAvailable;
            // Check if Podman is installed
            // podman version 2.1.1
            String podmanVersionOutput;
            boolean podmanAvailable;
            if (CONTAINER_EXECUTABLE != null) {
                if (CONTAINER_EXECUTABLE.trim().equalsIgnoreCase("docker")) {
                    dockerVersionOutput = getVersionOutputFor(ContainerRuntime.DOCKER);
                    dockerAvailable = dockerVersionOutput.contains("Docker version");
                    if (dockerAvailable) {
                        storeContainerRuntimeInSystemProperty(ContainerRuntime.DOCKER);
                        return ContainerRuntime.DOCKER;
                    }
                }
                if (CONTAINER_EXECUTABLE.trim().equalsIgnoreCase("podman")) {
                    podmanVersionOutput = getVersionOutputFor(ContainerRuntime.PODMAN);
                    podmanAvailable = podmanVersionOutput.startsWith("podman version");
                    if (podmanAvailable) {
                        storeContainerRuntimeInSystemProperty(ContainerRuntime.PODMAN);
                        return ContainerRuntime.PODMAN;
                    }
                }
                log.warn("quarkus.native.container-runtime config property must be set to either podman or docker " +
                        "and the executable must be available. Ignoring it.");
            }
            dockerVersionOutput = getVersionOutputFor(ContainerRuntime.DOCKER);
            dockerAvailable = dockerVersionOutput.contains("Docker version");
            if (dockerAvailable) {
                // Check if "docker" is an alias to "podman"
                if (dockerVersionOutput.startsWith("podman version") ||
                        dockerVersionOutput.startsWith("podman.exe version")) {
                    storeContainerRuntimeInSystemProperty(ContainerRuntime.PODMAN);
                    return ContainerRuntime.PODMAN;
                }
                storeContainerRuntimeInSystemProperty(ContainerRuntime.DOCKER);
                return ContainerRuntime.DOCKER;
            }
            podmanVersionOutput = getVersionOutputFor(ContainerRuntime.PODMAN);
            podmanAvailable = podmanVersionOutput.startsWith("podman version") ||
                    podmanVersionOutput.startsWith("podman.exe version");
            if (podmanAvailable) {
                storeContainerRuntimeInSystemProperty(ContainerRuntime.PODMAN);
                return ContainerRuntime.PODMAN;
            }

            storeContainerRuntimeInSystemProperty(ContainerRuntime.UNAVAILABLE);

            if (required) {
                throw new IllegalStateException("No container runtime was found. "
                        + "Make sure you have either Docker or Podman installed in your environment.");
            }

            return ContainerRuntime.UNAVAILABLE;
        }
    }

    private static ContainerRuntime loadContainerRuntimeFromSystemProperty() {
        final String runtime = System.getProperty(CONTAINER_RUNTIME_SYS_PROP);

        if (runtime == null) {
            return null;
        }

        ContainerRuntime containerRuntime = ContainerRuntime.valueOf(runtime);

        if (containerRuntime == null) {
            log.warnf("System property %s contains an unknown value %s. Ignoring it.",
                    CONTAINER_RUNTIME_SYS_PROP, runtime);
        }

        return containerRuntime;
    }

    private static void storeContainerRuntimeInSystemProperty(ContainerRuntime containerRuntime) {
        System.setProperty(CONTAINER_RUNTIME_SYS_PROP, containerRuntime.name());
    }

    private static String getVersionOutputFor(ContainerRuntime containerRuntime) {
        Process versionProcess = null;
        try {
            final ProcessBuilder pb = new ProcessBuilder(containerRuntime.getExecutableName(), "--version")
                    .redirectErrorStream(true);
            versionProcess = pb.start();
            final int timeoutS = 10;
            if (versionProcess.waitFor(timeoutS, TimeUnit.SECONDS)) {
                return new String(versionProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } else {
                log.debugf("Failure. It took command %s more than %d seconds to execute.", containerRuntime.getExecutableName(),
                        timeoutS);
                return "";
            }
        } catch (IOException | InterruptedException e) {
            // If an exception is thrown in the process, just return an empty String
            log.debugf(e, "Failure to read version output from %s", containerRuntime.getExecutableName());
            return "";
        } finally {
            if (versionProcess != null) {
                versionProcess.destroy();
            }
        }
    }

    private static boolean getRootlessStateFor(ContainerRuntime containerRuntime) {
        Process rootlessProcess = null;
        ProcessBuilder pb = null;
        try {
            pb = new ProcessBuilder(containerRuntime.getExecutableName(), "info").redirectErrorStream(true);
            rootlessProcess = pb.start();
            int exitCode = rootlessProcess.waitFor();
            if (exitCode != 0) {
                log.warnf("Command \"%s\" exited with error code %d. " +
                        "Rootless container runtime detection might not be reliable or the container service is not running at all.",
                        String.join(" ", pb.command()), exitCode);
            }
            try (InputStream inputStream = rootlessProcess.getInputStream();
                    InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
                    BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
                if (exitCode != 0) {
                    log.debugf("Command \"%s\" output: %s", String.join(" ", pb.command()),
                            bufferedReader.lines().collect(Collectors.joining(System.lineSeparator())));
                    return false;
                } else {
                    final Predicate<String> stringPredicate;
                    // Docker includes just "rootless" under SecurityOptions, while podman includes "rootless: <boolean>"
                    if (containerRuntime == ContainerRuntime.DOCKER) {
                        stringPredicate = line -> line.trim().equals("rootless");
                    } else {
                        stringPredicate = line -> line.trim().equals("rootless: true");
                    }
                    return bufferedReader.lines().anyMatch(stringPredicate);
                }
            }
        } catch (IOException | InterruptedException e) {
            // If an exception is thrown in the process, assume we are not running rootless (default docker installation)
            log.debugf(e, "Failure to read info output from %s", String.join(" ", pb.command()));
            return false;
        } finally {
            if (rootlessProcess != null) {
                rootlessProcess.destroy();
            }
        }
    }

    /**
     * Supported Container runtimes
     */
    public enum ContainerRuntime {
        DOCKER("docker" + (OS.current() == OS.WINDOWS ? ".exe" : "")),
        PODMAN("podman" + (OS.current() == OS.WINDOWS ? ".exe" : "")),
        UNAVAILABLE(null);

        private Boolean rootless;

        private String executableName;

        ContainerRuntime(String executableName) {
            this.executableName = executableName;
        }

        public String getExecutableName() {
            if (this == UNAVAILABLE) {
                throw new IllegalStateException("Cannot get an executable name when no container runtime is available");
            }

            return executableName;
        }

        public boolean isRootless() {
            if (rootless != null) {
                return rootless;
            } else {
                if (this != ContainerRuntime.UNAVAILABLE) {
                    rootless = getRootlessStateFor(this);
                } else {
                    throw new IllegalStateException("No container runtime was found. "
                            + "Make sure you have either Docker or Podman installed in your environment.");
                }
            }
            return rootless;
        }

        public static ContainerRuntime of(String value) {
            for (ContainerRuntime containerRuntime : values()) {
                if (containerRuntime.name().equalsIgnoreCase(value)) {
                    return containerRuntime;
                }
            }

            return null;
        }
    }
}
