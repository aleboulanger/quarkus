package io.quarkus.cli.plugin;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;

import io.quarkus.cli.build.BaseBuildCommand;
import io.quarkus.cli.common.TargetQuarkusPlatformGroup;
import io.quarkus.cli.utils.Registries;
import io.quarkus.devtools.project.QuarkusProject;
import picocli.CommandLine;

public class CliPluginsBase extends BaseBuildCommand {

    @CommandLine.Option(names = { "-t",
            "--type" }, paramLabel = "PLUGIN_TYPE", order = 3, description = "Only list plugins from the specified type.")
    Optional<PluginType> type = Optional.empty();

    @CommandLine.ArgGroup(order = 2, heading = "%nQuarkus version (absolute):%n")
    TargetQuarkusPlatformGroup targetQuarkusVersion = new TargetQuarkusPlatformGroup();

    @CommandLine.ArgGroup(order = 3, heading = "%nCatalog:%n")
    PluginCatalogOptions catalogOptions = new PluginCatalogOptions();

    public Optional<QuarkusProject> quarkusProject() {
        try {
            Path projectRoot = projectRoot();
            if (projectRoot == null || !projectRoot.toFile().exists()) {
                return Optional.empty();
            }
            return Optional.of(
                    registryClient.createQuarkusProject(projectRoot, targetQuarkusVersion, getRunner().getBuildTool(), output));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public PluginManager pluginManager() {
        Set<String> registries = Registries.getRegistries(registryClient, "quarkusio");
        PluginManagerSettings settings = PluginManagerSettings.defaultSettings()
                .withCatalogs(registries)
                .withInteractivetMode(!output.isCliTest());

        return new PluginManager(settings, output,
                catalogOptions.userDirectory.or(() -> Optional.ofNullable(Paths.get(System.getProperty("user.home")))),
                catalogOptions.user ? Optional.empty() : Optional.ofNullable(projectRoot()),
                catalogOptions.user ? Optional.empty() : quarkusProject(),
                p -> type.map(t -> t == p.getType()).orElse(true));
    }
}
