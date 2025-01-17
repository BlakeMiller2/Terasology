// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.assets.ResourceUrn;
import org.terasology.assets.management.AssetManager;
import org.terasology.engine.config.SystemConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.core.Uri;
import org.terasology.engine.i18n.assets.Translation;
import org.terasology.engine.persistence.TemplateEngine;
import org.terasology.engine.persistence.TemplateEngineImpl;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A translation system that uses {@link Translation} data assets to perform the lookup.
 */
public class TranslationSystemImpl implements TranslationSystem {

    private static final Logger logger = LoggerFactory.getLogger(TranslationSystemImpl.class);

    private final List<Consumer<TranslationProject>> changeListeners = new CopyOnWriteArrayList<>();
    private final Map<Uri, TranslationProject> projects = new HashMap<>();

    private final SystemConfig systemConfig;

    private AssetManager assetManager;

    /**
     * @param context the context to use
     */
    public TranslationSystemImpl(Context context) {

        systemConfig = context.get(SystemConfig.class);
        assetManager = context.get(AssetManager.class);
        refresh();
    }

    @Override
    public void refresh() {
        Set<ResourceUrn> urns = assetManager.getAvailableAssets(Translation.class);
        for (ResourceUrn urn : urns) {
            Optional<Translation> asset = assetManager.getAsset(urn, Translation.class);
            if (asset.isPresent()) {
                Translation trans = asset.get();
                Uri uri = trans.getProjectUri();
                if (uri.isValid()) {
                    TranslationProject proj = projects.computeIfAbsent(uri, e -> new StandardTranslationProject());
                    proj.add(trans);
                    trans.subscribe(this::onAssetChanged);
                    logger.info("Discovered " + trans);
                } else {
                    logger.warn("Ignoring invalid project uri: {}", uri);
                }
            }
        }
    }

    @Override
    public TranslationProject getProject(Uri name) {
        return projects.get(name);
    }

    @Override
    public String translate(String id) {
        return translate(id, systemConfig.locale.get());
    }

    @Override
    public String translate(String text, Locale otherLocale) {
        TemplateEngine templateEngine = new TemplateEngineImpl(id -> {
            ResourceUrn uri = new ResourceUrn(id);
            SimpleUri projectUri = new SimpleUri(uri.getModuleName(), uri.getResourceName());
            TranslationProject project = getProject(projectUri);
            if (project != null) {
                Optional<String> opt = project.translate(uri.getFragmentName(), otherLocale);
                if (opt.isPresent()) {
                    return opt.get();
                } else {
                    logger.warn("No translation for '{}'", id);
                    return "?" + uri.getFragmentName() + "?";
                }
            } else {
                logger.warn("Invalid project id '{}'", id);
                return "?" + uri.getFragmentName() + "?";
            }
        });

        return templateEngine.transform(text);
    }

    @Override
    public void subscribe(Consumer<TranslationProject> reloadListener) {
        changeListeners.add(reloadListener);
    }

    @Override
    public void unsubscribe(Consumer<TranslationProject> reloadListener) {
        changeListeners.remove(reloadListener);
    }

    private void onAssetChanged(Translation trans) {
        Uri uri = trans.getProjectUri();
        TranslationProject project = projects.get(uri);
        if (trans.isDisposed()) {
            project.remove(trans);
        }
        for (Consumer<TranslationProject> listener : changeListeners) {
            listener.accept(project);
        }
    }
}
