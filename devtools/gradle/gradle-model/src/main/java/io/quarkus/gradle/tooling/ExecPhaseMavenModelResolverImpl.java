package io.quarkus.gradle.tooling;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.ModelSource2;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Mirror;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.providers.file.FileWagon;
import org.apache.maven.wagon.providers.http.HttpWagon;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.wagon.WagonProvider;
import org.eclipse.aether.transport.wagon.WagonTransporterFactory;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultAuthenticationSelector;
import org.eclipse.aether.util.repository.DefaultMirrorSelector;
import org.eclipse.aether.util.repository.DefaultProxySelector;

import io.quarkus.maven.dependency.GAV;

public class ExecPhaseMavenModelResolverImpl implements ModelResolver {

    private final RepositorySystem repositorySystem;
    private final DefaultRepositorySystemSession repositorySession;
    private final CopyOnWriteArrayList<RemoteRepository> remoteRepositories;
    private final Map<GAV, Optional<File>> pomCache = new ConcurrentHashMap<>();
    private final Map<GAV, String> resolutionErrors = new ConcurrentHashMap<>();

    public ExecPhaseMavenModelResolverImpl(List<MavenRepoDescriptor> repositoryDescriptors, String localRepositoryPath) {
        validateLocalRepositoryPath(localRepositoryPath);
        this.repositorySystem = newRepositorySystem();
        this.repositorySession = newRepositorySystemSession(repositorySystem, localRepositoryPath);
        Settings settings = loadSettings();
        applySettings(repositorySession, settings);
        this.remoteRepositories = new CopyOnWriteArrayList<>(buildRemoteRepositories(repositoryDescriptors, repositorySession));
    }

    private ExecPhaseMavenModelResolverImpl(RepositorySystem repositorySystem,
            DefaultRepositorySystemSession repositorySession,
            List<RemoteRepository> remoteRepositories,
            Map<GAV, Optional<File>> pomCache,
            Map<GAV, String> resolutionErrors) {
        this.repositorySystem = repositorySystem;
        this.repositorySession = repositorySession;
        this.remoteRepositories = new CopyOnWriteArrayList<>(remoteRepositories);
        this.pomCache.putAll(pomCache);
        this.resolutionErrors.putAll(resolutionErrors);
    }

    @Override
    public ModelSource2 resolveModel(String groupId, String artifactId, String version)
            throws UnresolvableModelException {
        GAV key = new GAV(groupId, artifactId, version);
        File pomFile = pomCache.computeIfAbsent(key, this::resolvePom).orElse(null);
        if (pomFile == null) {
            String details = resolutionErrors.get(key);
            String message = "Could not resolve POM for " + groupId + ":" + artifactId + ":" + version;
            if (details != null && !details.isBlank()) {
                message = message + " [" + details + "]";
            }
            throw new UnresolvableModelException(
                    message,
                    groupId, artifactId, version);
        }

        final File resolvedPom = pomFile;
        return new ModelSource2() {
            @Override
            public InputStream getInputStream() throws IOException {
                return new FileInputStream(resolvedPom);
            }

            @Override
            public String getLocation() {
                return resolvedPom.getAbsolutePath();
            }

            @Override
            public ModelSource2 getRelatedSource(String relPath) {
                return null;
            }

            @Override
            public URI getLocationURI() {
                return resolvedPom.toURI();
            }
        };
    }

    private Optional<File> resolvePom(GAV gav) {
        try {
            var request = new ArtifactRequest()
                    .setArtifact(new DefaultArtifact(gav.getGroupId(), gav.getArtifactId(), "", "pom", gav.getVersion()))
                    .setRepositories(remoteRepositories);
            var result = repositorySystem.resolveArtifact(repositorySession, request);
            resolutionErrors.remove(gav);
            return Optional.ofNullable(result.getArtifact().getFile());
        } catch (ArtifactResolutionException e) {
            resolutionErrors.put(gav, compactResolutionError(e));
            return Optional.empty();
        }
    }

    @Override
    public ModelSource2 resolveModel(Parent parent) throws UnresolvableModelException {
        return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }

    @Override
    public ModelSource2 resolveModel(Dependency dependency) throws UnresolvableModelException {
        return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
    }

    @Override
    public void addRepository(Repository repository) {
        addRepository(repository, false);
    }

    @Override
    public void addRepository(Repository repository, boolean replace) {
        RemoteRepository remoteRepository = toRemoteRepository(repository, repositorySession);
        if (replace) {
            remoteRepositories.removeIf(existing -> existing.getId().equals(remoteRepository.getId()));
            remoteRepositories.add(remoteRepository);
            return;
        }
        boolean present = remoteRepositories.stream()
                .anyMatch(existing -> existing.getId().equals(remoteRepository.getId())
                        && existing.getUrl().equals(remoteRepository.getUrl()));
        if (!present) {
            remoteRepositories.add(remoteRepository);
        }
    }

    @Override
    public ModelResolver newCopy() {
        return this;
    }

    private static String compactResolutionError(ArtifactResolutionException e) {
        if (e.getResult() != null && e.getResult().getExceptions() != null && !e.getResult().getExceptions().isEmpty()) {
            return e.getResult().getExceptions().stream()
                    .map(Throwable::getMessage)
                    .filter(msg -> msg != null && !msg.isBlank())
                    .distinct()
                    .limit(3)
                    .collect(Collectors.joining(" | "));
        }
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return e.getMessage();
    }

    private static RepositorySystem newRepositorySystem() {
        // TODO: stop using deprecated stuff
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, WagonTransporterFactory.class);
        locator.addService(WagonProvider.class, StaticWagonProvider.class);
        return locator.getService(RepositorySystem.class);
    }

    public static final class StaticWagonProvider implements WagonProvider {

        @Override
        public Wagon lookup(String roleHint) {
            if ("http".equals(roleHint) || "https".equals(roleHint)) {
                return new HttpWagon();
            }
            if ("file".equals(roleHint)) {
                return new FileWagon();
            }
            throw new IllegalArgumentException("Unsupported wagon role hint: " + roleHint);
        }

        @Override
        public void release(Wagon wagon) {
            // no-op
        }
    }

    private static DefaultRepositorySystemSession newRepositorySystemSession(RepositorySystem repositorySystem,
            String localRepositoryPath) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        Path localRepoPath = Path.of(localRepositoryPath);
        LocalRepository localRepo = new LocalRepository(localRepoPath.toFile(), "simple");
        session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(session, localRepo));
        System.out.println("[ExecPhaseResolver] LocalRepositoryManager impl: "
                + session.getLocalRepositoryManager().getClass().getName());
        return session;
    }

    private static void validateLocalRepositoryPath(String localRepositoryPath) {
        Objects.requireNonNull(localRepositoryPath, "localRepositoryPath must not be null");
        if (localRepositoryPath.isBlank()) {
            throw new IllegalArgumentException("localRepositoryPath must not be blank");
        }
    }

    private static Settings loadSettings() {
        try {
            var request = new DefaultSettingsBuildingRequest();
            request.setSystemProperties(System.getProperties());
            File userSettings = defaultUserSettings();
            if (userSettings.isFile()) {
                request.setUserSettingsFile(userSettings);
            }
            File globalSettings = defaultGlobalSettings();
            if (globalSettings != null && globalSettings.isFile()) {
                request.setGlobalSettingsFile(globalSettings);
            }
            SettingsBuildingResult result = new DefaultSettingsBuilderFactory().newInstance().build(request);
            return result.getEffectiveSettings();
        } catch (SettingsBuildingException e) {
            return new Settings();
        }
    }

    private static File defaultUserSettings() {
        String override = System.getProperty("maven.settings");
        if (override != null && !override.isBlank()) {
            return new File(override);
        }
        return new File(System.getProperty("user.home"), ".m2/settings.xml");
    }

    private static File defaultGlobalSettings() {
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome == null || mavenHome.isBlank()) {
            mavenHome = System.getProperty("maven.home");
        }
        if (mavenHome == null || mavenHome.isBlank()) {
            return null;
        }
        return new File(mavenHome, "conf/settings.xml");
    }

    private static void applySettings(DefaultRepositorySystemSession session, Settings settings) {
        DefaultMirrorSelector mirrorSelector = new DefaultMirrorSelector();
        for (Mirror mirror : settings.getMirrors()) {
            // TODO: Stop using deprecated deprecated DefaultMirrorSelector.add()
            mirrorSelector.add(mirror.getId(), mirror.getUrl(), mirror.getLayout(), false,
                    mirror.getMirrorOf(), mirror.getMirrorOfLayouts());
        }

        DefaultProxySelector proxySelector = new DefaultProxySelector();
        for (Proxy proxy : settings.getProxies()) {
            if (!proxy.isActive()) {
                continue;
            }
            Authentication auth = new AuthenticationBuilder()
                    .addUsername(proxy.getUsername())
                    .addPassword(proxy.getPassword())
                    .build();
            org.eclipse.aether.repository.Proxy aetherProxy = new org.eclipse.aether.repository.Proxy(
                    proxy.getProtocol(), proxy.getHost(), proxy.getPort(), auth);
            proxySelector.add(aetherProxy, proxy.getNonProxyHosts());
        }

        DefaultAuthenticationSelector authSelector = new DefaultAuthenticationSelector();
        for (Server server : settings.getServers()) {
            AuthenticationBuilder authBuilder = new AuthenticationBuilder();
            if (server.getUsername() != null) {
                authBuilder.addUsername(server.getUsername());
            }
            if (server.getPassword() != null) {
                authBuilder.addPassword(server.getPassword());
            }
            if (server.getPrivateKey() != null) {
                authBuilder.addPrivateKey(server.getPrivateKey(), server.getPassphrase());
            }
            authSelector.add(server.getId(), authBuilder.build());
        }

        session.setMirrorSelector(mirrorSelector);
        session.setProxySelector(proxySelector);
        session.setAuthenticationSelector(authSelector);
    }

    private static List<RemoteRepository> buildRemoteRepositories(
            List<MavenRepoDescriptor> repositoryDescriptors,
            DefaultRepositorySystemSession session) {
        DefaultMirrorSelector mirrorSelector = (DefaultMirrorSelector) session.getMirrorSelector();
        DefaultProxySelector proxySelector = (DefaultProxySelector) session.getProxySelector();
        DefaultAuthenticationSelector authSelector = (DefaultAuthenticationSelector) session.getAuthenticationSelector();

        if (repositoryDescriptors == null) {
            return List.of();
        }
        return repositoryDescriptors.stream()
                .filter(repoDescriptor -> repoDescriptor != null && !repoDescriptor.url().isBlank())
                .map(repoDescriptor -> {
                    RemoteRepository repo = new RemoteRepository.Builder(repoDescriptor.id(), "default", repoDescriptor.url())
                            .setReleasePolicy(new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_DAILY,
                                    RepositoryPolicy.CHECKSUM_POLICY_WARN))
                            .setSnapshotPolicy(new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_DAILY,
                                    RepositoryPolicy.CHECKSUM_POLICY_WARN))
                            .build();
                    if (mirrorSelector != null) {
                        RemoteRepository mirror = mirrorSelector.getMirror(repo);
                        if (mirror != null) {
                            repo = new RemoteRepository.Builder(mirror).build();
                        }
                    }
                    Authentication auth = authSelector == null ? null : authSelector.getAuthentication(repo);
                    org.eclipse.aether.repository.Proxy proxy = proxySelector == null ? null : proxySelector.getProxy(repo);
                    return new RemoteRepository.Builder(repo)
                            .setAuthentication(auth)
                            .setProxy(proxy)
                            .build();
                })
                .toList();
    }

    private static RemoteRepository toRemoteRepository(Repository repository,
            DefaultRepositorySystemSession session) {
        if (repository == null || repository.getUrl() == null || repository.getUrl().isBlank()) {
            throw new IllegalArgumentException("Repository URL must not be null or blank");
        }
        String id = repository.getId();
        if (id == null || id.isBlank()) {
            id = "maven-model-repo-" + Integer.toHexString(repository.getUrl().hashCode());
        }
        String layout = repository.getLayout();
        if (layout == null || layout.isBlank()) {
            layout = "default";
        }
        RemoteRepository remoteRepository = new RemoteRepository.Builder(id, layout, repository.getUrl())
                .setReleasePolicy(toRepositoryPolicy(repository.getReleases()))
                .setSnapshotPolicy(toRepositoryPolicy(repository.getSnapshots()))
                .build();
        return applySessionSelectors(remoteRepository, session);
    }

    private static RepositoryPolicy toRepositoryPolicy(org.apache.maven.model.RepositoryPolicy repositoryPolicy) {
        if (repositoryPolicy == null) {
            return new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_DAILY,
                    RepositoryPolicy.CHECKSUM_POLICY_WARN);
        }
        boolean enabled = repositoryPolicy.getEnabled() == null
                || !"false".equalsIgnoreCase(repositoryPolicy.getEnabled());
        String updatePolicy = repositoryPolicy.getUpdatePolicy() == null || repositoryPolicy.getUpdatePolicy().isBlank()
                ? RepositoryPolicy.UPDATE_POLICY_DAILY
                : repositoryPolicy.getUpdatePolicy();
        String checksumPolicy = repositoryPolicy.getChecksumPolicy() == null || repositoryPolicy.getChecksumPolicy().isBlank()
                ? RepositoryPolicy.CHECKSUM_POLICY_WARN
                : repositoryPolicy.getChecksumPolicy();
        return new RepositoryPolicy(enabled, updatePolicy, checksumPolicy);
    }

    private static RemoteRepository applySessionSelectors(RemoteRepository repository,
            DefaultRepositorySystemSession session) {
        DefaultMirrorSelector mirrorSelector = (DefaultMirrorSelector) session.getMirrorSelector();
        DefaultProxySelector proxySelector = (DefaultProxySelector) session.getProxySelector();
        DefaultAuthenticationSelector authSelector = (DefaultAuthenticationSelector) session.getAuthenticationSelector();

        RemoteRepository selectedRepository = repository;
        if (mirrorSelector != null) {
            RemoteRepository mirror = mirrorSelector.getMirror(selectedRepository);
            if (mirror != null) {
                selectedRepository = new RemoteRepository.Builder(mirror).build();
            }
        }
        Authentication auth = authSelector == null ? null : authSelector.getAuthentication(selectedRepository);
        org.eclipse.aether.repository.Proxy proxy = proxySelector == null ? null : proxySelector.getProxy(selectedRepository);
        return new RemoteRepository.Builder(selectedRepository)
                .setAuthentication(auth)
                .setProxy(proxy)
                .build();
    }
}
