/*
 * Copyright 2013 Moving Blocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.world.block.family;

import com.google.common.collect.Maps;
import com.google.gson.JsonObject;
import org.terasology.asset.AssetUri;
import org.terasology.math.Side;
import org.terasology.world.block.Block;
import org.terasology.world.block.BlockUri;
import org.terasology.world.block.loader.BlockDefinition;

import java.util.Map;

@RegisterBlockFamilyFactory("horizontal")
public class HorizontalBlockFamilyFactory implements BlockFamilyFactory {
    protected Side getArchetypeSide() {
        return Side.FRONT;
    }

    @Override
    public BlockFamily createBlockFamily(BlockBuilderHelper blockBuilder, AssetUri blockDefUri, BlockDefinition blockDefinition, JsonObject blockDefJson) {
        Map<Side, Block> blockMap = Maps.newHashMap();
        blockMap.putAll(blockBuilder.constructHorizontalRotatedBlocks(blockDefUri, blockDefinition));
        return new HorizontalBlockFamily(new BlockUri(blockDefUri.getPackage(), blockDefUri.getAssetName()), getArchetypeSide(), blockMap, blockDefinition.categories);
    }

}
