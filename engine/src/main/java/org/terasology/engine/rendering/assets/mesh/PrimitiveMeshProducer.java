// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.rendering.assets.mesh;

import com.google.common.collect.ImmutableSet;
import org.terasology.assets.AssetDataProducer;
import org.terasology.assets.ResourceUrn;
import org.terasology.assets.module.annotations.RegisterAssetDataProducer;
import org.terasology.engine.core.TerasologyConstants;
import org.terasology.engine.rendering.assets.texture.TextureUtil;
import org.terasology.module.sandbox.API;
import org.terasology.naming.Name;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

@RegisterAssetDataProducer
@API
public class PrimitiveMeshProducer implements AssetDataProducer<MeshData> {
    public static final Name SCREEN_QUAD_RESOURCE_NAME = new Name("ScreenQuad");

    @Override
    public Set<ResourceUrn> getAvailableAssetUrns() {
        return Collections.emptySet();
    }

    @Override
    public Set<Name> getModulesProviding(Name resourceName) {
        if (TextureUtil.NOISE_RESOURCE_NAME.equals(resourceName)) {
            return ImmutableSet.of(TerasologyConstants.ENGINE_MODULE);
        }
        return Collections.emptySet();
    }

    @Override
    public ResourceUrn redirect(ResourceUrn urn) {
        return urn;
    }

    @Override
    public Optional<MeshData> getAssetData(ResourceUrn urn) throws IOException {
        if (TerasologyConstants.ENGINE_MODULE.equals(urn.getModuleName())) {
            if (SCREEN_QUAD_RESOURCE_NAME.equals(urn.getResourceName())) {
                MeshData data = new MeshData();
                data.getVertices().addAll(new float[]{
                        -1.0f, -1.0f, 0.0f,
                        -1.0f, 1.0f, 0.0f,
                        1.0f, 1.0f, 0.0f,
                        1.0f, -1.0f, 0.0f,
                });
                data.getIndices().addAll(new int[]{
                        0, 1, 2,
                        0, 2, 3
                });
                data.getTexCoord0().addAll(new float[]{
                        0.f, 0.f,
                        0.f, 1.f,
                        1.f, 1.f,
                        1.f, 0.f
                });
                return Optional.of(data);
            }
        }
        return Optional.empty();
    }
}
