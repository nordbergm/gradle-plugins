package co.elastic.cloud.gradle.docker.jib;

import co.elastic.cloud.gradle.docker.DockerBuildExtension;
import co.elastic.cloud.gradle.docker.DockerBuildInfo;
import co.elastic.cloud.gradle.docker.DockerBuildResultExtension;
import co.elastic.cloud.gradle.docker.DockerPluginConventions;
import co.elastic.cloud.gradle.util.CacheUtil;
import com.google.cloud.tools.jib.api.*;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.FilePermissions;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@CacheableTask
public class JibBuildTask extends DefaultTask {

    private final Path applicationLayerCache;
    private final Path projectImageArchive;
    private final Path imageBuildInfo;
    private DockerBuildExtension extension;

    public JibBuildTask() {
        super();
        this.applicationLayerCache = DockerPluginConventions.jibApplicationLayerCachePath(getProject());
        this.projectImageArchive = DockerPluginConventions.projectTarImagePath(getProject());
        this.imageBuildInfo = DockerPluginConventions.imageBuildInfo(getProject());
    }

    public void build(ImageReference imageReference) {
        try {
            // Base image is the tar archive stored by dockerBuild of another project if referenced
            // or the baseImage path stored by the dockerJibPull of this project
            JibContainerBuilder jibBuilder = Jib.from(
                    TarImage.at(
                            extension.getFromProject().map(DockerPluginConventions::projectTarImagePath)
                                    .orElseGet(() -> DockerPluginConventions.jibBaseImagePath(getProject()))));

            Optional.ofNullable(getExtension().getMaintainer())
                    .ifPresent(maintainer -> jibBuilder.addLabel("maintainer", maintainer));

            getExtension().forEachCopyLayer(
                    (ordinal, _action) -> {
                        // We can't add directly to / causing a NPE in Jib
                        // We need to walk through the contexts to add them separately => https://github.com/GoogleContainerTools/jib/issues/2195
                        File contextFolder = DockerPluginConventions.contextPath(getProject()).resolve("layer" + ordinal).toFile();
                        if (contextFolder.exists() && contextFolder.isDirectory() && contextFolder.listFiles().length > 0) {
                            Arrays.stream(contextFolder.listFiles()).forEach(file -> {
                                try {
                                    jibBuilder.addFileEntriesLayer(
                                        FileEntriesLayer.builder()
                                                .addEntryRecursive(
                                                    file.toPath(), 
                                                    AbsoluteUnixPath.get("/" + file.getName()), 
                                                    JibBuildTask::getJibFilePermission,
                                                    FileEntriesLayer.DEFAULT_MODIFICATION_TIME_PROVIDER,
                                                    _action.owner.isPresent() ? 
                                                        (sourcePath, destinationPath) -> _action.owner.get() :
                                                        FileEntriesLayer.DEFAULT_OWNERSHIP_PROVIDER
                                                ).build());
                                } catch (IOException e) {
                                    throw new GradleException("Error configuring layer" + ordinal + " for Jib docker image", e);
                                }
                            });
                        } else {
                            throw new GradleException("Error in copy configuration : layer" + ordinal + " is not an existing folder.");
                        }
                    }
            );

            Optional.ofNullable(getExtension().getEntryPoint())
                    .ifPresent(jibBuilder::setEntrypoint);

            Optional.ofNullable(getExtension().getCmd())
                    .ifPresent(jibBuilder::setProgramArguments);

            Optional.ofNullable(getExtension().getLabel())
                    .ifPresent(labels -> labels.forEach(jibBuilder::addLabel));

            Optional.ofNullable(getExtension().getEnv())
                    .ifPresent(envs -> envs.forEach(jibBuilder::addEnvironmentVariable));


            JibContainer jibContainer = jibBuilder.containerize(
                    Containerizer
                            .to(TarImage.at(getProjectImageArchive()).named(imageReference))
                            .setApplicationLayersCache(getApplicationLayerCache()));

            getProject().getExtensions().add(DockerBuildResultExtension.class,
                    "dockerBuildResult",
                    new DockerBuildResultExtension(jibContainer.getImageId().toString(), getProjectImageArchive()));

            try (FileWriter writer = new FileWriter(imageBuildInfo.toFile())) {
                writer.write(new Gson().toJson(new DockerBuildInfo()
                        .setTag(imageReference.toString())
                        .setBuilder(DockerBuildInfo.Builder.JIB)
                        .setImageId(jibContainer.getImageId().getHash())));

            } catch (IOException e) {
                throw new GradleException("Error writing image info file", e);
            }
        } catch (InterruptedException | RegistryException | IOException | CacheDirectoryCreationException | ExecutionException e) {
            throw new GradleException("Error running Jib docker image build", e);
        }
    }

    @TaskAction
    public void cleanAndBuild() {
        // Clean application cache before build to avoid useless application layer in the cache
        getProject().delete(getApplicationLayerCache());

        ImageReference imageReference = DockerPluginConventions.imageReference(getProject());

        build(imageReference);
    }

    @OutputDirectory
    public Path getApplicationLayerCache() {
        return applicationLayerCache;
    }

    @Internal
    public Path getProjectImageArchive() {
        return projectImageArchive;
    }

    @OutputFile
    public Path getImageBuildInfo() {
        return imageBuildInfo;
    }

    @Nested
    public DockerBuildExtension getExtension() {
        return extension;
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public Path getContextPath() {
        return DockerPluginConventions.contextPath(getProject());
    }

    public void setExtension(DockerBuildExtension extension) {
        this.extension = extension;
    }

    private static FilePermissions getJibFilePermission(Path sourcePath, AbsoluteUnixPath target) {
        try {
            return FilePermissions.fromPosixFilePermissions(Files.getPosixFilePermissions(sourcePath));
        } catch (UnsupportedOperationException e) {
            Set<PosixFilePermission> permissions = new HashSet<>();
            File sourceFile = sourcePath.toFile();
            if (sourceFile.canRead()) {
                permissions.add(PosixFilePermission.OWNER_READ);
                permissions.add(PosixFilePermission.GROUP_READ);
                permissions.add(PosixFilePermission.OTHERS_READ);
            }
            if (sourceFile.canWrite()) {
                permissions.add(PosixFilePermission.OWNER_WRITE);
                permissions.add(PosixFilePermission.GROUP_WRITE);
            }
            if (sourceFile.canExecute() || sourceFile.isDirectory()) {
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
                permissions.add(PosixFilePermission.GROUP_EXECUTE);
                permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            }
            return FilePermissions.fromPosixFilePermissions(permissions);
        } catch (IOException | SecurityException e) {
            throw new GradleException("Error while detecting permissions for " + sourcePath.toString(), e);
        }
    }
}
