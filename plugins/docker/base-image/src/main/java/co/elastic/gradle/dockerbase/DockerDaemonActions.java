/*
 *
 *  * ELASTICSEARCH CONFIDENTIAL
 *  * __________________
 *  *
 *  *  Copyright Elasticsearch B.V. All rights reserved.
 *  *
 *  * NOTICE:  All information contained herein is, and remains
 *  * the property of Elasticsearch B.V. and its suppliers, if any.
 *  * The intellectual and technical concepts contained herein
 *  * are proprietary to Elasticsearch B.V. and its suppliers and
 *  * may be covered by U.S. and Foreign Patents, patents in
 *  * process, and are protected by trade secret or copyright
 *  * law.  Dissemination of this information or reproduction of
 *  * this material is strictly forbidden unless prior written
 *  * permission is obtained from Elasticsearch B.V.
 *
 */

package co.elastic.gradle.dockerbase;

import co.elastic.gradle.utils.RegularFileUtils;
import co.elastic.gradle.utils.docker.DockerUtils;
import co.elastic.gradle.utils.docker.instruction.*;
import org.gradle.api.GradleException;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class DockerDaemonActions {

    private final DockerUtils dockerUtils;
    private final ImageBuildable buildable;
    private final Path workingDir;
    private final UUID uuid;
    private String user;

    @Inject
    public DockerDaemonActions(ImageBuildable buildable) {
        this.dockerUtils = new DockerUtils(getExecOperations());
        this.buildable = buildable;
        this.workingDir = RegularFileUtils.toPath(buildable.getWorkingDirectory());
        uuid = UUID.randomUUID();
    }

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Inject
    protected abstract FileSystemOperations getFilesystemOperations();


    public void checkVersion() {
        ByteArrayOutputStream commandOutput = new ByteArrayOutputStream();
        dockerUtils.exec(spec -> {
            spec.setStandardOutput(commandOutput);
            spec.setEnvironment(Collections.emptyMap());
            spec.commandLine("docker", "version", "--format='{{.Server.Version}}'");
        });
        String dockerVersion = commandOutput.toString(StandardCharsets.UTF_8)
                .trim()
                .replaceAll("'", "");
        int dockerMajorVersion = Integer.parseInt(dockerVersion.split("\\.")[0]);
        if (dockerMajorVersion < 19) {
            throw new IllegalStateException("Docker daemon version must be 19 and above. Currently " + dockerVersion);
        }
    }

    public String dockerFileFromInstructions() {
        return "##########################################################\n" +
               "#                                                        #\n" +
               "#                Auto generated Dockerfile               #\n" +
               "#                                                        #\n" +
               "##########################################################\n" +
               "# syntax = docker/dockerfile:1.3\n" +
               "# Internal UUID: " + uuid + "\n" +
               "# Building " + buildable + "\n\n" +
               buildable.getActualInstructions().stream()
                       .flatMap(this::convertInstallToRun)
                       .map(this::instructionAsDockerFileInstruction)
                       .collect(Collectors.joining("\n"));
    }

    public static Run wrapInstallCommand(ImageBuildable buildable, String command) {
        final OSDistribution distribution = buildable.getOSDistribution().get();
        final boolean requiresCleanLayers = buildable.getIsolateFromExternalRepos().get();
        List<String> installCommand;
        switch (distribution) {
            case UBUNTU:
            case DEBIAN:
                installCommand = Stream.of(
                        Stream.of(
                                "cp /var/packages-from-gradle/__META__Packages* /var/packages-from-gradle/Packages.gz"
                        ).filter(s -> requiresCleanLayers),
                        Stream.of(
                                "rm -f /etc/apt/apt.conf.d/docker-clean",
                                "echo 'Binary::apt::APT::Keep-Downloaded-Packages \"true\";' > /etc/apt/apt.conf.d/docker-dirty"
                        ).filter(s -> !requiresCleanLayers),
                        Stream.of("apt-get update", command),
                        Stream.of(
                                "apt-get clean",
                                "rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* /etc/apt/apt.conf.d/90sslConfig"
                        ).filter(s -> requiresCleanLayers)
                ).flatMap(s -> s).collect(Collectors.toList());
                break;
            case CENTOS:
                installCommand = Stream.of(
                        Stream.of(
                                "cd /var/packages-from-gradle/",
                                "tar -xf __META__repodata*"
                        ).filter(s -> requiresCleanLayers),
                        Stream.of(command),
                        Stream.of(
                                "yum clean all",
                                "rm -rf /var/cache/yum /tmp/* /var/tmp/*"
                        ).filter(s -> requiresCleanLayers)
                ).flatMap(
                        Function.identity()
                ).collect(Collectors.toList());
                break;
            default:
                throw new IllegalArgumentException();
        }
        return new Run(
                installCommand
        );
    }

    private Stream<? extends ContainerImageBuildInstruction> convertInstallToRun(ContainerImageBuildInstruction instruction) {
        if (instruction instanceof Install) {
            Install install = (Install) instruction;
            final String packagesToInstall = install.getPackages().stream()
                    .filter(p -> !p.contains("__META__"))
                    .collect(Collectors.joining(" "));
            String installCommand;
            switch (buildable.getOSDistribution().get()) {
                case UBUNTU:
                case DEBIAN:
                    installCommand = "apt-get install -y " + packagesToInstall;
                    break;
                case CENTOS:
                    installCommand = "yum install --setopt=skip_missing_names_on_install=False -y " +
                            packagesToInstall;
                    break;
                default:
                    throw new IllegalArgumentException();
            }
            return Stream.of(
                    // Install instructions need to be run with root
                    new SetUser("root"),
                    wrapInstallCommand(
                            buildable,
                            installCommand
                    ),
                    // And we restore the user after that
                    new SetUser(user)
            );
        }
        return Stream.of(instruction);
    }

    public String instructionAsDockerFileInstruction(ContainerImageBuildInstruction instruction) {
        if (instruction instanceof From) {
            From from = (From) instruction;
            return "FROM " + from.getReference().get();
        } else if (instruction instanceof FromLocalImageBuild) {
            final FromLocalImageBuild fromLocalImageBuild = (FromLocalImageBuild) instruction;
            return "# " + fromLocalImageBuild.getOtherProjectPath() + "\n" +
                   "FROM " + fromLocalImageBuild.getReference().get();
        } else if (instruction instanceof Copy) {
            Copy copySpec = (Copy) instruction;
            return "COPY " + Optional.ofNullable(copySpec.getOwner()).map(s -> "--chown=" + s + " ").orElse("") +
                   workingDir.relativize(getContextDir().resolve(copySpec.getLayer())) + " /";
        } else if (instruction instanceof Run) {
            Run run = (Run) instruction;
            String mountOptions = getBindMounts().entrySet().stream()
                    .map(entry -> {
                        // Key is something like: target=/mnt, additional options are possible
                        return "--mount=type=bind," + entry.getKey() +
                               ",source=" + workingDir.relativize(entry.getValue());
                    }).collect(Collectors.joining(" "));
            return "RUN " + mountOptions + "\\\n " +
                   String.join(" && \\ \n\t", run.getCommands());
        } else if (instruction instanceof RepoConfigRun) {
            RepoConfigRun repoConfig = (RepoConfigRun) instruction;
            if (buildable.getIsolateFromExternalRepos().get()) {
                return "";
            } else {
                return "RUN " + String.join(" && \\ \n\t", repoConfig.getCommands());
            }
        }
        else if (instruction instanceof CreateUser) {
            CreateUser createUser = (CreateUser) instruction;
            // Specific case for Alpine and Busybox
            return String.format(
                    "RUN if ! command -v busybox &> /dev/null; then \\ \n" +
                            "       groupadd -g %4$s %3$s ; \\ \n" +
                            "       useradd -r -s /bin/false -g %4$s --uid %2$s %1$s ; \\ \n" +
                            "   else \\ \n" + // Specific case for Alpine and Busybox
                            "       addgroup --gid %4$s %3$s ; \\ \n" +
                            "       adduser -S -s /bin/false --ingroup %3$s -H -D -u %2$s %1$s ; \\ \n" +
                            "   fi",
                    createUser.getUsername(),
                    createUser.getUserId(),
                    createUser.getGroup(),
                    createUser.getGroupId()
            );
        } else if (instruction instanceof SetUser) {
            user = ((SetUser) instruction).getUsername();
            return "USER " + user;
        } else if (instruction instanceof Env) {
            return "ENV " + ((Env) instruction).getKey() + "=" + ((Env) instruction).getValue();
        } else if (instruction instanceof HealthCheck) {
            HealthCheck healthcheck = (HealthCheck) instruction;
            return "HEALTHCHECK " + Optional.ofNullable(healthcheck.getInterval()).map(interval -> "--interval=" + interval + " ").orElse("") +
                   Optional.ofNullable(healthcheck.getTimeout()).map(timeout -> "--timeout=" + timeout + " ").orElse("") +
                   Optional.ofNullable(healthcheck.getStartPeriod()).map(startPeriod -> "--start-period=" + startPeriod + " ").orElse("") +
                   Optional.ofNullable(healthcheck.getRetries()).map(retries -> "--retries=" + retries + " ").orElse("") +
                   "CMD " + healthcheck.getCmd();
        } else {
            throw new GradleException("Docker instruction " + instruction + " is not supported for Docker daemon build");
        }
    }

    public Map<String, Path> getBindMounts() {
        final HashMap<String, Path> result = new HashMap<>();

        result.put(
                "readonly,target=" + buildable.getDockerEphemeralMount().get(), getDockerEphemeralDir()
        );
        if (buildable.getIsolateFromExternalRepos().get()) {
            String destPath;
            switch (buildable.getOSDistribution().get()) {
                case DEBIAN:
                case UBUNTU:
                    destPath = "/etc/apt/sources.list";
                    break;
                case CENTOS:
                    destPath = "/etc/yum.repos.d";
                    break;
                default:
                    throw new IllegalArgumentException();
            }
            Path sourcePath;
            switch (buildable.getOSDistribution().get()) {
                case DEBIAN:
                case UBUNTU:
                    sourcePath = getRepositoryEphemeralDir().resolve("sources.list");
                    break;
                case CENTOS:
                    sourcePath = getRepositoryEphemeralDir();
                    break;
                default:
                    throw new IllegalArgumentException();
            }
            result.put("readonly,target=" + destPath, sourcePath);
            result.put("readwrite,target=/var/packages-from-gradle", getOSPackagesDir());
        }
        return result;
    }

    public Path getWorkingDir() {
        return workingDir;
    }

    public Path getDockerEphemeralDir() {
        return workingDir.resolve("ephemeral/docker");
    }

    public Path getOSPackagesDir() {
        return workingDir.resolve("ephemeral/packages");
    }

    public Path getRepositoryEphemeralDir() {
        return workingDir.resolve("ephemeral/repos");
    }

    public Path getContextDir() {
        return workingDir.resolve("context");
    }

    private void generateEphemeralRepositories() throws IOException {
        final Path listsEphemeralDir = getRepositoryEphemeralDir();

        Files.createDirectories(listsEphemeralDir);

        final URL url = new URL("file:///var/packages-from-gradle");
        final String name = "gradle-configuration";
        try {
            String repoFileName;
            switch (buildable.getOSDistribution().get()) {
                case CENTOS:
                    repoFileName = name + ".repo";
                    break;
                case DEBIAN:
                case UBUNTU:
                    repoFileName = "sources.list";
                    break;
                default:
                    throw new IllegalArgumentException();
            }
            Iterable<? extends CharSequence> repoLines;
            switch (buildable.getOSDistribution().get()) {
                case CENTOS:
                    repoLines = List.of(
                            "[" + name + "]",
                            "name=" + name,
                            "baseurl=" + url,
                            "enabled=1",
                            "gpgcheck=0"
                    );
                    break;
                case DEBIAN:
                case UBUNTU:
                    repoLines = List.of(
                            "deb [trusted=yes] " + url + " /"
                    );
                    break;
                default:
                    throw new IllegalArgumentException();
            }
            Files.write(
                    listsEphemeralDir.resolve(repoFileName),
                    repoLines
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }

    public UUID build() throws IOException {
        checkVersion();
        Files.createDirectories(workingDir);
        synchronizeFiles();
        generateEphemeralRepositories();

        {
            final String baseImage = buildable.getActualInstructions().stream()
                    .filter(each -> each instanceof FromImageReference)
                    .map(each -> ((FromImageReference) each).getReference().get())
                    .findFirst()
                    .orElseThrow(() -> new GradleException("A base image is not configured"));
            final ByteArrayOutputStream whoAmIOut = new ByteArrayOutputStream();
            dockerUtils.exec(execSpec -> {
                execSpec.setStandardOutput(whoAmIOut);
                execSpec.commandLine("docker", "run", "--rm", "--entrypoint", "/bin/sh", baseImage, "-c", "'whoami'");
            });
            user = whoAmIOut.toString().trim();
        }

        Path dockerFile = workingDir.resolve("Dockerfile");
        Files.writeString(
                dockerFile,
                dockerFileFromInstructions()
        );

        Files.writeString(
                workingDir.resolve(".dockerignore"),
                "**\n" + Stream.concat(
                                Stream.of(
                                        workingDir.relativize(getContextDir())
                                ),
                                getBindMounts().values().stream()
                                        .map(each -> workingDir.relativize(each).toString())
                        )
                        .map(each -> "!" + each)
                        .collect(Collectors.joining("\n"))
        );

        // We build with --no-cache to make things more straight forward, since we already cache images using Gradle's build cache
        int imageBuild = dockerUtils.exec(spec -> {
            spec.setWorkingDir(dockerFile.getParent().toFile());
            if (System.getProperty("co.elastic.unsafe.use-docker-cache", "false").equals("true")) {
                // This is usefull for development when we don't care about image corectness, but otherwhise dagerous,
                //   e.g. dockerEphemeral content in run commands could lead to incorrect results
                spec.commandLine("docker", "image", "build", "--platform", "linux/" + buildable.getArchitecture().get().dockerName(),
                        "--quiet=false",
                        "--progress=plain",
                        "--iidfile=" + buildable.getImageIdFile().get().getAsFile(), ".", "-t",
                        uuid
                );
            } else {
                spec.commandLine("docker", "image", "build", "--platform", "linux/" + buildable.getArchitecture().get().dockerName(),
                        "--quiet=false",
                        "--no-cache",
                        "--progress=plain",
                        "--iidfile=" + buildable.getImageIdFile().get().getAsFile(), ".", "-t",
                        uuid
                );
            }
            spec.setIgnoreExitValue(true);
        }).getExitValue();
        if (imageBuild != 0) {
            throw new GradleException("Failed to build docker image, see the docker build log in the task output");
        }

        return uuid;
    }

    private void synchronizeFiles() throws IOException {
        Files.createDirectories(getContextDir());
        getFilesystemOperations().sync(spec -> {
                    spec.into(getContextDir());
                    spec.with(buildable.getRootCopySpec());
                }
        );

        final Path dockerEphemeralDir = getDockerEphemeralDir();
        Files.createDirectories(dockerEphemeralDir);
        getFilesystemOperations().sync(copySpec -> {
            copySpec.from(buildable.getDockerEphemeralConfiguration().get());
            copySpec.into(dockerEphemeralDir);
        });

        final Path osPackagesDir = getOSPackagesDir();
        Files.createDirectories(osPackagesDir);
        getFilesystemOperations().sync(copySpec -> {
            copySpec.from(buildable.getOSPackagesConfiguration().get());
            copySpec.into(osPackagesDir);
        });
    }

}
