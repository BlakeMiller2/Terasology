// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.core.module;

import com.google.common.collect.Sets;
import org.reflections.Reflections;
import org.reflections.serializers.Serializer;
import org.reflections.serializers.XmlSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.assets.Asset;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.SystemConfig;
import org.terasology.engine.core.TerasologyConstants;
import org.terasology.engine.core.paths.PathManager;
import org.terasology.engine.utilities.Jvm;
import org.terasology.input.device.KeyboardDevice;
import org.terasology.module.ClasspathModule;
import org.terasology.module.DependencyInfo;
import org.terasology.module.Module;
import org.terasology.module.ModuleEnvironment;
import org.terasology.module.ModuleLoader;
import org.terasology.module.ModuleMetadata;
import org.terasology.module.ModuleMetadataJsonAdapter;
import org.terasology.module.ModulePathScanner;
import org.terasology.module.ModuleRegistry;
import org.terasology.module.TableModuleRegistry;
import org.terasology.module.sandbox.APIScanner;
import org.terasology.module.sandbox.ModuleSecurityManager;
import org.terasology.module.sandbox.ModuleSecurityPolicy;
import org.terasology.module.sandbox.PermissionProvider;
import org.terasology.module.sandbox.PermissionProviderFactory;
import org.terasology.module.sandbox.StandardPermissionProviderFactory;
import org.terasology.module.sandbox.WarnOnlyProviderFactory;
import org.terasology.nui.UIWidget;
import org.terasology.reflection.ModuleTypeRegistry;
import org.terasology.reflection.TypeRegistry;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.ReflectPermission;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Policy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Verify.verifyNotNull;

public class ModuleManager {
    /** Set this environment variable to "true" to load all modules in the classpath by default. */
    public final static String LOAD_CLASSPATH_MODULES_ENV = "TERASOLOGY_LOAD_CLASSPATH_MODULES";
    public final static String LOAD_CLASSPATH_MODULES_PROPERTY = "org.terasology.load_classpath_modules";

    private static final Logger logger = LoggerFactory.getLogger(ModuleManager.class);
    private final StandardPermissionProviderFactory permissionProviderFactory = new StandardPermissionProviderFactory();
    private final PermissionProviderFactory wrappingPermissionProviderFactory = new WarnOnlyProviderFactory(permissionProviderFactory);

    private final ModuleRegistry registry;
    private ModuleEnvironment environment;
    private final ModuleMetadataJsonAdapter metadataReader;
    private final ModuleInstallManager installManager;
    private Module engineModule;

    public ModuleManager(String masterServerAddress) {
        this(masterServerAddress, Collections.emptyList());
    }

    public ModuleManager(String masterServerAddress, List<Class<?>> classesOnClasspathsToAddToEngine) {
        this(masterServerAddress, classesOnClasspathsToAddToEngine, null);
    }

    public ModuleManager(String masterServerAddress, List<Class<?>> classesOnClasspathsToAddToEngine, Boolean loadModulesFromClasspath) {
        PathManager pathManager = PathManager.getInstance();  // get early so if it needs to initialize, it does it now

        metadataReader = newMetadataReader();

        engineModule = loadEngineModule(classesOnClasspathsToAddToEngine);

        registry = new TableModuleRegistry();
        registry.add(engineModule);

        if (doLoadModulesFromClasspath(loadModulesFromClasspath)) {
            loadModulesFromClassPath();
        } else {
            logger.info("Not loading classpath modules.");
        }

        loadModulesFromApplicationPath(pathManager);

        ensureModulesDependOnEngine(engineModule);

        setupSandbox();
        loadEnvironment(Sets.newHashSet(engineModule), true);
        installManager = new ModuleInstallManager(this, masterServerAddress);
    }

    /**
     * I wondered why this is important, and found MovingBlocks/Terasology#1450.
     * It's not a worry that the engine module wouldn't be loaded without it. 
     * It's about ordering: some things run in an order derived from the dependency 
     * tree, and we want to make sure engine is at the root of it.
     */   
    private void ensureModulesDependOnEngine(Module engineModule) {
        DependencyInfo engineDep = new DependencyInfo();
        engineDep.setId(engineModule.getId());
        engineDep.setMinVersion(engineModule.getVersion());
        engineDep.setMaxVersion(engineModule.getVersion().getNextPatchVersion());

        registry.stream().filter(mod -> mod != engineModule).forEach(mod -> mod.getMetadata().getDependencies().add(engineDep));
    }

    private void loadModulesFromApplicationPath(PathManager pathManager) {
        ModulePathScanner scanner = new ModulePathScanner(new ModuleLoader(metadataReader));
        scanner.getModuleLoader().setModuleInfoPath(TerasologyConstants.MODULE_INFO_FILENAME);
        scanner.scan(registry, pathManager.getModulePaths());
    }

    private Module loadEngineModule(List<Class<?>> classesOnClasspathsToAddToEngine) {
        Module engineModule;
        try (Reader reader = new InputStreamReader(getClass().getResourceAsStream("/engine-module.txt"), TerasologyConstants.CHARSET)) {
            ModuleMetadata metadata = metadataReader.read(reader);
            List<Class<?>> additionalClassesList = new ArrayList<>(classesOnClasspathsToAddToEngine.size() + 2);
            additionalClassesList.add(Module.class); // provide access to gestalt-module.jar
            additionalClassesList.add(Asset.class); // provide access to gestalt-asset-core.jar
            additionalClassesList.add(UIWidget.class); // provide access to nui.jar
            additionalClassesList.add(TypeRegistry.class); // provide access to nui-reflect.jar
            additionalClassesList.add(KeyboardDevice.class); // provide access to nui-input.jar
            additionalClassesList.add(ModuleTypeRegistry.class); // provide access to nui-gestalt7.jar
            additionalClassesList.addAll(classesOnClasspathsToAddToEngine); // provide access to any facade-provided classes
            Class<?>[] additionalClassesArray = new Class[additionalClassesList.size()];
            additionalClassesArray = additionalClassesList.toArray(additionalClassesArray);
            engineModule = ClasspathModule.create(metadata, getClass(), additionalClassesArray);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read engine metadata", e);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to convert engine library location to path", e);
        }

        enrichReflectionsWithSubsystems(engineModule);

        return engineModule;
    }

    public ModuleManager(Config config) {
        this(config, Collections.emptyList());
    }

    public ModuleManager(Config config, List<Class<?>> classesOnClasspathsToAddToEngine) {
        this(config.getNetwork().getMasterServer(), classesOnClasspathsToAddToEngine);
    }

    private ModuleMetadataJsonAdapter newMetadataReader() {
        final ModuleMetadataJsonAdapter metadataJsonAdapter = new ModuleMetadataJsonAdapter();
        for (ModuleExtension ext : StandardModuleExtension.values()) {
            metadataJsonAdapter.registerExtension(ext.getKey(), ext.getValueType());
        }
        for (ModuleExtension ext : ExtraDataModuleExtension.values()) {
            metadataJsonAdapter.registerExtension(ext.getKey(), ext.getValueType());
        }
        return metadataJsonAdapter;
    }

    boolean doLoadModulesFromClasspath(Boolean loadModulesFromClasspath) {
        boolean env = Boolean.parseBoolean(System.getenv(LOAD_CLASSPATH_MODULES_ENV));
        boolean prop = Boolean.getBoolean(LOAD_CLASSPATH_MODULES_PROPERTY);
        boolean useClasspath;
        if (loadModulesFromClasspath != null) {
            useClasspath = loadModulesFromClasspath;
        } else {
            useClasspath = env || prop;
        }
        logger.debug("Load modules from classpath? {} [arg: {}, env: {}, property: {}]",
                useClasspath,
                loadModulesFromClasspath,
                System.getenv(LOAD_CLASSPATH_MODULES_ENV),
                System.getProperty(LOAD_CLASSPATH_MODULES_PROPERTY));
        return useClasspath;
    }

    /**
     * Overrides modules in modules/ with those specified via -classpath in the JVM
     */
    void loadModulesFromClassPath() {
        logger.debug("loadModulesFromClassPath with classpath:");
        Jvm.logClasspath(logger);

        ModuleLoader loader = new ClasspathSupportingModuleLoader(metadataReader, true, true);
        loader.setModuleInfoPath(TerasologyConstants.MODULE_INFO_FILENAME);

        List<Path> classPaths = Arrays.stream(
                System.getProperty("java.class.path").split(System.getProperty("path.separator", ":"))
        ).map(Paths::get).collect(Collectors.toList());

        // I thought I'd make the ClasspathSupporting stuff in the shape of a ModuleLoader
        // so I could use it with the existing ModulePathScanner, but no. The inputs to that
        // are the _parent directories_ of what we have.
        for (Path path : classPaths) {
            attemptToLoadAsClasspathModule(loader, path);
        }
    }

    /**
     * Attempt to load a module from the given path.
     *
     * Assumes that the path <em>may or may not</em> contain a Terasology module. Will add the module to
     * {@link #registry} if successful.
     *
     * For troubleshooting failure cases, check for log messages from this package and from {@link ModuleLoader}.
     *
     * @param loader the module loader to use
     * @param path the path to the jar or directory
     */
    public void attemptToLoadAsClasspathModule(ModuleLoader loader, Path path) {
        // The conditions here mirror those of org.terasology.module.ModulePathScanner.loadModule
        Module module;
        try {
            module = loader.load(path);
        } catch (IOException e) {
            logger.warn("Failed to load module from classpath at {}", path, e);
            return;
        }

        if (module == null) {
            return;
        }

        boolean isNew = registry.add(module);
        if (isNew) {
            logger.info("Added new module: {} from {} on classpath", module, path.getFileName());
        } else {
            logger.warn("Skipped duplicate module: {}-{} from {} on classpath",
                    module.getId(), module.getVersion(), path.getFileName());
        }
    }

    /**
     * Load a module from the location containing a class.
     *
     * Assumes that the location <em>should</em> contain a Terasology module. Will add the module to
     * {@link #registry} and return the resulting module if successful.
     *
     * May throw IOException or RuntimeExceptions on failure.
     *
     * For troubleshooting failure cases, check for log messages from this package and from {@link ModuleLoader}.
     *
     * @param clazz a class in the module, see {@link ClasspathModule#create}
     */
    public Module loadClasspathModule(Class<?> clazz) throws IOException {
        ClasspathSupportingModuleLoader loader = new ClasspathSupportingModuleLoader(metadataReader, true, false);
        loader.setModuleInfoPath(TerasologyConstants.MODULE_INFO_FILENAME);

        Module module = verifyNotNull(loader.load(clazz), "Failed to load module from %s", clazz);
        boolean isNew = registry.add(module);
        if (isNew) {
            logger.info("Added new module: {} from {} on classpath", module, module.getClasspaths());
        } else {
            logger.warn("Skipped duplicate module: {}-{} from {} on classpath",
                    module.getId(), module.getVersion(), module.getClasspaths());
        }
        return module;
    }

    private void setupSandbox() {
        ExternalApiWhitelist.CLASSES.stream().forEach(clazz ->
                permissionProviderFactory.getBasePermissionSet().addAPIClass(clazz));
        ExternalApiWhitelist.PACKAGES.stream().forEach(packagee ->
                permissionProviderFactory.getBasePermissionSet().addAPIPackage(packagee));

        APIScanner apiScanner = new APIScannerTolerantOfAssetOnlyModules(permissionProviderFactory);
        apiScanner.scan(registry);

        permissionProviderFactory.getBasePermissionSet().grantPermission("com.google.gson", ReflectPermission.class);
        permissionProviderFactory.getBasePermissionSet().grantPermission("com.google.gson.internal", ReflectPermission.class);

        Policy.setPolicy(new ModuleSecurityPolicy());
        System.setSecurityManager(new ModuleSecurityManager());
    }

    public ModuleRegistry getRegistry() {
        return registry;
    }

    public ModuleInstallManager getInstallManager() {
        return installManager;
    }

    public ModuleEnvironment getEnvironment() {
        return environment;
    }

    public ModuleEnvironment loadEnvironment(Set<Module> modules, boolean asPrimary) {
        Set<Module> finalModules = Sets.newLinkedHashSet(modules);
        finalModules.add(engineModule);
        ModuleEnvironment newEnvironment;
        boolean permissiveSecurityEnabled = Boolean.parseBoolean(System.getProperty(SystemConfig.PERMISSIVE_SECURITY_ENABLED_PROPERTY));
        if (permissiveSecurityEnabled) {
            newEnvironment = new ModuleEnvironment(finalModules, wrappingPermissionProviderFactory, Collections.emptyList());
        } else {
            newEnvironment = new ModuleEnvironment(finalModules, permissionProviderFactory, Collections.emptyList());
        }
        if (asPrimary) {
            environment = newEnvironment;
        }
        return newEnvironment;
    }

    public ModuleMetadataJsonAdapter getModuleMetadataReader() {
        return metadataReader;
    }

    private void enrichReflectionsWithSubsystems(Module engineModule) {
        Serializer serializer = new XmlSerializer();
        try {
            Enumeration<URL> urls = ModuleManager.class.getClassLoader().getResources("reflections.cache");
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if (url.getPath().contains("subsystem")) {
                    Reflections subsystemReflections = serializer.read(url.openStream());
                    engineModule.getReflectionsFragment().merge(subsystemReflections);
                }
            }
        } catch (IOException e) {
            logger.error("Cannot enrich engine's reflections with subsystems");
        }
    }

    public PermissionProvider getPermissionProvider(Module module) {
        return permissionProviderFactory.createPermissionProviderFor(module);
    }
}
